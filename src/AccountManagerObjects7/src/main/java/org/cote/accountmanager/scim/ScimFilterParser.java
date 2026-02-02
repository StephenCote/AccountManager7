package org.cote.accountmanager.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class ScimFilterParser {

	private static final Logger logger = LogManager.getLogger(ScimFilterParser.class);

	private static final Set<String> OPERATORS = Set.of("eq", "ne", "co", "sw", "ew", "gt", "ge", "lt", "le", "pr");
	private static final Set<String> LOGICALS = Set.of("and", "or", "not");

	private final String modelType;

	public ScimFilterParser(String modelType) {
		this.modelType = modelType;
	}

	public void applyFilter(String filter, Query query) {
		if (filter == null || filter.isBlank()) {
			return;
		}
		List<FilterToken> tokens = tokenize(filter);
		if (tokens.isEmpty()) {
			return;
		}
		parseExpression(tokens, 0, query, query);
	}

	public List<FilterToken> tokenize(String filter) {
		List<FilterToken> tokens = new ArrayList<>();
		int i = 0;
		int len = filter.length();

		while (i < len) {
			char c = filter.charAt(i);

			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}

			if (c == '(') {
				tokens.add(new FilterToken(FilterToken.TokenType.GROUP_OPEN, "("));
				i++;
				continue;
			}
			if (c == ')') {
				tokens.add(new FilterToken(FilterToken.TokenType.GROUP_CLOSE, ")"));
				i++;
				continue;
			}

			if (c == '"') {
				int end = filter.indexOf('"', i + 1);
				if (end == -1) end = len;
				tokens.add(new FilterToken(FilterToken.TokenType.VALUE, filter.substring(i + 1, end)));
				i = end + 1;
				continue;
			}

			int start = i;
			while (i < len && !Character.isWhitespace(filter.charAt(i)) && filter.charAt(i) != '(' && filter.charAt(i) != ')') {
				i++;
			}
			String word = filter.substring(start, i);

			if (LOGICALS.contains(word.toLowerCase())) {
				tokens.add(new FilterToken(FilterToken.TokenType.LOGICAL, word.toLowerCase()));
			} else if (OPERATORS.contains(word.toLowerCase())) {
				tokens.add(new FilterToken(FilterToken.TokenType.OPERATOR, word.toLowerCase()));
			} else if (word.equalsIgnoreCase("true") || word.equalsIgnoreCase("false") || word.equalsIgnoreCase("null")) {
				tokens.add(new FilterToken(FilterToken.TokenType.VALUE, word));
			} else if (word.matches("^\\d+$")) {
				tokens.add(new FilterToken(FilterToken.TokenType.VALUE, word));
			} else {
				tokens.add(new FilterToken(FilterToken.TokenType.ATTRIBUTE, word));
			}
		}
		return tokens;
	}

	private int parseExpression(List<FilterToken> tokens, int pos, Query query, BaseRecord parent) {
		if (pos >= tokens.size()) return pos;

		pos = parsePrimary(tokens, pos, query, parent);

		while (pos < tokens.size()) {
			FilterToken tok = tokens.get(pos);
			if (tok.getType() != FilterToken.TokenType.LOGICAL) break;
			if (tok.getValue().equals("not")) break;

			String logical = tok.getValue();
			pos++;

			ComparatorEnumType groupComp = "or".equals(logical) ? ComparatorEnumType.GROUP_OR : ComparatorEnumType.GROUP_AND;

			QueryField group = query.field(null, groupComp, null, parent);
			pos = parsePrimary(tokens, pos, query, group);
		}
		return pos;
	}

	private int parsePrimary(List<FilterToken> tokens, int pos, Query query, BaseRecord parent) {
		if (pos >= tokens.size()) return pos;

		FilterToken tok = tokens.get(pos);

		if (tok.getType() == FilterToken.TokenType.LOGICAL && "not".equals(tok.getValue())) {
			pos++;
			return parsePrimary(tokens, pos, query, parent);
		}

		if (tok.getType() == FilterToken.TokenType.GROUP_OPEN) {
			pos++;
			pos = parseExpression(tokens, pos, query, parent);
			if (pos < tokens.size() && tokens.get(pos).getType() == FilterToken.TokenType.GROUP_CLOSE) {
				pos++;
			}
			return pos;
		}

		if (tok.getType() == FilterToken.TokenType.ATTRIBUTE) {
			String attr = tok.getValue();
			String fieldName = mapScimAttribute(attr);
			pos++;

			if (pos >= tokens.size()) return pos;

			FilterToken opTok = tokens.get(pos);
			if (opTok.getType() != FilterToken.TokenType.OPERATOR) return pos;

			String op = opTok.getValue();
			pos++;

			if ("pr".equals(op)) {
				query.field(fieldName, ComparatorEnumType.NOT_NULL, null, parent);
				return pos;
			}

			if (pos >= tokens.size()) return pos;

			FilterToken valTok = tokens.get(pos);
			String value = valTok.getValue();
			pos++;

			ComparatorEnumType comp = mapOperator(op);
			Object queryValue = convertValue(value, op);

			query.field(fieldName, comp, queryValue, parent);
		}
		return pos;
	}

	public String mapScimAttribute(String scimAttr) {
		if (scimAttr == null) return scimAttr;

		return switch (scimAttr) {
			case "userName" -> FieldNames.FIELD_NAME;
			case "id" -> FieldNames.FIELD_OBJECT_ID;
			case "externalId" -> FieldNames.FIELD_URN;
			case "active" -> FieldNames.FIELD_STATUS;
			case "displayName" -> FieldNames.FIELD_NAME;
			case "name.givenName" -> FieldNames.FIELD_FIRST_NAME;
			case "name.familyName" -> FieldNames.FIELD_LAST_NAME;
			case "name.middleName" -> FieldNames.FIELD_MIDDLE_NAME;
			case "meta.created" -> FieldNames.FIELD_CREATED_DATE;
			case "meta.lastModified" -> FieldNames.FIELD_MODIFIED_DATE;
			default -> scimAttr;
		};
	}

	public static ComparatorEnumType mapOperator(String scimOp) {
		return switch (scimOp.toLowerCase()) {
			case "eq" -> ComparatorEnumType.EQUALS;
			case "ne" -> ComparatorEnumType.NOT_EQUALS;
			case "co" -> ComparatorEnumType.LIKE;
			case "sw" -> ComparatorEnumType.LIKE;
			case "ew" -> ComparatorEnumType.LIKE;
			case "gt" -> ComparatorEnumType.GREATER_THAN;
			case "ge" -> ComparatorEnumType.GREATER_THAN_OR_EQUALS;
			case "lt" -> ComparatorEnumType.LESS_THAN;
			case "le" -> ComparatorEnumType.LESS_THAN_OR_EQUALS;
			case "pr" -> ComparatorEnumType.NOT_NULL;
			default -> ComparatorEnumType.EQUALS;
		};
	}

	private Object convertValue(String value, String op) {
		if ("true".equalsIgnoreCase(value)) {
			if ("eq".equals(op) && "active".equals(value)) {
				return ScimUserAdapter.mapActiveToStatus(true);
			}
			return true;
		}
		if ("false".equalsIgnoreCase(value)) {
			if ("eq".equals(op) && "active".equals(value)) {
				return ScimUserAdapter.mapActiveToStatus(false);
			}
			return false;
		}
		if ("null".equalsIgnoreCase(value)) return null;

		switch (op) {
			case "co":
				return "%" + value + "%";
			case "sw":
				return value + "%";
			case "ew":
				return "%" + value;
			default:
				return value;
		}
	}
}
