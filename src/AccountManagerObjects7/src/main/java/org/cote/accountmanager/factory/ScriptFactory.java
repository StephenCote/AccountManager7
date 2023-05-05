package org.cote.accountmanager.factory;

import java.util.Map;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.ScriptUtil;

public class ScriptFactory extends BaseFactory {
	
	private String scriptSource = null;
	private String invokeHeaderStr = """
		let recordFactory = org.cote.accountmanager.record.RecordFactory;
		let modelNames = org.cote.accountmanager.schema.ModelNames;
		let fieldNames = org.cote.accountmanager.schema.FieldNames;

	""";
	private String invokeStr = """
		if(mode == "new"){
			if(typeof recordStr != "undefined"){
				record = JSON.parse(recordStr);
			}
			if(typeof newInstance == "function"){
				record = newInstance(record, params);
			}
		}
		else if(mode == "implement" && typeof implement == "function"){
			record = implement(record, params);
		}
		JSON.stringify(record);
	""";
	
	public ScriptFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
		logger.info("Initialize script factory");
		if(schema.getFactory().startsWith("resource:")) {
			setupScriptSource(ResourceUtil.getResource(schema.getFactory().replace("resource:", "")));
		}
	}
	
	private void setupScriptSource(String script) {
		StringBuffer buff = new StringBuffer();

		if(script == null) {
			logger.error("Null script source: " + schema.getFactory());
		}
		buff.append("logger.info('Invoke " + schema.getFactory() + " ' + mode);\n");
		buff.append(invokeHeaderStr);
		buff.append(script);
		buff.append(invokeStr);
		
		scriptSource = buff.toString();
	}
	
	private Map<String, Object> getParamMap(String mode, BaseRecord rec, BaseRecord... arguments){

		BaseRecord owner = null;
		if(arguments != null && arguments.length > 0) {
			owner = arguments[0];
		}
		Map<String, Object> params = ScriptUtil.getCommonParameterMap(owner);
		params.put("modelFactory", this.modelFactory);
		params.put("mode", mode);
		params.put("record", rec);
		params.put("params", arguments);
		
		return params;
	}
	

	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		
		logger.info("Invoke script factory - new");
		if(scriptSource == null || scriptSource.length() == 0) {
			throw new FactoryException("Invalid script source");
		}
		
		BaseRecord rec = null;
		Map<String, Object> params = getParamMap("new", null, arguments);
		
		try {
			BaseRecord trec = RecordFactory.newInstance(this.schema.getName());
			params.put("recordStr", trec.toString());
			String val = ScriptUtil.run(String.class, scriptSource, params);
			if(val != null) {
				rec = JSONUtil.importObject(val, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
				// rec = JSONUtil.importObject(val, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
			}
		} catch (ScriptException | FieldException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		
		
		return rec;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		logger.info("Invoke script factory - implement");
		if(scriptSource == null || scriptSource.length() == 0) {
			throw new FactoryException("Invalid script source");
		}

		Map<String, Object> params = getParamMap("implement", newRecord, arguments);

		if(arguments != null && arguments.length > 0) {
			BaseRecord owner = arguments[0];
			params = ScriptUtil.getCommonParameterMap(owner);
		}
		
		return newRecord;
	}

}
