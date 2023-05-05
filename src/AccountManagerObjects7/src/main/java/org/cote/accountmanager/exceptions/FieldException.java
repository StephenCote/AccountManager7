package org.cote.accountmanager.exceptions;

public class FieldException extends Exception {

	private static final long serialVersionUID = 1L;
	public static final String FIELD_NOT_CONVERTIBLE = "Model \"%s\" field \"%s\" is not convertible from \"%s\" to \"%s\"";
	public static final String FIELD_NOT_FOUND = "Model \"%s\" field \"%s\" was not found";
	public static final String ABSTRACT_FIELD = "Model \"%s\" field \"%s\" is flexible and must be set with a specific data type";
	public static final String NOT_ABSTRACT_FIELD = "Model \"%s\" field \"%s\" is not abstract and cannot be overwritten";

	public FieldException(String msg) {
		super(msg);
	}
	
	public FieldException(Exception e) {
		super(e);
	}
}
