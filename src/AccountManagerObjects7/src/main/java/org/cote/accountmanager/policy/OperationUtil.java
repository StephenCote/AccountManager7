
package org.cote.accountmanager.policy;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.IOperation;

;

public class OperationUtil {
	public static final Logger logger = LogManager.getLogger(OperationUtil.class);

	private static Map<String,Class<?>> operations = new ConcurrentHashMap<>();
	private static Map<String,IOperation> operationInst = new ConcurrentHashMap<>();
	private static Map<String,Object[]> operationIo = new ConcurrentHashMap<>();
	
	public static IOperation getOperationInstance(String className, IReader reader, ISearch search){
		Class<?> cls = getOperation(className);
		IOperation oper = null;
		if(cls == null){
			logger.error(className + " is not defined");
			return null;
		}
		
		/// An instance outliving the IOContext it was built for keeps answering from that context's closed
		/// (and no longer invalidated) search cache - OwnerOperation then reports a stale ownerId after
		/// IOSystem is reset in the same JVM. Rebuild whenever the reader or search differs.
		Object[] io = operationIo.get(className);
		if(operationInst.containsKey(className) && io != null && io[0] == reader && io[1] == search) return operationInst.get(className);
		try {
			oper = (IOperation)cls.getDeclaredConstructor(IReader.class, ISearch.class).newInstance(reader, search);
			operationInst.put(className, oper);
			operationIo.put(className, new Object[] { reader, search });

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
