package org.cote.accountmanager.provider;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.FileUtil;

public class StreamProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(StreamProvider.class);
	
	public Path[] permittedPaths = new Path[0];
	
	public StreamProvider() {
		permittedPaths =  new Path[]{Paths.get(IOFactory.DEFAULT_FILE_BASE).toAbsolutePath()};
	}
	
	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		
		if(!model.inherits(ModelNames.MODEL_STREAM)) {
			throw new ModelException("Model does not inherit from " + ModelNames.MODEL_STREAM);
		}
		if(!model.hasField(FieldNames.FIELD_OBJECT_ID)) {
			throw new ModelException("Model " + model.getModel() + " does not include the " + FieldNames.FIELD_OBJECT_ID + " field.");
		}
		if(operation == RecordOperation.CREATE || operation == RecordOperation.UPDATE) {
			logger.info("***** Create/Update with segments");
			StreamEnumType set = StreamEnumType.valueOf(model.get("type"));
			switch(set) {
				case FILE:
					// logger.info("Handle file stream write");
					writeSegments(model);
					break;
				default:
					logger.error("UNHANDLED STREAM TYPE: " + set.toString());
					break;
			}
			// logger.info(model.toFullString());
		}
		else if(operation == RecordOperation.READ) {
			logger.info("***** Read segment");
			/*
			StackTraceElement[] st = new Throwable().getStackTrace();
			for(int i = 0; i < st.length; i++) {
				logger.error(st[i].toString());
			}
			*/
		}
		else if(operation == RecordOperation.INSPECT) {
			// logger.info("Skip inspect segment");
		}

	}
	
	private String getFileStreamPath(BaseRecord stream) {
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
	

	private boolean isRestrictedPath(String path) {
		boolean restricted = false;
		for(Path cpath : permittedPaths) {
			//Path path1 = Paths.get(cpath).toAbsolutePath();
			Path path2 = Paths.get(path).toAbsolutePath();
			if(!path2.startsWith(cpath)) {
				restricted = true;
				break;
			}
		}
		return restricted;
	}
	
	private void writeSegments(BaseRecord stream) throws ModelException {
		List<BaseRecord> segments = stream.get(FieldNames.FIELD_SEGMENTS);
		String path = getFileStreamPath(stream);
		if(isRestrictedPath(path)) {
			throw new ModelException("Path " + path + " is restricted");
		}
		FileUtil.makePath(path.substring(0, path.lastIndexOf("/")));
		long size = 0L;
		try (
			RandomAccessFile writer = new RandomAccessFile(path, "rw");
			FileChannel channel = writer.getChannel()
		){
            FileLock lock = channel.tryLock();

            while (!lock.isValid()) {
                lock = channel.tryLock();
            }
            if(lock.isValid()) {
				for(BaseRecord segment : segments) {
					long start = segment.get(FieldNames.FIELD_START_POSITION);
					if(start == 0L) {
						start = channel.size();
					}
					channel.position(start);
					ByteBuffer buff = ByteBuffer.wrap(segment.get(FieldNames.FIELD_STREAM));
					channel.write(buff);
					size = channel.size();
				}
				lock.release();
            }
	    } catch (IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model,
			FieldSchema lfield, FieldType field)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		// Nothing to do

	}

}
