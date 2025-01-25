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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.security.VaultService;

public class StreamUtil {
	public static final Logger logger = LogManager.getLogger(StreamUtil.class);
	private static int DEFAULT_STREAM_CUTOFF = 1048576;
	private static int STREAM_CUTOFF = DEFAULT_STREAM_CUTOFF;

	private static StreamSegmentUtil ssUtil = new StreamSegmentUtil();
	public static void setStreamCutoff(int cut) {
		if(cut > 0) {
			STREAM_CUTOFF = cut;
		}
	}
	
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
	
	/// box, ubox, isbox directly on the file target.
	
	private static String boxExt = ".box";
	private static Map<String, Boolean> unboxedMap = new HashMap<>();
	public static boolean isStreamUnboxed(BaseRecord stream) {
		StreamEnumType type = stream.getEnum(FieldNames.FIELD_TYPE);
		if(type != StreamEnumType.FILE) {
			return true;
		}
		
		String oid = stream.get(FieldNames.FIELD_OBJECT_ID);
		if (unboxedMap.containsKey(oid)) {
			return unboxedMap.get(oid);
		}
		/// Secondary check
		File f = null;
		try {
			f = new File(getFileStreamPath(stream));
		} catch (ModelException e) {
			logger.error(e);
		}
		if(f != null && f.exists()) {
			unboxedMap.put(oid, true);
		}
		return false;
	}
	public static int clearUnboxedStreams() throws ModelException {
		StreamSegmentUtil ssu = new StreamSegmentUtil();
		int count = 0;
		for(String id : unboxedMap.keySet()) {
			
			BaseRecord stream = ssu.getStream(id);
			if(stream != null) {
				clearUnboxedStream(stream);
				count++;
			}
		}
		return count;
	}
	public static boolean clearUnboxedStream(BaseRecord stream) throws ModelException {
		String path = getFileStreamPath(stream);
		String bpath = getFileBoxPath(stream);
		File f = new File(path);
		File f2 = new File(bpath);
		if(!f2.exists()) {
			logger.error("Encrypted file " + bpath + " doesn't exist");
			return false;
		}
		if(f.exists()) {
			f.delete();
		}
		String oid = stream.get(FieldNames.FIELD_OBJECT_ID);
		unboxedMap.remove(oid);
		return true;
	}
	
	/// Given some file that is the result of a data stream (eg: A large video file, etc), encrypt the file and discard the original
	///
	public static boolean boxStream(BaseRecord stream, boolean overwrite) throws ModelException {

		StreamEnumType type = stream.getEnum(FieldNames.FIELD_TYPE);
		if(type != StreamEnumType.FILE) {
			return true;
		}
		String path = getFileStreamPath(stream);
		String bpath = getFileBoxPath(stream);
		File f = new File(path);
		File f2 = new File(bpath);
		
		/// box exists
		if(f2 != null && f2.exists() && !overwrite) {
			/// box exists
			logger.info("Box " + bpath + " already exists");
			/// ubox exists too
			if(f.exists()) {
				logger.warn("Unencrypted file " + path + " should not exist.");
			}
			return true;
		}
		
		/// unbox must exist
		if(!f.exists()) {
			logger.error("Unencrypted file " + path + " must exist");
			return false;
		}
		if(f2.exists()) {
			f2.delete();
		}
		return rebox(stream, f, bpath, true);
	}

	/// Given some file that is the result of a data stream (eg: A large video file, etc), encrypt the file and discard the original
	///
	public static boolean unboxStream(BaseRecord stream, boolean force) throws ModelException {

		if(!force && isStreamUnboxed(stream)) {
			return true;
		}
		
		StreamEnumType type = stream.getEnum(FieldNames.FIELD_TYPE);
		if(type != StreamEnumType.FILE) {
			return true;
		}
		
		String path = getFileStreamPath(stream);
		String bpath = getFileBoxPath(stream);
		File f = new File(path);
		File f2 = new File(bpath);
		
		/// ubox exists
		if(!force && f != null && f.exists()) {
			logger.warn("Unencrypted file " + path + " exists and it shouldn't");
			String oid = stream.get(FieldNames.FIELD_OBJECT_ID);
			unboxedMap.put(oid, true);
			return true;
		}
		
		/// box must exist
		if(!f2.exists()) {
			logger.error("Encrypted file " + path + " must exist");
			return false;
		}
		if(f.exists()) {
			f.delete();
		}
		
		return rebox(stream, f2, path, false);
	}

	private static boolean rebox(BaseRecord stream, File f1, String path, boolean enc) {
		boolean outBool = false;
		VaultBean vault = IOSystem.getActiveContext().findOrganizationContext(stream).getVault();
		if(vault == null) {
			logger.error("Failed to retrieve vault");
			return false;
		}
		if(RecordUtil.isIdentityRecord(stream)) {
			IOSystem.getActiveContext().getReader().populate(stream, new String[] {FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID, FieldNames.FIELD_VAULTED});
		}
		String keyId = stream.get(FieldNames.FIELD_KEY_ID);
		if(keyId == null) {
			if(!enc) {
				logger.error("Stream does not define a key.");
				return false;
			}
			try {
				VaultService.getInstance().vaultModel(vault, stream);
				Queue.queueUpdate(stream, new String[] {FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID, FieldNames.FIELD_VAULTED});
				Queue.processQueue();
			} catch (ValueException | ModelException e) {
				logger.error(e);
				return false;
			}
		}
		
		CryptoBean key = VaultService.getInstance().getVaultCipher(vault, keyId);
		if(key == null) {
			logger.error("Vault cipher is null");
			return false;
		}
		
		logger.info((enc ? "B" : "Unb") + "oxing " + path + " ...");

		/// read source file
		byte[] eval = new byte[0];
		if(enc) {
			eval = CryptoUtil.encipher(key,  fileHandleToBytes(f1));
		}
		else {
			eval = CryptoUtil.decipher(key,  fileHandleToBytes(f1));
		}
		if (eval == null || eval.length == 0) {
			logger.error("Failed to decipher file");
			return false;
		}

		try {
			writeFile(path, eval, 0);
			outBool = true;
		} catch (ValueException e) {
			logger.error(e);
		}
		return outBool;
	}
	
	public static String getFileBoxPath(BaseRecord stream) throws ModelException {
		String path = getFileStreamPath(stream);
		if(path != null) {
			return path + boxExt;	
		}
		return null;
	}
	public static String getFileStreamPath(BaseRecord stream) throws ModelException {
		String path = ssUtil.getFileStreamPath(stream);
		if (ssUtil.isRestrictedPath(path)) {
			throw new ModelException("Path " + path + " is restricted");
		}
        return path;		
	}
	
	private static void writeFile(String path, byte[] bytes, long startPosition) throws ValueException {
		FileUtil.makePath(path.substring(0, path.lastIndexOf("/")));
		try (
			RandomAccessFile writer = new RandomAccessFile(path, "rw");
			FileChannel channel = writer.getChannel()
		){
            FileLock lock = channel.tryLock();
            while (!lock.isValid()) {
                lock = channel.tryLock();
            }
            if(lock.isValid()) {
				if(startPosition == 0L) {
					startPosition = channel.size();
				}
				channel.position(startPosition);
				ByteBuffer buff = ByteBuffer.wrap(bytes);
				channel.write(buff);
				lock.release();
            }
	    } catch (IOException e) {
	    	
			logger.error(e);
			throw new ValueException(e);
		}

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
			/*
			ssUtil.updateStreamSize(stream);
			*/
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
	
	public static boolean streamToData(BaseRecord user, String name, String description, String groupPath, long groupId, InputStream stream) throws FieldException, ModelNotFoundException, ValueException, IOException, FactoryException, IndexException, ReaderException, ModelException {
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
    	int maxReadSize = STREAM_CUTOFF;
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
    	logger.info("Stream to data length: " + baos.size());
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
				if(streamRec != null) {
					boxStream(streamRec, false);
					clearUnboxedStream(streamRec);
				}
				
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
