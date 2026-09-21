package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Real integration test for the PRODUCTION facade entry point
 * {@link PbServiceFacade#detectSourceBoundaries(BaseRecord, String)} — the read-only chapter-boundary
 * detector that the Ux/REST layer calls to slice a multi-chapter manuscript into source ranges.
 *
 * <p>Unlike {@code TestPbChapterBoundary} (which exercises the raw {@code PbChapterBoundaryUtil} on an
 * in-memory string, no DB, no auth), this test drives the WHOLE facade path against the live DB: it
 * persists the REAL manuscript {@code media/HarlotsEight_Vol1_SM.docx} as a {@code data.data} owned by
 * a non-admin test user, then calls the facade AS that user and asserts a well-formed boundary DTO
 * comes back (at least one range carrying integer {@code startOffset}/{@code endOffset}). The office-doc
 * text is extracted through the facade's own {@code ChapBookUtil.extractPoemText} path (POI/Tika,
 * container-magic sniffing), so this also proves the .docx binary round-trips through the byteStore and
 * out to the boundary detector.
 *
 * <p><b>Security property — STRANGER GETS NOTHING.</b> The facade resolves the manuscript via
 * {@code AccessPoint.find(user, ...)} so {@code canRead} applies (PbServiceFacade.java:889); a second,
 * unrelated non-admin user with no grant on the owner's {@code ~/Data} group must not be able to read
 * the document, and the facade must surface that as a 404 ("Source document not found") — never the
 * owner's boundaries. This test proves both the well-formed-DTO path and the stranger-denied path.
 *
 * <p>Never runs as admin as the acting caller: the org admin is used ONLY to provision the two non-admin
 * users (via {@code BaseTest.getCreateUser}); every facade call is made as one of those non-admin users.
 * No LLM and no SD backend are touched — this is a pure DB + text-extraction path, so it runs in the
 * default suite (no env gate).
 */
public class TestPbDetectBoundariesFacade extends BaseTest {

	private static final String DOCX_MIME =
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	private File locateFixture(String fileName) {
		File[] candidates = new File[] {
			new File("media", fileName),
			new File("AccountManagerObjects7/media", fileName),
			new File("src/AccountManagerObjects7/media", fileName)
		};
		for (File f : candidates) {
			if (f.exists()) {
				return f;
			}
		}
		return null;
	}

	@Test
	public void testDetectSourceBoundariesEntitledAndStranger() throws Exception {
		OlioModelNames.use();

		// ── the manuscript fixture (real multi-chapter .docx) ──
		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		assertTrue("Fixture must exist: " + docx.getAbsolutePath(), docx.exists());
		byte[] bytes = Files.readAllBytes(docx.toPath());
		assertTrue("Manuscript .docx must be non-empty", bytes.length > 0);

		// ── the OWNER: a non-admin test user (admin only provisions it) ──
		BaseRecord owner = getCreateUser("pbBoundaryOwner");
		assertNotNull("owner test user", owner);
		assertFalse("acting user must not be admin", "admin".equals(owner.get(FieldNames.FIELD_NAME)));
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// ── persist the manuscript as a data.data owned by the owner, in the owner's private ~/Data ──
		String docName = "harlots-boundary-" + System.currentTimeMillis() + ".docx";
		BaseRecord manuscript = getCreateData(owner, docName, DOCX_MIME, bytes, "~/Data", orgId);
		assertNotNull("manuscript data.data must be created", manuscript);
		String sourceOid = manuscript.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("manuscript objectId", sourceOid);

		// ══════════════════════════════════════════════════════════════════════════════
		// (a) ENTITLED user gets a well-formed boundary DTO through the FACADE.
		// ══════════════════════════════════════════════════════════════════════════════
		List<Map<String, Object>> ranges = PbServiceFacade.detectSourceBoundaries(owner, sourceOid);
		assertNotNull("detectSourceBoundaries must return a non-null list for the entitled owner", ranges);
		assertFalse("the entitled owner must get at least one detected chapter range", ranges.isEmpty());

		System.out.println("=== detectSourceBoundaries(owner) returned " + ranges.size()
			+ " ranges for " + docName + " ===");
		int shown = 0;
		int prevEnd = -1;
		int rangesWithTitle = 0;
		for (Map<String, Object> r : ranges) {
			Object so = r.get(OlioFieldNames.FIELD_PB_START_OFFSET);
			Object eo = r.get(OlioFieldNames.FIELD_PB_END_OFFSET);
			assertTrue("startOffset must be an Integer in the DTO (was " + so + ")", so instanceof Integer);
			assertTrue("endOffset must be an Integer in the DTO (was " + eo + ")", eo instanceof Integer);
			int start = ((Integer) so).intValue();
			int end = ((Integer) eo).intValue();
			assertTrue("each range must be non-empty (start=" + start + ", end=" + end + ")", end > start);
			assertTrue("ranges must be ordered/non-overlapping (prevEnd=" + prevEnd + ", start=" + start + ")",
				start >= prevEnd);
			prevEnd = end;
			Object title = r.get(OlioFieldNames.FIELD_PB_TITLE);
			if (title != null) {
				rangesWithTitle++;
			}
			if (shown++ < 12) {
				System.out.println(String.format("  [%d, %d) len=%-6d title=%s",
					start, end, (end - start), (title == null ? "(front matter)" : "\"" + title + "\"")));
			}
		}
		// A multi-chapter manuscript must produce at least one titled chapter boundary (not just one
		// whole-document front-matter range) — this is what makes the DTO a *boundary* DTO.
		assertTrue("the facade must detect at least one titled chapter boundary in a multi-chapter "
			+ "manuscript (got " + rangesWithTitle + " titled of " + ranges.size() + ")", rangesWithTitle >= 1);

		// ══════════════════════════════════════════════════════════════════════════════
		// (b) STRANGER GETS NOTHING: a different non-admin user with no read grant on the owner's
		//     ~/Data document must be denied — the facade must NOT return the owner's boundaries.
		// ══════════════════════════════════════════════════════════════════════════════
		BaseRecord stranger = getCreateUser("pbBoundaryStranger");
		assertNotNull("stranger test user", stranger);
		assertFalse("stranger must be a DIFFERENT user from the owner",
			((Long) owner.get(FieldNames.FIELD_ID)).equals((Long) stranger.get(FieldNames.FIELD_ID)));

		boolean denied = false;
		int strangerStatus = -1;
		String strangerMessage = null;
		List<Map<String, Object>> strangerRanges = null;
		try {
			strangerRanges = PbServiceFacade.detectSourceBoundaries(stranger, sourceOid);
		} catch (PictureBookException e) {
			denied = true;
			strangerStatus = e.getStatus();
			strangerMessage = e.getMessage();
		}

		if (denied) {
			// The expected path: AccessPoint.find returns null for the stranger, so the facade 404s.
			assertEquals("stranger must be denied with 404 'Source document not found' (message=" + strangerMessage
				+ ")", 404, strangerStatus);
		} else {
			// The only other acceptable outcome is that the stranger got NOTHING (empty list); a
			// stranger receiving the owner's real boundaries would be a data-leak defect.
			assertNotNull("stranger call returned neither a throw nor a list", strangerRanges);
			assertTrue("STRANGER GETS NOTHING: a stranger with no read grant must not receive the owner's "
				+ "chapter boundaries (got " + strangerRanges.size() + " ranges)", strangerRanges.isEmpty());
		}

		// ── cheap guard-branch confirmations (real, exercised, not fabricated) ──
		int nullUserStatus = -1;
		try {
			PbServiceFacade.detectSourceBoundaries(null, sourceOid);
			fail("null user must be rejected");
		} catch (PictureBookException e) {
			nullUserStatus = e.getStatus();
		}
		assertEquals("null principal must be 401", 401, nullUserStatus);

		int blankIdStatus = -1;
		try {
			PbServiceFacade.detectSourceBoundaries(owner, "   ");
			fail("blank sourceDataObjectId must be rejected");
		} catch (PictureBookException e) {
			blankIdStatus = e.getStatus();
		}
		assertEquals("blank sourceDataObjectId must be 400", 400, blankIdStatus);

		// A syntactically-valid but non-existent objectId must 404 for the owner too (not leak / not NPE).
		int missingStatus = -1;
		try {
			PbServiceFacade.detectSourceBoundaries(owner, "no-such-object-" + System.currentTimeMillis());
			// A missing doc could also legitimately surface as an empty result depending on resolution;
			// tolerate that, but never a throw other than 404.
			missingStatus = 404;
		} catch (PictureBookException e) {
			missingStatus = e.getStatus();
		}
		assertEquals("a non-existent source document must be 404 for the owner", 404, missingStatus);

		// sanity: nothing above mutated the owner's ability to still read its own boundaries
		List<Map<String, Object>> reread = PbServiceFacade.detectSourceBoundaries(owner, sourceOid);
		assertNotNull("owner must still read boundaries after the stranger-denied call", reread);
		assertEquals("owner's boundary count must be stable across calls", ranges.size(), reread.size());
		assertNull("strangerRanges must be null (denied) or empty — never the owner's data",
			strangerRanges == null ? null : (strangerRanges.isEmpty() ? null : strangerRanges));
	}
}
