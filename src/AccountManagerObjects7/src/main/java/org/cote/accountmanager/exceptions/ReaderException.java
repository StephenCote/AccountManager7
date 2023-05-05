package org.cote.accountmanager.exceptions;

public class ReaderException extends Exception {
	private static final long serialVersionUID = 1L;
	public static final String NOT_IMPLEMENTED = "Operation not implemented";
	public ReaderException(String msg) {
		super(msg);
	}
	public ReaderException(Exception e) {
		super(e);
	}
}
