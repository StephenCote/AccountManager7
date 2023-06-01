package org.cote.accountmanager.factory;

import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.VerificationEnumType;

public class FactoryBase implements IFactory {
	public static final Logger logger = LogManager.getLogger(FactoryBase.class);
	
	public static final String ADMIN_USER_NAME = "admin";
	
	protected final Factory modelFactory;
	protected final ModelSchema schema;
	public FactoryBase(Factory modelFactory, ModelSchema schema) {
		this.modelFactory = modelFactory;
		this.schema = schema;
	}
	
	
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		BaseRecord rec = null;
		try {
			rec = RecordFactory.newInstance(schema.getName());
			if(recordTemplate != null && schema.getName().equals(recordTemplate.getModel())) {
				// logger.info("Apply template");
				//for(FieldType f : recordTemplate.getFields()) {
				for(int i = 0; i < recordTemplate.getFields().size(); i++) {
					FieldType f = recordTemplate.getFields().get(i);
					FieldSchema fs = schema.getFieldSchema(f.getName());
					if(fs.isReadOnly() || fs.isIdentity()) {
						continue;
					}
					try {
						//logger.info("Set " + f.getName() + " = " + recordTemplate.get(f.getName()));
						rec.set(f.getName(), recordTemplate.get(f.getName()));
					} catch (ValueException e) {
						logger.error(e);
					}
				}
			}
			else if(recordTemplate != null) {
				logger.info("Skip record template");
			}
		} catch (FieldException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		/*
		if(rec.getModel().equals(ModelNames.MODEL_DATA)) {
			logger.info(rec.toString());
		}
		*/
		return rec;
	}

	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		return newRecord;
	}

	@Override
	public ModelSchema getSchema() {
		return schema;
	}



	@Override
	public VerificationEnumType verify(BaseRecord contextUser, BaseRecord rec, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		return VerificationEnumType.DEFAULT;
	}
	
	
}
