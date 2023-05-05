package org.cote.accountmanager.exceptions;

public class IndexException extends Exception {
	private static final long serialVersionUID = 1L;
	public static final String NOT_INDEXABLE = "Record %s does not define %s";
	public static final String INDEX_NOT_FOUND = "Index not found";
	public static final String INDEX_VALUE_NOT_FOUND = "Index %s not found";
	public static final String INDEX_COLLISION = "Index collision";
	public static final String INDEX_ENTRY_NOT_FOUND = "Index entry not found";
	public static final String INDEX_EXISTS = "Index for %s already exists";
	public static final String INDEX_SERIALIZATION_ERROR = "Index failed to serialize";
	public static final String INDEX_IO_ERROR = "Index failed IO operation";
	public static final String INDEX_IDENTITY_NOT_FOUND = "Indexed identity value(s) %s were not found";
	
	public IndexException(String msg) {
		super(msg);
	}
}
