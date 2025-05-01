package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
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
	
	public void patch(BaseRecord src, BaseRecord targ) {
		patch(src, targ, false);
	}
	public void patch(BaseRecord src, BaseRecord targ, boolean full) {
		if(src != null && targ != null) {
			List<String> upf =new ArrayList<>();
			for(FieldType f: src.getFields()) {
				//FieldType sf = targ.getField(f.getName());
				try {
					//sf.setValue(f.getValue());
					targ.set(f.getName(), f.getValue());
					upf.add(f.getName());
				} catch (ValueException | FieldException | ModelNotFoundException e) {
					logger.error(e);
					e.printStackTrace();
				}
			}
			if(upf.size() > 0) {
				if(!upf.contains(FieldNames.FIELD_ID)) {
					upf.add(FieldNames.FIELD_ID);
				}
				if(!upf.contains(FieldNames.FIELD_OWNER_ID) && targ.hasField(FieldNames.FIELD_OWNER_ID) && (long)targ.get(FieldNames.FIELD_OWNER_ID) > 0L) {
					upf.add(FieldNames.FIELD_OWNER_ID);
				}
				if(!upf.contains(FieldNames.FIELD_ORGANIZATION_ID) && targ.hasField(FieldNames.FIELD_ORGANIZATION_ID) && (long)targ.get(FieldNames.FIELD_ORGANIZATION_ID) > 0L) {
					upf.add(FieldNames.FIELD_ORGANIZATION_ID);
				}
				if(updateRecord((full ? targ : targ.copyRecord(upf.toArray(new String[0]))))) {
					logger.info("Patched " + getIdentityString(targ) + " " + (full ? "object" :  upf.stream().collect(Collectors.joining(", "))));
				}
				else {
					logger.warn("Failed to patch " + getIdentityString(targ) + " " + (full ? "object" : upf.stream().collect(Collectors.joining(", "))));
				}
			}
			 
		}
	}
	
	public static String getIdentityString(BaseRecord rec) {
		String id = null;
		if(rec.hasField(FieldNames.FIELD_URN)) {
			id = rec.get(FieldNames.FIELD_URN);
		}
		else if(rec.hasField(FieldNames.FIELD_OBJECT_ID)) {
			id = rec.get(FieldNames.FIELD_OBJECT_ID);
		}
		else if(rec.hasField(FieldNames.FIELD_ID)) {
			id = "#" + Long.toString(rec.get(FieldNames.FIELD_ID));
		}
		return id;


	}

	public static String toForeignFilteredJSONString(BaseRecord rec) {
		sortFields(rec);
		return JSONUtil.exportObject(rec, RecordSerializerConfig.getForeignFilteredModule());
	}
	public static String toFilteredJSONString(BaseRecord rec) {
		sortFields(rec);
		return JSONUtil.exportObject(rec, RecordSerializerConfig.getFilteredModule());
	}
	
	public static String toJSONString(BaseRecord rec) {
		return toJSONString(rec, false, false);
	}
	public static String toJSONString(BaseRecord rec, boolean noPrettyPrint, boolean noQuotes) {
		sortFields(rec);
		return JSONUtil.exportObject(rec, RecordSerializerConfig.getUnfilteredModule(), noPrettyPrint, noQuotes);
	}
	
	public static String toFullJSONString(BaseRecord rec) {
		sortFields(rec);
		return JSONUtil.exportObject(rec, RecordSerializerConfig.getForeignUnfilteredModule());
	}
	
	public static String hash(BaseRecord rec) {
		List<String> fieldNames = new ArrayList<>();
		RecordUtil.sortFields(rec);
		ModelSchema lbm = RecordFactory.getSchema(rec.getSchema());

		for(FieldType f : rec.getFields()) {
			FieldSchema lbf = lbm.getFieldSchema(f.getName());
			if(lbf.isRestricted() || lbf.isEphemeral() || lbf.isVirtual() || f.getName().equals(FieldNames.FIELD_JOURNAL) || f.getName().equals(FieldNames.FIELD_SIGNATURE)) {
				continue;
			}
			fieldNames.add(f.getName());
		}

		BaseRecord crec = rec.copyRecord(fieldNames.toArray(new String[0]));
		String hash = null;
		try{
			hash = CryptoUtil.getDigestAsString(toFullJSONString(crec));
		}
		catch(Exception e) {
			logger.error(e);
			logger.error(crec.toFullString());
			hash = "Tmp hash - " + UUID.randomUUID().toString();
		}
		return hash;
	}
	public static String hash(BaseRecord rec, String[] fieldNames) {
		BaseRecord crec = rec.copyRecord(fieldNames);
		return CryptoUtil.getDigestAsString(toJSONString(crec));
	}	
	public static boolean isEqual(BaseRecord rec, BaseRecord rec2) {
		return hash(rec).equals(hash(rec2));
	}
	public static void sortFields(ModelSchema schema) {
		if(schema != null) {
			schema.getFields().sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
		}
	}
	public static void sortFields(BaseRecord rec) {
		if(rec != null) {
			rec.getFields().sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
		}
	}
	/// Given a list of field names, return ones that are valid for the model 
	public static String[] getPossibleFields(String model, String[] fields) {
		/// Disallowed due to stackoverflow on initial start

		Set<String> fieldMap = Set.of(fields);
		ModelSchema schema = RecordFactory.getSchema(model);
		List<String> matFields = schema.getFields().stream()
			.filter(f -> (
					fieldMap.contains(f.getName())
					&& !f.isEphemeral()
					&& !f.isVirtual()
				)
			)
			.map(f -> f.getName())
			.collect(Collectors.toList())
		;
		return matFields.toArray(new String[0]);
	}
	public static String[] getFieldNames(String model) {
		ModelSchema ms = RecordFactory.getSchema(model);
		return ms.getFields().stream().map(f -> f.getName()).collect(Collectors.toList()).toArray(new String[0]);
	}
	public static String[] getCommonFields(String model) {
		// List<String> flds = new ArrayList<>();
		Set<String> flds = ConcurrentHashMap.newKeySet();
		ModelSchema ms = RecordFactory.getSchema(model);
		if(ms.getQuery() != null) {
			flds.addAll(ms.getQuery());
		}
		for(String s : ms.getImplements()) {
			if(!s.equals(model)) {
				ModelSchema mis = RecordFactory.getSchema(s);
				if(mis.getQuery() != null) {
					flds.addAll(mis.getQuery());
				}
			}
		}
		return flds.toArray(new String[0]);
	}
	
	public static List<String> getRequestFields(ModelSchema schema, List<String> filter) {
		return schema.getFields().stream()
			.filter(s -> !filter.contains(s.getName()) && !s.isEphemeral())
			.map(f -> f.getName())
			.collect(Collectors.toList())
		;
	}
	public static List<String> getRequestFields(String model) {
		return getRequestFields(model, new ArrayList<>());
	}
	public static List<String> getRequestFields(String model, List<String> filter) {
		ModelSchema schema = RecordFactory.getSchema(model);
		if(schema == null) {
			logger.error("Null schema for " + model);
			return new ArrayList<>();
		}
		return getRequestFields(schema, filter);
	}
	
	public static List<String> getMostRequestFields(String model){
		return getRequestFields(model, Arrays.asList(new String[] {
			FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_TAGS,
			FieldNames.FIELD_CONTROLS
		}));
	}
	
	public static boolean inherits(ModelSchema ms, String fieldName) {
		boolean outBool = false;
		if(ms.getName().equals(fieldName) || ms.getInherits().contains(fieldName) || ms.getLikeInherits().contains(fieldName)) {
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
		ModelSchema schema = RecordFactory.getSchema(rec.getSchema());
		for(FieldType f : rec.getFields()) {
			FieldSchema fs = schema.getFieldSchema(f.getName());
			if(fs.isIdentity() && !f.isNullOrEmpty(rec.getSchema())) {
				isId = true;
				break;
			}
		}
		return isId;
	}
	
	public static boolean matchIdentityRecordsByIdx(IndexEntry idx, BaseRecord rec) {
		if(!rec.getSchema().equals(idx.get(FieldNames.FIELD_TYPE))) {
			logger.warn("Wrong model");
			return false;
		}
		if(!isIdentityRecord(rec)) {
			logger.debug("Record does not contain identity information");
			logger.debug(rec.toString());
			return false;
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
	public static List<String> getHints(ModelSchema ms) {
		return getHints(ms, new ArrayList<>());
	}
	public static List<String> getHints(ModelSchema ms, List<String> hints) {
		hints.addAll(ms.getHints());
		for(String i : ms.getInherits()){
			getHints(RecordFactory.getSchema(i), hints);
		}
		return hints;
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
			return orec;
		}
		
		Query q = new Query(rec.getSchema());
		q.setRequest(fields);

		if(rec.hasField(FieldNames.FIELD_ID) && ((long)rec.get(FieldNames.FIELD_ID)) > 0L) {
			q.field(FieldNames.FIELD_ID, ComparatorEnumType.EQUALS, rec.get(FieldNames.FIELD_ID));	
		}
		else if(rec.hasField(FieldNames.FIELD_OBJECT_ID) && rec.get(FieldNames.FIELD_OBJECT_ID) != null) {
			q.field(FieldNames.FIELD_OBJECT_ID, ComparatorEnumType.EQUALS, rec.get(FieldNames.FIELD_OBJECT_ID));
		}
		else if(rec.hasField(FieldNames.FIELD_URN) && rec.get(FieldNames.FIELD_URN) != null) {
			q.field(FieldNames.FIELD_URN, ComparatorEnumType.EQUALS, rec.get(FieldNames.FIELD_URN));
		}
		else {
			logger.error("Invalid query!");
			logger.error(rec.toFullString());
			return orec;
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
		} catch (ReaderException e) {
			logger.error(e);
			
		}
		return qr;
	}

	public void conditionalPopulate(BaseRecord rec, String[] requestFields) {
		reader.conditionalPopulate(rec, requestFields);
	}
	public void populate(BaseRecord rec) {
		reader.populate(rec);
	}
	public void populate(BaseRecord rec, String[] requestFields) {
		reader.populate(rec, requestFields);
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
		ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
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
	
	public boolean isSimilar(BaseRecord[] models) {
		if(models.length == 0) {
			return false;
		}
		else if(models.length == 1) {
			return true;
		}
		
		Set<String> mlist = Arrays.asList(models).stream().map(m -> m.getSchema()).collect(Collectors.toSet());
		BaseRecord firstModel = models[0];
		final long id;
		if(firstModel.hasField(FieldNames.FIELD_ID)) {
			id = firstModel.get(FieldNames.FIELD_ID);
		}
		else {
			id = 0L;
		}
		
		final String partModel;
		if(firstModel.getSchema().equals(ModelNames.MODEL_PARTICIPATION)) {
			partModel = firstModel.get(FieldNames.FIELD_PARTICIPATION_MODEL);
		}
		else {
			partModel = null;
		}
		
		List<BaseRecord> rlist = Arrays.asList(models).stream().filter(
				m -> {
					boolean size = (m.getFields().size() != firstModel.getFields().size());
					long rid = 0L;
					if(m.hasField(FieldNames.FIELD_ID)) {
						rid = m.get(FieldNames.FIELD_ID);
					}
					boolean ids = ((rid == 0L && id != 0L) || (rid > 0L && id == 0L));
					String check = null;
					if(m.hasField(FieldNames.FIELD_PARTICIPATION_MODEL)) {
						check = m.get(FieldNames.FIELD_PARTICIPATION_MODEL);
					}
					boolean partCheck = ((partModel == null && check != null) || (partModel != null && !partModel.equals(check)));
					if((size || ids || partCheck)) {
						logger.warn(firstModel.getSchema() + " " + size + " " +  m.getFields().size() + " <> " + firstModel.getFields().size() + " " + ids + " " + rid + " <> " + id + " Part " + partCheck);
						// logger.warn(firstModel.toFullString());
						// logger.warn(m.toFullString());
					}
					return (size || ids || partCheck);
				} 
		).collect(Collectors.toList());

		return (mlist.size() == 1 && rlist.size() == 0);
	}
	
	public int updateRecords(BaseRecord[] recs) {
		return createRecords(recs, true);
	}
	
	public int createRecords(BaseRecord[] recs) {
		return createRecords(recs, false);
	}
	public int createRecords(BaseRecord[] recs, boolean flush) {
		return createRecords(recs, flush, false);
	}
	
	public int createRecords(BaseRecord[] recs, boolean flush, boolean skipCustomIO) {
		if(recs.length == 0) {
			return 0;
		}
		if(!isSimilar(recs)) {
			logger.error("Models (" + recs[0].getSchema() + ") are not similar enough to be processed concurrently");
			return 0;
		}
		ModelSchema ms = RecordFactory.getSchema(recs[0].getSchema());
		IWriter useWriter = null;
		if(!skipCustomIO && ms.getIo() != null && ms.getIo().getWriter() != null) {
			IWriter modelWriter = RecordFactory.getClassInstance(ms.getIo().getWriter());
			if(modelWriter != null) {
				useWriter = modelWriter;
			}
			else {
				logger.error("Failed to instantiate the customer writer: " + ms.getIo().getWriter());
				return 0;
			}
		}
		else {
			useWriter = writer;
		}
		for(BaseRecord rec : recs) {
			useWriter.translate(RecordOperation.INSPECT, rec);
		}
		int writeCount = 0;
		try {
			writeCount = useWriter.write(recs);
			if(flush) {
				useWriter.flush();
			}
		} catch (WriterException e) {
			logger.error(e);
		}
		return writeCount;
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

	public void applyNameGroupOwnership(BaseRecord user, BaseRecord rec, String name, String ipath, long organizationId) throws FieldException, ValueException, ModelNotFoundException {
		if(rec.inherits(ModelNames.MODEL_NAME)) {
			rec.set(FieldNames.FIELD_NAME, name);
		}
		String path = resolveUserPath(user, ipath);
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
	public BaseRecord getCreateRecord(BaseRecord user, String model, String name, String ipath, long organizationId) throws FieldException, ModelNotFoundException, ValueException {
		BaseRecord rec = null;
		populate(user);
		String path = resolveUserPath(user, ipath);

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
		} catch (ReaderException e) {
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
		} catch (ReaderException e) {
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
		} catch (ReaderException e) {
			logger.error(e);
			
		}
		return rec;
	}
	
	public String resolveUserPath(BaseRecord user, String path) {
		String outPath = path;
		if(outPath.startsWith("~/")) {
			BaseRecord homeDir = user.get(FieldNames.FIELD_HOME_DIRECTORY);
			populate(homeDir);
			outPath = homeDir.get(FieldNames.FIELD_PATH) + outPath.substring(1);
			
		}		
		return outPath;
	}
	
	public BaseRecord getRecord(BaseRecord user, String model, String name, long parentId, String groupPath) {
		String path = resolveUserPath(user, groupPath);
		BaseRecord dir = pathUtil.makePath(user, ModelNames.MODEL_GROUP, path, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
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
		} catch (ReaderException e) {
			logger.error(e);
		}
		return rec;
	}
	
	public BaseRecord getRecordByQuery(Query query) {
		BaseRecord rec = null;
		QueryResult res = null;
		try {
			res = IOSystem.getActiveContext().getSearch().find(query);
		} catch (ReaderException  e) {
			logger.error(e);
		}
		if(res != null && res.getResults().length > 0) {
			rec = res.getResults()[0];
		}
		return rec;
	}
	
    public Query getLatestReferenceQuery(BaseRecord ref, String modelName) {
		Query query = QueryUtil.createQuery(modelName, FieldNames.FIELD_REFERENCE_TYPE, ref.getSchema());
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
