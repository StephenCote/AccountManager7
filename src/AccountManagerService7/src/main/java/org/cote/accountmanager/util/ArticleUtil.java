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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.service.util.ServiceUtil;


public class ArticleUtil {
	public static final Logger logger = LogManager.getLogger(ArticleUtil.class);
	private static final Pattern articlePattern = Pattern.compile("^\\/([\\sA-Za-z0-9\\.]+)\\/([%-_\\/\\s\\.A-Za-z0-9]+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
	private static final Pattern headerLinkPattern = Pattern.compile("\\<h1(?:\\s*)\\>((.|\\n|\\r)*?)\\</h1(?:\\s*)\\>");
	private static String articleTemplate = null;
	private static String articleSectionTemplate = null;
	private static String articleMetaDataTemplate = null;
	private static String articleNavBackTemplate = null;
	private static String articleNavForwardTemplate = null;
	protected static final String[] ARTICLE_ROLES = new String[]{
			"BlogAuthor",
			"ArticleAuthor"
		};
		
	protected static int MAX_RECORD_COUNT = 3;
		
	public static void clearCache() {
		articleTemplate = null;
		articleSectionTemplate = null;
		articleMetaDataTemplate = null;
		articleNavBackTemplate = null;
		articleNavForwardTemplate = null;
	}
	public static String getArticleTemplate(ServletContext context){
		if(articleTemplate != null) return articleTemplate;
		articleTemplate = getResourceFromParam(context, "template.article");
		return articleTemplate;
	}
	
	public static String getArticleSectionTemplate(ServletContext context){
		if(articleSectionTemplate != null) return articleSectionTemplate;
		articleSectionTemplate = getResourceFromParam(context, "template.article.section");
		return articleSectionTemplate;
	}
	
	public static String getArticleMetaDataTemplate(ServletContext context){
		if(articleMetaDataTemplate != null) return articleMetaDataTemplate;
		articleMetaDataTemplate = getResourceFromParam(context, "template.article.meta");
		return articleMetaDataTemplate;
	}
	
	public static String getArticleNavBackTemplate(ServletContext context){
		if(articleNavBackTemplate != null) return articleNavBackTemplate;
		articleNavBackTemplate = getResourceFromParam(context, "template.article.navback");
		return articleNavBackTemplate;
	}
	
	public static String getArticleNavForwardTemplate(ServletContext context){
		if(articleNavForwardTemplate != null) return articleNavForwardTemplate;
		articleNavForwardTemplate = getResourceFromParam(context, "template.article.navforward");
		return articleNavForwardTemplate;
	}

	public static String getResourceFromParam(ServletContext context,String paramName){
		String outStr = null;
		try {
			BufferedInputStream bis = new BufferedInputStream(context.getResourceAsStream(context.getInitParameter(paramName)));
			outStr = StreamUtil.streamToString(bis);
			bis.close();
		} catch (IOException e) {
			
			logger.error("Error",e);
		}
		return outStr;
	}
	
	public static void writeBinaryContent(HttpServletRequest request, HttpServletResponse response, MediaOptions options) throws IOException{
		
		BaseRecord audit = AuditUtil.startAudit(null, ActionEnumType.READ, null, null);
		String path = request.getPathInfo();
		if(path == null || path.length() == 0){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Path is null or empty");
			response.sendError(404);
			return;
		}
		
		
		Matcher m = articlePattern.matcher(path);
		if(!m.find() || m.groupCount() != 2){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Unexpected path construct");
			response.sendError(404);
			return;
		}
		
		/// Supported prefix patterns are:
		///		/OrgPath/[ArticleType]/[User/SubPath]
		///			EG: Article/Public/Blog/Steve
		///		[DefaultOrgPath]/[ArticleType]/[User/SubPath]
		///			EG: /Blog/Steve
		
		String orgPath = "/" + m.group(1).trim().replace('.', '/');
		String sBaseDir = options.getMediaBase();
		/// SubPath ==
		///   0 : UserName
		///	  1 : Article Name
		///	If 1 is empty, then it's a list
		///

		String[] subPath = m.group(2).split("/");
		
		if(orgPath.length() == 0 || sBaseDir.length() == 0 || subPath.length == 0){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Type, path, or name did not contain a value");
			response.sendError(404);
			return;
		}

		long organizationId = 0L;
		BaseRecord user = null;
		BaseRecord targUser = null;
		BaseRecord role = null;
		BaseRecord dir = null;
		try{
			OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext(orgPath, null);
			if(org == null || !org.isInitialized()){
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Organization is invalid: '" + orgPath + "'");
				response.sendError(404);
				return;
			}
			organizationId = org.getOrganizationId();

			user = ServiceUtil.getPrincipalUser(request);
			if(user == null){
				user = org.getDocumentControl();
				if(user == null){
					AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Null user identified");
					response.sendError(404);
					return;
				}
			}
			
			BaseRecord[] res = IOSystem.getActiveContext().getSearch().findByName(ModelNames.MODEL_USER, subPath[0]);
			if(res.length > 0) {
				targUser = res[0];
			}
			if(targUser == null){
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Target user is invalid: '" + subPath[0] + "'");
				response.sendError(404);
				return;
			}

			IOSystem.getActiveContext().getReader().populate(targUser);
			dir = targUser.get(FieldNames.FIELD_HOME_DIRECTORY);
			if(dir == null){
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Content directory is null for " + targUser.get(FieldNames.FIELD_NAME) + ": '~/" + sBaseDir + "'");
				response.sendError(404);
				return;
			}
			
			IOSystem.getActiveContext().getReader().populate(dir);
			
			/// This role check is in here more to stop people from driving random tests into the system
			/// So if a user isn't in this role, they obviously don't want to share anything this way, so stop checking
			///
			role = IOSystem.getActiveContext().getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_ROLE, "/" + AccessSchema.ROLE_ARTICLE_AUTHORS, RoleEnumType.USER.toString(), organizationId);
			if(!IOSystem.getActiveContext().getMemberUtil().isMember(targUser, role, true)) {
				AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "User " + subPath[0] + " is not an authorized author in : '" + sBaseDir + "Author' role");
				response.sendError(404);
				return;
			}
			
			/// Finally, make sure the requesting user has read access to the directory
			///
			if(IOSystem.getActiveContext().getPolicyUtil().readPermitted(user, user, null, dir)) {
				AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "User " + user.get(FieldNames.FIELD_NAME) + " is not authorized to view '" + dir.get(FieldNames.FIELD_NAME) + ".  NOTE: This may stem from an authenticated user other than the owner not having explicit rights, where the anonymous case does through Document Control.  Need to make sure the directory has rights for both public users as well as document control.");
				response.sendError(404);
				return;
				
			}

		}
		catch(ReaderException | IndexException e) {
			logger.error(e.getMessage());
			logger.error("Error",e);
		}
		
		String name = null;
		if(subPath.length > 1) name = subPath[1].trim();
		//List<DataType> articleData = new ArrayList<DataType>();
		BaseRecord[] articleData = new BaseRecord[0];
		
		long startIndex = 0;
		int recordCount = MAX_RECORD_COUNT;
		int totalCount = 0;
		String navBack = "";
		String navForward = "";
		boolean singleMode = false;
		if(name == null || name.length() == 0){
			String pageStr = request.getParameter("page");
			long page = 0;
			if(pageStr != null && pageStr.matches("^\\d+$")){
				page = (Long.parseLong(pageStr)-1);
				startIndex = page * recordCount;
			}
			totalCount = IOSystem.getActiveContext().getAccessPoint().count(user, QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID)));
			logger.info("Page = " + pageStr + " / " + startIndex + " / " + recordCount);
			if(startIndex < 0) startIndex = 0;
			if(startIndex >= totalCount) startIndex = totalCount - recordCount;
			
			String urlBase = "/AccountManagerService/article" + orgPath + "/" + targUser.get(FieldNames.FIELD_NAME);
			
			if((startIndex + recordCount) < totalCount){
				navForward = getArticleNavForwardTemplate(request.getServletContext());
				navForward = navForward.replaceAll("%FORWARD_URL%", urlBase + "?page=" + (page+2));
			}
			if(page > 0){
				navBack = getArticleNavBackTemplate(request.getServletContext());
				navBack = navBack.replaceAll("%BACK_URL%", urlBase + (page > 1 ? "?page=" + (page) : ""));
			}
			try {
				Query search = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
	
				search.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
				search.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
				search.setRequestRange(startIndex, recordCount);
	
				QueryResult qr = IOSystem.getActiveContext().getSearch().find(search);
				articleData = qr.getResults();
			}
			catch(ReaderException | FieldException | ValueException | ModelNotFoundException | IndexException e) {
				logger.error(e);
			}
		
		}
		/// Single mode
		else{
			singleMode = true;
			BaseRecord data = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_DATA, dir.get(FieldNames.FIELD_ID), name);
			if(data == null){
				AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "Null data returned for " + name);
				response.sendError(404);
				return;
			}
			articleData = new BaseRecord[] {data};
		}
		if(articleData == null){
			AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "Null data list returned - this is an internal error");
			response.sendError(404);
			return;
		}
		
		String template = getArticleTemplate(request.getServletContext());
		if(template == null || template.length() == 0){
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Failed to load template");
			response.sendError(404);
			return;
		}
		
		BaseRecord profile = UserUtil.getProfile(targUser);
		try {
			String blogTitle = AttributeUtil.getAttributeValue(profile, "blog.title");
			String blogSubtitle = AttributeUtil.getAttributeValue(profile, "blog.subtitle");
			String author = AttributeUtil.getAttributeValue(profile, "blog.signature");
			if(blogTitle == null || blogTitle.length() == 0) blogTitle = targUser.get(FieldNames.FIELD_NAME) + "'s Blog";
			if(blogSubtitle == null) blogSubtitle = "";
			
			if(singleMode == false) template = template.replaceAll("%PAGETITLE%",blogTitle);
			template = template.replaceAll("%TITLE%",blogTitle);
			template = template.replaceAll("%SUBTITLE%", blogSubtitle);
			template = template.replaceAll("%AUTHOR_USERNAME%",targUser.get(FieldNames.FIELD_NAME));
			StringBuilder buff = new StringBuilder();
	
			for(int i = 0; i < articleData.length;i++){
				String section = getArticleSectionTemplate(request.getServletContext());
				String meta = getArticleMetaDataTemplate(request.getServletContext());
				if(section == null || section.length() == 0){
					AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "Failed to load section template");
					response.sendError(404);
					return;
				}
				if(meta == null || meta.length() == 0){
					AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "Failed to load metadata template");
					response.sendError(404);
					return;
				}
	
				BaseRecord data = articleData[0];
	
				/// For lists, inject [h1] and [h2] if they don't already exist based on the data name and description
				///
				StringBuilder preface = new StringBuilder();
				String contentDataStr = data.get(FieldNames.FIELD_BYTE_STORE);
				String desc = data.get(FieldNames.FIELD_DESCRIPTION);
				if(contentDataStr.indexOf("[h1]") == -1) preface.append("[h1]" + data.get(FieldNames.FIELD_NAME) + "[/h1]");
				if(contentDataStr.indexOf("[h2]") == -1 && desc != null && desc.length() > 0) preface.append("[h2]" + desc + "[/h2]");
				String contentStr = AMCodeUtil.decodeAMCodeToHtml(preface.toString() + contentDataStr);
				String linkUrl = "/AccountManagerService/article" + orgPath + "/" + targUser.get(FieldNames.FIELD_NAME) + "/" + data.get(FieldNames.FIELD_NAME);
				Matcher headerM = headerLinkPattern.matcher(contentStr);
				/// this is an error if it doesn't find because it was just added when missing
				///
				String articleTitle = "";
				if(headerM.find()){
					/// If single mode, change the page title to be that of the article
					///
					/// articleTitle = headerM.group(1);
					
					if(singleMode == true) template = template.replaceAll("%PAGETITLE%",articleTitle);
					/// otherwise, add a link to the single instance
					///
					else contentStr = headerM.replaceFirst("<h1><a class = \"uwm-content-title-link\" href = \"" + linkUrl + "\">$1</a></h1>");
					
				}
				String metaStr = "Written by " + author + " on " + CalendarUtil.exportDateAsString(data.get(FieldNames.FIELD_CREATED_DATE), "yyyy/MM/dd");
				meta = meta.replace("%META%", metaStr);
	
				
				StringBuilder tagStr = new StringBuilder();
	
				
				section = section.replace("%CONTENT%", contentStr)
						.replace("%ARTICLE_ID%", data.get(FieldNames.FIELD_OBJECT_ID))
						.replace("%ARTICLE_TAGS%", tagStr.toString())
						.replace("%META%", meta)
				;
				buff.append(section + "\n");
				template = template.replace("%NAVIGATION%",navBack + navForward);
				template = template.replace("%CONTENT%", buff.toString());
	
			} /// end for
		}
		catch(ModelException e) {
			logger.error(e);
		}
		

		
		response.setContentType("text/html; charset=UTF-8");
		response.setContentLength(template.length());
		response.getWriter().write(template);
		response.flushBuffer();

	}
}
