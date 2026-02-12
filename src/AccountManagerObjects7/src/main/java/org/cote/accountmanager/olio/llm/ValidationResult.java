package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

public class ValidationResult {

	private final List<UnknownToken> unknownTokens = new ArrayList<>();

	public boolean isValid() {
		return unknownTokens.isEmpty();
	}

	public List<UnknownToken> getUnknownTokens() {
		return unknownTokens;
	}

	public void addUnknownToken(String token, String fieldName) {
		unknownTokens.add(new UnknownToken(token, fieldName));
	}

	public static class UnknownToken {
		private final String token;
		private final String fieldName;

		public UnknownToken(String token, String fieldName) {
			this.token = token;
			this.fieldName = fieldName;
		}

		public String getToken() {
			return token;
		}

		public String getFieldName() {
			return fieldName;
		}

		@Override
		public String toString() {
			return "${" + token + "} in field '" + fieldName + "'";
		}
	}
}
