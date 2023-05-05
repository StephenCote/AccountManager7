package org.cote.accountmanager.exceptions;

public class RecordException extends Exception {
	private static final long serialVersionUID = 1L;

	public RecordException(String msg) {
		super(msg);
	}
	public RecordException(Exception e) {
		super(e);
	}
}
