package org.cote.accountmanager.exceptions;

public class ModelValidationException  extends Exception {

	private static final long serialVersionUID = 1L;
	public static final String FIELD_READ_ONLY = "Model \"%s\" field \"%s\" is read-only and may not be changed";
	public static final String VALIDATION_ERROR = "Model \"%s\" field \"%s\" is invalid";
	
	public ModelValidationException(String msg){
		super(msg);
	}
}
