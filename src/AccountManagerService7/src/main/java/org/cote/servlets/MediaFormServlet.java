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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.Arrays;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.service.util.ServiceUtil;


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
	
	private BaseRecord getSegment(BaseRecord stream, byte[] data) throws FieldException, ModelNotFoundException, ValueException {
		BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
		seg.set(FieldNames.FIELD_STREAM, data);
		seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
		return seg;
		
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		boolean bBit = false;
		
		// Create a new file upload handler
		ServletFileUpload upload = new ServletFileUpload();
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
			FileItemIterator iter = upload.getItemIterator(request);

			while (iter.hasNext()) {
			    FileItemStream item = iter.next();

			    String fname = item.getFieldName();
			    InputStream stream = item.openStream();
			    if (item.isFormField()) {
			    	if(fname.equals("responseId")){
			    		responseId = Streams.asString(stream);
			    	}
			    	else if(fname.equals("description")){
			    		description = Streams.asString(stream);
			    	}
			    	else if(fname.equals("groupId")){
			    		groupId = Long.parseLong(Streams.asString(stream));
			    	}
			    	else if(fname.equals("groupPath")){
			    		groupPath = Streams.asString(stream);
			    	}
			    	else if(fname.equals("organizationPath")){
			    		orgPath = Streams.asString(stream);
			    	}
			    	else if(fname.equals("name")){
			    		name = Streams.asString(stream);
			    	}
			    	else if(fname.equals("id")){
			    		id = Long.parseLong(Streams.asString(stream));
			    	}
			    } else {
			    	
			    	contentType = ContentTypeUtil.getTypeFromExtension(item.getName());
			    	int read = 0;
			    	int maxReadSize = 1048576;
			    	byte[] bytes = new byte[maxReadSize];
			    	streamRec = null;
			    	ByteArrayOutputStream baos = new ByteArrayOutputStream();
			    	while ((read = stream.read(bytes, 0, bytes.length)) != -1) {
			    		logger.info("Reading " + read);
			    	  /// Convert to stream?
			    		boolean streamWrite = (baos.size() >= maxReadSize);
			    		if(streamRec != null && streamWrite) {
			    			logger.info("Writing segment: " + read + " bytes");
			    			BaseRecord seg = getSegment(streamRec, Arrays.copyOf(bytes, read));
			    			IOSystem.getActiveContext().getAccessPoint().create(user, seg);
			    			baos = new ByteArrayOutputStream();
			    		}
			    		else if(streamWrite) {
			    			logger.info("Writing stream: " + read + " bytes");
			    			ParameterList plist = ParameterList.newParameterList("path", groupPath);
			    			plist.parameter("name", UUID.randomUUID().toString());

			    			streamRec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, plist);
			    			streamRec.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			    			streamRec.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
			    			BaseRecord seg = getSegment(streamRec, Arrays.copyOf(bytes, read));
			    			List<BaseRecord> segs = streamRec.get(FieldNames.FIELD_SEGMENTS);
			    			segs.add(seg);
			    			streamRec = IOSystem.getActiveContext().getAccessPoint().create(user, streamRec);
			    			baos = new ByteArrayOutputStream();
				    	}
				    	else {
				    		  // data = Arrays.copyOf(bytes, read);
				    		  baos.write(bytes, 0, read);
				    		  
				    	}
			    	}
			    	if(streamRec == null) {
			    		data = baos.toByteArray();
			    	}
			    	else if(baos.size() > 0) {
		    			logger.info("Writing segment: " + baos.size() + " bytes");
		    			BaseRecord seg = getSegment(streamRec, baos.toByteArray());
		    			IOSystem.getActiveContext().getAccessPoint().create(user, seg);
			    	}
			    }
			    stream.close();
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException | FactoryException | NumberFormatException | FileUploadException e){
			logger.error(e);
		}
	
		if((streamRec != null || data.length > 0) && user != null){
			try{
    			ParameterList plist = ParameterList.newParameterList("path", groupPath);
    			plist.parameter("name", name);

    			BaseRecord newData = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
    			if(groupId > 0L) newData.set(FieldNames.FIELD_GROUP_ID, groupId);
				newData.set(FieldNames.FIELD_DESCRIPTION, description);
				newData.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
				newData.set(FieldNames.FIELD_BYTE_STORE,  data);
				newData.set(FieldNames.FIELD_STREAM, streamRec);
				newData = IOSystem.getActiveContext().getAccessPoint().create(user, newData);
				bBit = (newData != null);
			}
			 catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
				logger.error(e);
			} 
		}
		else {
			logger.error("No stream, data, or user");
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
