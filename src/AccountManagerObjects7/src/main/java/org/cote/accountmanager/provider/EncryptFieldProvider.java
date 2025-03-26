package org.cote.accountmanager.provider;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.RecordUtil;

public class EncryptFieldProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(EncryptFieldProvider.class);
	private static final String[] provideFields = new String[] {FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID };
	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
	}

	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		if(!model.inherits(ModelNames.MODEL_VAULT_EXT)) {
			throw new ModelException(String.format(ModelException.INHERITENCE_EXCEPTION, model.getSchema(), ModelNames.MODEL_VAULT_EXT));
		}
		
		if(!RecordOperation.CREATE.equals(operation) && !RecordOperation.UPDATE.equals(operation) && !RecordOperation.READ.equals(operation)) {
			return;
		}
		
		if(!lfield.isEncrypt()) {
			return;
		}

		String[] fields = RecordUtil.getPossibleFields(model.getSchema(), provideFields);
		IOSystem.getActiveContext().getReader().conditionalPopulate(model, fields);

		OrganizationContext org = IOSystem.getActiveContext().findOrganizationContext(model);
		if(org == null) {
			logger.error(model.toFullString());
			throw new ValueException("Failed to retrieve organization context for model");
		}
		VaultBean vault = org.getVault();
		if(vault == null) {
			throw new ValueException("Failed to retrieve organization vault for " + org.getOrganizationPath());
		}
		if((RecordOperation.READ.equals(operation) || RecordOperation.UPDATE.equals(operation)) && (!model.hasField(FieldNames.FIELD_KEY_ID) || !model.hasField(FieldNames.FIELD_VAULT_ID))) {
			logger.info("Inject key and vault id");
			BaseRecord urec = IOSystem.getActiveContext().getRecordUtil().findByRecord(contextUser, model, new String[] {FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID, FieldNames.FIELD_ORGANIZATION_ID});
			if(urec != null) {
				model.set(FieldNames.FIELD_KEY_ID, urec.get(FieldNames.FIELD_KEY_ID));
				model.set(FieldNames.FIELD_VAULT_ID, urec.get(FieldNames.FIELD_VAULT_ID));
				model.set(FieldNames.FIELD_VAULTED, urec.get(FieldNames.FIELD_VAULTED));
			}
		}

		if(RecordOperation.CREATE.equals(operation) || RecordOperation.UPDATE.equals(operation)) {
			boolean wasVaulted = VaultService.getInstance().vaultField(vault, model, field);
			if(wasVaulted) {
				List<String> vaulted = model.get(FieldNames.FIELD_VAULTED_FIELDS);
				if(!vaulted.contains(field.getName())) {
					throw new FieldException("Field value was not vaulted.");
				}
			}
		}
		else if(RecordOperation.READ.equals(operation)) {
			VaultService.getInstance().unvaultField(vault, model, field);
			List<String> unvaulted = model.get(FieldNames.FIELD_UNVAULTED_FIELDS);
			if(!unvaulted.contains(field.getName())) {
				throw new FieldException("Field " + field.getName() + " value was not unvaulted.");
			}
		}
		else {
			logger.warn("Operation not handled: " + operation.toString());
		}
		
	}


	@Override
	public String describe(ModelSchema lmodel, BaseRecord model) {
		// TODO Auto-generated method stub
		return null;
	}
	
}
