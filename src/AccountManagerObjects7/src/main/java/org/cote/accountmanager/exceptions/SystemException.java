package org.cote.accountmanager.exceptions;

public class SystemException extends Exception {

	private static final long serialVersionUID = 1L;
	public SystemException(String msg) {
		super(msg);
	}
	public SystemException(Exception e) {
		super(e);
	}
}
