package org.cote.accountmanager.factory;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.VerificationEnumType;

public interface IFactory {
	public ModelSchema getSchema();
	// public BaseRecord implement(BaseRecord newRecord, BaseRecord... arguments) throws FactoryException;
	// public BaseRecord newInstance(BaseRecord... arguments) throws FactoryException;
	public VerificationEnumType verify(BaseRecord contextUser, BaseRecord rec, ParameterList parameterList, BaseRecord... arguments) throws FactoryException;
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException;
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException;


}
