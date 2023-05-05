package org.cote.accountmanager.factory;

import org.cote.accountmanager.schema.ModelSchema;
;

public class GenericFactory extends FactoryBase {
	public GenericFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
		logger.info("Generic: " + schema.getName());
	}

}
