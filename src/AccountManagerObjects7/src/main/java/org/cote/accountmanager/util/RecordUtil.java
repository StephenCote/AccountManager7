package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;

public class RecordUtil {
	public static final Logger logger = LogManager.getLogger(RecordUtil.class);
	
	private final IReader reader;
	private final IWriter writer;
	private final ISearch search;
	private final IPath pathUtil;
	
	public RecordUtil(IReader reader, IWriter writer, ISearch search) {
		this.reader = reader;
		this.writer = writer;
		this.search = search;
		this.pathUtil = IOFactory.getPathUtil(reader, writer, search);
	}

	public RecordUtil(IOContext context) {
		this.reader = context.getReader();
		this.writer = context.getWriter();
		this.search = context.getSearch();
		this.pathUtil = context.getPathUtil();
	}

	public static String toFilteredJSONString(BaseRecord rec) {
		sortFields(rec);
		return JSONUtil.exportObject(rec, RecordSerializerConfig.getFilteredModule());
	}
	
	public static String toJSONString(BaseRecord rec) {
		sortFields(rec);
		return JSONUtil.exportObject(rec, RecordSerializerConfig.getUnfilteredModule());
	}
	
	public static String toFullJSONString(BaseRecord rec) {
		sortFields(rec);
		return JSONUtil.exportObject(rec, RecordSerializerConfig.getForeignUnfilteredModule());
	}
	
	public static String hash(BaseRecord rec) {
		List<String> fieldNames = new ArrayList<>();
		RecordUtil.sortFields(rec);
		ModelSchema lbm = RecordFactory.getSchema(rec.getModel());

		for(FieldType f : rec.getFields()) {
			FieldSchema lbf = lbm.getFieldSchema(f.getName());
			if(lbf.isRestricted() || lbf.isEphemeral() || lbf.isVirtual() || f.getName().equals(FieldNames.FIELD_JOURNAL) || f.getName().equals(FieldNames.FIELD_SIGNATURE)) {
				continue;
			}
			fieldNames.add(f.getName());
		}

		BaseRecord crec = rec.copyRecord(fieldNames.toArray(new String[0]));
		return CryptoUtil.getDigestAsString(toJSONString(crec));
	}
	public static String hash(BaseRecord rec, String[] fieldNames) {
		BaseRecord crec = rec.copyRecord(fieldNames);
		return CryptoUtil.getDigestAsString(toJSONString(crec));
	}	
	public static boolean isEqual(BaseRecord rec, BaseRecord rec2) {
		return hash(rec).equals(hash(rec2));
	}
	
	public static void sortFields(BaseRecord rec) {
		if(rec != null) {
			rec.getFields().sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
		}
	}
	
	public static boolean inherits(ModelSchema ms, String fieldName) {
		boolean outBool = false;
		if(ms.getInherits().contains(fieldName)) {
			outBool = true;
		}
		else {
			for(String s: ms.getInherits()) {
				ModelSchema ims = RecordFactory.getSchema(s);
				outBool = inherits(ims, fieldName);
				if(outBool) {
					break;
				}
			}
		}
		return outBool;
	}
	public static boolean isIdentityModel(ModelSchema schema) {
		boolean hasIdentity = false;
		if(schema != null) {
			hasIdentity = schema.getFields().stream().filter(o -> {
				return o.isIdentity();
			}).collect(Collectors.toList()).size() > 0;
		}
		return hasIdentity;
	}
	
	public static boolean isIdentityRecord(BaseRecord rec) {

		boolean isId = false;
		ModelSchema schema = RecordFactory.getSchema(rec.getModel());
		for(FieldType f : rec.getFields()) {
			FieldSchema fs = schema.getFieldSchema(f.getName());
			if(fs.isIdentity() && !f.isNullOrEmpty(rec.getModel())) {
				isId = true;
				break;
			}
		}
		return isId;
	}
	
	public static boolean matchIdentityRecordsByIdx(IndexEntry idx, BaseRecord rec) {
		if(!rec.getModel().equals(idx.get(FieldNames.FIELD_TYPE))) {
			logger.warn("Wrong model");
			return false;
		}
		if(!isIdentityRecord(rec)) {
			logger.error("Record does not contain identity information");
			logger.error(rec.toString());
		}

		long id = idx.getValue(FieldEnumType.LONG, FieldNames.FIELD_ID, 0L);
		String objectId = idx.getValue(FieldNames.FIELD_OBJECT_ID, null);
		String urn = idx.getValue(FieldNames.FIELD_URN, null);
		if(rec.hasField(FieldNames.FIELD_ID) && id > 0L) {
			return (long)rec.get(FieldNames.FIELD_ID) == id;
		}
		if(rec.hasField(FieldNames.FIELD_OBJECT_ID) && objectId != null) {
			return ((String)rec.get(FieldNames.FIELD_OBJECT_ID)).equals(objectId);
		}
		if(rec.hasField(FieldNames.FIELD_URN) && urn != null) {
			return ((String)rec.get(FieldNames.FIELD_URN)).equals(urn);
		}

		return false;

	}
	
	public static boolean matchIdentityRecords(BaseRecord rec, BaseRecord rec2) {
		if(!isIdentityRecord(rec) || !isIdentityRecord(rec2)) {
			return false;
		}
		if(rec.hasField(FieldNames.FIELD_ID) && rec2.hasField(FieldNames.FIELD_ID)) {
			return (long)rec.get(FieldNames.FIELD_ID) == (long)rec2.get(FieldNames.FIELD_ID);
		}
		if(rec.hasField(FieldNames.FIELD_OBJECT_ID) && rec2.hasField(FieldNames.FIELD_OBJECT_ID)) {
			return ((String)rec.get(FieldNames.FIELD_OBJECT_ID)).equals((String)rec2.get(FieldNames.FIELD_OBJECT_ID));
		}
		if(rec.hasField(FieldNames.FIELD_URN) && rec2.hasField(FieldNames.FIELD_URN)) {
			return ((String)rec.get(FieldNames.FIELD_URN)).equals((String)rec2.get(FieldNames.FIELD_URN));
		}
		return false;

	}
	
	
	public static List<String> getConstraints(ModelSchema ms) {
		return getConstraints(ms, new ArrayList<>());
	}
	public static List<String> getConstraints(ModelSchema ms, List<String> constraints) {
		constraints.addAll(ms.getConstraints());
		for(String i : ms.getInherits()){
			getConstraints(RecordFactory.getSchema(i), constraints);
		}
		return constraints;
	}
	public static boolean isConstrained(Query query) {
		ModelSchema ms = RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE));
		List<String> constraints = getConstraints(ms);

		boolean isC = false;
		for(String pair : constraints) {
			String keys[] = pair.split(",");
			int iter = 0;
			for(String key : keys) {
				if(query.hasQueryField(key.trim())) {
					iter++;
				}
			}
			if(iter == keys.length) {
				isC = true;
				break;
			}
		}
		return isC;
	}
	public static boolean isConstrainedByField(Query query, String fieldName) {
		ModelSchema ms = RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE));
		List<String> constraints = getConstraints(ms);

		boolean isC = false;
		for(String pair : constraints) {
			String keys[] = pair.split(",");
			for(String key : keys) {
				if(key.trim().equals(fieldName)) {
					isC = true;
				}
			}
			if(isC) {
				break;
			}
		}
		return isC;
	}
	public static List<String> getConstraints(Query query) {
		return getConstraints(query, null);
	}
	public static List<String> getConstraints(Query query, String filterFieldName) {
		ModelSchema ms = RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE));
		List<String> constraints = getConstraints(ms);
		List<String> outFields = new ArrayList<>();

		for(String pair : constraints) {
			String keys[] = pair.split(",");
			int iter = 0;
			boolean matchField = false;
			List<String> chkFields = new ArrayList<>();
			for(String keyP : keys) {
				String key = keyP.trim();
				if(query.hasQueryField(key.trim())) {
					if(filterFieldName == null || !key.equals(filterFieldName)) {
						chkFields.add(key);
					}
					else {
						matchField = true;
					}
					iter++;
				}
			}
			if(iter == keys.length && matchField) {
				outFields = chkFields;
				break;
			}
		}
		return outFields;
	}
	
	public BaseRecord findChildRecord(BaseRecord user, BaseRecord rec, String field) {
		BaseRecord orec = null;
		BaseRecord qreq = findByRecord(user, rec, new String[] {field});
		if(qreq != null) {
			orec = qreq.get(field);
		}
		return orec;
	}
	public BaseRecord findByRecord(BaseRecord user, BaseRecord rec, String[] fields) {

		BaseRecord orec = null;
		if(!rec.hasField(FieldNames.FIELD_OBJECT_ID) && !rec.hasField(FieldNames.FIELD_ID)) {
			logger.error("Record does not include an identity field");
			logger.error(rec.toString());
			return orec;
		}
		
		Query q = new Query(rec.getModel());

		q.setRequest(fields);
		if(rec.hasField(FieldNames.FIELD_ID)) {
			q.field(FieldNames.FIELD_ID, ComparatorEnumType.EQUALS, rec.get(FieldNames.FIELD_ID));	
		}
		else if(rec.hasField(FieldNames.FIELD_OBJECT_ID)) {
			q.field(FieldNames.FIELD_OBJECT_ID, ComparatorEnumType.EQUALS, rec.get(FieldNames.FIELD_OBJECT_ID));
		}
		QueryResult res = find(q);
		if(res != null && res.getResponse() == OperationResponseEnumType.SUCCEEDED && res.getResults().length > 0) {
			orec = res.getResults()[0];
		}
		return orec;
	}

	public QueryResult find(Query query) {
		QueryResult qr = null;
		if(search == null) {
			logger.error("Search is null");
			return null;
		}
		try {
			qr = search.find(query);
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		return qr;
	}
	
	public void populate(BaseRecord rec) {
		reader.populate(rec);
	}
	public void populate(BaseRecord rec, int foreignDepth) {
		reader.populate(rec, foreignDepth);
	}
	
	public boolean updateRecord(BaseRecord rec) {
		// logger.info("**** UPDATE");
		return createRecord(rec, true);
	}
	public boolean createRecord(BaseRecord rec) {
		// logger.info("**** CREATE");
		return createRecord(rec, false);
	}
	public boolean createRecord(BaseRecord rec, boolean flush) {
		return createRecord(rec, flush, false);
	}
	public boolean createRecord(BaseRecord rec, boolean flush, boolean skipCustomIO) {
		ModelSchema ms = RecordFactory.getSchema(rec.getModel());
		IWriter useWriter = null;
		if(!skipCustomIO && ms.getIo() != null && ms.getIo().getWriter() != null) {
			IWriter modelWriter = RecordFactory.getClassInstance(ms.getIo().getWriter());
			if(modelWriter != null) {
				useWriter = modelWriter;
			}
			else {
				logger.error("Failed to instantiate the customer writer: " + ms.getIo().getWriter());
				return false;
			}
		}
		else {
			useWriter = writer;
		}
		useWriter.translate(RecordOperation.INSPECT, rec);
		boolean outBool = false;
		try {
			outBool = useWriter.write(rec);
			if(flush) {
				useWriter.flush();
			}
		} catch (WriterException e) {
			logger.error(e);
		}
		return outBool;
	}

	public boolean deleteRecord(BaseRecord rec) {
		return deleteRecord(rec, false);
	}
	public boolean deleteRecord(BaseRecord rec, boolean flush) {

		boolean outBool = false;
		try {
			outBool = writer.delete(rec);
			if(flush) {
				writer.flush();
			}
		} catch (WriterException e) {
			logger.error(e);
		}
		return outBool;
	}

	public void applyNameGroupOwnership(BaseRecord user, BaseRecord rec, String name, String path, long organizationId) throws FieldException, ValueException, ModelNotFoundException {
		rec.set(FieldNames.FIELD_NAME, name);
		if(path.startsWith("~/")) {
			BaseRecord homeDir = user.get(FieldNames.FIELD_HOME_DIRECTORY);
			populate(homeDir);
			path = homeDir.get(FieldNames.FIELD_PATH) + path.substring(1);
		}
		
		BaseRecord dir = pathUtil.makePath(user, ModelNames.MODEL_GROUP, path, "DATA", organizationId);
		if(dir != null) {
			rec.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		}
		applyOwnership(user, rec, organizationId);
	}
	
	public void applyOwnership(BaseRecord user, BaseRecord rec, long organizationId) throws FieldException, ValueException, ModelNotFoundException {
		rec.set(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		rec.set(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
	}
	public BaseRecord getCreateRecord(BaseRecord user, String model, String name, String path, long organizationId) throws FieldException, ModelNotFoundException, ValueException {
		BaseRecord rec = null;
		populate(user);
		if(path.startsWith("~/")) {
			BaseRecord homeDir = user.get(FieldNames.FIELD_HOME_DIRECTORY);
			path = homeDir.get(FieldNames.FIELD_PATH) + path.substring(1);
		}
		rec = getRecord(user, model, name, -1, path);
		if(rec == null) {
			rec = RecordFactory.model(model).newInstance();
			applyNameGroupOwnership(user, rec, name, path, organizationId);
			createRecord(rec);
		}
		return rec;
	}
	
	public BaseRecord getRecordByUrn(BaseRecord user, String model, String urn) {
		BaseRecord rec = null;
		try {
			BaseRecord[] recs = search.findByUrn(model, urn);
			if(recs.length > 1) {
				logger.error("Multiple index matches found");
			}
			else if(recs.length == 1) {
				rec = recs[0];
			}
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		return rec;
	}
	
	public BaseRecord getRecordById(BaseRecord user, String model, long id) {
		BaseRecord rec = null;
		try {
			BaseRecord[] recs = search.findById(model, id);
			if(recs.length > 1) {
				logger.error("Multiple index matches found");
			}
			else if(recs.length == 1) {
				rec = recs[0];
			}
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		return rec;
	}
	
	public BaseRecord getRecordByObjectId(BaseRecord user, String model, String objectId) {
		BaseRecord rec = null;
		try {
			BaseRecord[] recs = search.findByObjectId(model, objectId);
			if(recs.length == 1) {
				rec = recs[0];
			}
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		return rec;
	}
	
	public BaseRecord getRecord(BaseRecord user, String model, String name, long parentId, String groupPath) {
		if(groupPath.startsWith("~/")) {
			BaseRecord homeDir = user.get(FieldNames.FIELD_HOME_DIRECTORY);
			populate(homeDir);
			groupPath = homeDir.get(FieldNames.FIELD_PATH) + groupPath.substring(1);
			
		}
		BaseRecord dir = pathUtil.makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return getRecord(user, model, name, parentId, (long)dir.get(FieldNames.FIELD_ID), (long)dir.get(FieldNames.FIELD_ORGANIZATION_ID));
	}

	public BaseRecord getRecord(BaseRecord user, String model, String name, long parentId, long groupId, long organizationId) {
		BaseRecord rec = null;

		try {
			BaseRecord[] records = new BaseRecord[0];
			if(parentId > 0L) {
				records = search.findByNameInParent(model, parentId, name, organizationId);
			}
			else if (groupId > 0L) {
				records = search.findByNameInGroup(model, groupId, name, organizationId);
			}
			
			else {
				records = search.findByName(model, name, organizationId);
			}
			if(records.length == 1) {
				rec = records[0];
			}
			else if(records.length > 1) {
				logger.warn("Unexpected number of results looking for model " + model + " named " + name + " in parent " + parentId + " in group " + groupId + ": " + records.length);
			}
			else {
				logger.debug("Didn't find any records");
			}
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
		return rec;
	}
	
	public BaseRecord getRecordByQuery(Query query) {
		BaseRecord rec = null;
		QueryResult res = null;
		try {
			res = IOSystem.getActiveContext().getSearch().find(query);
		} catch (IndexException | ReaderException  e) {
			logger.error(e);
		}
		if(res != null && res.getResults().length > 0) {
			rec = res.getResults()[0];
		}
		return rec;
	}
	
    public Query getLatestReferenceQuery(BaseRecord ref, String modelName) {
		Query query = QueryUtil.createQuery(modelName, FieldNames.FIELD_REFERENCE_TYPE, ref.getModel());
		try {
			query.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
			query.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			query.field(FieldNames.FIELD_REFERENCE_ID, ref.get(FieldNames.FIELD_ID));
			query.set(FieldNames.FIELD_RECORD_COUNT, 1);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return query;
    }
	
	public void flush() {
		writer.flush();
	}
}
