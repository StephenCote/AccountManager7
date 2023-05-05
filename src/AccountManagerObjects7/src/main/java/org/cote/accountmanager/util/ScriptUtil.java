
package org.cote.accountmanager.util;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Value;

public class ScriptUtil {
	public static final Logger logger = LogManager.getLogger(ScriptUtil.class);
	private static final String scriptEngineGraal = "graal.js";
	private static String scriptEngineName = scriptEngineGraal;
	public static void setScriptEngineName(String s){
		scriptEngineName = s;
	}
	// private static Map<String,CompiledScript> jsCompiled = new HashMap<>();
	
	public static Context getJavaScriptEngine(){
		Context jsEngine = null;
		if(jsEngine == null){
			if(scriptEngineName.contentEquals(scriptEngineGraal)) {
			    Context context = Context.newBuilder("js")
			    	.allowIO(true)
			    	.allowPolyglotAccess(PolyglotAccess.ALL)
			    	.allowHostAccess(HostAccess.ALL)
			    	.allowHostClassLookup(className -> ScriptClassFilter.classFilter(className))
			    	.option("engine.WarnInterpreterOnly", "false")
			    	.build();
			    context.getBindings("js").putMember("ScriptResolver", new ScriptResolver());
			    jsEngine = context;
			}
			else{
				logger.error("Not supported: " + scriptEngineName);
			}
		}
		return jsEngine;
	}
	
	public static String mapAndConvertParameters(BaseRecord[] records) {
		StringBuilder buff = new StringBuilder();
		int i = 1;
		for(BaseRecord rec : records) {
			String line = "model" + (i++) + " = %s;\n";
			buff.append(String.format(line, rec.toString()));
		}
		return buff.toString();
	}

	public static Map<String, Object> getCommonParameterMap(BaseRecord contextUser){
		Map<String,Object> params = new HashMap<String,Object>();
		params.put("logger", logger);
		if(contextUser != null) {
			params.put("contextUser", contextUser);
			params.put(FieldNames.FIELD_ORGANIZATION_PATH, contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH));
			
		}
		
		return params;
	}

	public static String processTokens(BaseRecord user, String scriptText){
		return scriptText.replaceAll("\\$\\{userUrn\\}", user.get(FieldNames.FIELD_URN));
	}
	
	public static <T> T run(Class<T> cls, Map<String,Object> params, BaseRecord data) throws ScriptException{

		String value = new String((byte[])data.get(FieldNames.FIELD_BYTE_STORE));
		return run(cls, value, params);
	}
	
	public static <T> T run(Class<T> cls, String script, Map<String,Object> params) throws ScriptException{
		return run(script, params).as(cls);
	}
	public static Value run(String script, Map<String,Object> params) throws ScriptException{
		Context context = getJavaScriptEngine();
		Value resp = null;
		if(context != null){
			if(params != null) {
				for(String key : params.keySet()){
					///logger.info("Binding: " + key);
					context.getBindings("js").putMember(key, params.get(key));
				}
			}
			resp = context.eval("js",script);
		}
		else{
			throw new ScriptException("Script context is null");
		}
		return resp;
	}

	public static class ScriptResolver {
		public String contextPath() {
			return System.getProperty("user.dir").replace("\\", "/");
		}
	}
}

class ScriptClassFilter {
    private static Pattern[] restrictedClasses = new Pattern[]{
    		Pattern.compile("^org\\.cote\\.accountmanager\\.factory"),
    		Pattern.compile("^org\\.cote\\.accountmanager\\.io")
	};
   
    public static boolean classFilter(String className){
    	boolean matched = false;
  	  for(int i = 0; i < restrictedClasses.length; i++){
  		  if(restrictedClasses[i].matcher(className).find()){
  			  matched = true;
  			  break;
  		  }
  	  }
      return (matched == false);
    }

  }
