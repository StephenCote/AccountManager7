package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Test;

/// §6.C 286 f (closed by §24 I-7, §6.C 303): {@code ChapBookUtil.resolveSceneBook}'s BY-ID branch - taken when a
/// scene's {@code book} FK carries its numeric {@code id} but no {@code objectId} - authorized the book on the
/// full {@code bookRequest()} projection, the exposure §6.A removed from {@code readBook} / {@code findBookBySlug}
/// / {@code requireBookUpdate}: {@code PolicyUtil.getSchemaRules} adds one foreign-access rule per populated
/// foreign field, so a series Writer who may read the chapter row was refused for lacking Read over the series
/// row, the shared world and the author's manuscript. The branch now goes through the new
/// {@code PbBookUtil.readBookById} - the same authorizing shape ({@code bookAuthorizingRequest()}, then the raw
/// linkage step). Proven here, on a real series chapter over the real manuscript, the way
/// {@code TestPbBookReadProjection} proved §6.A:
///
/// 1. SHAPE: what {@code resolveSceneBook} returns for a crafted scene whose {@code book} FK carries ONLY
///    {@code id} is field-for-field the pre-change by-id read ({@code AccessPoint.find} with
///    {@code bookRequest()}, as the owner, who can read both). Compared programmatically.
/// 2. AUTHORIZATION: a non-owner series Writer resolves the book through the by-id branch (ONE bracketed PBAC
///    trace), gets the same shape, while the SAME Writer's pre-change by-id read returns nothing - the
///    narrowing is load-bearing. A stranger gets the branch's 404, never a record.
/// 3. PUBLIC PATH: {@code ChapBookUtil.mergeSceneUp} on a real scene of the chapter - the Writer reaches the
///    CHAPBOOK guard (403: a series chapter is not a CHAPBOOK), the stranger gets 404 - so the only public
///    callers of {@code resolveSceneBook} still deny in the order they did.
///
/// Real DB, no LLM, no SD. The org admin only applies the Writer enrolment, exactly as production does.
public class TestPbResolveSceneBookById extends BaseTest {

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

	/// The PRE-CHANGE by-id read: exactly what resolveSceneBook's else-branch did before §6.C 303.
	private BaseRecord legacyReadBookById(BaseRecord user, long id, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.bookRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// A scene record as a default projection materialises it for a caller that only has the stored FK column:
	/// identity plus a {@code book} FK carrying ONLY its numeric {@code id}. This is the input the by-id branch
	/// exists for, and the only way to drive that branch deterministically from outside the DB reader.
	private static BaseRecord craftedScene(String sceneObjectId, long bookId) throws Exception {
		BaseRecord fk = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] { FieldNames.FIELD_ID });
		fk.set(FieldNames.FIELD_ID, Long.valueOf(bookId));
		BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[] { FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_BOOK });
		scene.set(FieldNames.FIELD_OBJECT_ID, sceneObjectId);
		scene.set(OlioFieldNames.FIELD_PB_BOOK, fk);
		return scene;
	}

	private static Set<String> fieldNames(BaseRecord rec) {
		return rec.getFields().stream().map(FieldType::getName).collect(Collectors.toCollection(HashSet::new));
	}

	/// Field-by-field equality of two book projections, one level deep into every nested MODEL field
	/// (the TestPbBookReadProjection comparator).
	private static void assertSameShape(String label, BaseRecord expected, BaseRecord actual) {
		Set<String> expNames = fieldNames(expected);
		Set<String> actNames = fieldNames(actual);
		assertEquals(label + ": field set must be identical to bookRequest()'s", expNames, actNames);
		for (FieldType f : expected.getFields()) {
			String name = f.getName();
			Object ev = expected.get(name);
			Object av = actual.get(name);
			if (f.getValueType() == FieldEnumType.MODEL) {
				if (ev == null) {
					assertNull(label + "." + name + " must be null as in bookRequest()", av);
					continue;
				}
				assertNotNull(label + "." + name + " must be populated as in bookRequest()", av);
				BaseRecord er = (BaseRecord) ev;
				BaseRecord ar = (BaseRecord) av;
				assertEquals(label + "." + name + ".schema", er.getSchema(), ar.getSchema());
				assertEquals(label + "." + name + " nested field set", fieldNames(er), fieldNames(ar));
				for (FieldType nf : er.getFields()) {
					if (nf.getValueType() == FieldEnumType.MODEL || nf.getValueType() == FieldEnumType.LIST) {
						continue;
					}
					assertEquals(label + "." + name + "." + nf.getName(), (Object) er.get(nf.getName()), (Object) ar.get(nf.getName()));
				}
			}
			else if (f.getValueType() == FieldEnumType.LIST) {
				List<?> el = (List<?>) ev;
				List<?> al = (List<?>) av;
				assertEquals(label + "." + name + " list size", (el == null ? 0 : el.size()), (al == null ? 0 : al.size()));
			}
			else {
				assertEquals(label + "." + name, ev, av);
			}
		}
	}

	private static int statusOf(Runnable call) {
		try {
			call.run();
		}
		catch (PictureBookException e) {
			return e.getStatus();
		}
		return -1;
	}

	@Test
	public void testResolveSceneBookByIdShapeAndNarrowedAuthorization() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbResolveByIdOwner");
		assertNotNull("owner user", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		BaseRecord sourceData = getCreateFileData(owner, "~/PbResolveByIdData", docx.getAbsolutePath());
		assertNotNull("sourceData must be created", sourceData);
		String sourceDataOid = sourceData.get(FieldNames.FIELD_OBJECT_ID);

		String tag = shortId();
		String seriesSlug = "rbs" + tag;
		BaseRecord series = PbSeriesUtil.getCreateSeries(owner, dataPath, seriesSlug, "Resolve ById Series " + tag);
		assertNotNull("series must be created", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);

		String slug = "rbc" + tag;
		Map<String, Object> range = new LinkedHashMap<>();
		range.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(300));
		range.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(1700));
		range.put(OlioFieldNames.FIELD_PB_TITLE, "Resolve ById Chapter");
		Map<String, Object> r = PbServiceFacade.createChapter(owner, dataPath, seriesOid, null, slug,
			"Resolve ById Chapter Title", Integer.valueOf(1), sourceDataOid, range, null, null);
		assertNotNull("createChapter", r);
		String bookOid = (String) r.get("bookObjectId");
		assertNotNull("chapter bookObjectId", bookOid);

		BaseRecord book = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull("owner must read the chapter", book);
		long bookId = ((Number) book.get(FieldNames.FIELD_ID)).longValue();
		assertTrue("chapter id", bookId > 0L);
		// The chapter carries every linkage FK - so the legacy by-id read WOULD evaluate the four foreign rules.
		for (String f : PbBookUtil.BOOK_LINKAGE_FIELDS) {
			assertNotNull("the chapter must carry its " + f + " linkage for this proof to mean anything", book.get(f));
		}

		// A real scene in the chapter's own Book group, created as the owner through AccessPoint - the public
		// path (mergeSceneUp) below reads it as each user before it resolves the book.
		BaseRecord scene = PbBookUtil.createScene(owner, book, 0, "Scene 0", PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene must be created", scene);
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("scene objectId", sceneOid);

		BaseRecord crafted = craftedScene(sceneOid, bookId);
		BaseRecord craftedFk = crafted.get(OlioFieldNames.FIELD_PB_BOOK);
		assertNull("the crafted FK carries no objectId - this is what selects the by-id branch", craftedFk.get(FieldNames.FIELD_OBJECT_ID));

		// ── 1. SHAPE: resolveSceneBook (by-id branch) == the pre-change by-id read, field by field (as the owner) ──
		BaseRecord legacy = legacyReadBookById(owner, bookId, orgId);
		assertNotNull("owner must read the chapter under the legacy by-id full projection", legacy);
		BaseRecord resolved = ChapBookUtil.resolveSceneBook(owner, crafted, orgId);
		assertNotNull("owner must resolve the book through the by-id branch", resolved);
		assertSameShape("resolveSceneBook(owner, by-id)", legacy, resolved);
		BaseRecord viaById = PbBookUtil.readBookById(owner, bookId, orgId);
		assertNotNull("owner must read the chapter via readBookById", viaById);
		assertSameShape("readBookById(owner)", legacy, viaById);
		// And the objectId branch (the stored scene's FK as readScene projects it) agrees.
		BaseRecord resolvedReal = ChapBookUtil.resolveSceneBook(owner, PbBookUtil.readScene(owner, sceneOid, orgId), orgId);
		assertNotNull("owner must resolve the book from the stored scene", resolvedReal);
		assertSameShape("resolveSceneBook(owner, stored scene)", legacy, resolvedReal);
		assertNull("readBookById refuses a non-positive id", PbBookUtil.readBookById(owner, 0L, orgId));
		assertNull("readBookById refuses a null user", PbBookUtil.readBookById(null, bookId, orgId));

		// ── 2. AUTHORIZATION: a non-owner series Writer ──
		BaseRecord writer = getCreateUser("pbResolveByIdWriter");
		assertNotNull("writer user", writer);
		assertFalse("writer must be a different user from the owner",
			((Long) owner.get(FieldNames.FIELD_ID)).equals((Long) writer.get(FieldNames.FIELD_ID)));
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		BaseRecord writerRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.seriesWriterRolePath(seriesSlug), RoleEnumType.USER.toString(), orgId);
		assertNotNull("series Writer role", writerRole);
		assertTrue("enrol writer", ioContext.getMemberUtil().member(orgContext.getAdminUser(), writerRole, writer, null, true));

		// TRACE - the by-id branch as the Writer (ONE call bracketed): the authorizing projection, PERMIT.
		System.out.println("=== TRACE: resolveSceneBook(writer, by-id FK) - authorizing projection ===");
		ioContext.getPolicyUtil().setTrace(true);
		BaseRecord asWriter;
		try {
			asWriter = ChapBookUtil.resolveSceneBook(writer, crafted, orgId);
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		System.out.println("=== TRACE end ===");
		assertNotNull("a non-owner series Writer MUST resolve the chapter through the by-id branch", asWriter);
		assertSameShape("resolveSceneBook(writer, by-id)", legacy, asWriter);
		// The pre-change branch: the SAME Writer, the full projection - nothing. This is why 286 f was open.
		assertNull("the legacy by-id full-projection read returns nothing for the Writer - the narrowing is load-bearing",
			legacyReadBookById(writer, bookId, orgId));

		// ── A stranger gets the branch's 404, never a record ──
		BaseRecord stranger = getCreateUser("pbResolveByIdStranger");
		assertNotNull("stranger user", stranger);
		assertNull("a stranger must NOT read the chapter by id", PbBookUtil.readBookById(stranger, bookId, orgId));
		assertNull("nor under the legacy by-id projection", legacyReadBookById(stranger, bookId, orgId));
		assertEquals("a stranger's resolveSceneBook is the branch's 404", 404,
			statusOf(() -> ChapBookUtil.resolveSceneBook(stranger, crafted, orgId)));

		// ── 3. PUBLIC PATH: mergeSceneUp resolves the book from the stored scene as each user ──
		// The Writer reads the scene (series Writer CRUD on the chapter's Book group), resolves the book, and is
		// stopped by the CHAPBOOK guard: a series chapter is not a CHAPBOOK. Before 286 f's close the by-id
		// branch would have 404ed a Writer here whenever the FK came without an objectId.
		int writerStatus = statusOf(() -> ChapBookUtil.mergeSceneUp(writer, sceneOid));
		assertEquals("Writer: book resolved, CHAPBOOK guard refuses (403)", 403, writerStatus);
		int strangerStatus = statusOf(() -> ChapBookUtil.mergeSceneUp(stranger, sceneOid));
		assertEquals("stranger: 404 (scene or book not readable), never a leak", 404, strangerStatus);
		int ownerStatus = statusOf(() -> ChapBookUtil.mergeSceneUp(owner, sceneOid));
		assertEquals("owner: same CHAPBOOK guard (403) - the guard, not authorization, is what stops a chapter", 403, ownerStatus);

		System.out.println("=== §6.C 286 f PROOF: writer by-id resolve=" + (asWriter != null) + " legacyById(writer)=null"
			+ " stranger=404 mergeSceneUp writer=403 stranger=404 (book=" + bookOid + " id=" + bookId + " scene=" + sceneOid + ")");
	}
}
