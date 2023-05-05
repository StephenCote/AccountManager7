package org.cote.accountmanager.exceptions;

public class FactoryException extends Exception {
	private static final long serialVersionUID = 1L;

	public FactoryException(String msg) {
		super(msg);
	}
	public FactoryException(Exception e) {
		super(e);
	}
}
