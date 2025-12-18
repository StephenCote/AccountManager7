package org.cote.accountmanager.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ErrorUtil {
	public static final Logger logger = LogManager.getLogger(ErrorUtil.class);
	
	public static void printStackTrace() {	
		logger.error(getStackTrace());
	}
	
	public static String getStackTrace() {	
		StringBuilder buff = new StringBuilder();
		StackTraceElement[] st = new Throwable().getStackTrace();
		for(int i = 0; i < st.length; i++) {
			buff.append(st[i].toString() + System.lineSeparator());
		}
		return buff.toString();
	}
}
