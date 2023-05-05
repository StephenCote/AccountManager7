
package org.cote.accountmanager.provider;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
;

public class ProviderUtil {
	public static final Logger logger = LogManager.getLogger(ProviderUtil.class);

	private static Map<String,Class<?>> providers = new HashMap<>();
	private static Map<String,IProvider> providerInst = new HashMap<>();
	
	public static void clearCache() {
		providers.clear();
		providerInst.clear();
	}
	
	public static IProvider getProviderInstance(String className){
		Class<?> cls = getProvider(className);
		IProvider oper = null;
		if(cls == null){
			logger.error(className + " is not defined");
			return null;
		}
		
		if(providerInst.containsKey(className)) return providerInst.get(className);
		try {
			oper = (IProvider)cls.getDeclaredConstructor().newInstance();
			providerInst.put(className, oper);

		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			logger.error(e);
		}
		return oper;
	}
	public static Class<?> getProvider(String className){
		if(providers.containsKey(className)) return providers.get(className);
		Class<?> cls = null;
		try {
			cls = Class.forName(className);
			providers.put(className, cls);
		} catch (ClassNotFoundException e) {
			
			logger.error(e);
		}
		return cls;
	}
	
}
