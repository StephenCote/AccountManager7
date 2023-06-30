/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY 
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cote.accountmanager.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.GraphicsUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.service.util.ServiceUtil;

public class MediaUtil {
	public static final Logger logger = LogManager.getLogger(MediaUtil.class);
	private static Pattern recPattern = Pattern.compile("^\\/([\\sA-Za-z0-9\\.]+)\\/([\\w]+)([%-_\\/\\s\\.A-Za-z0-9]+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
	private static Pattern dimPattern = Pattern.compile("(\\/\\d+x\\d+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
	private static final VaultService vaultService = null;
	private static int maximumImageWidth = -1;
	private static int maximumImageHeight = -1;
	private static boolean restrictImageSize = false;
	private static boolean allowDataPointers = false;
	private static boolean checkConfig = false;
	private static boolean checkConfigDataPoint = false;
	private static Map<String,String> templateContents = new HashMap<>();
	
	protected static boolean getRestrictImageSize(HttpServletRequest request){
		if(checkConfig) return restrictImageSize;
		restrictImageSize = getBoolParam(request,"image.restrict.size");
		checkConfig = true;
		return restrictImageSize;
	}
	protected static boolean isAllowDataPointers(HttpServletRequest request){
		if(checkConfigDataPoint) return allowDataPointers;
		checkConfigDataPoint = true;
		allowDataPointers = getBoolParam(request,"data.pointers.enabled");
		return allowDataPointers;
	}
	protected static int getMaximumImageWidth(HttpServletRequest request){ 
		if(maximumImageWidth >= 0) return maximumImageWidth;
		maximumImageWidth = getIntParam(request, "image.maximum.width");
		return maximumImageWidth;
	}
	protected static int getMaximumImageHeight(HttpServletRequest request){ 
		if(maximumImageHeight >= 0) return maximumImageHeight;
		maximumImageHeight = getIntParam(request, "image.maximum.height");
		return maximumImageHeight;
	}
	protected static boolean getBoolParam(HttpServletRequest request, String name){
		boolean ret = false;
		String iV = request.getServletContext().getInitParameter(name);
		if(iV != null && iV.length() > 0){
			ret = Boolean.parseBoolean(iV);
		}
		return ret;
	}
	protected static int getIntParam(HttpServletRequest request, String name){
		int ret = 0;
		String iV = request.getServletContext().getInitParameter(name);
		if(iV != null && iV.length() > 0){
			ret = Integer.parseInt(iV);
		}
		return ret;
	}
	public static void writeBinaryContent(HttpServletRequest request, HttpServletResponse response) throws IOException{
		writeBinaryContent(request, response, new MediaOptions());
	}
	public static void writeBinaryContent(HttpServletRequest request, HttpServletResponse response, MediaOptions options) throws IOException{
		
		String path = request.getPathInfo();
		if(path == null || path.length() == 0){
			logger.error("Path is null or empty");
			response.sendError(404);
			return;
		}
		logger.debug("Media path: " + path);
		Matcher m = recPattern.matcher(path);
		if(!m.find() || m.groupCount() != 3){
			logger.error("Unexpected path construct");
			response.sendError(404);
			return;
		}
		
		String orgPath = "/" + m.group(1).trim().replace('.', '/');
		String type = m.group(2).trim();
		String subPath = m.group(3).trim();
		String name = null;
		int index = 0;
		
		if((index = subPath.lastIndexOf('/')) > -1){
			logger.debug("Testing '" + subPath + "' for dimensions");
			Matcher d = dimPattern.matcher(subPath);
			if(d.find() && d.groupCount() == 1){
				String[] dimPair = d.group(1).trim().replace("/", "").split("x");
				options.setThumbWidth(Integer.parseInt(dimPair[0]));
				options.setThumbHeight(Integer.parseInt(dimPair[1]));
				subPath = d.replaceAll("");
				index = subPath.lastIndexOf('/');
				logger.debug("Adjust path for dimenion information");
				logger.debug("New Path: " + subPath);
			}
			else{
				logger.debug("No alternate dimensions discovered: " + d.groupCount());
			}
			name = subPath.substring(index+1,subPath.length()).trim();
			subPath = subPath.substring(0,index);
		}
		
		if(orgPath.length() == 0 || type.length() == 0 || subPath.length() == 0 || name == null || name.length() == 0){
			logger.error("Type, path, or name did not contain a value");
			response.sendError(404);
			return;
		}

		writeBinaryContent(request, response, options, type.toLowerCase(), orgPath, subPath, name);
	}
	public static void writeBinaryContent(
			HttpServletRequest request,
			HttpServletResponse response,
			MediaOptions options,
			String type,
			String orgPath,
			String objPath,
			String objName
	) throws IOException{
		
		OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext(orgPath, null);
		if(org == null){
			logger.error("Organization is invalid: '" + orgPath + "'");
			response.sendError(404);
			return;
		}
		
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		
		/// if(user == null) user = org.getDocumentControl();
		BaseRecord audit = AuditUtil.startAudit(user, ActionEnumType.READ, user, null);
		writeBinaryContent(request, response, options, audit, type, org, user, objPath, objName);
	}
	public static void writeBinaryContent(
			HttpServletRequest request,
			HttpServletResponse response,
			MediaOptions options,
			BaseRecord audit,
			String type,
			OrganizationContext org,
			BaseRecord user,
			String objPath,
			String objName
	) throws IOException{
		
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, objPath, GroupEnumType.DATA.toString(), org.getOrganizationId());
		if(dir == null){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Path '" + objPath + "' is invalid for " + (user == null ? "null user":user.get(FieldNames.FIELD_NAME)) + " in organization " + org.getOrganization().get(FieldNames.FIELD_NAME));
			response.sendError(404);
			return;
		}
		writeBinaryContent(request, response, options, audit, type, org, user, dir, objName);
		
	}
	public static void writeBinaryContent(
			HttpServletRequest request,
			HttpServletResponse response,
			MediaOptions options,
			BaseRecord audit,
			String type,
			OrganizationContext org,
			BaseRecord user,
			BaseRecord group,
			String objName
	) throws IOException{
		if(type.equals(ModelNames.MODEL_DATA) || type.equals(ModelNames.MODEL_THUMBNAIL)) {
			writeBinaryData(request, response, options,audit, type, org, user, group, objName);
		}
		else {
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Unexpected target type: " + type);
			response.sendError(404);
		}
	}

	public static void writeBinaryData(
			HttpServletRequest request,
			HttpServletResponse response,
			MediaOptions options,
			BaseRecord audit,
			String type,
			OrganizationContext org,
			BaseRecord user,
			BaseRecord group,
			String objName
	) throws IOException{
		BaseRecord data = null;
		boolean can_view = false;
		
		
		if(user == null){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "User is null");
			response.sendError(404);
			return;	
		}
		
		/// If the config stipulates a maximum width and height, then force thumbnail size to be no larger
		/// If the optional force image bit is set, force all image requests through the thumbnail mechanism to prevent delivery of the full original resolution
		///
		int maxWidth = getMaximumImageWidth(request);
		int maxHeight = getMaximumImageHeight(request);
		boolean restrictSize = getRestrictImageSize(request);
		if(maxWidth > 0 && maxHeight > 0 && options.isThumbnail()){
			boolean bLim = false;
			if(options.getThumbHeight() > maxHeight){
				bLim = true;
				options.setThumbHeight(maximumImageHeight);
			}
			if(options.getThumbWidth() > maxWidth){
				bLim = true;
				options.setThumbWidth(maximumImageWidth);
			}
			if(bLim){
				logger.info("Limiting width and height to " + maxWidth + "," + maxHeight);
			}
		}
	
		/// If this is a thumbnail request, then:
		/// 1) get the details only data and confirm it's an image
		/// 
		try{
			if(options.isThumbnail()){
				Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
				q.field(FieldNames.FIELD_NAME, objName);
				q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID});
				BaseRecord sdata = IOSystem.getActiveContext().getSearch().findRecord(q);
				data = ThumbnailUtil.getCreateThumbnail(sdata, options.getThumbWidth(), options.getThumbHeight());
				if(data == null){
					logger.warn("Thumbnail data is null for data name " + objName + " and user " + user.get(FieldNames.FIELD_NAME));
				}
			} /// End if thumbnail
			else{
				data = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_DATA, (long)group.get(FieldNames.FIELD_ID), objName);
				
				if(data != null && data.get(FieldNames.FIELD_CONTENT_TYPE) != null && ((String)data.get(FieldNames.FIELD_CONTENT_TYPE)).startsWith("image/") && restrictSize){
					logger.info("Redirecting to restricted image path");
					IOSystem.getActiveContext().getReader().populate(group);
					String dotPath = org.getOrganizationPath().substring(1,org.getOrganizationPath().length()).replace('/', '.');
					AuditUtil.closeAudit(audit, ResponseEnumType.PENDING, "Redirecting user " + user.get(FieldNames.FIELD_NAME) + " to " + request.getServletContext().getContextPath() + "/thumbnail/" + dotPath + "/Data" + group.get(FieldNames.FIELD_PATH) + "/" + objName + "/" + maxWidth + "x" + maxHeight + " with restricted dimensions");
					response.sendRedirect(request.getServletContext().getContextPath() + "/thumbnail/" + dotPath + "/Data" + group.get(FieldNames.FIELD_PATH) + "/" + objName + "/" + maxWidth + "x" + maxHeight);
					return;
				}
			}
			if(data != null && IOSystem.getActiveContext().getPolicyUtil().readPermitted(user, user, null, data)){
				can_view = true;
			}
		}
		catch(ReaderException | IndexException | FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			
			logger.error(e);
		}
		if(data == null){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Data is invalid: '" + objName + "'");
			response.sendError(404);
			return;
		}
		if(can_view == false){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "User '" + user.get(FieldNames.FIELD_NAME) + "' is not authorized to view data '" + data.get(FieldNames.FIELD_NAME) + "' in organization '" + org.getOrganization().get(FieldNames.FIELD_NAME) + "' because the view bit is set to false.");
			response.sendError(404);
			return;	
		}
		AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, "User " + user.get(FieldNames.FIELD_NAME) + " is authorized to view  " + data.get(FieldNames.FIELD_NAME) + " in " + data.get(FieldNames.FIELD_GROUP_ID));
		response.setContentType(data.get(FieldNames.FIELD_CONTENT_TYPE));

		byte[] value = new byte[0];
			
			if(data.hasField(FieldNames.FIELD_STREAM) && data.get(FieldNames.FIELD_STREAM) != null) {
				//logger.warn("*** TODO: Read from stream");
				BaseRecord stream = data.get(FieldNames.FIELD_STREAM);
				StreamSegmentUtil ssu = new StreamSegmentUtil();
				value = ssu.streamToEnd(stream.get(FieldNames.FIELD_OBJECT_ID), 0, 0);
			}
			else{
				value = data.get(FieldNames.FIELD_BYTE_STORE);
				/*
				VaultBean vaultBean = (data.getVaulted() ? vaultService.getVaultByUrn(user, data.getVaultId()) : null);
				if(data.getVaulted()){
				if(vaultBean != null && vaultBean.getActiveKeyId() == null) vaultService.newActiveKey(vaultBean);
					value = vaultService.extractVaultData(vaultBean, data);
				
				}
				else{
					value = DataUtil.getValue(data);
				}
				*/
			}

		if(options.isEncodeData()){
			value = BinaryUtil.toBase64(value);
		}
		if(options.isUseTemplate() && options.getTemplatePath() != null){
			
			InputStream resourceContent = null;
			String template = null;
			if(templateContents.containsKey(options.getTemplatePath())) template = templateContents.get(options.getTemplatePath());
			else{
				try {

					resourceContent = request.getServletContext().getResourceAsStream(options.getTemplatePath());
					template = StreamUtil.streamToString(new BufferedInputStream(resourceContent));
					if(template != null && template.length() > 0){
						templateContents.put(options.getTemplatePath(), template);
					}
				} catch (IOException e) {
					
					logger.error(e);
				}
				finally{
					if(resourceContent != null)
						try {
							resourceContent.close();
						} catch (IOException e) {
							
							logger.error(e);
						}
				}

			}
			if(template != null){
				template = template.replaceAll("%TITLE%", data.get(FieldNames.FIELD_NAME) + " (" + data.get(FieldNames.FIELD_OBJECT_ID) + ") - Distributed Web Application Component");
				template = template.replaceAll("%CONTENT%", request.getRequestURI().replaceAll("/dwac/", "/media/"));
				value = template.getBytes();
				if(options.getTemplateContentType() != null) response.setContentType(options.getTemplateContentType());
			}
			else{
				response.sendError(500);
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Template is invalid: '" + options.getTemplatePath() + "'");
			}
		}
		response.setContentLength(value.length);
		response.getOutputStream().write(value); 
		response.flushBuffer();
	}


	
}
