package org.cote.accountmanager.exceptions;

public class StoreException extends Exception {
	
	public static final String STORE_NOT_INITIALIZED = "Store is not initialized";
	public StoreException(String msg) {
		super(msg);
	}
}
