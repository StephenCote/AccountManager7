package org.cote.accountmanager.io.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.StreamUtil;

public class StreamSegmentUtil {
	public static final Logger logger = LogManager.getLogger(StreamSegmentUtil.class);
	
	// private List<Path> permittedPaths = new ArrayList<>();;
	
	public StreamSegmentUtil() {
		/*
		permittedPaths.add(Paths.get(IOFactory.DEFAULT_FILE_BASE));
		for(String p : IOFactory.PERMIT_PATH) {
			permittedPaths.add(Paths.get(p));	
		}
		*/
	}
	
	public byte[] streamToEnd(String streamId, long start, long len) {

		StreamSegmentUtil ssu = new StreamSegmentUtil();
		BaseRecord stream = ssu.getStream(streamId);
		try {
			StreamUtil.unboxStream(stream, false);
		} catch (ModelException e) {
			logger.error(e);
		}
		
		BaseRecord seg = ssu.newSegment(streamId, start, len);
		BaseRecord rseg = null;
		long osize = 0L;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {

			while((rseg = IOSystem.getActiveContext().getReader().read(seg)) != null && (osize = (long)rseg.get(FieldNames.FIELD_SIZE)) > 0L) {
				long newPos = (long)seg.get(FieldNames.FIELD_START_POSITION) + (long)rseg.get(FieldNames.FIELD_SIZE);
				seg.set(FieldNames.FIELD_START_POSITION, newPos);
				//logger.info("Next seg: " + seg.toFullString());
				baos.write(rseg.get(FieldNames.FIELD_STREAM));
				if(osize < len) {
					break;
				}
			}
		}
		catch(ReaderException | FieldException | ValueException | ModelNotFoundException | IOException e) {
			logger.error(e);
		}
		return baos.toByteArray();
	}
	
	public 	BaseRecord newSegment(String streamId) {
		return newSegment(streamId, 0L, 0L);
	}
	public BaseRecord newSegment(String streamId, long startPosition, long length) {
		BaseRecord seg = null;
		try {
			seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM_ID, streamId);
			seg.set(FieldNames.FIELD_START_POSITION, startPosition);
			seg.set(FieldNames.FIELD_LENGTH, length);
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		return seg;
	}

	protected BaseRecord getStream(BaseRecord segment) {
		String streamId = segment.get(FieldNames.FIELD_STREAM_ID);
		return getStream(streamId);
	}
	
	public BaseRecord getStream(String streamId) {
		BaseRecord stream = null;
		if(streamId != null) {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_STREAM, FieldNames.FIELD_OBJECT_ID, streamId);
			q.planCommon(false);
			stream = IOSystem.getActiveContext().getSearch().findRecord(q);

		}
		return stream;
	}
	
	public boolean isRestrictedPath(String path) {
		boolean restricted = true;
		if(path == null || path.length() == 0) {
			logger.warn("Stream source is null");
			return restricted;
		}

		for(String spath : IOFactory.PERMIT_PATH) {
			Path cpath = Paths.get(spath);
			Path path2 = Paths.get(path).toAbsolutePath();
			if(path2.startsWith(cpath.toAbsolutePath())) {
				restricted = false;
				break;
			}
		}

		return restricted;
	}
	
	public String getFileStreamPath(BaseRecord stream) {
		String source = stream.get(FieldNames.FIELD_STREAM_SOURCE);
		
		if(source == null) {
			long organizationId = stream.get(FieldNames.FIELD_ORGANIZATION_ID);
			long groupId = stream.get(FieldNames.FIELD_GROUP_ID);
			String objectId = stream.get(FieldNames.FIELD_OBJECT_ID);
			if(objectId == null) {
				logger.warn("Object id is missing.  Assigning a temporary name");
				objectId = "Temporary-" + UUID.randomUUID().toString();
			}
			String orgPath = stream.get(FieldNames.FIELD_ORGANIZATION_PATH);
			String groupPath = stream.get(FieldNames.FIELD_GROUP_PATH);
			String contentType = stream.get(FieldNames.FIELD_CONTENT_TYPE);
			String ext = "";
			if(contentType != null) {
				String text = ContentTypeUtil.getExtensionFromType(contentType);
				if(text != null && text.length() > 0) {
					ext = "." + text;
				}
			}
			if(groupPath == null) {
				groupPath = "Anonymous";
			}
			if(orgPath == null) {
				orgPath = "Anonymous";
			}
			else {
				orgPath = orgPath.substring(1).replace('/', '.');
			}
			String fullPath = IOFactory.DEFAULT_FILE_BASE + "/.streams/" + orgPath + groupPath + "/" + objectId + ext;
			String densePath = IOFactory.DEFAULT_FILE_BASE + "/.streams/" + organizationId + "/" + groupId + "/" + objectId + ext;

			source = densePath;
			try {
				stream.set(FieldNames.FIELD_STREAM_SOURCE, source);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		else {
			/// When encrypting the streamSource field, it's necessary to manually decrypt the field value during initial create stream operations and auto-writing segments
			/// This is because the streamSource field is encrypted before the model-level stream provider is invoked, wherein the segments are written to the then encrypted stream source.
			///
			if(stream.inherits(ModelNames.MODEL_VAULT_EXT)) {
				FieldSchema fs = RecordFactory.getSchema(ModelNames.MODEL_STREAM).getFieldSchema(FieldNames.FIELD_STREAM_SOURCE);
				if(fs.isEncrypt()) {
					List<String> vfields = stream.get(FieldNames.FIELD_VAULTED_FIELDS);
					if(vfields.contains(FieldNames.FIELD_STREAM_SOURCE)) {
						logger.warn("Decrypting stream source in-flight - This will happen when segments are auto-created with stream creation");
						source = null;
						BaseRecord crec = stream.copyRecord();
						OrganizationContext org = IOSystem.getActiveContext().findOrganizationContext(crec);
						VaultBean vault = org.getVault();
						try {
							VaultService.getInstance().unvaultField(vault, crec, crec.getField(FieldNames.FIELD_STREAM_SOURCE));
							source = crec.get(FieldNames.FIELD_STREAM_SOURCE);
						} catch (ModelException | ValueException | FieldException e) {
							logger.error(e);
						}
					}
					else {
						/// Expected behavior that the value is already decrypted
					}
				}
			}
		}
		return source;
	}
	
	public void updateStreamSize(BaseRecord stream) {
		try {
			stream.set(FieldNames.FIELD_SIZE, getFileStreamSize(stream));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public long getFileStreamSize(BaseRecord stream) {
		String streamSource = getFileStreamPath(stream); 
		//stream.get(FieldNames.FIELD_STREAM_SOURCE);
		long outSize = 0L;
		if(streamSource != null) {
			outSize = getFileStreamSize(streamSource);
		}
		return outSize;
	}
	private long getFileStreamSize(String streamSource) {
		StreamSegmentUtil ssu = new StreamSegmentUtil();
		long size = 0L;
		try (
			RandomAccessFile writer = new RandomAccessFile(streamSource, "r");
			FileChannel channel = writer.getChannel()
		){
			size = channel.size();
        }
	    catch (IOException e) {
			logger.error(e);
		}
		return size;

}
}
