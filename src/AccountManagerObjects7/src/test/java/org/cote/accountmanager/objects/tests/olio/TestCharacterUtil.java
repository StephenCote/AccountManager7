package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Regression coverage for the empty-word-list crash in {@link CharacterUtil#randomPerson}.
 *
 * On a fresh container the Olio seed corpus at {@code datagen.path} can be empty, so the name
 * "decks" ({@code Decks.get*Deck(parWorld)}) return ZERO-LENGTH (but non-null) arrays that flow into
 * {@code randomPerson}. The pre-fix guard tested {@code names != null} only, so a zero-length array
 * made {@code names[rand.nextInt(names.length)]} throw {@code IllegalArgumentException: bound must be
 * positive}, which {@code OlioContext.initialize()} re-throws as an unconditional HTTP 500 on
 * {@code GET /rest/olio/roll}. The fix additionally checks {@code .length > 0} so an empty array
 * degrades exactly like a null/absent one (falling through to {@code OlioUtil.randomSelectionName}
 * and, if that is also dry, the synthesized-name backstop).
 */
public class TestCharacterUtil extends BaseTest {

	private OlioContext getOlioContext() {
		String dataPath = testProperties.getProperty("test.datagen.path");
		try {
			return OlioTestUtil.getContext(orgContext, dataPath);
		} catch (StackOverflowError | Exception e) {
			logger.error("Failed to get OlioContext: " + e.getMessage());
			return null;
		}
	}

	/**
	 * The crash reproducer: empty (zero-length, non-null) name arrays must NOT throw
	 * IllegalArgumentException; randomPerson must route into the existing graceful-degradation path and
	 * return a valid person whose name is non-empty and not the literal string "null".
	 */
	@Test
	public void TestRandomPersonEmptyNameArrays() {
		logger.info("Test CharacterUtil.randomPerson with EMPTY name arrays (unseeded-corpus condition)");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		String[] empty = new String[0];
		BaseRecord person = null;
		try {
			/// Pass zero-length arrays exactly as an unseeded Decks.get*Deck() would produce. Pre-fix this
			/// threw IllegalArgumentException at the names[rand.nextInt(0)] draw.
			person = CharacterUtil.randomPerson(octx, null, ZonedDateTime.now(), empty, empty, empty, empty);
		} catch (IllegalArgumentException e) {
			org.junit.Assert.fail("randomPerson threw IllegalArgumentException on empty name arrays (the regression): " + e.getMessage());
		}

		assertNotNull("randomPerson returned null on empty name arrays", person);
		String name = person.get(FieldNames.FIELD_NAME);
		assertNotNull("Person name is null", name);
		assertFalse("Person name is blank", name.isBlank());
		assertFalse("Person name contains the literal string 'null': " + name, name.toLowerCase().contains("null"));
		logger.info("randomPerson (empty arrays) produced name: " + name);
	}

	/**
	 * Behavior-preservation guard: when the name arrays ARE populated the draw must still come from the
	 * array (the fix must not change the populated-path behavior). A single-element, UUID-unique token
	 * array forces a deterministic, collision-free draw so first/middle/last must equal that token.
	 */
	@Test
	public void TestRandomPersonPopulatedNameArraysUnchanged() {
		logger.info("Test CharacterUtil.randomPerson draws from a POPULATED name array (behavior preserved)");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		/// UUID-unique alphanumeric token => the assembled name is unique, so the uniqueness-retry loop
		/// never fires and the synthesis fallback is never reached; the draw is deterministic.
		String tok = "Nx" + UUID.randomUUID().toString().replace("-", "");
		/// Same single-element array for male AND female pools so the (random) gender selection is moot.
		String[] one = new String[] { tok };

		BaseRecord person = CharacterUtil.randomPerson(octx, null, ZonedDateTime.now(), one, one, one, one);
		assertNotNull("randomPerson returned null on populated name arrays", person);

		assertEquals("First name should be drawn from the populated array", tok, person.get(FieldNames.FIELD_FIRST_NAME));
		assertEquals("Middle name should be drawn from the populated array", tok, person.get(FieldNames.FIELD_MIDDLE_NAME));
		assertEquals("Last name should be drawn from the populated array", tok, person.get(FieldNames.FIELD_LAST_NAME));

		String name = person.get(FieldNames.FIELD_NAME);
		assertTrue("Assembled name should contain the drawn token", name != null && name.contains(tok));
		logger.info("randomPerson (populated array) produced name: " + name);
	}
}
