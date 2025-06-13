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
package org.cote.servlets;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.fileupload2.core.FileItemInput;
import org.apache.commons.fileupload2.core.FileItemInputIterator;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


/**
 * Servlet implementation class MediaFormServlet
 */
public class MediaFormServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	public static final Logger logger = LogManager.getLogger(MediaFormServlet.class);
       
    /**
     * @see HttpServlet#HttpServlet()
     */
    public MediaFormServlet() {
        super();
        
    }

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
	}
	
	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		boolean bBit = false;
		
		// Create a new file upload handler
		JakartaServletFileUpload upload = new JakartaServletFileUpload();
		String responseId = null;
		String name = null;
		String description = null;
		String contentType = null;
		long groupId = 0;
		String groupPath = null;
		String orgPath = null;
		long id = 0;
		byte[] data = new byte[0];
		BaseRecord streamRec = null;

		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		logger.info("Parsing ...");
		// Parse the request
		try{
			FileItemInputIterator iter = upload.getItemIterator(request);

			while (iter.hasNext()) {
			    FileItemInput item = iter.next();

			    String fname = item.getFieldName();
			    InputStream stream = item.getInputStream();
			    if (item.isFormField()) {
			    	if(fname.equals("responseId")){
			    		responseId = IOUtils.toString(stream);
			    	}
			    	else if(fname.equals("description")){
			    		description = IOUtils.toString(stream);
			    	}
			    	else if(fname.equals("groupId")){
			    		groupId = Long.parseLong(IOUtils.toString(stream));
			    	}
			    	else if(fname.equals("groupPath")){
			    		groupPath = IOUtils.toString(stream);
			    	}
			    	else if(fname.equals("organizationPath")){
			    		orgPath = IOUtils.toString(stream);
			    	}
			    	else if(fname.equals(FieldNames.FIELD_NAME)){
			    		name = IOUtils.toString(stream);
			    	}
			    	else if(fname.equals(FieldNames.FIELD_ID)){
			    		id = Long.parseLong(IOUtils.toString(stream));
			    	}
			    } else {
			    	logger.info("Handle file upload stream");
			    	logger.info("Stream to data: " + (user != null ? user.get(FieldNames.FIELD_URN) : "Null user") + " / name " + " / " + groupPath + " / " + groupId);
			    	bBit = StreamUtil.streamToData(user, name, description, groupPath, groupId, stream);
			    }
			    stream.close();
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException | FactoryException | NumberFormatException | IndexException | ReaderException | ModelException e){
			logger.error(e);
		}
		
		response.setContentType("text/html");
		response.getWriter().write("<html><head><title>Media Form</title><script type = \"text/javascript\">" + getResponseScript(responseId, bBit) + "</script></head>");
		response.getWriter().write("</html>");
		response.flushBuffer();
	}
	
	private static String getResponseScript(String responseId, boolean success){
		StringBuilder buff = new StringBuilder();
		buff.append("window.onload = Init;");
		buff.append("function Init(){");
		buff.append("if(window != window.parent && typeof window.parent.Hemi == \"object\"){");
        buff.append("window.parent.Hemi.message.service.publish(\"frame_response\",{id:\"" + responseId + "\",status:" + (success ? "true":"false") + "});");
		buff.append("}");
		buff.append("}");
		buff.append("</script>");
		return buff.toString();
	}

}
