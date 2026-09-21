package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// Proof #2 (binding plan §4): ONE shared world per series (N1/N2, Q6/Q8).
///
/// Creates a real {@code olio.pb.series} (which builds its ONE shared Olio world), then adds TWO
/// chapters to it through the production {@link PbServiceFacade#createChapter} path over the REAL
/// manuscript {@code media/HarlotsEight_Vol1_SM.docx} as {@code sourceData}. It then asserts BOTH
/// chapter books' {@code world} FK is the SAME id as the series' {@code universe} (the shared series
/// world) - not a fresh per-chapter world - and that each chapter carries its {@code series},
/// {@code chapter}, {@code sourceData} and {@code sourceRange}.
///
/// Real DB, no LLM (copyRecordObjectIds is null, so createChapter returns after persisting source
/// provenance - no cast copy, no embedding). Runs as a dedicated test user (never admin); series/world
/// rows are olio-principal-owned but the series Writer role grants the creator read/write on the shared
/// world tree. Lives in the production package because createChapter/PbBookUtil are exercised through
/// their public API - no package-private access is needed here, but proof #3 (same suite) does need it,
/// and keeping both together mirrors the existing {@link TestSeriesWorldDeleteGuard} convention.
public class TestPbSeriesOneWorld extends BaseTest {

	private BaseRecord testUser;
	private long orgId;
	private String dataPath;

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

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static Long idOf(BaseRecord fk) {
		if (fk == null) {
			return null;
		}
		Object id = fk.get(FieldNames.FIELD_ID);
		return (id instanceof Number) ? Long.valueOf(((Number) id).longValue()) : null;
	}

	@Test
	public void testTwoChaptersShareOneSeriesWorld() throws Exception {
		OlioModelNames.use();
		testUser = getCreateUser("pbSeriesOneWorldUser");
		assertNotNull("test user", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		// The real manuscript, uploaded as this user's own data.data (the chapter sourceData).
		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		BaseRecord sourceData = getCreateFileData(testUser, "~/PbSeriesOneWorldData", docx.getAbsolutePath());
		assertNotNull("sourceData must be created", sourceData);
		String sourceDataOid = sourceData.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("sourceData objectId", sourceDataOid);

		String tag = shortId();
		String seriesSlug = "onewld" + tag; // lowercase alnum -> matches BOOK_SLUG_PATTERN
		BaseRecord series = PbSeriesUtil.getCreateSeries(testUser, dataPath, seriesSlug, "One World Series " + tag);
		assertNotNull("series must be created", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("series objectId", seriesOid);
		Long seriesId = ((Number) series.get(FieldNames.FIELD_ID)).longValue();

		BaseRecord seriesWorld = series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
		assertNotNull("series must reference its ONE shared world via universe FK", seriesWorld);
		Long seriesWorldId = idOf(seriesWorld);
		assertNotNull("series world FK must carry an id", seriesWorldId);
		assertEquals("the shared world is named by the series slug", seriesSlug, seriesWorld.get(FieldNames.FIELD_NAME));

		// Two distinct chapters, distinct lowercase slugs, distinct source spans, no cast copy (null).
		String slug1 = "chapa" + tag;
		String slug2 = "chapb" + tag;
		Map<String, Object> range1 = new LinkedHashMap<>();
		range1.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(0));
		range1.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(1200));
		range1.put(OlioFieldNames.FIELD_PB_TITLE, "Chapter A");
		Map<String, Object> range2 = new LinkedHashMap<>();
		range2.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(1200));
		range2.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(2600));
		range2.put(OlioFieldNames.FIELD_PB_TITLE, "Chapter B");

		Map<String, Object> r1 = PbServiceFacade.createChapter(testUser, dataPath, seriesOid, null, slug1,
			"Chapter A Title", Integer.valueOf(1), sourceDataOid, range1, null, null);
		assertNotNull("createChapter #1 returned a result", r1);
		Map<String, Object> r2 = PbServiceFacade.createChapter(testUser, dataPath, seriesOid, null, slug2,
			"Chapter B Title", Integer.valueOf(2), sourceDataOid, range2, null, null);
		assertNotNull("createChapter #2 returned a result", r2);

		// Read both chapters back through the authorized projection.
		BaseRecord book1 = PbBookUtil.findBookBySlug(testUser, slug1, orgId);
		BaseRecord book2 = PbBookUtil.findBookBySlug(testUser, slug2, orgId);
		assertNotNull("chapter 1 must be readable", book1);
		assertNotNull("chapter 2 must be readable", book2);

		// They are TWO distinct chapter rows.
		String book1Oid = book1.get(FieldNames.FIELD_OBJECT_ID);
		String book2Oid = book2.get(FieldNames.FIELD_OBJECT_ID);
		assertNotEquals("the two chapters must be distinct book rows", book1Oid, book2Oid);

		// THE CORE PROOF: both chapters' world FK is the SAME id as the series' shared world - not two worlds.
		BaseRecord book1World = book1.get(OlioFieldNames.FIELD_PB_WORLD);
		BaseRecord book2World = book2.get(OlioFieldNames.FIELD_PB_WORLD);
		Long book1WorldId = idOf(book1World);
		Long book2WorldId = idOf(book2World);
		assertNotNull("chapter 1 must carry a world FK", book1WorldId);
		assertNotNull("chapter 2 must carry a world FK", book2WorldId);
		assertEquals("chapter 1's world must BE the series' shared world", seriesWorldId, book1WorldId);
		assertEquals("chapter 2's world must BE the series' shared world", seriesWorldId, book2WorldId);
		assertEquals("both chapters must share ONE world (not a fresh per-chapter world)",
			book1WorldId, book2WorldId);
		assertEquals("chapter 1's world is named by the series slug", seriesSlug,
			book1World.get(FieldNames.FIELD_NAME));
		assertEquals("chapter 2's world is named by the series slug", seriesSlug,
			book2World.get(FieldNames.FIELD_NAME));

		// series FK on each chapter points at the one series.
		assertEquals("chapter 1's series FK", seriesId, idOf(book1.get(OlioFieldNames.FIELD_PB_SERIES)));
		assertEquals("chapter 2's series FK", seriesId, idOf(book2.get(OlioFieldNames.FIELD_PB_SERIES)));

		// chapter ordinals as supplied.
		assertEquals("chapter 1 ordinal", Integer.valueOf(1), book1.get(OlioFieldNames.FIELD_PB_CHAPTER));
		assertEquals("chapter 2 ordinal", Integer.valueOf(2), book2.get(OlioFieldNames.FIELD_PB_CHAPTER));

		// sourceData FK links each chapter to the real manuscript.
		Long sourceDataId = ((Number) sourceData.get(FieldNames.FIELD_ID)).longValue();
		assertEquals("chapter 1 sourceData FK", sourceDataId, idOf(book1.get(OlioFieldNames.FIELD_PB_SOURCE_DATA)));
		assertEquals("chapter 2 sourceData FK", sourceDataId, idOf(book2.get(OlioFieldNames.FIELD_PB_SOURCE_DATA)));

		// sourceRange sub-record present on each chapter (the span within the manuscript).
		assertNotNull("chapter 1 must carry a sourceRange", book1.get(OlioFieldNames.FIELD_PB_SOURCE_RANGE));
		assertNotNull("chapter 2 must carry a sourceRange", book2.get(OlioFieldNames.FIELD_PB_SOURCE_RANGE));

		System.out.println("=== ONE-SHARED-WORLD PROOF ===");
		System.out.println("  series          objectId=" + seriesOid + " id=" + seriesId
			+ " world(id=" + seriesWorldId + ", name=" + seriesWorld.get(FieldNames.FIELD_NAME) + ")");
		System.out.println("  chapter 1 slug=" + slug1 + " objectId=" + book1Oid
			+ " world.id=" + book1WorldId + " chapter=" + book1.get(OlioFieldNames.FIELD_PB_CHAPTER));
		System.out.println("  chapter 2 slug=" + slug2 + " objectId=" + book2Oid
			+ " world.id=" + book2WorldId + " chapter=" + book2.get(OlioFieldNames.FIELD_PB_CHAPTER));
		System.out.println("  => two distinct chapters, ONE shared world id=" + seriesWorldId);
	}
}
