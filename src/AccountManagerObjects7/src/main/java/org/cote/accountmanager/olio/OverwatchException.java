package org.cote.accountmanager.olio;

public class OverwatchException extends Exception {
	public OverwatchException(String msg) {
		super(msg);
	}
	public OverwatchException(Exception e) {
		super(e);
	}
}
