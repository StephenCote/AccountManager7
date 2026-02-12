package org.cote.accountmanager.olio.llm;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

/**
 * Evaluates condition expressions against chatConfig state.
 * Supports simple boolean fields, comparisons, negation, and logical operators.
 *
 * Condition syntax:
 *   "fieldName"                  - truthy check (boolean true, non-null string, non-empty list)
 *   "!fieldName"                 - negated truthy check
 *   "field==value"               - equality comparison (string or enum)
 *   "field!=value"               - inequality
 *   "field.size>0"               - list size comparison
 *   "expr1&&expr2"               - logical AND
 *   "expr1||expr2"               - logical OR (evaluated after AND)
 *
 * Null/empty condition always returns true (unconditional section).
 */
public class PromptConditionEvaluator {
	private static final Logger logger = LogManager.getLogger(PromptConditionEvaluator.class);

	private PromptConditionEvaluator() {}

	public static boolean evaluate(String condition, BaseRecord chatConfig) {
		if (condition == null || condition.trim().isEmpty()) {
			return true;
		}
		if (chatConfig == null) {
			return false;
		}
		try {
			return evaluateExpression(condition.trim(), chatConfig);
		} catch (Exception e) {
			logger.warn("Condition evaluation error for '" + condition + "': " + e.getMessage());
			return false;
		}
	}

	private static boolean evaluateExpression(String expr, BaseRecord chatConfig) {
		// Handle OR (lowest precedence) — split on || not inside tokens
		int orIdx = findOperator(expr, "||");
		if (orIdx >= 0) {
			String left = expr.substring(0, orIdx).trim();
			String right = expr.substring(orIdx + 2).trim();
			return evaluateExpression(left, chatConfig) || evaluateExpression(right, chatConfig);
		}

		// Handle AND
		int andIdx = findOperator(expr, "&&");
		if (andIdx >= 0) {
			String left = expr.substring(0, andIdx).trim();
			String right = expr.substring(andIdx + 2).trim();
			return evaluateExpression(left, chatConfig) && evaluateExpression(right, chatConfig);
		}

		// Handle negation
		if (expr.startsWith("!")) {
			return !evaluateExpression(expr.substring(1).trim(), chatConfig);
		}

		// Handle comparison operators
		int neqIdx = expr.indexOf("!=");
		if (neqIdx > 0) {
			String field = expr.substring(0, neqIdx).trim();
			String value = expr.substring(neqIdx + 2).trim();
			return !valueEquals(resolveValue(field, chatConfig), value);
		}

		int eqIdx = expr.indexOf("==");
		if (eqIdx > 0) {
			String field = expr.substring(0, eqIdx).trim();
			String value = expr.substring(eqIdx + 2).trim();
			return valueEquals(resolveValue(field, chatConfig), value);
		}

		// Handle .size>N comparison for lists
		int sizeIdx = expr.indexOf(".size>");
		if (sizeIdx > 0) {
			String field = expr.substring(0, sizeIdx).trim();
			String threshold = expr.substring(sizeIdx + 6).trim();
			return listSizeGreaterThan(chatConfig, field, Integer.parseInt(threshold));
		}
		int sizeLtIdx = expr.indexOf(".size<");
		if (sizeLtIdx > 0) {
			String field = expr.substring(0, sizeLtIdx).trim();
			String threshold = expr.substring(sizeLtIdx + 6).trim();
			return listSizeLessThan(chatConfig, field, Integer.parseInt(threshold));
		}

		// Simple truthy check on a field
		return isTruthy(resolveValue(expr, chatConfig));
	}

	/**
	 * Find operator at top level (not inside nested expressions).
	 * Simple scan — no parenthesis support needed for current use cases.
	 */
	private static int findOperator(String expr, String op) {
		return expr.indexOf(op);
	}

	private static Object resolveValue(String fieldPath, BaseRecord chatConfig) {
		try {
			return chatConfig.get(fieldPath);
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean isTruthy(Object value) {
		if (value == null) return false;
		if (value instanceof Boolean) return (Boolean) value;
		if (value instanceof String) return !((String) value).isEmpty();
		if (value instanceof List) return !((List<?>) value).isEmpty();
		if (value instanceof Number) return ((Number) value).doubleValue() != 0;
		// Enum values are truthy if not null
		return true;
	}

	private static boolean valueEquals(Object fieldValue, String compareValue) {
		if (fieldValue == null) return "null".equals(compareValue);
		String fieldStr = fieldValue.toString();
		return fieldStr.equalsIgnoreCase(compareValue);
	}

	private static boolean listSizeGreaterThan(BaseRecord chatConfig, String field, int threshold) {
		Object val = resolveValue(field, chatConfig);
		if (val instanceof List) {
			return ((List<?>) val).size() > threshold;
		}
		return false;
	}

	private static boolean listSizeLessThan(BaseRecord chatConfig, String field, int threshold) {
		Object val = resolveValue(field, chatConfig);
		if (val instanceof List) {
			return ((List<?>) val).size() < threshold;
		}
		return false;
	}
}
