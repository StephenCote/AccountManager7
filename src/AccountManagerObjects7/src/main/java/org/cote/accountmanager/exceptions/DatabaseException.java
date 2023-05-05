package org.cote.accountmanager.exceptions;

public class DatabaseException extends Exception {
	private static final long serialVersionUID = 1L;

	public DatabaseException(String msg) {
		super(msg);
	}
	public DatabaseException(Exception e) {
		super(e);
	}

}
