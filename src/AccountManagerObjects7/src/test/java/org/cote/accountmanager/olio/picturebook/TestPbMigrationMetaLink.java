package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbMigrationUtil.ImportResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 §24 I-5 (218 d): {@code importV1Book} writes the {@code pb2BookObjectId} back-link into the PB1
 * {@code .pictureBookMeta} note - the one PB1 write the import performs, the same key {@code /health}'s
 * {@code META_LINK_BROKEN} repair sets - so an imported book does not start life with that WARN, and so
 * {@code PictureBookUtil.resolveBookGroupEither} (the name-independent scene-group resolution every PB1-backed
 * reader path uses) finds the PB1 group even when its name is not the slug.
 * <p>
 * Two nonce-named PB1 fixtures for {@code pbCustomTestUser} in {@code /Development/PictureBook Custom Tests}
 * (the {@code TestPbMigration} org): <b>A</b> whose group name IS the derived slug (the slug-named lookup
 * already resolves it; before 218 d {@code /health} reported {@code META_LINK_BROKEN} "missing"), and
 * <b>B</b> whose name is NOT the slug (before 218 d nothing resolved the group at all: {@code META_MISSING}).
 * For each: the meta note after import parses to the meta before plus exactly the {@code pb2BookObjectId} key
 * (the exporter drops empty values, so the comparison strips empties from the before map - the fixtures carry
 * none); {@code resolveBookGroupEither} returns the fixture group; {@code checkBook} reports neither
 * {@code META_LINK_BROKEN} nor {@code META_MISSING}. Then the book is deleted: the link must not turn the PB1
 * source into collateral of the PB2 delete, so B's meta note (live group, linked only through the key) survives
 * {@code deleteBookComplete} with exactly {@code pb2BookObjectId} removed ({@code deleteOrphanedMetaNotes}'
 * unlink branch) and the slug is free again; A's slug-named group is the tree step 4 removes as the book's own
 * (218 f, pre-existing, left) and is logged, not asserted. The importing user has no {@code generalChat}, so the
 * location pass (218 e) ends as a warning on the result, recorded here and not asserted. Live {@code am7db};
 * no LLM call succeeds, no GPU.
 */
public class TestPbMigrationMetaLink extends BaseTest {

	private static final String ORG_PATH = "/Development/PictureBook Custom Tests";
	private static final String TEST_USER = "pbCustomTestUser";
	private static final String PICTURE_BOOKS_PATH = "~/Data/PictureBooks";

	private OrganizationContext ctx;
	private BaseRecord user;
	private long orgId;

	@Before
	public void setUp() {
		OlioContextUtil.clearCache();
		OlioModelNames.use();
		ctx = getTestOrganization(ORG_PATH);
		assertNotNull(ctx);
		orgId = ctx.getOrganizationId();
		user = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), TEST_USER, orgId);
		assertNotNull("Failed to resolve " + TEST_USER, user);
	}

	@Test
	public void importLinksMetaToBookWhetherOrNotNameIsSlug() throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

		String nameA = "migrationlinka" + nonce;               // deriveSlug(nameA) == nameA
		String nameB = "Migration Link B " + nonce;             // deriveSlug(nameB) == "migration-link-b-<nonce>"
		assertEquals("fixture A's name is its slug", nameA, PbPipelineUtil.deriveSlug(nameA));
		assertNotEquals("fixture B's name is not its slug", nameB, PbPipelineUtil.deriveSlug(nameB));

		runFixture("A (name == slug)", nameA, dataPath);
		runFixture("B (name != slug)", nameB, dataPath);
	}

	private void runFixture(String label, String groupName, String dataPath) throws Exception {
		String fixturePath = PICTURE_BOOKS_PATH + "/" + groupName;
		List<BaseRecord> fixtures = new ArrayList<>();
		BaseRecord fixtureGroup = null;
		String bookOid = null;
		try {
			// 1. PB1 fixture: group, one scene note, meta note WITHOUT a pb2BookObjectId.
			fixtureGroup = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
				fixturePath, GroupEnumType.DATA.toString(), orgId);
			assertNotNull(label + ": fixture group", fixtureGroup);
			String fixtureOid = fixtureGroup.get(FieldNames.FIELD_OBJECT_ID);
			long fixtureId = ((Number) fixtureGroup.get(FieldNames.FIELD_ID)).longValue();
			String expectedSlug = PbPipelineUtil.deriveSlug(groupName);
			assertNotNull(expectedSlug);
			assertNull(label + ": fresh fixture must not already be migrated", PbBookUtil.findBookBySlug(user, expectedSlug, orgId));

			BaseRecord sceneNote = createNote(fixturePath, ".scene0",
				"{\"title\":\"Link scene\",\"summary\":\"A room.\",\"setting\":\"A quiet room.\",\"action\":\"Nothing moves.\",\"mood\":\"Still\",\"blurb\":\"Scene 0.\"}");
			fixtures.add(sceneNote);
			String metaBefore = "{\"schema\":\"olio.pictureBookMeta\",\"workName\":\"Meta Link " + groupName + "\","
				+ "\"scenes\":[{\"schema\":\"olio.pictureBookScene\",\"objectId\":\"" + sceneNote.get(FieldNames.FIELD_OBJECT_ID) + "\"}]}";
			BaseRecord metaNote = createNote(fixturePath, PbMigrationUtil.V1_META_NOTE_NAME, metaBefore);
			fixtures.add(metaNote);
			Map<String, Object> before = JSONUtil.getMap(metaBefore.getBytes(), String.class, Object.class);
			assertNotNull(before);
			assertFalse(label + ": the fixture meta carries no link", before.containsKey("pb2BookObjectId"));

			// 2. Import.
			ImportResult result = PbMigrationUtil.importV1Book(user, dataPath, fixtureOid);
			assertNotNull(label + ": import result", result);
			bookOid = result.bookObjectId;
			assertNotNull(bookOid);
			assertEquals(label + ": slug", expectedSlug, result.slug);
			assertEquals(label + ": scenesFailed", 0, result.scenesFailed);
			assertEquals(label + ": scenesImported", 1, result.scenesImported);
			logger.info("[i5-218d] {} imported book {} slug {} warnings={}", label, bookOid, result.slug, result.warnings);
			for(String w : result.warnings) {
				assertFalse(label + ": the link write must not have warned: " + w, w.contains("pb2BookObjectId"));
			}

			// 3. The meta note: before + exactly the link, nothing else changed (uncached re-read).
			Query mq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, fixtureId);
			mq.field(FieldNames.FIELD_NAME, PbMigrationUtil.V1_META_NOTE_NAME);
			mq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			mq.planMost(true);
			mq.setCache(false);
			BaseRecord metaAfterRec = IOSystem.getActiveContext().getAccessPoint().find(user, mq);
			assertNotNull(label + ": meta note still exists", metaAfterRec);
			assertEquals(label + ": the same note row", (String) metaNote.get(FieldNames.FIELD_OBJECT_ID), (String) metaAfterRec.get(FieldNames.FIELD_OBJECT_ID));
			String metaAfterText = metaAfterRec.get("text");
			assertNotNull(metaAfterText);
			Map<String, Object> after = JSONUtil.getMap(metaAfterText.getBytes(), String.class, Object.class);
			assertNotNull(label + ": meta after import parses", after);
			assertEquals(label + ": pb2BookObjectId is the imported book", bookOid, after.get("pb2BookObjectId"));
			Map<String, Object> afterMinusLink = new LinkedHashMap<>(after);
			afterMinusLink.remove("pb2BookObjectId");
			assertEquals(label + ": every other key survives unchanged", stripEmpty(before), stripEmpty(afterMinusLink));

			// 4. The link resolves the PB1 group for the book - name-independent.
			BaseRecord resolved = PictureBookUtil.resolveBookGroupEither(user, bookOid, orgId);
			assertNotNull(label + ": resolveBookGroupEither finds the PB1 group through the link", resolved);
			assertEquals(label + ": it is the fixture group", fixtureId, ((Number) resolved.get(FieldNames.FIELD_ID)).longValue());

			// 5. /health: no META_LINK_BROKEN, no META_MISSING.
			Map<String, Object> report = PbHealthUtil.checkBook(user, bookOid);
			List<String> codes = findingCodes(report);
			logger.info("[i5-218d] {} /health codes: {}", label, codes);
			assertFalse(label + ": no META_LINK_BROKEN after import: " + codes, codes.contains(PbHealthUtil.META_LINK_BROKEN));
			assertFalse(label + ": no META_MISSING after import: " + codes, codes.contains(PbHealthUtil.META_MISSING));

			// 6. Delete the imported book. The PB1 source is not something the book created, so it must outlive
			// the book: B's meta note (a live group the book's slug does not name, linked only through
			// pb2BookObjectId) survives with exactly that key removed - deleteOrphanedMetaNotes' unlink branch -
			// and the PB1 book is importable again. A's group IS the slug-named ~/Data/PictureBooks/<slug> tree
			// that deleteBookComplete step 4 removes as the book's own (218 f, pre-existing, left): logged only.
			DeleteResult del = PbDeleteUtil.deleteBookComplete(user, bookOid);
			assertNotNull(label + ": delete result", del);
			assertTrue(label + ": deleteBookComplete: " + del.reason, del.deleted);
			String deletedOid = bookOid;
			bookOid = null;
			assertFalse(label + ": the slug is free again after the delete", PbBookUtil.isSlugTaken(expectedSlug, orgId));
			Query dq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, fixtureId);
			dq.field(FieldNames.FIELD_NAME, PbMigrationUtil.V1_META_NOTE_NAME);
			dq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			dq.planMost(true);
			dq.setCache(false);
			BaseRecord metaAfterDelete = IOSystem.getActiveContext().getAccessPoint().find(user, dq);
			Query gq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, fixtureId);
			gq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			gq.setCache(false);
			BaseRecord groupAfterDelete = IOSystem.getActiveContext().getAccessPoint().find(user, gq);
			String afterDelete;
			if(groupName.equals(expectedSlug)) {
				afterDelete = (groupAfterDelete == null) ? "tree-deleted(218f)" : (metaAfterDelete == null ? "meta-deleted" : "survived");
				logger.info("[i5-218d] {} after delete (slug-named PB1 group, 218 f - not asserted): group={} meta={}", label,
					groupAfterDelete != null ? "present" : "gone", metaAfterDelete != null ? "present" : "gone");
			}
			else {
				assertNotNull(label + ": the PB1 group survives the book's delete", groupAfterDelete);
				assertNotNull(label + ": the PB1 meta note survives the book's delete", metaAfterDelete);
				assertEquals(label + ": the same note row after delete", (String) metaNote.get(FieldNames.FIELD_OBJECT_ID), (String) metaAfterDelete.get(FieldNames.FIELD_OBJECT_ID));
				String unlinkedText = metaAfterDelete.get("text");
				assertNotNull(unlinkedText);
				Map<String, Object> unlinked = JSONUtil.getMap(unlinkedText.getBytes(), String.class, Object.class);
				assertNotNull(label + ": meta after delete parses", unlinked);
				assertFalse(label + ": pb2BookObjectId is removed by the delete, not left pointing at " + deletedOid, unlinked.containsKey("pb2BookObjectId"));
				assertEquals(label + ": every other key survives the unlink", stripEmpty(before), stripEmpty(unlinked));
				afterDelete = "unlinked";
			}

			System.out.println("=== I-5 218d PROOF " + label + ": book=" + deletedOid + " slug=" + expectedSlug + " group=" + groupName
				+ " meta.pb2BookObjectId=" + after.get("pb2BookObjectId") + " resolved=" + resolved.get(FieldNames.FIELD_ID) + " health=" + codes
				+ " deleted=" + del.deleted + " afterDelete=" + afterDelete);
		}
		finally {
			if(bookOid != null) {
				try {
					DeleteResult del = PbDeleteUtil.deleteBookComplete(user, bookOid);
					logger.info("[i5-218d] cleanup book {}: deleted={} reason={}", bookOid, del.deleted, del.reason);
				}
				catch(Exception e) {
					logger.error("[i5-218d] cleanup book " + bookOid + ": " + e.getMessage());
				}
			}
			for(int i = fixtures.size() - 1; i >= 0; i--) {
				deleteQuietly(fixtures.get(i));
			}
			deleteQuietly(fixtureGroup);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private BaseRecord createNote(String groupPath, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set("text", text);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("fixture note " + name, created);
		return created;
	}

	/** Deep copy with null / empty-string / empty-collection values removed - what {@code JSONUtil.exportObject} (NON_EMPTY) drops. */
	@SuppressWarnings("unchecked")
	private static Object stripEmpty(Object o) {
		if(o instanceof Map) {
			Map<String, Object> out = new LinkedHashMap<>();
			for(Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
				Object v = stripEmpty(e.getValue());
				if(v == null || (v instanceof String && ((String) v).isEmpty()) || (v instanceof Map && ((Map<?, ?>) v).isEmpty()) || (v instanceof List && ((List<?>) v).isEmpty())) {
					continue;
				}
				out.put(e.getKey(), v);
			}
			return out;
		}
		if(o instanceof List) {
			List<Object> out = new ArrayList<>();
			for(Object v : (List<Object>) o) {
				out.add(stripEmpty(v));
			}
			return out;
		}
		return o;
	}

	@SuppressWarnings("unchecked")
	private static List<String> findingCodes(Map<String, Object> report) {
		List<String> codes = new ArrayList<>();
		Object f = report.get("findings");
		if(f instanceof List) {
			for(Object o : (List<Object>) f) {
				if(o instanceof Map) {
					codes.add(String.valueOf(((Map<String, Object>) o).get("code")));
				}
			}
		}
		return codes;
	}

	private void deleteQuietly(BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			Query q = QueryUtil.createQuery(rec.getSchema(), FieldNames.FIELD_OBJECT_ID, rec.get(FieldNames.FIELD_OBJECT_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setCache(false);
			BaseRecord fresh = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(fresh == null) {
				return; // already gone (deleteBookComplete removes the slug-named PB1 group and its notes)
			}
			if(!IOSystem.getActiveContext().getAccessPoint().delete(user, fresh)) {
				logger.warn("[i5-218d] cleanup: {} {} was not deleted", rec.getSchema(), rec.get(FieldNames.FIELD_NAME));
			}
		}
		catch(Exception e) {
			logger.error("[i5-218d] cleanup " + rec.getSchema() + " " + rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}
}
