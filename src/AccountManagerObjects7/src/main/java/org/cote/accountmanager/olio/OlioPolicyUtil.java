package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.policy.FactUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ResourceUtil;

public class OlioPolicyUtil {
	public static final Logger logger = LogManager.getLogger(OlioPolicyUtil.class);
	private static ResourceUtil resourceUtil = new ResourceUtil();
	static {
		resourceUtil.setResourcePrefix("olio/");
	}
	
	public static BaseRecord evalPolicy(OlioContext octx, List<BaseRecord> params, String name) {
		BaseRecord character = FactUtil.getParameterValue(params, "character");
		if(character == null) {
			logger.error("Character is null");
			return null;
		}
		
		BaseRecord pol = getPolicy(octx.getOlioUser(), character, "canMove");
		
		PolicyDefinitionType pdef = null;
		PolicyRequestType preq = null;
		PolicyResponseType prt = null;

		try {
			pdef = IOSystem.getActiveContext().getPolicyDefinitionUtil().generatePolicyDefinition(pol).toConcrete();
			preq = new PolicyRequestType(IOSystem.getActiveContext().getPolicyDefinitionUtil().generatePolicyRequest(pdef));
			preq.setContextUser(octx.getOlioUser());
			for(FactType fact : preq.getFacts()) {
				configureParameters(octx, fact, params);
			}
			
			prt = IOSystem.getActiveContext().getPolicyEvaluator().evaluatePolicyRequest(preq, pol).toConcrete();
			
		}
		catch (ModelException | FieldException | ModelNotFoundException | ValueException | ScriptException | IndexException | ReaderException e) {
			logger.error(e);
		}
		return prt;
		
	}
	public static <T> BaseRecord createFactParameter(String name, FieldEnumType fieldType, T value) {
		BaseRecord fp = null;
		if(value == null) {
			logger.warn("Null value for " + name);
		}
		try {
			fp = RecordFactory.newInstance(ModelNames.MODEL_FACT_PARAMETER);
			fp.set(FieldNames.FIELD_NAME, name);
			fp.set("valueType", fieldType);
			fp.setFlex("value", value);
			if(fieldType == FieldEnumType.MODEL && value != null) {
				fp.set("valueModel", ((BaseRecord)value).getModel());
			}
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		return fp;
	}
	
	public static void configureParameters(OlioContext ctx, FactType param, List<BaseRecord> fparams) {

		List<BaseRecord> params = param.get("parameters");
		BaseRecord character = FactUtil.getParameterValue(fparams, "character");
		if(params.size() > 0) {
			for(BaseRecord pp: params) {
				BaseRecord ifp = FactUtil.getParameter(fparams, pp.get(FieldNames.FIELD_NAME));
				if(ifp != null) {
					pp.setValue("value", ifp.get("value"));
				}
				else {
					logger.warn("Missing fact parameter: " + pp.get(FieldNames.FIELD_NAME));
				}
			}
		}
		else if(character != null && FieldEnumType.MODEL == param.getEnum("valueType")) {
			if(OlioModelNames.MODEL_CHAR_STATE.equals(param.get(FieldNames.FIELD_MODEL_TYPE))){
				param.setValue("factReference", character.get(FieldNames.FIELD_STATE));
			}
			else {
				logger.warn("Unhandled model type: " + param.getModelType());
			}
		}
		else {
			logger.warn("Unhandled param value type: " + param.get("valueType"));
		}
	}
	
	private static Map<String, String> altResourceMap = new HashMap<>(); //Collections.synchronizedMap(new HashMap<>());
	private static String getAltResource(String name) {
		if(!altResourceMap.containsKey(name)) {
				logger.info("Load policy: " + name);
				String polStr = resourceUtil.getPolicyResource(name);
				if(polStr == null) {
					logger.error("Resource is null for " + name);
					return null;
				}
				altResourceMap.put(name, polStr);
			}
		return altResourceMap.get(name);
	}
	
	public static BaseRecord getPolicy(BaseRecord user, BaseRecord character, String name) {
		BaseRecord pol = null;
		String polStr = getAltResource(name);
		if(polStr == null) {
			return null;
		}
		try {
			pol = IOSystem.getActiveContext().getPolicyUtil().getResourcePolicy(resourceUtil, name, character, null, null);
		}
		catch (ReaderException e) {
			logger.error(e);
		}
		
		return pol;
	}
	
}
