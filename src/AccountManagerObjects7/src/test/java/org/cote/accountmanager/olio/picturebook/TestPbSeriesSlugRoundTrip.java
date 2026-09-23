package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// B1 regression proof for the {@code seriesSlug} field on {@code olio.pictureBookRequest}.
///
/// POST /rest/picture-book/series ({@code PictureBookService.createSeries}) deserializes its JSON body
/// with {@code JSONUtil.importObject(..., LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule())}
/// and then reads {@code params.get("seriesSlug")}. RecordDeserializer SILENTLY DROPS any JSON property
/// that is not declared on the target model (the KI-24 pattern). Before {@code seriesSlug} was declared on
/// {@code olio.pictureBookRequest}, the client's {@code seriesSlug} therefore never arrived —
/// {@code params.get("seriesSlug")} was null — and {@code createSeries} failed downstream with a 400.
///
/// This test reproduces the EXACT transport deserialization (the same {@code LooseRecord} + unfiltered
/// module the service uses) and asserts:
///   (1) the declared {@code seriesSlug} (and its N-series sibling {@code seriesObjectId}) SURVIVE
///       deserialization and carry their exact values, and
///   (2) a deliberately UNDECLARED property in the same body is DROPPED — proving the round-trip in (1)
///       is a consequence of the field being DECLARED, not of the deserializer keeping everything.
///
/// Pure deserialization: no DB rows, no LLM. It exercises RecordDeserializer against the registered
/// {@code olio.pictureBookRequest} schema ({@code OlioModelNames.use()}).
public class TestPbSeriesSlugRoundTrip extends BaseTest {

	/// The schema the service stamps onto the body in ensureSchema() when none is present.
	private static final String PB_REQUEST_SCHEMA = "olio.pictureBookRequest";

	/// Mirror of PictureBookService.parseParams(): same LooseRecord target + unfiltered module.
	private static BaseRecord parseAsService(String bodyWithSchema) {
		return JSONUtil.importObject(bodyWithSchema, LooseRecord.class,
				RecordDeserializerConfig.getUnfilteredModule());
	}

	@Test
	public void testSeriesSlugSurvivesDeserialization() {
		OlioModelNames.use();

		String seriesSlug = "harlots8series";
		String seriesObjectId = "11111111-2222-3333-4444-555555555555";
		String title = "The Harlots Eight";

		String json = "{\"schema\":\"" + PB_REQUEST_SCHEMA + "\","
				+ "\"seriesSlug\":\"" + seriesSlug + "\","
				+ "\"seriesObjectId\":\"" + seriesObjectId + "\","
				+ "\"title\":\"" + title + "\","
				// A property that is NOT declared on olio.pictureBookRequest — RecordDeserializer must drop it.
				+ "\"bogusUndeclaredField\":\"definitely-not-a-declared-field\"}";

		BaseRecord params = parseAsService(json);
		assertNotNull("request body must deserialize to a record", params);

		// (1) seriesSlug — the field under test — round-trips with its exact value (what createSeries reads).
		assertTrue("seriesSlug must be present after deserialization (declared field)",
				params.hasField("seriesSlug"));
		assertEquals("seriesSlug value must round-trip verbatim", seriesSlug, params.get("seriesSlug"));

		// Its N-series sibling, declared alongside for the same KI-24 reason, must survive too.
		assertTrue("seriesObjectId must be present after deserialization (declared field)",
				params.hasField("seriesObjectId"));
		assertEquals("seriesObjectId value must round-trip verbatim",
				seriesObjectId, params.get("seriesObjectId"));

		assertEquals("title value must round-trip verbatim", title, params.get("title"));

		// (2) An UNDECLARED property is dropped — proving the round-trip above is a consequence of the
		// field being declared, and reproducing exactly why seriesSlug had to be added (KI-24).
		assertFalse("an undeclared JSON property must be dropped by RecordDeserializer (KI-24)",
				params.hasField("bogusUndeclaredField"));

		System.out.println("=== seriesSlug ROUND-TRIP PROOF ===");
		System.out.println("  seriesSlug     -> " + params.get("seriesSlug"));
		System.out.println("  seriesObjectId -> " + params.get("seriesObjectId"));
		System.out.println("  title          -> " + params.get("title"));
		System.out.println("  bogusUndeclaredField present? " + params.hasField("bogusUndeclaredField"));
	}
}
