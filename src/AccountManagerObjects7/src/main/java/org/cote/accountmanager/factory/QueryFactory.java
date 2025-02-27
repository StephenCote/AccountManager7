package org.cote.accountmanager.factory;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.ParameterUtil;

public class QueryFactory extends FactoryBase {
	public QueryFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		Query query = new Query(super.newInstance(contextUser, recordTemplate, parameterList, arguments));
		try {
			query.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
			if(arguments.length > 0 && arguments[0] != null) {
				query.set(FieldNames.FIELD_TYPE, arguments[0].getSchema());
				QueryUtil.createQueryGroup(query, query, arguments);
			}
			else if(parameterList != null) {
				//logger.info(JSONUtil.exportObject(parameterList, RecordSerializerConfig.getUnfilteredModule()));
				String type = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_TYPE, String.class, null);
				if(type != null) {
					query.set(FieldNames.FIELD_TYPE, type);	
				}
				else {
					logger.error("Parameter type is null");
				}
			}
			else {
				logger.warn("Parameter list is null");;
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			throw new FactoryException(e);
		}
		return query;
	}
}
