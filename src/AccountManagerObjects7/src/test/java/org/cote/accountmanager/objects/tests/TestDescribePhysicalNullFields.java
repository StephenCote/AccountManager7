package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * {@code NarrativeUtil.describePhysical} interpolated nullable record fields straight into the
 * appearance sentence, so an unset one emitted the LITERAL STRING "null".
 *
 * <p>MEASURED on am72db 2026-09-18: the character "Veronique" in picture book "BWO 3" has a NULL
 * {@code hairstyle} column, and her persisted {@code narrative.physicalDescription} reads
 * "...with dark brown eyes and dark brown <b>null</b> hair." That string is now the source of
 * picture-book image prompts, so "null" was being sent to the diffusion model as a hair descriptor.
 *
 * <p>This is the record-side twin of the LLM literal-"null" trap {@code NarrativeUtil.isMeaningful}
 * exists for: the value is neither null nor blank by the time it is concatenated, because
 * concatenation already turned it into text.
 */
public class TestDescribePhysicalNullFields {
	public static final Logger logger = LogManager.getLogger(TestDescribePhysicalNullFields.class);

	/**
	 * A profile over a bare schema-built charPerson. Deliberately NOT built through
	 * {@code ProfileUtil.getProfile}, which analyses personality and needs a database — the method
	 * under test reads only the record plus age/gender/race off the profile, so this is the whole
	 * surface it touches.
	 */
	private static PersonalityProfile profileFor(String hairStyle, boolean withColors) throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_CHAR_PERSON);
		BaseRecord person = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		person.set(FieldNames.FIELD_NAME, "Test Person");
		person.set(FieldNames.FIELD_GENDER, "female");
		person.set(FieldNames.FIELD_AGE, 20);
		person.set(OlioFieldNames.FIELD_RACE, Arrays.asList("E"));
		if (hairStyle != null) person.set(OlioFieldNames.FIELD_HAIR_STYLE, hairStyle);
		if (withColors) {
			RecordFactory.model("data.color");
			BaseRecord hair = RecordFactory.newInstance("data.color");
			hair.set(FieldNames.FIELD_NAME, "Dark Brown");
			BaseRecord eye = RecordFactory.newInstance("data.color");
			eye.set(FieldNames.FIELD_NAME, "Brown (Traditional)");
			person.set(OlioFieldNames.FIELD_HAIR_COLOR, hair);
			person.set(OlioFieldNames.FIELD_EYE_COLOR, eye);
		}
		PersonalityProfile pp = new PersonalityProfile();
		pp.setRecord(person);
		pp.setAge(20);
		pp.setGender("female");
		return pp;
	}

	/// The reported case: hairStyle NULL, colours present.
	@Test
	public void aNullHairStyleNeverRendersTheWordNull() throws Exception {
		String d = NarrativeUtil.describePhysical(profileFor(null, true));
		assertNotNull(d);
		logger.info("describePhysical (no hairStyle): " + d);
		assertFalse("A null hairStyle must not become the literal word 'null': " + d,
			d.toLowerCase().contains("null"));
		// The colour survives - only the missing word is dropped.
		assertTrue("The hair colour must still be described: " + d, d.contains("dark brown hair"));
		assertTrue("The eye colour must still be described: " + d, d.contains("brown eyes"));
	}

	/// No colours AND no style: the sentence must simply end, not trail "with null eyes and null hair".
	@Test
	public void aRecordWithNoColoursDescribesWhatItHas() throws Exception {
		String d = NarrativeUtil.describePhysical(profileFor(null, false));
		assertNotNull(d);
		logger.info("describePhysical (nothing optional set): " + d);
		assertFalse("Nothing optional is set, so nothing may be invented: " + d,
			d.toLowerCase().contains("null"));
		assertFalse("No colours means no 'with ...' clause at all: " + d, d.contains("with "));
		assertTrue("...but the core description must survive: " + d, d.contains("20 year old"));
		assertTrue("A sentence, not a fragment: " + d, d.trim().endsWith("."));
	}

	/// A fully-populated record is unchanged apart from the doubled space getColor used to leave in
	/// ("brown  eyes" came from "Brown (Traditional)" -> "brown " with the qualifier stripped).
	@Test
	public void aFullyPopulatedRecordReadsCleanly() throws Exception {
		String d = NarrativeUtil.describePhysical(profileFor("messy", true));
		assertNotNull(d);
		logger.info("describePhysical (fully populated): " + d);
		assertFalse(d.toLowerCase().contains("null"));
		assertFalse("No doubled spaces from a stripped colour qualifier: " + d, d.contains("  "));
		assertTrue(d.contains("with brown eyes and dark brown messy hair."));
	}
}
