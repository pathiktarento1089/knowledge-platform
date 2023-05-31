package org.sunbird.content.actors

import java.util
import java.util.concurrent.CompletionException
import java.io.File
import org.apache.commons.io.FilenameUtils
import javax.inject.Inject
import org.apache.commons.lang3.ObjectUtils
import org.apache.commons.lang3.StringUtils
import org.apache.commons.collections4.{CollectionUtils, MapUtils}
import org.sunbird.`object`.importer.{ImportConfig, ImportManager}
import org.sunbird.actor.core.BaseActor
import org.sunbird.cache.impl.RedisCache
import org.sunbird.content.util.{AcceptFlagManager, ContentConstants, CopyManager, DiscardManager, FlagManager, RetireManager}
import org.sunbird.cloudstore.StorageService
import org.sunbird.common.{ContentParams, JsonUtils, Platform, Slug}
import org.sunbird.common.dto.{Request, Response, ResponseHandler}
import org.sunbird.common.exception.ClientException
import org.sunbird.content.dial.DIALManager
import org.sunbird.content.review.mgr.ReviewManager
import org.sunbird.util.RequestUtil
import org.sunbird.content.upload.mgr.UploadManager
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.dac.model.Node
import org.sunbird.graph.nodes.DataNode
import org.sunbird.graph.utils.NodeUtil
import org.sunbird.managers.HierarchyManager
import org.sunbird.managers.HierarchyManager.hierarchyPrefix

import scala.collection.JavaConverters
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class ContentActor @Inject() (implicit oec: OntologyEngineContext, ss: StorageService) extends BaseActor {

	implicit val ec: ExecutionContext = getContext().dispatcher
	private lazy val importConfig = getImportConfig()
	private lazy val importMgr = new ImportManager(importConfig)

	override def onReceive(request: Request): Future[Response] = {
		request.getOperation match {
			case "createContent" => create(request)
			case "readContent" => read(request)
			case "readPrivateContent" => privateRead(request)
			case "updateContent" => update(request)
			case "uploadContent" => upload(request)
			case "retireContent" => retire(request)
			case "copy" => copy(request)
			case "uploadPreSignedUrl" => uploadPreSignedUrl(request)
			case "discardContent" => discard(request)
			case "flagContent" => flag(request)
			case "acceptFlag" => acceptFlag(request)
			case "linkDIALCode" => linkDIALCode(request)
			case "importContent" => importContent(request)
			case "systemUpdate" => systemUpdate(request)
			case "reviewContent" => reviewContent(request)
			case "rejectContent" => rejectContent(request)
			case _ => ERROR(request.getOperation)
		}
	}

	def create(request: Request): Future[Response] = {
		populateDefaultersForCreation(request)
		RequestUtil.restrictProperties(request)
		DataNode.create(request, dataModifier).map(node => {
			ResponseHandler.OK.put("identifier", node.getIdentifier).put("node_id", node.getIdentifier)
				.put("versionKey", node.getMetadata.get("versionKey"))
		})
	}

	def read(request: Request): Future[Response] = {
		val responseSchemaName: String = request.getContext.getOrDefault(ContentConstants.RESPONSE_SCHEMA_NAME, "").asInstanceOf[String]
		val fields: util.List[String] = JavaConverters.seqAsJavaListConverter(request.get("fields").asInstanceOf[String].split(",").filter(field => StringUtils.isNotBlank(field) && !StringUtils.equalsIgnoreCase(field, "null"))).asJava
		request.getRequest.put("fields", fields)
		DataNode.read(request).map(node => {
			val metadata: util.Map[String, AnyRef] = NodeUtil.serialize(node, fields, node.getObjectType.toLowerCase.replace("image", ""), request.getContext.get("version").asInstanceOf[String])
			metadata.put("identifier", node.getIdentifier.replace(".img", ""))
			if (StringUtils.equalsIgnoreCase(metadata.get("visibility").asInstanceOf[String],"Private")) {
				throw new ClientException("ERR_ACCESS_DENIED", "content visibility is private, hence access denied")
			}
			var sa = metadata.get("secureSettings")
			var securityAttribute : util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]
			if(sa.isInstanceOf[String]) {
				securityAttribute = JsonUtils.deserialize(sa.asInstanceOf[String], classOf[java.util.Map[String, AnyRef]])
				metadata.put("secureSettings", securityAttribute)
			} else if (sa.isInstanceOf[util.Map[String, AnyRef]]) {
				securityAttribute = metadata.getOrDefault("secureSettings", new util.HashMap[String, AnyRef]).asInstanceOf[util.Map[String, AnyRef]]
			}
			//var securityAttribute : util.Map[String, AnyRef] = metadata.getOrDefault("secureSettings", new util.HashMap[String, AnyRef]).asInstanceOf[util.Map[String, AnyRef]]
			if (MapUtils.isNotEmpty(securityAttribute)) {
				var orgList : util.ArrayList[String] = securityAttribute.getOrDefault("organisation", new util.ArrayList[String]).asInstanceOf[util.ArrayList[String]]
				if (!CollectionUtils.isEmpty(orgList)) {
					//Content should be read by unique org users only.
					var userChannelId : String = request.getRequest.getOrDefault("x-user-channel-id", "").asInstanceOf[String]
					if (!orgList.contains(userChannelId)) {
						throw new ClientException("ERR_ACCESS_DENIED", "User is not allowed to read this content.")
					}
				}
			}
			val response: Response = ResponseHandler.OK
			if (responseSchemaName.isEmpty) {
				response.put("content", metadata)
			} else {
				response.put(responseSchemaName, metadata)
			}
			response
		})
	}

	def privateRead(request: Request): Future[Response] = {
		val responseSchemaName: String = request.getContext.getOrDefault(ContentConstants.RESPONSE_SCHEMA_NAME, "").asInstanceOf[String]
		val fields: util.List[String] = JavaConverters.seqAsJavaListConverter(request.get("fields").asInstanceOf[String].split(",").filter(field => StringUtils.isNotBlank(field) && !StringUtils.equalsIgnoreCase(field, "null"))).asJava
		request.getRequest.put("fields", fields)
		if (StringUtils.isBlank(request.getRequest.getOrDefault("channel", "").asInstanceOf[String])) throw new ClientException("ERR_INVALID_CHANNEL", "Please Provide Channel!")
		DataNode.read(request).map(node => {
			val metadata: util.Map[String, AnyRef] = NodeUtil.serialize(node, fields, node.getObjectType.toLowerCase.replace("image", ""), request.getContext.get("version").asInstanceOf[String])
			metadata.put("identifier", node.getIdentifier.replace(".img", ""))
			val response: Response = ResponseHandler.OK
				if (StringUtils.equalsIgnoreCase(metadata.getOrDefault("channel", "").asInstanceOf[String],request.getRequest.getOrDefault("channel", "").asInstanceOf[String])) {
					if (responseSchemaName.isEmpty) {
						response.put("content", metadata)
					}
					else {
						response.put(responseSchemaName, metadata)
					}
					response
				}
				else {
					throw new ClientException("ERR_ACCESS_DENIED", "Channel id is not matched")
				}
		})
	}

	def update(request: Request): Future[Response] = {
		populateDefaultersForUpdation(request)
		if (StringUtils.isBlank(request.getRequest.getOrDefault("versionKey", "").asInstanceOf[String])) throw new ClientException("ERR_INVALID_REQUEST", "Please Provide Version Key!")
		RequestUtil.restrictProperties(request)
		DataNode.update(request, dataModifier).map(node => {
			val identifier: String = node.getIdentifier.replace(".img", "")
			ResponseHandler.OK.put("node_id", identifier).put("identifier", identifier)
				.put("versionKey", node.getMetadata.get("versionKey"))
		})
	}

	def upload(request: Request): Future[Response] = {
		val identifier: String = request.getContext.getOrDefault("identifier", "").asInstanceOf[String]
		val readReq = new Request(request)
		readReq.put("identifier", identifier)
		readReq.put("fields", new util.ArrayList[String])
		DataNode.read(readReq).map(node => {
			if (null != node & StringUtils.isNotBlank(node.getObjectType))
				request.getContext.put("schemaName", node.getObjectType.toLowerCase())
			UploadManager.upload(request, node)
		}).flatMap(f => f)
	}

	def copy(request: Request): Future[Response] = {
		RequestUtil.restrictProperties(request)
		CopyManager.copy(request)
	}

	def uploadPreSignedUrl(request: Request): Future[Response] = {
		val `type`: String = request.get("type").asInstanceOf[String].toLowerCase()
		val fileName: String = request.get("fileName").asInstanceOf[String]
		val filePath: String = request.getRequest.getOrDefault("filePath","").asInstanceOf[String]
			.replaceAll("^/+|/+$", "")
		val identifier: String = request.get("identifier").asInstanceOf[String]
		validatePreSignedUrlRequest(`type`, fileName, filePath)
		DataNode.read(request).map(node => {
			val objectKey = if (StringUtils.isEmpty(filePath)) "content" + File.separator + `type` + File.separator + identifier + File.separator + Slug.makeSlug(fileName, true)
				else filePath + File.separator + "content" + File.separator + `type` + File.separator + identifier + File.separator + Slug.makeSlug(fileName, true)
			val expiry = Platform.config.getString("cloud_storage.upload.url.ttl")
			val preSignedURL = ss.getSignedURL(objectKey, Option.apply(expiry.toInt), Option.apply("w"))
			ResponseHandler.OK().put("identifier", identifier).put("pre_signed_url", preSignedURL)
				.put("url_expiry", expiry)
		}) recoverWith { case e: CompletionException => throw e.getCause }
	}

	def retire(request: Request): Future[Response] = {
		RetireManager.retire(request)
	}
	def discard(request: Request): Future[Response] = {
		RequestUtil.restrictProperties(request)
		DiscardManager.discard(request)
	}

	def flag(request: Request): Future[Response] = {
		FlagManager.flag(request)
	}

	def acceptFlag(request: Request): Future[Response] = {
		AcceptFlagManager.acceptFlag(request)
	}

	def linkDIALCode(request: Request): Future[Response] = DIALManager.link(request)

	def importContent(request: Request): Future[Response] = importMgr.importObject(request)

	def reviewContent(request: Request): Future[Response] = {
		val identifier: String = request.getContext.getOrDefault("identifier", "").asInstanceOf[String]
		val readReq = new Request(request)
		readReq.put("identifier", identifier)
		readReq.put("mode", "edit")
		DataNode.read(readReq).map(node => {
			if (null != node & StringUtils.isNotBlank(node.getObjectType))
				request.getContext.put("schemaName", node.getObjectType.toLowerCase())
			if (StringUtils.equalsAnyIgnoreCase("Processing", node.getMetadata.getOrDefault("status", "").asInstanceOf[String]))
				throw new ClientException("ERR_NODE_ACCESS_DENIED", "Review Operation Can't Be Applied On Node Under Processing State")
			else ReviewManager.review(request, node)
		}).flatMap(f => f)
	}

	def populateDefaultersForCreation(request: Request) = {
		setDefaultsBasedOnMimeType(request, ContentParams.create.name)
		setDefaultLicense(request)
	}

	private def setDefaultLicense(request: Request): Unit = {
		if (StringUtils.isEmpty(request.getRequest.getOrDefault("license", "").asInstanceOf[String])) {
			val cacheKey = "channel_" + request.getRequest.getOrDefault("channel", "").asInstanceOf[String] + "_license"
			val defaultLicense = RedisCache.get(cacheKey, null, 0)
			if (StringUtils.isNotEmpty(defaultLicense)) request.getRequest.put("license", defaultLicense)
			else println("Default License is not available for channel: " + request.getRequest.getOrDefault("channel", "").asInstanceOf[String])
		}
	}

	def populateDefaultersForUpdation(request: Request) = {
		if (request.getRequest.containsKey(ContentParams.body.name)) request.put(ContentParams.artifactUrl.name, null)
	}

	private def setDefaultsBasedOnMimeType(request: Request, operation: String): Unit = {
		val mimeType = request.get(ContentParams.mimeType.name).asInstanceOf[String]
		if (StringUtils.isNotBlank(mimeType) && operation.equalsIgnoreCase(ContentParams.create.name)) {
			if (StringUtils.equalsIgnoreCase("application/vnd.ekstep.plugin-archive", mimeType)) {
				val code = request.get(ContentParams.code.name).asInstanceOf[String]
				if (null == code || StringUtils.isBlank(code)) throw new ClientException("ERR_PLUGIN_CODE_REQUIRED", "Unique code is mandatory for plugins")
				request.put(ContentParams.identifier.name, request.get(ContentParams.code.name))
			}
			else request.put(ContentParams.osId.name, "org.ekstep.quiz.app")
			if (mimeType.endsWith("archive") || mimeType.endsWith("vnd.ekstep.content-collection") || mimeType.endsWith("epub")) request.put(ContentParams.contentEncoding.name, ContentParams.gzip.name)
			else request.put(ContentParams.contentEncoding.name, ContentParams.identity.name)
			if (mimeType.endsWith("youtube") || mimeType.endsWith("x-url")) request.put(ContentParams.contentDisposition.name, ContentParams.online.name)
			else request.put(ContentParams.contentDisposition.name, ContentParams.inline.name)
		}
	}

	private def validatePreSignedUrlRequest(`type`: String, fileName: String, filePath: String): Unit = {
		if (StringUtils.isEmpty(fileName))
			throw new ClientException("ERR_CONTENT_BLANK_FILE_NAME", "File name is blank")
		if (StringUtils.isBlank(FilenameUtils.getBaseName(fileName)) || StringUtils.length(Slug.makeSlug(fileName, true)) > 256)
			throw new ClientException("ERR_CONTENT_INVALID_FILE_NAME", "Please Provide Valid File Name.")
		if (!preSignedObjTypes.contains(`type`))
			throw new ClientException("ERR_INVALID_PRESIGNED_URL_TYPE", "Invalid pre-signed url type. It should be one of " + StringUtils.join(preSignedObjTypes, ","))
		if(StringUtils.isNotBlank(filePath) && filePath.size > 100)
			throw new ClientException("ERR_CONTENT_INVALID_FILE_PATH", "Please provide valid filepath of character length 100 or Less ")
	}

	def dataModifier(node: Node): Node = {
		if(node.getMetadata.containsKey("trackable") &&
				node.getMetadata.getOrDefault("trackable", new java.util.HashMap[String, AnyRef]).asInstanceOf[java.util.Map[String, AnyRef]].containsKey("enabled") &&
		"Yes".equalsIgnoreCase(node.getMetadata.getOrDefault("trackable", new java.util.HashMap[String, AnyRef]).asInstanceOf[java.util.Map[String, AnyRef]].getOrDefault("enabled", "").asInstanceOf[String])) {
			node.getMetadata.put("contentType", "Course")
		}
		node
	}

	def getImportConfig(): ImportConfig = {
		val requiredProps = Platform.getStringList("import.required_props", java.util.Arrays.asList("name", "code", "mimeType", "contentType", "artifactUrl", "framework")).asScala.toList
		val validStages = Platform.getStringList("import.valid_stages", java.util.Arrays.asList("create", "upload", "review", "publish")).asScala.toList
		val propsToRemove = Platform.getStringList("import.remove_props", java.util.Arrays.asList("downloadUrl", "variants", "previewUrl", "streamingUrl", "itemSets")).asScala.toList
		val topicName = Platform.config.getString("import.output_topic_name")
		val reqLimit = Platform.getInteger("import.request_size_limit", 200)
		val validSourceStatus = Platform.getStringList("import.valid_source_status", java.util.Arrays.asList()).asScala.toList
		ImportConfig(topicName, reqLimit, requiredProps, validStages, propsToRemove, validSourceStatus)
	}

	def systemUpdate(request: Request): Future[Response] = {
		val identifier = request.getContext.get("identifier").asInstanceOf[String]
		RequestUtil.validateRequest(request)
		RedisCache.delete(hierarchyPrefix + request.get("rootId"))

		val readReq = new Request(request)
		val identifiers = new util.ArrayList[String](){{
			add(identifier)
			if (!identifier.endsWith(".img"))
				add(identifier.concat(".img"))
		}}
		readReq.put("identifiers", identifiers)
		DataNode.list(readReq).flatMap(response => {
			val objectType = request.getContext.get("objectType").asInstanceOf[String]
			if (objectType.toLowerCase.equals("collection"))
				DataNode.systemUpdate(request, response, "content", Option(HierarchyManager.getHierarchy))
			else
				DataNode.systemUpdate(request, response,"", None)
		}).map(node => {
			ResponseHandler.OK.put("identifier", identifier).put("status", "success")
		})
	}

	def rejectContent(request: Request): Future[Response] = {
		RequestUtil.validateRequest(request)
		DataNode.read(request).map(node => {
			val status = node.getMetadata.get("status").asInstanceOf[String]
			if (StringUtils.isBlank(status))
				throw new ClientException("ERR_METADATA_ISSUE", "Content metadata error, status is blank for identifier:" + node.getIdentifier)
      if (StringUtils.equals("Review", status)) {
        request.getRequest.put("status", "Draft")
				request.getRequest.put("prevStatus", "Review")
      } else if (StringUtils.equals("FlagReview", status)) {
        request.getRequest.put("status", "FlagDraft")
				request.getRequest.put("prevStatus", "FlagReview")
			}
      else new ClientException("ERR_INVALID_REQUEST", "Content not in Review status.")

			request.getRequest.put("versionKey", node.getMetadata.get("versionKey"))
			request.putIn("publishChecklist", null).putIn("publishComment", null)
      //updating node after changing the status
			RequestUtil.restrictProperties(request)
			DataNode.update(request).map(node => {
				val identifier: String = node.getIdentifier.replace(".img", "")
				ResponseHandler.OK.put("node_id", identifier).put("identifier", identifier)
			})
		}).flatMap(f => f)
	}

}
