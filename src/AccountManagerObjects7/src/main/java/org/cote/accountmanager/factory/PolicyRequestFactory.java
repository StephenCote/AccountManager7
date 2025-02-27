package org.cote.accountmanager.factory;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.policy.PolicyDefinitionUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public class PolicyRequestFactory extends FactoryBase {
	
	public PolicyRequestFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		//BaseRecord newRequest = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		BaseRecord newRequest = null;
		BaseRecord policy = null;
		BaseRecord param1 = null;
		if(arguments.length > 0) {
			if(!arguments[0].inherits(ModelNames.MODEL_POLICY)) {
				throw new FactoryException("Expected first argument to be a policy");
			}
			policy = arguments[0];
			if(arguments.length > 1) {
				param1 = arguments[1];
			}
		}
		PolicyRequestType preq = null;
		
		PolicyDefinitionUtil pdu = IOSystem.getActiveContext().getPolicyDefinitionUtil();
		try {
			PolicyDefinitionType pdef = pdu.generatePolicyDefinition(policy).toConcrete();
			preq = new PolicyRequestType(pdu.generatePolicyRequest(pdef));
			preq.setContextUser(contextUser);
			if(param1 != null) {
				preq.getFacts().get(0).setSourceUrn(param1.get("urn"));
				preq.getFacts().get(0).setModelType(param1.getSchema());
			}
			newRequest = preq;
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
			
		}
		
		return newRequest;
	}
}
