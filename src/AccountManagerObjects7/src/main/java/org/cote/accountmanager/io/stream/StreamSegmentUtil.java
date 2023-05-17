package org.cote.accountmanager.io.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ContentTypeUtil;

public class StreamSegmentUtil {
	public static final Logger logger = LogManager.getLogger(StreamSegmentUtil.class);
	
	private List<Path> permittedPaths = new ArrayList<>();;
	
	public StreamSegmentUtil() {
		// permittedPaths =  new Path[]{Paths.get(IOFactory.DEFAULT_FILE_BASE).toAbsolutePath()};
		permittedPaths.add(Paths.get(IOFactory.DEFAULT_FILE_BASE));
		for(String p : IOFactory.PERMIT_PATH) {
			permittedPaths.add(Paths.get(p));	
		}
	}
	
	public void addPermittedPath(String path) {
		permittedPaths.add(Paths.get(path));
	}
	
	public byte[] streamToEnd(String streamId, long start, long len) {

		BaseRecord seg = new StreamSegmentUtil().newSegment(streamId, start, len);
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
		BaseRecord stream = null;
		if(streamId != null) {
			try {
				stream = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_STREAM, streamId);
			} catch (ReaderException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		return stream;
	}
	
	public boolean isRestrictedPath(String path) {
		boolean restricted = true;
		if(path == null || path.length() == 0) {
			logger.warn("Stream source is null");
			return restricted;
		}
		for(Path cpath : permittedPaths) {
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
			source = IOFactory.DEFAULT_FILE_BASE + "/streams/" + orgPath + groupPath + "/" + stream.get(FieldNames.FIELD_OBJECT_ID) + ext;
			try {
				stream.set(FieldNames.FIELD_STREAM_SOURCE, source);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return source;
	}
}
