package org.cote.accountmanager.objects.tests.scim;

import org.cote.accountmanager.objects.tests.BaseTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.scim.FilterToken;
import org.cote.accountmanager.scim.ScimFilterParser;
import org.junit.Test;

public class TestScimFilterParser extends BaseTest {

	@Test
	public void testTokenize() {
		logger.info("Testing SCIM Filter Parser - tokenize");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
		List<FilterToken> tokens = parser.tokenize("userName eq \"jsmith\"");
		assertNotNull("Tokens should not be null", tokens);
		assertEquals("Should have 3 tokens", 3, tokens.size());
		assertEquals("First token should be ATTRIBUTE", FilterToken.TokenType.ATTRIBUTE, tokens.get(0).getType());
		assertEquals("Second token should be OPERATOR", FilterToken.TokenType.OPERATOR, tokens.get(1).getType());
		assertEquals("Third token should be VALUE", FilterToken.TokenType.VALUE, tokens.get(2).getType());
		assertEquals("Attribute should be userName", "userName", tokens.get(0).getValue());
		assertEquals("Operator should be eq", "eq", tokens.get(1).getValue());
		assertEquals("Value should be jsmith", "jsmith", tokens.get(2).getValue());
	}

	@Test
	public void testTokenizeCompound() {
		logger.info("Testing SCIM Filter Parser - compound expression");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
		List<FilterToken> tokens = parser.tokenize("userName eq \"john\" and active eq true");
		assertNotNull("Tokens should not be null", tokens);
		assertEquals("Should have 7 tokens", 7, tokens.size());
		assertEquals("Fourth token should be LOGICAL", FilterToken.TokenType.LOGICAL, tokens.get(3).getType());
		assertEquals("Logical should be and", "and", tokens.get(3).getValue());
	}

	@Test
	public void testTokenizeParens() {
		logger.info("Testing SCIM Filter Parser - parentheses");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
		List<FilterToken> tokens = parser.tokenize("(userName eq \"a\" or userName eq \"b\") and active eq true");
		assertNotNull("Tokens should not be null", tokens);

		boolean hasOpen = tokens.stream().anyMatch(t -> t.getType() == FilterToken.TokenType.GROUP_OPEN);
		boolean hasClose = tokens.stream().anyMatch(t -> t.getType() == FilterToken.TokenType.GROUP_CLOSE);
		assertTrue("Should have opening paren", hasOpen);
		assertTrue("Should have closing paren", hasClose);
	}

	@Test
	public void testAttributeMapping() {
		logger.info("Testing SCIM Filter Parser - attribute mapping");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);

		assertEquals("userName should map to name", FieldNames.FIELD_NAME, parser.mapScimAttribute("userName"));
		assertEquals("id should map to objectId", FieldNames.FIELD_OBJECT_ID, parser.mapScimAttribute("id"));
		assertEquals("externalId should map to urn", FieldNames.FIELD_URN, parser.mapScimAttribute("externalId"));
		assertEquals("active should map to status", FieldNames.FIELD_STATUS, parser.mapScimAttribute("active"));
		assertEquals("name.givenName should map to firstName", FieldNames.FIELD_FIRST_NAME, parser.mapScimAttribute("name.givenName"));
		assertEquals("name.familyName should map to lastName", FieldNames.FIELD_LAST_NAME, parser.mapScimAttribute("name.familyName"));
		assertEquals("name.middleName should map to middleName", FieldNames.FIELD_MIDDLE_NAME, parser.mapScimAttribute("name.middleName"));
		assertEquals("meta.created should map to createdDate", FieldNames.FIELD_CREATED_DATE, parser.mapScimAttribute("meta.created"));
		assertEquals("meta.lastModified should map to modifiedDate", FieldNames.FIELD_MODIFIED_DATE, parser.mapScimAttribute("meta.lastModified"));
	}

	@Test
	public void testOperatorMapping() {
		logger.info("Testing SCIM Filter Parser - operator mapping");

		assertEquals("eq should map to EQUALS", ComparatorEnumType.EQUALS, ScimFilterParser.mapOperator("eq"));
		assertEquals("ne should map to NOT_EQUALS", ComparatorEnumType.NOT_EQUALS, ScimFilterParser.mapOperator("ne"));
		assertEquals("co should map to LIKE", ComparatorEnumType.LIKE, ScimFilterParser.mapOperator("co"));
		assertEquals("sw should map to LIKE", ComparatorEnumType.LIKE, ScimFilterParser.mapOperator("sw"));
		assertEquals("ew should map to LIKE", ComparatorEnumType.LIKE, ScimFilterParser.mapOperator("ew"));
		assertEquals("gt should map to GREATER_THAN", ComparatorEnumType.GREATER_THAN, ScimFilterParser.mapOperator("gt"));
		assertEquals("ge should map to GREATER_THAN_OR_EQUALS", ComparatorEnumType.GREATER_THAN_OR_EQUALS, ScimFilterParser.mapOperator("ge"));
		assertEquals("lt should map to LESS_THAN", ComparatorEnumType.LESS_THAN, ScimFilterParser.mapOperator("lt"));
		assertEquals("le should map to LESS_THAN_OR_EQUALS", ComparatorEnumType.LESS_THAN_OR_EQUALS, ScimFilterParser.mapOperator("le"));
		assertEquals("pr should map to NOT_NULL", ComparatorEnumType.NOT_NULL, ScimFilterParser.mapOperator("pr"));
	}

	@Test
	public void testEmptyFilter() {
		logger.info("Testing SCIM Filter Parser - empty filter");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
		List<FilterToken> tokens = parser.tokenize("");
		assertNotNull("Tokens should not be null", tokens);
		assertEquals("Empty filter should produce no tokens", 0, tokens.size());
	}

	@Test
	public void testTokenizePresence() {
		logger.info("Testing SCIM Filter Parser - presence operator");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
		List<FilterToken> tokens = parser.tokenize("name.givenName pr");
		assertNotNull("Tokens should not be null", tokens);
		assertEquals("Should have 2 tokens", 2, tokens.size());
		assertEquals("First token should be ATTRIBUTE", FilterToken.TokenType.ATTRIBUTE, tokens.get(0).getType());
		assertEquals("Second token should be OPERATOR", FilterToken.TokenType.OPERATOR, tokens.get(1).getType());
		assertEquals("Operator should be pr", "pr", tokens.get(1).getValue());
	}

	@Test
	public void testTokenizeNot() {
		logger.info("Testing SCIM Filter Parser - not operator");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
		List<FilterToken> tokens = parser.tokenize("not (userName eq \"admin\")");
		assertNotNull("Tokens should not be null", tokens);
		assertEquals("First token should be LOGICAL", FilterToken.TokenType.LOGICAL, tokens.get(0).getType());
		assertEquals("Logical should be not", "not", tokens.get(0).getValue());
	}

	@Test
	public void testSqlInjectionSafety() {
		logger.info("Testing SCIM Filter Parser - SQL injection safety");
		ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
		List<FilterToken> tokens = parser.tokenize("userName eq \"x'; DROP TABLE--\"");
		assertNotNull("Tokens should not be null", tokens);
		assertEquals("Should have 3 tokens", 3, tokens.size());
		assertEquals("Value should be treated as literal", "x'; DROP TABLE--", tokens.get(2).getValue());
	}
}
