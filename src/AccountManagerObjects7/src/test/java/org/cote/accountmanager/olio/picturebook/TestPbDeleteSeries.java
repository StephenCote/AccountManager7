package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Test;

/// Whole-series delete (PbSeriesUtil.deleteSeries): a chaptered book is removed from the series itself,
/// alongside the existing per-chapter delete. Proves, on the real DB with no LLM:
///
///  1. A NON-ENTITLED stranger gets 403 and NOTHING is deleted - series row, both chapters, both chapter
///     containers, the shared world and the series roles all survive.
///  2. The entitled OWNER deletes the series: both chapter rows and their container trees are gone, the
///     ONE shared series world AND its container are gone (the thing every per-chapter path refuses to
///     touch), the series row is gone, and the ~/Roles/Olio/Series/{slug} role subtree is gone.
///  3. A second delete of the same series is a clean 404.
///
/// Fixture: a real olio.pb.series + TWO chapters over media/HarlotsEight_Vol1_SM.docx, created through
/// the production paths (getCreateSeries / PbServiceFacade.createChapter), exactly as TestPbListSeriesBooks
/// does. Never runs as admin.
public class TestPbDeleteSeries extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

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

	private BaseRecord findGroup(BaseRecord olioUser, String path, long orgId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId);
	}

	private BaseRecord findRole(BaseRecord olioUser, String path, long orgId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, path,
			RoleEnumType.USER.toString(), orgId);
	}

	@Test
	public void testStrangerDeniedThenOwnerDeletesWholeSeries() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbDelSeriesOwner");
		assertNotNull("owner user", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		BaseRecord sourceData = getCreateFileData(owner, "~/PbDelSeriesData", docx.getAbsolutePath());
		assertNotNull("sourceData must be created", sourceData);
		String sourceDataOid = sourceData.get(FieldNames.FIELD_OBJECT_ID);

		// ── fixture: one series, its shared world, two chapters ──
		String tag = shortId();
		String seriesSlug = "del" + tag;
		BaseRecord series = PbSeriesUtil.getCreateSeries(owner, dataPath, seriesSlug, "Delete Series " + tag);
		assertNotNull("series must be created", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("seriesSlug() must invert seriesName()", seriesSlug, PbSeriesUtil.seriesSlug(series));

		String slug1 = "dca" + tag;
		String slug2 = "dcb" + tag;
		Map<String, Object> range1 = new LinkedHashMap<>();
		range1.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(0));
		range1.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(1200));
		range1.put(OlioFieldNames.FIELD_PB_TITLE, "Chapter A");
		Map<String, Object> range2 = new LinkedHashMap<>();
		range2.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(1200));
		range2.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(2600));
		range2.put(OlioFieldNames.FIELD_PB_TITLE, "Chapter B");
		Map<String, Object> r1 = PbServiceFacade.createChapter(owner, dataPath, seriesOid, null, slug1,
			"Chapter A Title", Integer.valueOf(1), sourceDataOid, range1, null, null);
		assertNotNull("createChapter #1", r1);
		String book1Oid = (String) r1.get("bookObjectId");
		Map<String, Object> r2 = PbServiceFacade.createChapter(owner, dataPath, seriesOid, null, slug2,
			"Chapter B Title", Integer.valueOf(2), sourceDataOid, range2, null, null);
		assertNotNull("createChapter #2", r2);
		String book2Oid = (String) r2.get("bookObjectId");

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);

		// Everything the delete must remove exists beforehand - otherwise the "gone" assertions are vacuous.
		assertNotNull("chapter 1 row", PbBookUtil.readBook(olioUser, book1Oid, orgId));
		assertNotNull("chapter 2 row", PbBookUtil.readBook(olioUser, book2Oid, orgId));
		assertNotNull("chapter 1 container", findGroup(olioUser, PbBookUtil.bookContainerPath(slug1), orgId));
		assertNotNull("chapter 2 container", findGroup(olioUser, PbBookUtil.bookContainerPath(slug2), orgId));
		assertNotNull("shared series world", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));
		assertNotNull("shared series world container", findGroup(olioUser, PbBookUtil.bookContainerPath(seriesSlug), orgId));
		assertNotNull("series Writer role", findRole(olioUser, PbOlioContextUtil.seriesWriterRolePath(seriesSlug), orgId));
		assertNotNull("series Admin role", findRole(olioUser, PbOlioContextUtil.seriesAdminRolePath(seriesSlug), orgId));

		// ── PROOF 1: a non-entitled stranger is refused and nothing changes ──
		BaseRecord stranger = getCreateUser("pbDelSeriesStranger");
		assertNotNull("stranger user", stranger);
		try {
			PbSeriesUtil.deleteSeries(stranger, seriesOid);
			fail("a non-entitled stranger must not be able to delete the series");
		} catch (PictureBookException e) {
			assertEquals("stranger is refused with 403", 403, e.getStatus());
		}
		assertNotNull("series row survives the refused delete", PbSeriesUtil.readSeries(olioUser, seriesOid, orgId));
		assertNotNull("chapter 1 survives the refused delete", PbBookUtil.readBook(olioUser, book1Oid, orgId));
		assertNotNull("chapter 2 survives the refused delete", PbBookUtil.readBook(olioUser, book2Oid, orgId));
		assertNotNull("chapter 1 container survives", findGroup(olioUser, PbBookUtil.bookContainerPath(slug1), orgId));
		assertNotNull("chapter 2 container survives", findGroup(olioUser, PbBookUtil.bookContainerPath(slug2), orgId));
		assertNotNull("shared world survives the refused delete",
			WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));
		assertNotNull("series Writer role survives", findRole(olioUser, PbOlioContextUtil.seriesWriterRolePath(seriesSlug), orgId));

		// ── PROOF 2: the owner deletes the whole series ──
		int chapters = PbSeriesUtil.deleteSeries(owner, seriesOid);
		assertEquals("both chapters torn down", 2, chapters);

		assertNull("chapter 1 row is gone", PbBookUtil.readBook(olioUser, book1Oid, orgId));
		assertNull("chapter 2 row is gone", PbBookUtil.readBook(olioUser, book2Oid, orgId));
		assertNull("chapter 1 container tree is gone", findGroup(olioUser, PbBookUtil.bookContainerPath(slug1), orgId));
		assertNull("chapter 2 container tree is gone", findGroup(olioUser, PbBookUtil.bookContainerPath(slug2), orgId));
		assertNull("the ONE shared series world is gone",
			WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));
		assertNull("the shared world's container tree is gone",
			findGroup(olioUser, PbBookUtil.bookContainerPath(seriesSlug), orgId));
		assertNull("series row is gone", PbSeriesUtil.readSeries(olioUser, seriesOid, orgId));
		assertNull("series row is gone by slug too", PbSeriesUtil.findSeriesBySlug(olioUser, seriesSlug, orgId));
		assertNull("series Writer role is gone", findRole(olioUser, PbOlioContextUtil.seriesWriterRolePath(seriesSlug), orgId));
		assertNull("series Admin role is gone", findRole(olioUser, PbOlioContextUtil.seriesAdminRolePath(seriesSlug), orgId));
		assertNull("series role parent is gone",
			findRole(olioUser, PbOlioContextUtil.SERIES_ROLE_BASE + "/" + seriesSlug, orgId));

		// ── PROOF 3: deleting it again is a clean 404 ──
		try {
			PbSeriesUtil.deleteSeries(owner, seriesOid);
			fail("a second delete of the same series must be a 404");
		} catch (PictureBookException e) {
			assertEquals("second delete is 404", 404, e.getStatus());
		}
		assertTrue("book1/book2/series ids were real", book1Oid != null && book2Oid != null && seriesOid != null);
		System.out.println("=== deleteSeries PROOF: series=" + seriesOid + " slug=" + seriesSlug
			+ " chapters=" + chapters + " stranger=403 second=404 ===");
	}
}
