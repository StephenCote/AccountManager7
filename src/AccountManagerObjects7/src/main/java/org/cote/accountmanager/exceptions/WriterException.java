package org.cote.accountmanager.exceptions;

public class WriterException extends Exception {

	private static final long serialVersionUID = 1L;
	public static final String NOT_IMPLEMENTED = "Operation not implemented";
	public static final String FILE_DOESNT_EXIST = "File %s does not exist";
	public WriterException(String msg) {
		super(msg);
	}
}
