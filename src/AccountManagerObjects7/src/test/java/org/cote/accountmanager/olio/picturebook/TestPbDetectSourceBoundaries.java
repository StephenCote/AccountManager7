package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Test;

/// Gap 1 (PictureBook N-series opportunity gap): JUnit coverage for
/// {@link PbServiceFacade#detectSourceBoundaries(BaseRecord, String)} — the read-only N3 endpoint that
/// turns a manuscript ({@code data.data}) into the ordered {@code {startOffset, endOffset, title}} range
/// DTOs that {@code POST /chapter}'s {@code sourceRange} argument accepts.
///
/// Lives in the production package because {@link PbServiceFacade} is package-visible and the whole point
/// is to call the SHIPPED facade method directly (no HTTP transport, no mocking) against the live DB, over
/// the REAL corpus {@code media/HarlotsEight_Vol1_SM.docx} — the same single .docx the N-series was
/// designed around (range-within-one-manuscript), not synthetic content.
///
/// Two proofs:
///   1. {@link #testDetectSourceBoundariesReturnsWellFormedRangeDtos()} — an entitled owner gets a
///      well-formed, ordered, gapless list of range DTOs (Integer offsets, title key present) with several
///      real chapter headings, matching {@code TestPbChapterBoundary}'s detector-level result but proven
///      through the facade's own PBAC-scoped resolve + content-type-aware extraction.
///   2. {@link #testStrangerGetsNothing()} — the stranger-gets-nothing contract: a DIFFERENT user cannot
///      read the owner's manuscript, so {@code AccessPoint.find} returns null and the facade collapses it to
///      a 404 (never a leak), and the trivial 401 (null principal) / 400 (blank id) guards.
public class TestPbDetectSourceBoundaries extends BaseTest {

	private static final String MANUSCRIPT = "HarlotsEight_Vol1_SM.docx";
	private static final String DOCX_CT =
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	/// Locate the real manuscript regardless of the working directory the runner picked (git root, src/, or
	/// the module dir) — mirrors {@code TestPbChapterBoundary#locateFixture}.
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

	/// Create a user-owned {@code data.data} manuscript in the acting user's home {@code ~/Data} group,
	/// carrying the real .docx bytes. byteStore is written via {@link ByteModelUtil} (never a raw
	/// {@code .set()}). Returns the created record's objectId.
	private String createManuscript(BaseRecord owner, String name, byte[] bytes) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, owner, null, plist);
		work.set(FieldNames.FIELD_CONTENT_TYPE, DOCX_CT);
		ByteModelUtil.setValue(work, bytes);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, work);
		assertNotNull("manuscript data.data must be created", created);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("created manuscript must expose an objectId", oid);
		return oid;
	}

	@Test
	public void testDetectSourceBoundariesReturnsWellFormedRangeDtos() throws Exception {
		OlioModelNames.use();
		BaseRecord owner = getCreateUser("pbBoundaryOwner");
		assertNotNull("owner test user", owner);
		assertFalse("owner must not be admin", "admin".equals(owner.get(FieldNames.FIELD_NAME)));

		File docx = locateFixture(MANUSCRIPT);
		assertNotNull("real manuscript fixture must be locatable (media/" + MANUSCRIPT + ")", docx);
		byte[] bytes = Files.readAllBytes(docx.toPath());
		assertTrue("manuscript .docx must be non-empty", bytes.length > 0);

		String manuscriptOid = createManuscript(owner, "pb-boundary-" + System.currentTimeMillis() + ".docx", bytes);

		List<Map<String, Object>> ranges = PbServiceFacade.detectSourceBoundaries(owner, manuscriptOid);
		assertNotNull("facade must return a (non-null) range list for a readable manuscript", ranges);
		assertFalse("a real multi-chapter manuscript must not extract to zero ranges", ranges.isEmpty());
		assertTrue("a full manuscript must yield several ranges (front matter + chapters); got " + ranges.size(),
			ranges.size() >= 4);

		// ── DTO shape + ordering/coverage: exactly the {startOffset, endOffset, title} shape createChapter
		// accepts, with Integer offsets, ordered, non-empty, gapless from 0. ──
		int prevEnd = 0;
		int titled = 0;
		int chapterTitled = 0;
		for (int i = 0; i < ranges.size(); i++) {
			Map<String, Object> m = ranges.get(i);
			assertTrue("range " + i + " must carry a startOffset key",
				m.containsKey(OlioFieldNames.FIELD_PB_START_OFFSET));
			assertTrue("range " + i + " must carry an endOffset key",
				m.containsKey(OlioFieldNames.FIELD_PB_END_OFFSET));
			assertTrue("range " + i + " must carry a title key (value may be null for front matter)",
				m.containsKey(OlioFieldNames.FIELD_PB_TITLE));

			Object so = m.get(OlioFieldNames.FIELD_PB_START_OFFSET);
			Object eo = m.get(OlioFieldNames.FIELD_PB_END_OFFSET);
			assertTrue("startOffset must be an Integer, was " + (so == null ? "null" : so.getClass()),
				so instanceof Integer);
			assertTrue("endOffset must be an Integer, was " + (eo == null ? "null" : eo.getClass()),
				eo instanceof Integer);
			int s = (Integer) so;
			int e = (Integer) eo;
			assertEquals("ranges must be contiguous (gapless, non-overlapping) at range " + i, prevEnd, s);
			assertTrue("range " + i + " must be non-empty [" + s + "," + e + ")", e > s);
			prevEnd = e;

			Object t = m.get(OlioFieldNames.FIELD_PB_TITLE);
			if (t != null) {
				assertTrue("a title, when present, must be a String", t instanceof String);
				titled++;
				if (((String) t).toLowerCase().startsWith("chapter")) {
					chapterTitled++;
				}
			}
		}
		assertEquals("first range must start at offset 0",
			0, ((Integer) ranges.get(0).get(OlioFieldNames.FIELD_PB_START_OFFSET)).intValue());
		assertTrue("a full manuscript must detect several titled chapter ranges; got " + titled + " titled",
			titled >= 4);
		assertTrue("most titled ranges should be 'Chapter ...' headings; got " + chapterTitled + " of " + titled,
			chapterTitled >= 4);
	}

	@Test
	public void testStrangerGetsNothing() throws Exception {
		OlioModelNames.use();
		BaseRecord owner = getCreateUser("pbBoundaryOwner");
		assertNotNull("owner test user", owner);

		File docx = locateFixture(MANUSCRIPT);
		assertNotNull("real manuscript fixture must be locatable (media/" + MANUSCRIPT + ")", docx);
		byte[] bytes = Files.readAllBytes(docx.toPath());
		String manuscriptOid = createManuscript(owner, "pb-boundary-stranger-" + System.currentTimeMillis() + ".docx", bytes);

		// Sanity: the owner CAN read it (so the negative below is proven to be an authorization boundary,
		// not a broken/absent record).
		List<Map<String, Object>> ownerRanges = PbServiceFacade.detectSourceBoundaries(owner, manuscriptOid);
		assertNotNull(ownerRanges);
		assertFalse("owner must read the manuscript they uploaded", ownerRanges.isEmpty());

		// ── Stranger-gets-nothing: a DIFFERENT user (same org, no grant on the owner's home-dir record) ──
		BaseRecord stranger = getCreateUser("pbBoundaryStranger");
		assertNotNull("stranger test user", stranger);
		String ownerOid = owner.get(FieldNames.FIELD_OBJECT_ID);
		String strangerOid = stranger.get(FieldNames.FIELD_OBJECT_ID);
		assertFalse("stranger must be a different principal than the owner", ownerOid.equals(strangerOid));

		try {
			PbServiceFacade.detectSourceBoundaries(stranger, manuscriptOid);
			fail("a stranger must not be able to detect boundaries in another user's manuscript");
		} catch (PictureBookException pbe) {
			assertEquals("an unreadable manuscript must collapse to a 404 (find returns null), never a leak",
				404, pbe.getStatus());
		}

		// ── Trivial guards on the read-only contract. ──
		try {
			PbServiceFacade.detectSourceBoundaries(null, manuscriptOid);
			fail("a null principal must be rejected");
		} catch (PictureBookException pbe) {
			assertEquals("null principal must be a 401", 401, pbe.getStatus());
		}
		try {
			PbServiceFacade.detectSourceBoundaries(owner, "   ");
			fail("a blank sourceDataObjectId must be rejected");
		} catch (PictureBookException pbe) {
			assertEquals("blank sourceDataObjectId must be a 400", 400, pbe.getStatus());
		}
	}
}
