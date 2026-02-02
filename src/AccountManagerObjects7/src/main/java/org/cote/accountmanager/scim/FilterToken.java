package org.cote.accountmanager.scim;

public class FilterToken {

	public enum TokenType {
		ATTRIBUTE,
		OPERATOR,
		VALUE,
		LOGICAL,
		GROUP_OPEN,
		GROUP_CLOSE,
		PRESENCE
	}

	private final TokenType type;
	private final String value;

	public FilterToken(TokenType type, String value) {
		this.type = type;
		this.value = value;
	}

	public TokenType getType() {
		return type;
	}

	public String getValue() {
		return value;
	}

	@Override
	public String toString() {
		return type + ":" + value;
	}
}
