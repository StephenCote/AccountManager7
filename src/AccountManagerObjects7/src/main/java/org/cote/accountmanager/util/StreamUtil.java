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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;

public class StreamUtil {
	public static final Logger logger = LogManager.getLogger(StreamUtil.class);
	public static long copyStream(InputStream in, OutputStream out) throws IOException{
		return in.transferTo(out);
	}

	public static byte[] getStreamBytes(InputStream in) throws IOException{
		return in.readAllBytes();
	}

	
	public static String streamToString(BufferedInputStream in) throws IOException{
		return new String(getStreamBytes(in),StandardCharsets.UTF_8);
	}
	
	public static byte[] fileHandleToBytes(File file){
		if(file == null || file.exists() == false){
			return new byte[0];
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try{
			if(file.exists()){
				FileInputStream fis=new FileInputStream(file);
				copyStream(fis,baos);
				fis.close();
			}
		}
		catch(IOException e){
			logger.error("StreamUtil:: fileHandleToBytes: " + e.toString());
		}
		return baos.toByteArray();
	}
	public static byte[] fileToBytes(String fileName){
		File f = new File(fileName);
		
		return fileHandleToBytes(f);
		
	}
	
	private static BaseRecord getSegment(BaseRecord stream, byte[] data, long start) throws FieldException, ModelNotFoundException, ValueException {
		BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
		seg.set(FieldNames.FIELD_STREAM, data);
		if(stream != null) {
			seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
		}
		seg.set(FieldNames.FIELD_START_POSITION, start);
		return seg;
	}
	
	public static boolean streamInPlaceToData(BaseRecord user, String filePath, String groupPath) {
		boolean outBool = false;
		StreamSegmentUtil ssUtil = new StreamSegmentUtil();
		if(ssUtil.isRestrictedPath(filePath)) {
			logger.error("Path is restricted: " + filePath);
			return outBool;
		}
		Path path = Paths.get(filePath);
		String name = path.getFileName().toString();
		
		BaseRecord group = IOSystem.getActiveContext().getAccessPoint().make(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString());
		if(group == null) {
			logger.error("Unable to find or create group: " + groupPath);
			return outBool;
		}
		long groupId = group.get(FieldNames.FIELD_ID);

		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_NAME, name);
		q.setRequest(new String[] {FieldNames.FIELD_ID});
		BaseRecord qdata = IOSystem.getActiveContext().getAccessPoint().find(user, q);
    	
		if(qdata != null) {
    		logger.error("Data " + name + " already exists in " + groupPath);
    		return outBool;
    	}
    	
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		
		try {
			BaseRecord stream = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, plist);
			stream.set(FieldNames.FIELD_STREAM_SOURCE, filePath);
			stream.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(name));
			stream.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			ssUtil.updateStreamSize(stream);
			long size = stream.get(FieldNames.FIELD_SIZE);
			if(size == 0L) {
				logger.error("Failed to obtain stream size");
				return outBool;
			}
			// logger.info("Create stream object - " + name);
			BaseRecord nstream = IOSystem.getActiveContext().getAccessPoint().create(user, stream);
			if(nstream == null) {
				logger.error("Failed to create stream object");
				return outBool;
			}
			
			BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
			data.set(FieldNames.FIELD_GROUP_ID, groupId);
			data.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(name));
			data.set(FieldNames.FIELD_STREAM, nstream);
			data.set(FieldNames.FIELD_SIZE, size);
			// logger.info("Create data object - " + name);
			BaseRecord ndata = IOSystem.getActiveContext().getAccessPoint().create(user, data);
			if(ndata == null) {
				logger.error("Failed to create data object");
				return outBool;
			}
			outBool = true;
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return outBool;
	}
	
	public static boolean streamToData(BaseRecord user, String name, String description, String groupPath, long groupId, InputStream stream) throws FieldException, ModelNotFoundException, ValueException, IOException, FactoryException, IndexException, ReaderException {
    	String contentType = ContentTypeUtil.getTypeFromExtension(name);
    	if(contentType == null) {
    		throw new ValueException("Invalid content type");
    	}
    	BaseRecord group = null;
    	if(groupId > 0L) {
    		group = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_GROUP, groupId);
    	}
    	else if(groupPath != null) {
    		group = IOSystem.getActiveContext().getAccessPoint().make(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString());
    	}
    	if(group == null) {
    		throw new ReaderException("Invalid group: " + groupPath + " / " + groupId);
    	}
    	if(groupId == 0L) {
    		groupId = group.get(FieldNames.FIELD_ID);
    	}
    	BaseRecord[] chk = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_DATA, groupId, name);
    	if(chk != null && chk.length > 0) {
    		logger.warn("Data " + name + " already exists");
    		return false;
    	}
    	
    	int read = 0;
    	boolean outBool = false;
    	int maxReadSize = 1048576;
    	byte[] data = new byte[0];
    	byte[] bytes = new byte[4096*4];
    	BaseRecord streamRec = null;
    	ByteArrayOutputStream baos = new ByteArrayOutputStream();
    	int total = 0;
    	while ((read = stream.read(bytes, 0, bytes.length)) != -1) {
    		total += read;
    		boolean streamWrite = (baos.size() >= maxReadSize);
    		
    		if(streamWrite) {
    			BaseRecord seg = getSegment(streamRec, baos.toByteArray(), 0);
    			if(streamRec == null) {
        			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
        			plist.parameter(FieldNames.FIELD_NAME, UUID.randomUUID().toString());
        			streamRec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, plist);
        			streamRec.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
        			streamRec.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
        			List<BaseRecord> segs = streamRec.get(FieldNames.FIELD_SEGMENTS);
        			segs.add(seg);
        			streamRec = IOSystem.getActiveContext().getAccessPoint().create(user, streamRec);
        			/// Bug/Patch - because stream uses a provider to automatically configure the source, and because the source is an encrypted field value, the field won't be encrypted because the field providers fire before the model provider
        			/// Therefore, it's necessary to update the stream source
        			IOSystem.getActiveContext().getAccessPoint().update(user, streamRec.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_STREAM_SOURCE, FieldNames.FIELD_GROUP_ID}));
    			}
    			else {
    				IOSystem.getActiveContext().getAccessPoint().create(user, seg);
    			}
    			baos.reset();
    		}
   		  	baos.write(bytes, 0, read);
    	}
    	if(streamRec == null) {
    		data = baos.toByteArray();
    	}
    	else if(baos.size() > 0) {
			BaseRecord seg = getSegment(streamRec, baos.toByteArray(), 0);
			IOSystem.getActiveContext().getAccessPoint().create(user, seg);
    	}
    	
    	if((streamRec != null || data.length > 0) && user != null){
			try{
    			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
    			plist.parameter(FieldNames.FIELD_NAME, name);

    			BaseRecord newData = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
    			if(groupId > 0L) newData.set(FieldNames.FIELD_GROUP_ID, groupId);
				newData.set(FieldNames.FIELD_DESCRIPTION, description);
				newData.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
				newData.set(FieldNames.FIELD_BYTE_STORE,  data);
				newData.set(FieldNames.FIELD_STREAM, streamRec);
				newData = IOSystem.getActiveContext().getAccessPoint().create(user, newData);
				outBool = (newData != null);
			}
			 catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
				logger.error(e);
			} 
		}
		else {
			logger.error("No stream, data, or user");
		}
    	
    	
    	return outBool;
	}
	
}
