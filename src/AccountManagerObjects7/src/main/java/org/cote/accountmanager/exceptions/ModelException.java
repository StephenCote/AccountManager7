package org.cote.accountmanager.exceptions;

public class ModelException extends Exception {
	private static final long serialVersionUID = 1L;
	public static final String ENUM_EXCEPTION = "Enumeration value was not valid";
	public static final String PROTECTED_NAME_EXCEPTION = "Field name %s is protected and cannot be used";
	public static final String INHERITENCE_EXCEPTION = "Model %s does not inherit from %s";
	
	public ModelException(String msg){
		super(msg);
	}
	public ModelException(Exception e){
		super(e);
	}
}
