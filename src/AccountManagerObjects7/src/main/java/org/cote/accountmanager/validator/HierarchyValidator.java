package org.cote.accountmanager.validator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IExecutable;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class HierarchyValidator implements IExecutable {
	public static final Logger logger = LogManager.getLogger(HierarchyValidator.class);

	@Override
	public boolean execute(BaseRecord rule, BaseRecord record, FieldType field) {
		return checkHierarchy(record, field.getName());
	}
	
	public static boolean checkHierarchy(BaseRecord record, String hierarchyField) {
		boolean inHier = inHierarchy(record.getAMModel(), hierarchyField, record.get(FieldNames.FIELD_ID), record.get(hierarchyField));
		
		return (inHier == false);
	}
	public static boolean inHierarchy(String model, String hierarchyField, long baseId, long id) {
		if(id == 0L) {
			return false;
		}
		if(baseId == id) {
			return true;
		}
		boolean outBool = false;
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ID, id);
		q.setRequest(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_ORGANIZATION_ID,FieldNames.FIELD_ID, hierarchyField});
		///  
		try {
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			if(qr != null && qr.getResults().length > 0) {
				outBool = inHierarchy(model, hierarchyField, baseId, qr.getResults()[0].get(hierarchyField));
			}
		} catch (ReaderException e) {
			logger.error(e);
		}
		return outBool;
	}
	
}
