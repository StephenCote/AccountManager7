package org.cote.accountmanager.exceptions;

public class ValueException extends Exception {
	
	private static final long serialVersionUID = 1L;
	public static final String ENUM_VALUE_EXCEPTION = "Value \"%s\" is not a valid entry in \"%s\"";
	public static final String MODEL_VALUE_EXCEPTION = "Value \"%s\" is not a valid model";
	public static final String INVALID_MODEL_EXCEPTION = "Value \"%s\" is not a \"%s\" model";
	public static final String PROTOTYPE_READONLY_EXCEPTION = "Value \"%s\" cannot be set on the \"%s\" prototype";
	
	public ValueException(String msg){
		super(msg);
	}
	public ValueException(Exception e){
		super(e);
	}
}
