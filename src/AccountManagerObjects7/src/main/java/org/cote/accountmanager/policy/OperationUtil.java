
package org.cote.accountmanager.policy;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.IOperation;

;

public class OperationUtil {
	public static final Logger logger = LogManager.getLogger(OperationUtil.class);

	private static Map<String,Class<?>> operations = new HashMap<>();
	private static Map<String,IOperation> operationInst = new HashMap<>();
	
	public static IOperation getOperationInstance(String className, IReader reader, ISearch search){
		Class<?> cls = getOperation(className);
		IOperation oper = null;
		if(cls == null){
			logger.error(className + " is not defined");
			return null;
		}
		
		if(operationInst.containsKey(className)) return operationInst.get(className);
		try {
			oper = (IOperation)cls.getDeclaredConstructor(IReader.class, ISearch.class).newInstance(reader, search);
			operationInst.put(className, oper);

		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			
			logger.error(e);
		}
		return oper;
	}
	public static Class<?> getOperation(String className){
		if(operations.containsKey(className)) return operations.get(className);
		Class<?> cls = null;
		try {
			cls = Class.forName(className);
			operations.put(className, cls);
		} catch (ClassNotFoundException e) {
			
			logger.error(e);
		}
		return cls;
	}
	
}
