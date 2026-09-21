package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Test;

/// N4 whole-series listing (PbServiceFacade.listSeriesBooks): a series' chapter books are returned to an
/// ENTITLED NON-OWNER - a holder of the series {@code Writer} role who is NOT the owner - with each
/// chapter's series/chapter/world linkage, while a NON-ENTITLED stranger gets an empty list. This is the
/// authorization-boundary proof: the series FK only scopes the candidate set; the per-chapter
/// {@code PbBookUtil.readBook} as the ACTING user is what enforces the entitlement.
///
/// Real DB, no LLM. A dedicated OWNER user creates a real {@code olio.pb.series} and TWO chapters over the
/// real manuscript {@code media/HarlotsEight_Vol1_SM.docx} (copyRecordObjectIds null -> no cast copy, no
/// embedding). A second user is enrolled in the series {@code Writer} role (the existing series role
/// inheritance - no new auth machinery) and must see BOTH chapters; a third, un-enrolled user must see
/// none. Never runs as admin: the org admin is used only to grant the Writer role, exactly as the
/// production series-context enrolment does.
public class TestPbListSeriesBooks extends BaseTest {

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

	@Test
	public void testEntitledNonOwnerSeesAllChaptersStrangerSeesNone() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbListSeriesOwner");
		assertNotNull("owner user", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		// The real manuscript, uploaded as the owner's own data.data (the chapter sourceData).
		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		BaseRecord sourceData = getCreateFileData(owner, "~/PbListSeriesData", docx.getAbsolutePath());
		assertNotNull("sourceData must be created", sourceData);
		String sourceDataOid = sourceData.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("sourceData objectId", sourceDataOid);

		// A real series + its ONE shared world, created by the owner (who getCreateSeries enrols as Writer).
		String tag = shortId();
		String seriesSlug = "lst" + tag; // lowercase alnum -> matches BOOK_SLUG_PATTERN
		BaseRecord series = PbSeriesUtil.getCreateSeries(owner, dataPath, seriesSlug, "List Series " + tag);
		assertNotNull("series must be created", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("series objectId", seriesOid);
		BaseRecord seriesWorld = series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
		assertNotNull("series must reference its shared world", seriesWorld);
		String seriesWorldOid = seriesWorld.get(FieldNames.FIELD_OBJECT_ID);

		// Two distinct chapters over distinct manuscript spans, no cast copy (null).
		String slug1 = "lca" + tag;
		String slug2 = "lcb" + tag;
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

		// The ENTITLED NON-OWNER: a distinct user enrolled ONLY in the series Writer role (existing series
		// role inheritance). The org admin performs the grant - the sole admin use, mirroring production.
		BaseRecord writer = getCreateUser("pbListSeriesWriter");
		assertNotNull("writer (entitled non-owner) user", writer);
		assertTrue("owner and entitled non-owner must be different users",
			!book1Oid.isEmpty()
			&& !((Long) owner.get(FieldNames.FIELD_ID)).equals((Long) writer.get(FieldNames.FIELD_ID)));
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		BaseRecord writerRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.seriesWriterRolePath(seriesSlug), RoleEnumType.USER.toString(), orgId);
		assertNotNull("series Writer role must exist", writerRole);
		assertTrue("enrol entitled non-owner into series Writer role",
			ioContext.getMemberUtil().member(orgContext.getAdminUser(), writerRole, writer, null, true));
		assertTrue("entitled non-owner is a member of the series Writer role",
			ioContext.getMemberUtil().isMember(writer, writerRole, null));

		// The NON-ENTITLED stranger: a fresh user (AccountUsers only), no series role.
		BaseRecord stranger = getCreateUser("pbListSeriesStranger");
		assertNotNull("stranger user", stranger);

		// ── PROOF 1: the entitled NON-OWNER sees BOTH chapters, each with full linkage ──
		List<Map<String, Object>> asWriter = PbServiceFacade.listSeriesBooks(writer, seriesOid);
		assertNotNull("listSeriesBooks(writer) must not be null", asWriter);
		System.out.println("=== N4 listSeriesBooks(entitled non-owner) ===");
		for (Map<String, Object> dto : asWriter) {
			System.out.println("  " + dto);
		}
		assertEquals("entitled non-owner must see BOTH chapters", 2, asWriter.size());

		Set<String> seenOids = new HashSet<>();
		Set<Object> seenChapters = new HashSet<>();
		for (Map<String, Object> dto : asWriter) {
			assertNotNull("objectId", dto.get("objectId"));
			assertNotNull("name", dto.get("name"));
			assertNotNull("slug", dto.get("slug"));
			assertNotNull("bookStatus", dto.get("bookStatus"));
			assertNotNull("chapter", dto.get("chapter"));
			assertEquals("seriesObjectId must be this series", seriesOid, dto.get("seriesObjectId"));
			assertEquals("worldObjectId must be the ONE shared series world", seriesWorldOid, dto.get("worldObjectId"));
			seenOids.add((String) dto.get("objectId"));
			seenChapters.add(dto.get("chapter"));
		}
		assertTrue("chapter 1 present", seenOids.contains(book1Oid));
		assertTrue("chapter 2 present", seenOids.contains(book2Oid));
		assertTrue("chapter ordinal 1 present", seenChapters.contains(Integer.valueOf(1)));
		assertTrue("chapter ordinal 2 present", seenChapters.contains(Integer.valueOf(2)));
		// Ascending-by-chapter ordering.
		assertEquals("first DTO is chapter 1", Integer.valueOf(1), asWriter.get(0).get("chapter"));
		assertEquals("second DTO is chapter 2", Integer.valueOf(2), asWriter.get(1).get("chapter"));

		// ── PROOF 2: the owner (also a Writer member) sees both chapters too ──
		List<Map<String, Object>> asOwner = PbServiceFacade.listSeriesBooks(owner, seriesOid);
		assertNotNull("listSeriesBooks(owner)", asOwner);
		assertEquals("owner sees both chapters", 2, asOwner.size());

		// ── PROOF 3: the non-entitled stranger sees NONE (empty, not a leak) ──
		List<Map<String, Object>> asStranger = PbServiceFacade.listSeriesBooks(stranger, seriesOid);
		assertNotNull("listSeriesBooks(stranger) must not be null", asStranger);
		System.out.println("=== N4 listSeriesBooks(non-entitled stranger) size=" + asStranger.size() + " ===");
		assertEquals("non-entitled stranger must see NO chapters", 0, asStranger.size());

		// ── PROOF 4: workflowView surfaces THIS chapter's series linkage (N4 whole-series entry point) ──
		// The Ux N4 canvas loads workflowView for the current chapter and must learn its seriesObjectId
		// (to call listSeriesBooks) and its chapter ordinal from that same DTO. Create a workflow for
		// chapter 1 (no SD/LLM) so workflowView has a workflow to return, then assert the two new fields.
		BaseRecord book1 = PbBookUtil.findBookBySlug(owner, slug1, orgId);
		assertNotNull("chapter 1 must be readable for the workflow-view proof", book1);
		BaseRecord wf1 = PbGraphUtil.getCreateWorkflow(owner, book1, PbBookUtil.workflowGroupPath(slug1));
		assertNotNull("a workflow must be creatable for chapter 1", wf1);
		Map<String, Object> wv = PbServiceFacade.workflowView(owner, book1Oid);
		assertNotNull("workflowView(chapter 1) must not be null", wv);
		System.out.println("=== N4 workflowView(chapter 1) seriesObjectId=" + wv.get("seriesObjectId")
			+ " chapter=" + wv.get("chapter") + " ===");
		assertEquals("workflowView must surface this chapter's seriesObjectId", seriesOid, wv.get("seriesObjectId"));
		assertEquals("workflowView must surface this chapter's ordinal", Integer.valueOf(1), wv.get("chapter"));

		System.out.println("=== N4 PROOF: entitled non-owner=" + asWriter.size()
			+ " chapters, owner=" + asOwner.size() + ", stranger=" + asStranger.size()
			+ " (series=" + seriesOid + ", world=" + seriesWorldOid + ")");
	}
}
