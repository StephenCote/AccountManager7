package org.cote.accountmanager.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public abstract class SearchBase implements ISearch {
	public static final Logger logger = LogManager.getLogger(SearchBase.class);
	protected IOStatistics stats = new IOStatistics();

	public IOStatistics getStatistics() {
		return stats;
	}
	public void enableStatistics(boolean enabled) {
		stats.setEnabled(enabled);
	}
	public void close() throws ReaderException {
	
	}
	
	public QueryResult findAlternate(Query query) {
		QueryResult qr = null;
		ModelSchema ms = RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE));
		if(ms != null && ms.getIo() != null && ms.getIo().getSearch() != null) {
			ISearch altSearch = RecordFactory.getClassInstance(ms.getIo().getSearch());
			try {
				qr = altSearch.find(query);
			} catch (ReaderException e) {
				logger.error(e);
			}
		}
		else {
			logger.error("Model schema " + query.get(FieldNames.FIELD_TYPE) + " does not define an alternate search interface");
		}
		return qr;
	}
	
	public boolean useAlternateIO(Query query) {
		ModelSchema ms = RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE));
		boolean outBool = false;
		if(ms != null && ms.getIo() != null && ms.getIo().getSearch() != null) {
			outBool = true;
		}
		return outBool;
	}
	
	public BaseRecord findRecord(Query query) {
		BaseRecord record = null;
		/*
		if(query.getType().equals(ModelNames.MODEL_GROUP)) {
			logger.info(query.key());
			logger.info(Arrays.asList(RecordUtil.getCommonFields(query.getType())).stream().collect(Collectors.joining(", ")));
		}
		*/;
		QueryResult result = null;
		try {
			result = find(query);
		} catch (NullPointerException | ReaderException e) {
			logger.error(e);
		}
		if(result != null && result.getCount() > 0) {
			record = result.getResults()[0];
		}

		return record;
	}
	
	public BaseRecord[] findRecords(Query query) {
		BaseRecord[] records = new BaseRecord[0];
		QueryResult result = null;
		try {
			result = find(query);
		} catch (NullPointerException | ReaderException e) {
			logger.error(e);
			e.printStackTrace();
			
		}
		if(result != null && result.getCount() > 0) {
			records = result.getResults();
		}
		else if(result == null){
			logger.error("Null QueryResult");
		}
		else {
			logger.debug("Zero results for " + query.key());
		}
		/*
		if(query.getType().equals(ModelNames.MODEL_GROUP) && records.length > 0){
			logger.info(records[0].toFullString());
		}
		*/
		return records;
	}
	
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, long organizationId) throws ReaderException {
		return findByPath(contextUser, modelName, path, null, organizationId);
	}
	
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, String type, long organizationId) throws ReaderException {
		return IOSystem.getActiveContext().getPathUtil().findPath(contextUser, modelName, path, type, organizationId);
	}
	
	public BaseRecord[] findByName(String model, String name) throws ReaderException {
		return findRecords(QueryUtil.createQuery(model, FieldNames.FIELD_NAME, name));
	}
	public BaseRecord[] findByName(String model, String name, long organizationId) throws ReaderException {
		Query query = QueryUtil.createQuery(model, FieldNames.FIELD_NAME, name);
		query.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		return findRecords(query);
	}
	
	public BaseRecord[] findByUrn(String model, String urn) throws ReaderException {
		return findRecords(QueryUtil.createQuery(model, FieldNames.FIELD_URN, urn));
	}
	public BaseRecord[] findByObjectId(String model, String objectId) throws ReaderException {
		return findRecords(QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId));
	}
	public BaseRecord[] findById(String model, long id) throws ReaderException {
		return findRecords(QueryUtil.createQuery(model, FieldNames.FIELD_ID, id));
	}
	public BaseRecord[] findByNameInParent(String model, long parentId, String name) throws ReaderException {
		return findByNameInParent(model, parentId, name, 0L);
	}
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type) throws ReaderException {
		return findByNameInParent(model, parentId, name, type, 0L);
	}
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, long organizationId) throws ReaderException {
		return findByNameInParent(model, parentId, name, null, organizationId);
	}
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type, long organizationId) throws ReaderException {
		Query query = QueryUtil.createQuery(model, FieldNames.FIELD_PARENT_ID, parentId);
		if(organizationId > 0L) {
			query.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		}
		if(name != null) {
			query.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);	
		}
		if(type != null && !type.equalsIgnoreCase("unknown")) {
			query.field(FieldNames.FIELD_TYPE, ComparatorEnumType.EQUALS, type.toUpperCase());
		}
		return findRecords(query);
	}

	public BaseRecord[] findByNameInGroup(String model, long groupId, String name) throws ReaderException {
		return findByNameInGroup(model, groupId, name, 0L);
	}
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name, String type) throws ReaderException {
		return findByNameInGroup(model, groupId, name, type, 0L);
	}
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name, long organizationId) throws ReaderException {
		return findByNameInGroup(model, groupId, name, null, organizationId);
	}
	
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name, String type, long organizationId) throws ReaderException {
		Query query = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, groupId);
		if(organizationId > 0L) {
			query.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		}
		if(name != null) {
			query.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);	
		}
		if(type != null) {
			query.field(FieldNames.FIELD_TYPE, ComparatorEnumType.EQUALS, type.toUpperCase());
		}
		return findRecords(query);
	}

}
