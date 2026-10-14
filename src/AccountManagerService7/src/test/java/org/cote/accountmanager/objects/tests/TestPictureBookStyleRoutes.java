package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.picturebook.PbStyleUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.ModelService;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// P2-3 (PictureBook V3 §4/§4.1, §6.C 157 n/p/q, 158 q): the three style routes on PictureBookService
/// (GET /{book}/style, PUT /{book}/style, POST /{book}/style/lock) exercised through the real service
/// methods -> PbServiceFacade -> PbStyleResolver/PbStyleUtil -> real AccessPoint/PBAC -> the real (H2)
/// database, with HttpServletRequestMock + UserPrincipal exactly as TestPbRenderPathRefusal and
/// TestModelServicePatch do. Nothing is mocked and no actor is ever the admin or olio principal.
///
/// FIXTURE. A bare olio.pb.book row the owning test user created through AccessPoint in a group it owns
/// (TestPbRenderPathRefusal.createBareBook) is the exact minimum: the style routes are not gated on the
/// render path and need no workflow, so requireBook -> requireBookUpdate -> body validation -> lock ->
/// write are all reachable without an Olio world. Every case creates its own book and deletes the book
/// and every style row it created in finally, as the test user.
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY,
/// so a null `own`, an empty `chain`, an empty `taskModels`/`sources` and a null `lockedBy`/`truncatedAt`
/// are ABSENT from the response rather than present as null/[]/{}. Assertions below accept absent-or-null
/// for those keys and say so in their messages.
public class TestPictureBookStyleRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbstyleowner";
	private static final String STRANGER_NAME = "pbstylestranger";
	private static final String READER_NAME = "pbstylereader";
	private static final String WRITER_NAME = "pbstylewriter";
	private static final String GROUP_PATH = "~/PbStyleRoutes";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";

	private final PictureBookService service = new PictureBookService();
	private final ModelService modelService = new ModelService();

	private BaseRecord owner;
	private BaseRecord stranger;
	private BaseRecord reader;
	private BaseRecord writer;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		owner = getCreateUser(OWNER_NAME);
		stranger = getCreateUser(STRANGER_NAME);
		reader = getCreateUser(READER_NAME);
		writer = getCreateUser(WRITER_NAME);
		assertNotNull("owner user is null", owner);
		assertNotNull("stranger user is null", stranger);
		assertNotNull("reader user is null", reader);
		assertNotNull("writer user is null", writer);
		assertNotEquals("owner and stranger must be different users", oid(owner), oid(stranger));
	}

	// ─────────────────────────────── request / response helpers ───────────────────────────────

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String) u.get(FieldNames.FIELD_NAME), organizationPath));
	}

	private Response get(BaseRecord u, String bookOid) {
		return service.getStyle(bookOid, requestAs(u));
	}

	private Response put(BaseRecord u, String bookOid, String json) {
		return service.putStyle(bookOid, json, requestAs(u));
	}

	private Response lock(BaseRecord u, String bookOid, String json) {
		return service.lockStyle(bookOid, json, requestAs(u));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(Response r) {
		assertNotNull("Response is null", r);
		Object entity = r.getEntity();
		assertNotNull("Response entity is null", entity);
		Map<String, Object> m = JSONUtil.importObject(entity.toString(), LinkedHashMap.class);
		assertNotNull("Response body did not parse as JSON: " + entity, m);
		return m;
	}

	/// The error text of a refusal: the facade's {"error":"msg"} or the route's {"error":true,"message":"msg"}.
	private String errorText(Response r) {
		Map<String, Object> m = body(r);
		Object msg = m.get("message");
		if(msg == null) {
			msg = m.get("error");
		}
		assertNotNull("Refusal body must carry 'error' or 'message': " + r.getEntity(), msg);
		return String.valueOf(msg);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Map<String, Object> m, String key) {
		Object o = (m != null ? m.get(key) : null);
		if(o == null) {
			return Collections.emptyMap();
		}
		assertTrue("'" + key + "' must be a JSON object, got " + o.getClass().getSimpleName(), o instanceof Map);
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private List<Object> list(Map<String, Object> m, String key) {
		Object o = (m != null ? m.get(key) : null);
		if(o == null) {
			return Collections.emptyList();
		}
		assertTrue("'" + key + "' must be a JSON array, got " + o.getClass().getSimpleName(), o instanceof List);
		return (List<Object>) o;
	}

	private Map<String, Object> own(Map<String, Object> dto) {
		return map(dto, "own");
	}

	private Map<String, Object> effectiveFields(Map<String, Object> dto) {
		return map(map(dto, "effective"), "fields");
	}

	private Map<String, Object> sources(Map<String, Object> dto) {
		return map(map(dto, "effective"), "sources");
	}

	private Map<String, Object> effectiveTaskModels(Map<String, Object> dto) {
		return map(map(dto, "effective"), "taskModels");
	}

	private String ownOid(Map<String, Object> dto) {
		Object o = own(dto).get("objectId");
		assertNotNull("own.objectId must be set: " + dto, o);
		return String.valueOf(o);
	}

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	private Map<String, Object> ok(Response r, String what) {
		assertEquals(what + " must be 200, body: " + r.getEntity(), 200, r.getStatus());
		return body(r);
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A bare book row owned by the owner user, in a group the owner owns. Created through AccessPoint as
	/// that user - no admin, no RecordUtil bypass - and deleted the same way (TestPbRenderPathRefusal).
	private BaseRecord createBareBook() throws Exception {
		String slug = "sty-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, GROUP_PATH, "DATA", orgId);
		assertNotNull("Fixture group is null", dir);
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(owner, book, slug, GROUP_PATH, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, book);
		assertNotNull("Failed to create the bare book fixture", created);
		assertNotNull("Created book carries no objectId", oid(created));
		return created;
	}

	private BaseRecord bookGroup(BaseRecord book) {
		long gid = book.get(FieldNames.FIELD_GROUP_ID);
		BaseRecord dir = ioContext.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, gid);
		assertNotNull("book group must be readable by its owner", dir);
		return dir;
	}

	/// Grant `grantee` the named system permissions on the book's group, DATA + GROUP types, AS THE OWNER
	/// (the group's owner) - the same perm set PbBookUtil.grantSeriesRolesOnChapterGroups applies to the
	/// series role pair, but granted by the owner through MemberUtil as TestModelServicePatch.grantRead
	/// does, never as the org admin. member(..., true) returns false when the entry already exists (re-runs
	/// share users), so the grant is proven by the grantee's subsequent call, not by the return value.
	private void grant(BaseRecord book, BaseRecord grantee, String... permNames) {
		BaseRecord dir = bookGroup(book);
		for(String type : new String[] { PermissionEnumType.GROUP.toString(), PermissionEnumType.DATA.toString() }) {
			for(String p : permNames) {
				BaseRecord perm = AccessSchema.getSystemPermission(p, type, orgId);
				assertNotNull(p + "/" + type + " permission is null", perm);
				ioContext.getMemberUtil().member(owner, dir, grantee, perm, true);
			}
		}
		ServiceUtil.clearCache();
	}

	private void revoke(BaseRecord book, BaseRecord grantee, String... permNames) {
		BaseRecord dir = bookGroup(book);
		for(String type : new String[] { PermissionEnumType.GROUP.toString(), PermissionEnumType.DATA.toString() }) {
			for(String p : permNames) {
				BaseRecord perm = AccessSchema.getSystemPermission(p, type, orgId);
				if(perm != null) {
					ioContext.getMemberUtil().member(owner, dir, grantee, perm, false);
				}
			}
		}
		ServiceUtil.clearCache();
	}

	private BaseRecord readStyleAs(BaseRecord u, String styleOid) {
		return PbStyleUtil.readStyle(u, styleOid, orgId);
	}

	/// Delete the book, then every style row named - as the owner, falling back to the writer for a row the
	/// owner cannot delete. Failures are logged, not asserted, so a teardown problem never masks the case.
	private void cleanup(BaseRecord book, List<String> styleOids) {
		if(book != null) {
			try {
				if(!IOSystem.getActiveContext().getAccessPoint().delete(owner, book)) {
					logger.error("cleanup: failed to delete book " + oid(book));
				}
			}
			catch(Exception e) {
				logger.error("cleanup: book delete threw: " + e.getMessage());
			}
		}
		for(String so : styleOids) {
			if(so == null) {
				continue;
			}
			boolean deleted = false;
			for(BaseRecord actor : new BaseRecord[] { owner, writer }) {
				try {
					BaseRecord s = readStyleAs(actor, so);
					if(s != null && IOSystem.getActiveContext().getAccessPoint().delete(actor, s)) {
						deleted = true;
						break;
					}
				}
				catch(Exception e) {
					logger.warn("cleanup: style " + so + " delete as " + actor.get(FieldNames.FIELD_NAME) + " threw: " + e.getMessage());
				}
			}
			if(!deleted) {
				logger.error("cleanup: style row " + so + " was NOT deleted");
			}
		}
	}

	// ─────────────────────────────── owner: read ───────────────────────────────

	/// GET of a book objectId that does not exist is 404 "Book not found" (requireBook).
	@Test
	public void TestGetUnknownBookIs404() {
		Response r = get(owner, NIL_UUID);
		assertEquals("unknown book must be 404, body: " + r.getEntity(), 404, r.getStatus());
		assertEquals("Book not found", errorText(r));
	}

	/// GET before any PUT: 200, no own style, an empty chain, complete, and the §29 defaults in effect
	/// (STANDARD quality, SQUARE page). own/chain/lockedBy are absent on the wire (NON_EMPTY), never present.
	@Test
	public void TestGetBeforeAnyPutIsEmptyAndComplete() throws Exception {
		BaseRecord book = createBareBook();
		try {
			Map<String, Object> dto = ok(get(owner, oid(book)), "GET on a style-less book");
			assertEquals(oid(book), dto.get("bookObjectId"));
			assertNull("own must be null/absent before any PUT: " + dto, dto.get("own"));
			assertTrue("chain must be empty/absent before any PUT: " + dto, list(dto, "chain").isEmpty());
			assertEquals("complete must be true for an empty chain", Boolean.TRUE, dto.get("complete"));
			assertEquals("cycleDetected must be false", Boolean.FALSE, dto.get("cycleDetected"));
			assertNull("lockedBy must be null/absent", dto.get("lockedBy"));
			assertNull("truncatedAt must be null/absent", dto.get("truncatedAt"));
			Map<String, Object> eff = map(dto, "effective");
			assertEquals("STANDARD", eff.get("qualityPreset"));
			assertEquals("SQUARE", eff.get("pageFormat"));
			assertTrue("no task pins in effect: " + eff, effectiveTaskModels(dto).isEmpty());
			assertTrue("no sources for a style-less book: " + eff, sources(dto).isEmpty());
		}
		finally {
			cleanup(book, Collections.emptyList());
		}
	}

	// ─────────────────────────────── owner: write ───────────────────────────────

	/// PUT creates the book's own BOOK-scope style named <slug>-style in the book's group, as the caller;
	/// the effective values and their sources point at it; GET agrees; a second PUT of one field leaves the
	/// other in place (sparse PATCH semantics on the existing style).
	@Test
	public void TestPutCreatesOwnStyleAndGetAgrees() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		try {
			String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
			Map<String, Object> dto = ok(put(owner, oid(book), "{\"palette\":\"warm\",\"qualityPreset\":\"draft\"}"), "first PUT");
			String so = ownOid(dto);
			styles.add(so);
			Map<String, Object> own = own(dto);
			assertEquals("own.scope", "BOOK", own.get("scope"));
			assertEquals("own.name is <slug>-style", slug + "-style", own.get("name"));
			assertEquals("own.locked", Boolean.FALSE, own.get("locked"));
			assertEquals("own.fields.palette (stored)", "warm", map(own, "fields").get("palette"));
			assertEquals("own.fields.qualityPreset (stored, canonical enum name)", "DRAFT", map(own, "fields").get("qualityPreset"));
			assertEquals("effective.fields.palette", "warm", effectiveFields(dto).get("palette"));
			assertEquals("effective.sources.palette is the own style", so, sources(dto).get("palette"));
			assertEquals("effective.sources.qualityPreset is the own style", so, sources(dto).get("qualityPreset"));
			assertEquals("effective.qualityPreset", "DRAFT", map(dto, "effective").get("qualityPreset"));
			assertEquals("effective.pageFormat stays the default", "SQUARE", map(dto, "effective").get("pageFormat"));
			List<Object> chain = list(dto, "chain");
			assertEquals("chain has exactly the own style", 1, chain.size());
			assertEquals("chain[0] is the own style", so, chainHop(chain, 0).get("objectId"));
			assertEquals("BOOK", chainHop(chain, 0).get("scope"));
			assertEquals(slug + "-style", chainHop(chain, 0).get("name"));
			assertEquals(Boolean.TRUE, dto.get("complete"));

			/// The persisted row: BOOK scope, in the book's group, owned by the caller.
			BaseRecord row = readStyleAs(owner, so);
			assertNotNull("own style row must be readable by the owner", row);
			assertEquals("style row groupId is the book's group", (long) book.get(FieldNames.FIELD_GROUP_ID), (long) row.get(FieldNames.FIELD_GROUP_ID));
			assertEquals("style row ownerId is the caller", (long) owner.get(FieldNames.FIELD_ID), (long) row.get(FieldNames.FIELD_OWNER_ID));
			assertEquals("warm", row.get(OlioFieldNames.FIELD_PB_PALETTE));

			/// GET shows the same.
			Map<String, Object> got = ok(get(owner, oid(book)), "GET after PUT");
			assertEquals(so, ownOid(got));
			assertEquals("warm", effectiveFields(got).get("palette"));
			assertEquals(so, sources(got).get("palette"));
			assertEquals("DRAFT", map(got, "effective").get("qualityPreset"));

			/// Update one field; the other is untouched and the own style's identity is unchanged.
			Map<String, Object> upd = ok(put(owner, oid(book), "{\"description\":\"ink wash\"}"), "second PUT");
			assertEquals("the own style is updated, not replaced", so, ownOid(upd));
			assertEquals("ink wash", effectiveFields(upd).get("description"));
			assertEquals("palette survives a PUT of another field", "warm", effectiveFields(upd).get("palette"));
			assertEquals("DRAFT", map(upd, "effective").get("qualityPreset"));
			assertEquals("ink wash", map(own(upd), "fields").get("description"));
			assertEquals("warm", map(own(upd), "fields").get("palette"));
			assertEquals(1, list(upd, "chain").size());
		}
		finally {
			cleanup(book, styles);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> chainHop(List<Object> chain, int i) {
		Object o = chain.get(i);
		assertTrue("chain hop must be an object", o instanceof Map);
		return (Map<String, Object>) o;
	}

	/// Every rejected body is a 400/404 that creates NO style: unknown key (naming the accepted list), empty
	/// object, blank body, malformed JSON, negative customWidth, unknown taskModels key (naming the accepted
	/// task keys), non-string palette, non-boolean photoreal, unknown enum, and an sdConfig that does not exist.
	@Test
	public void TestRejectedBodiesCreateNothing() throws Exception {
		BaseRecord book = createBareBook();
		try {
			String b = oid(book);

			Response r = put(owner, b, "{\"bogus\":\"x\"}");
			assertEquals("unknown key must be 400: " + r.getEntity(), 400, r.getStatus());
			String msg = errorText(r);
			assertTrue("must name the unknown key: " + msg, msg.contains("Unknown style field 'bogus'"));
			for(String f : PbServiceFacade.STYLE_PUT_FIELDS) {
				assertTrue("400 must list accepted field '" + f + "': " + msg, msg.contains(f));
			}
			assertTrue("accepted list includes parent", PbServiceFacade.STYLE_PUT_FIELDS.contains("parent"));
			assertFalse("name is never a PUT field", PbServiceFacade.STYLE_PUT_FIELDS.contains("name"));
			assertFalse("locked is never a PUT field", PbServiceFacade.STYLE_PUT_FIELDS.contains("locked"));
			assertFalse("scope is never a PUT field", PbServiceFacade.STYLE_PUT_FIELDS.contains("scope"));

			r = put(owner, b, "{}");
			assertEquals("empty object must be 400: " + r.getEntity(), 400, r.getStatus());
			assertTrue(errorText(r).contains("accepted"));

			r = put(owner, b, "");
			assertEquals("blank body must be 400: " + r.getEntity(), 400, r.getStatus());
			assertTrue(errorText(r).contains("at least one style field"));

			r = put(owner, b, "{not json");
			assertEquals("malformed JSON must be 400: " + r.getEntity(), 400, r.getStatus());
			assertEquals("Malformed request body", errorText(r));

			r = put(owner, b, "{\"customWidth\": -1}");
			assertEquals("negative customWidth must be 400: " + r.getEntity(), 400, r.getStatus());
			assertTrue(errorText(r), errorText(r).contains("customWidth must be a non-negative integer"));

			r = put(owner, b, "{\"customHeight\": 1.5}");
			assertEquals("fractional customHeight must be 400: " + r.getEntity(), 400, r.getStatus());

			r = put(owner, b, "{\"taskModels\": {\"notATask\": \"x.safetensors\"}}");
			assertEquals("unknown task key must be 400: " + r.getEntity(), 400, r.getStatus());
			msg = errorText(r);
			assertTrue("must name the bad key: " + msg, msg.contains("'notATask'"));
			for(PbModelTaskEnumType t : PbModelTaskEnumType.values()) {
				String k = ModelTaskKeys.key(t);
				if(k != null) {
					assertTrue("400 must list accepted task key '" + k + "': " + msg, msg.contains(k));
				}
			}

			r = put(owner, b, "{\"taskModels\": {\"portrait\": \"\"}}");
			assertEquals("blank checkpoint must be 400: " + r.getEntity(), 400, r.getStatus());

			r = put(owner, b, "{\"taskModels\": \"not an object\"}");
			assertEquals("taskModels string that is not JSON must be 400: " + r.getEntity(), 400, r.getStatus());

			r = put(owner, b, "{\"palette\": 7}");
			assertEquals("non-string palette must be 400: " + r.getEntity(), 400, r.getStatus());
			assertTrue(errorText(r).contains("palette must be a string"));

			r = put(owner, b, "{\"photoreal\": \"yes\"}");
			assertEquals("non-boolean photoreal must be 400: " + r.getEntity(), 400, r.getStatus());

			r = put(owner, b, "{\"qualityPreset\": \"ultra\"}");
			assertEquals("unknown qualityPreset must be 400: " + r.getEntity(), 400, r.getStatus());
			msg = errorText(r);
			assertTrue(msg, msg.contains("DRAFT") && msg.contains("STANDARD") && msg.contains("FINAL"));

			r = put(owner, b, "{\"pageFormat\": \"hexagonal\"}");
			assertEquals("unknown pageFormat must be 400: " + r.getEntity(), 400, r.getStatus());

			r = put(owner, b, "{\"sdConfig\": {\"objectId\": \"" + NIL_UUID + "\"}}");
			assertEquals("unknown sdConfig must be 404: " + r.getEntity(), 404, r.getStatus());
			assertTrue(errorText(r), errorText(r).contains("sdConfig " + NIL_UUID + " not found"));

			r = put(owner, b, "{\"sdConfig\": \"\"}");
			assertEquals("sdConfig without an id must be 400: " + r.getEntity(), 400, r.getStatus());

			r = put(owner, b, "{\"parent\": \"" + NIL_UUID + "\"}");
			assertEquals("unknown parent must be 404: " + r.getEntity(), 404, r.getStatus());
			assertTrue(errorText(r), errorText(r).contains("Parent style " + NIL_UUID + " not found"));

			/// None of the above may have created the own style.
			Map<String, Object> dto = ok(get(owner, b), "GET after rejected PUTs");
			assertNull("no own style may exist after rejected bodies: " + dto, dto.get("own"));
			assertTrue(list(dto, "chain").isEmpty());
		}
		finally {
			cleanup(book, Collections.emptyList());
		}
	}

	/// taskModels: keys are the lower-camel ModelTaskKeys.key(task) spelling; a write in the enum spelling
	/// is canonicalised; the value is NOT checked against any inventory here; the stored field is replaced
	/// as a whole by each PUT and cleared by {} or null.
	@Test
	public void TestTaskModelsAreCanonicalisedAndNotInventoryChecked() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		try {
			String b = oid(book);
			Map<String, Object> dto = ok(put(owner, b, "{\"taskModels\": {\"portrait\": \"x.safetensors\"}}"), "PUT taskModels.portrait");
			String so = ownOid(dto);
			styles.add(so);
			assertEquals("portrait", ModelTaskKeys.key(PbModelTaskEnumType.PORTRAIT));
			assertEquals("x.safetensors", effectiveTaskModels(dto).get("portrait"));
			assertEquals(so, map(map(dto, "effective"), "taskModelSources").get("portrait"));
			assertEquals("x.safetensors", map(map(own(dto), "fields"), "taskModels").get("portrait"));
			BaseRecord row = readStyleAs(owner, so);
			assertNotNull(row);
			Map<PbModelTaskEnumType, String> stored = ModelTaskKeys.parseTaskModels((String) row.get(OlioFieldNames.FIELD_PB_TASK_MODELS));
			assertEquals("x.safetensors", stored.get(PbModelTaskEnumType.PORTRAIT));

			/// Enum spelling on write -> canonical key on read; and the field is replaced whole (portrait gone).
			dto = ok(put(owner, b, "{\"taskModels\": {\"SCENE_DIRECT\": \"y.safetensors\"}}"), "PUT taskModels.SCENE_DIRECT");
			assertEquals(so, ownOid(dto));
			Map<String, Object> tm = effectiveTaskModels(dto);
			assertEquals("sceneDirect", ModelTaskKeys.key(PbModelTaskEnumType.SCENE_DIRECT));
			assertEquals("y.safetensors", tm.get("sceneDirect"));
			assertFalse("enum spelling must not leak to the wire: " + tm, tm.containsKey("SCENE_DIRECT"));
			assertNull("MEASURED: a PUT of taskModels replaces the stored object whole - portrait is gone: " + tm, tm.get("portrait"));
			String storedJson = (String) readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_TASK_MODELS);
			assertNotNull(storedJson);
			assertTrue("stored JSON is canonical lower-camel: " + storedJson, storedJson.contains("\"sceneDirect\""));
			assertFalse("stored JSON is not the enum spelling: " + storedJson, storedJson.contains("SCENE_DIRECT"));

			/// {} clears.
			dto = ok(put(owner, b, "{\"taskModels\": {}}"), "PUT taskModels {}");
			assertTrue("empty object clears every pin: " + dto, effectiveTaskModels(dto).isEmpty());

			/// A pin again, then null clears.
			dto = ok(put(owner, b, "{\"taskModels\": {\"location\": \"z.safetensors\"}}"), "PUT taskModels.location");
			assertEquals("z.safetensors", effectiveTaskModels(dto).get("location"));
			dto = ok(put(owner, b, "{\"taskModels\": null}"), "PUT taskModels null");
			assertTrue("null clears every pin: " + dto, effectiveTaskModels(dto).isEmpty());
		}
		finally {
			cleanup(book, styles);
		}
	}

	/// 157 p: a JSON null clears a field. The body is hand-built because JSONUtil.exportObject drops nulls.
	@Test
	public void TestNullClearsAField() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		try {
			String b = oid(book);
			Map<String, Object> dto = ok(put(owner, b, "{\"palette\":\"warm\",\"exclusions\":\"text, watermark\",\"customWidth\":640}"), "PUT three fields");
			String so = ownOid(dto);
			styles.add(so);
			assertEquals("warm", effectiveFields(dto).get("palette"));
			assertEquals(640, ((Number) effectiveFields(dto).get("customWidth")).intValue());

			dto = ok(put(owner, b, "{\"palette\": null}"), "PUT palette null");
			assertEquals(so, ownOid(dto));
			assertNull("palette cleared (effective): " + dto, effectiveFields(dto).get("palette"));
			assertNull("palette cleared (own): " + dto, map(own(dto), "fields").get("palette"));
			assertFalse("no source for a cleared field: " + sources(dto), sources(dto).containsKey("palette"));
			assertEquals("exclusions untouched", "text, watermark", effectiveFields(dto).get("exclusions"));
			assertEquals("customWidth untouched", 640, ((Number) effectiveFields(dto).get("customWidth")).intValue());
			BaseRecord row = readStyleAs(owner, so);
			assertNull("palette is null on the row", row.get(OlioFieldNames.FIELD_PB_PALETTE));

			dto = ok(put(owner, b, "{\"customWidth\": null}"), "PUT customWidth null");
			Object cw = effectiveFields(dto).get("customWidth");
			assertTrue("customWidth null -> 0 = unset, so absent or 0 in effect: " + cw, cw == null || ((Number) cw).intValue() == 0);
			assertEquals("int column reads 0 after a null PUT", 0, ((Number) readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH)).intValue());
		}
		finally {
			cleanup(book, styles);
		}
	}

	/// A style cannot be its own parent (string or {"objectId"} form) - 400, and the own style is unchanged.
	@Test
	public void TestSelfParentIs400() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		try {
			String b = oid(book);
			Map<String, Object> dto = ok(put(owner, b, "{\"palette\":\"cool\"}"), "PUT create");
			String so = ownOid(dto);
			styles.add(so);

			Response r = put(owner, b, "{\"parent\": \"" + so + "\"}");
			assertEquals("self parent (string) must be 400: " + r.getEntity(), 400, r.getStatus());
			assertEquals("A style cannot be its own parent", errorText(r));

			r = put(owner, b, "{\"parent\": {\"objectId\": \"" + so + "\"}}");
			assertEquals("self parent (object) must be 400: " + r.getEntity(), 400, r.getStatus());
			assertEquals("A style cannot be its own parent", errorText(r));

			r = put(owner, b, "{\"parent\": 42}");
			assertEquals("parent of the wrong JSON type must be 400: " + r.getEntity(), 400, r.getStatus());

			Map<String, Object> got = ok(get(owner, b), "GET after refusals");
			assertEquals(so, ownOid(got));
			assertNull("own.parent stays null", own(got).get("parent"));
			assertEquals("cool", effectiveFields(got).get("palette"));
			assertEquals(1, list(got, "chain").size());
		}
		finally {
			cleanup(book, styles);
		}
	}

	// ─────────────────────────────── lock (157 n) ───────────────────────────────

	/// Lock before any own style is 404; lock true freezes every PUT with a 409 naming the own style and the
	/// unlock route; GET stays readable; non-boolean / missing / malformed lock bodies are 400 at the route;
	/// unlock re-admits the PUT.
	@Test
	public void TestLockRefusesEditsUntilUnlocked() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		try {
			String b = oid(book);
			Response r = lock(owner, b, "{\"locked\": true}");
			assertEquals("lock with no own style must be 404: " + r.getEntity(), 404, r.getStatus());
			assertTrue(errorText(r), errorText(r).contains("no style of its own"));

			Map<String, Object> dto = ok(put(owner, b, "{\"palette\":\"warm\"}"), "PUT create");
			String so = ownOid(dto);
			styles.add(so);
			String ownName = String.valueOf(own(dto).get("name"));

			dto = ok(lock(owner, b, "{\"locked\": true}"), "lock true");
			assertEquals(so, ownOid(dto));
			assertEquals("own.locked", Boolean.TRUE, own(dto).get("locked"));
			assertEquals("lockedBy is the own style", so, dto.get("lockedBy"));
			assertEquals("chain[0].locked", Boolean.TRUE, chainHop(list(dto, "chain"), 0).get("locked"));
			assertEquals("the lock pins no value - palette still in effect", "warm", effectiveFields(dto).get("palette"));
			assertEquals(Boolean.TRUE, readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_LOCKED));

			r = put(owner, b, "{\"palette\":\"cold\"}");
			assertEquals("PUT while locked must be 409: " + r.getEntity(), 409, r.getStatus());
			String msg = errorText(r);
			assertTrue("409 must name the own style by name: " + msg, msg.contains("'" + ownName + "'"));
			assertTrue("409 must name the own style by objectId: " + msg, msg.contains(so));
			assertTrue("409 must say locked: " + msg, msg.contains("is locked"));
			assertTrue("409 must name the unlock route: " + msg, msg.contains("/style/lock") && msg.contains("\"locked\":false"));
			assertTrue("409 must name the scope: " + msg, msg.contains("BOOK"));

			r = put(owner, b, "{\"parent\": null}");
			assertEquals("re-parenting while locked is an edit too - 409: " + r.getEntity(), 409, r.getStatus());

			r = put(owner, b, "{\"bogus\": 1}");
			assertEquals("body validation precedes the lock check - 400, not 409: " + r.getEntity(), 400, r.getStatus());

			assertEquals("warm", effectiveFields(ok(get(owner, b), "GET while locked")).get("palette"));
			assertEquals("warm", readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_PALETTE));

			r = lock(owner, b, "{\"locked\": \"yes\"}");
			assertEquals("string locked must be 400: " + r.getEntity(), 400, r.getStatus());
			assertEquals("locked must be a JSON boolean", errorText(r));
			r = lock(owner, b, "{}");
			assertEquals("missing locked must be 400: " + r.getEntity(), 400, r.getStatus());
			r = lock(owner, b, "");
			assertEquals("blank lock body must be 400: " + r.getEntity(), 400, r.getStatus());
			r = lock(owner, b, "{oops");
			assertEquals("malformed lock body must be 400: " + r.getEntity(), 400, r.getStatus());
			assertEquals("Malformed request body", errorText(r));
			assertEquals("still locked after the bad bodies", Boolean.TRUE, readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_LOCKED));

			dto = ok(lock(owner, b, "{\"locked\": false}"), "unlock");
			assertEquals(Boolean.FALSE, own(dto).get("locked"));
			assertNull("lockedBy cleared: " + dto, dto.get("lockedBy"));
			assertEquals(Boolean.FALSE, readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_LOCKED));

			dto = ok(put(owner, b, "{\"palette\":\"cold\"}"), "PUT after unlock");
			assertEquals(so, ownOid(dto));
			assertEquals("cold", effectiveFields(dto).get("palette"));
			assertEquals("cold", readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_PALETTE));
		}
		finally {
			cleanup(book, styles);
		}
	}

	// ─────────────────────────────── other principals ───────────────────────────────

	/// A second user with no grant on the book's group: requireBook collapses "cannot read" into the same
	/// 404 as "does not exist" (no existence oracle), so GET, PUT and lock are all 404 - never 403, which
	/// would disclose the book. The owner's style is untouched.
	@Test
	public void TestStrangerIs404Everywhere() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		try {
			String b = oid(book);
			String so = ownOid(ok(put(owner, b, "{\"palette\":\"warm\"}"), "owner PUT"));
			styles.add(so);

			Response r = get(stranger, b);
			assertEquals("MEASURED: stranger GET is 404 (requireBook): " + r.getEntity(), 404, r.getStatus());
			assertEquals("Book not found", errorText(r));
			r = put(stranger, b, "{\"palette\":\"hijacked\"}");
			assertEquals("stranger PUT is 404 (requireBook precedes requireBookUpdate): " + r.getEntity(), 404, r.getStatus());
			assertEquals("Book not found", errorText(r));
			r = lock(stranger, b, "{\"locked\": true}");
			assertEquals("stranger lock is 404: " + r.getEntity(), 404, r.getStatus());
			assertEquals("Book not found", errorText(r));

			assertNull("stranger cannot read the style row either", readStyleAs(stranger, so));
			assertEquals("warm", readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals(Boolean.FALSE, readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_LOCKED));
		}
		finally {
			cleanup(book, styles);
		}
	}

	/// 158 q, read side: a user with only Read (DATA+GROUP) on the book's group can GET the style but PUT is
	/// 403 "edit the style" and lock is 403 "lock the style" (requireBookUpdate), and nothing is written.
	@Test
	public void TestReadOnlyGranteeCanReadButNotEdit() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		boolean granted = false;
		try {
			String b = oid(book);
			String so = ownOid(ok(put(owner, b, "{\"palette\":\"warm\"}"), "owner PUT"));
			styles.add(so);
			grant(book, reader, AccessSchema.SYSTEM_PERMISSION_READ);
			granted = true;

			Map<String, Object> dto = ok(get(reader, b), "reader GET");
			assertEquals("reader sees the own style", so, ownOid(dto));
			assertEquals("warm", effectiveFields(dto).get("palette"));
			assertEquals(Boolean.TRUE, dto.get("complete"));

			Response r = put(reader, b, "{\"palette\":\"hijacked\"}");
			assertEquals("read-only PUT must be 403: " + r.getEntity(), 403, r.getStatus());
			assertEquals("Not authorized to edit the style in this book", errorText(r));
			r = lock(reader, b, "{\"locked\": true}");
			assertEquals("read-only lock must be 403: " + r.getEntity(), 403, r.getStatus());
			assertEquals("Not authorized to lock the style in this book", errorText(r));

			assertEquals("nothing written", "warm", readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals(Boolean.FALSE, readStyleAs(owner, so).get(OlioFieldNames.FIELD_PB_LOCKED));
		}
		finally {
			if(granted) {
				revoke(book, reader, AccessSchema.SYSTEM_PERMISSION_READ);
			}
			cleanup(book, styles);
		}
	}

	/// 158 q, write side (architect Q7): createOwnStyle relies on the book group's CRU grants, not on the
	/// caller being the book's owner. A third user granted Read/Update/Create/Delete (DATA+GROUP) on the
	/// book's group - the same perm set grantSeriesRolesOnChapterGroups applies to the series Writer role -
	/// creates the own style as THEMSELVES (200; the row's ownerId is that user, its groupId the book's
	/// group) and the owner then sees and can edit it.
	@Test
	public void TestWriterGranteeCreatesOwnStyleAsThemselves() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		boolean granted = false;
		String[] crud = new String[] { AccessSchema.SYSTEM_PERMISSION_READ, AccessSchema.SYSTEM_PERMISSION_UPDATE,
			AccessSchema.SYSTEM_PERMISSION_CREATE, AccessSchema.SYSTEM_PERMISSION_DELETE };
		try {
			String b = oid(book);
			/// Before the grant the writer is a stranger.
			assertEquals(404, get(writer, b).getStatus());

			grant(book, writer, crud);
			granted = true;
			assertNull("precondition: no own style yet", ok(get(writer, b), "writer GET after grant").get("own"));

			Response r = put(writer, b, "{\"palette\":\"sepia\",\"qualityPreset\":\"final\"}");
			assertEquals("writer PUT must be 200: " + r.getEntity(), 200, r.getStatus());
			Map<String, Object> dto = body(r);
			String so = ownOid(dto);
			styles.add(so);
			assertEquals("BOOK", own(dto).get("scope"));
			assertEquals("sepia", effectiveFields(dto).get("palette"));
			assertEquals("FINAL", map(dto, "effective").get("qualityPreset"));

			BaseRecord row = readStyleAs(writer, so);
			assertNotNull("writer can read the row it created", row);
			assertEquals("own style ownerId is the writer, not the book owner",
				(long) writer.get(FieldNames.FIELD_ID), (long) row.get(FieldNames.FIELD_OWNER_ID));
			assertNotEquals((long) owner.get(FieldNames.FIELD_ID), (long) row.get(FieldNames.FIELD_OWNER_ID));
			assertEquals("own style lives in the book's group",
				(long) book.get(FieldNames.FIELD_GROUP_ID), (long) row.get(FieldNames.FIELD_GROUP_ID));

			/// The owner sees the writer's style as the book's own and can edit it.
			Map<String, Object> got = ok(get(owner, b), "owner GET");
			assertEquals(so, ownOid(got));
			assertEquals("sepia", effectiveFields(got).get("palette"));
			Map<String, Object> upd = ok(put(owner, b, "{\"exclusions\":\"blur\"}"), "owner PUT on the writer's style");
			assertEquals(so, ownOid(upd));
			assertEquals("blur", effectiveFields(upd).get("exclusions"));
			assertEquals("sepia", effectiveFields(upd).get("palette"));

			/// And the writer can edit and lock it too.
			Map<String, Object> w2 = ok(put(writer, b, "{\"palette\":\"umber\"}"), "writer second PUT");
			assertEquals("umber", effectiveFields(w2).get("palette"));
			assertEquals(Boolean.TRUE, own(ok(lock(writer, b, "{\"locked\": true}"), "writer lock")).get("locked"));
			assertEquals(409, put(owner, b, "{\"palette\":\"x\"}").getStatus());
			assertEquals(Boolean.FALSE, own(ok(lock(writer, b, "{\"locked\": false}"), "writer unlock")).get("locked"));
		}
		finally {
			if(granted) {
				revoke(book, writer, crud);
			}
			cleanup(book, styles);
		}
	}

	/// No principal is 401 on all three routes (requireBook's first gate), as TestPbRenderPathRefusal.
	@Test
	public void TestNoPrincipalIs401() throws Exception {
		BaseRecord book = createBareBook();
		try {
			String b = oid(book);
			Response r = service.getStyle(b, new HttpServletRequestMock());
			assertEquals("GET without a principal: " + r.getEntity(), 401, r.getStatus());
			assertEquals("No authenticated principal", errorText(r));
			r = service.putStyle(b, "{\"palette\":\"anon\"}", new HttpServletRequestMock());
			assertEquals("PUT without a principal: " + r.getEntity(), 401, r.getStatus());
			r = service.lockStyle(b, "{\"locked\": true}", new HttpServletRequestMock());
			assertEquals("lock without a principal: " + r.getEntity(), 401, r.getStatus());
			assertNull("nothing created", ok(get(owner, b), "owner GET").get("own"));
		}
		finally {
			cleanup(book, Collections.emptyList());
		}
	}

	// ─────────────────────────────── §10.1 seam: generic PATCH clears book.style ───────────────────────────────

	/// Clearing book.style is not routed through the style routes; it is the generic PATCH /rest/model seam
	/// (ModelService.patchModel, as TestModelServicePatch drives it). After `"style":null` the GET shows no
	/// own style again and the style row is an orphan that still exists (nothing cascades). The body is
	/// hand-built: JSONUtil.exportObject would drop the null.
	@Test
	public void TestGenericPatchClearsBookStyleLeavingAnOrphan() throws Exception {
		BaseRecord book = createBareBook();
		List<String> styles = new ArrayList<>();
		try {
			String b = oid(book);
			String so = ownOid(ok(put(owner, b, "{\"palette\":\"warm\"}"), "owner PUT"));
			styles.add(so);

			String json = "{\"schema\":\"" + OlioModelNames.MODEL_PB_BOOK + "\",\"id\":" + (long) book.get(FieldNames.FIELD_ID)
				+ ",\"objectId\":\"" + b + "\",\"name\":\"" + book.get(FieldNames.FIELD_NAME) + "\",\"style\":null}";
			Response r = modelService.patchModel(json, requestAs(owner), new HttpServletResponseMock());
			assertEquals("generic PATCH clearing book.style must be 200: " + r.getEntity(), 200, r.getStatus());
			assertEquals(Boolean.TRUE, r.getEntity());
			ServiceUtil.clearCache();

			Map<String, Object> dto = ok(get(owner, b), "GET after the FK was cleared");
			assertNull("own is null again: " + dto, dto.get("own"));
			assertTrue("chain is empty again: " + dto, list(dto, "chain").isEmpty());
			assertEquals(Boolean.TRUE, dto.get("complete"));
			assertNull("no palette in effect any more: " + dto, effectiveFields(dto).get("palette"));

			BaseRecord orphan = readStyleAs(owner, so);
			assertNotNull("the orphan style row still exists", orphan);
			assertEquals("warm", orphan.get(OlioFieldNames.FIELD_PB_PALETTE));

			/// Contract (§6.C 160): the orphan is never re-adopted (it may be locked, and the lock route cannot
			/// reach it once the FK is gone). The orphan still holds `<slug>-style`, which styleModel.json
			/// constrains unique per (name, groupId, organizationId), so the new own style is created as
			/// `<slug>-style-<8 hex>` instead; the orphan is left exactly as it was.
			String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
			Map<String, Object> again = ok(put(owner, b, "{\"palette\":\"cool\"}"), "PUT after clearing");
			String so2 = ownOid(again);
			styles.add(so2);
			assertNotEquals("a fresh own style is created; the orphan is not re-adopted", so, so2);
			String name2 = String.valueOf(own(again).get("name"));
			assertTrue("the second own style is suffixed past the orphan's name, got '" + name2 + "'",
				name2.startsWith(slug + "-style-") && name2.length() == (slug + "-style-").length() + 8);
			assertEquals("cool", effectiveFields(again).get("palette"));
			assertEquals("the new own style is the chain head", so2, chainHop(list(again, "chain"), 0).get("objectId"));

			BaseRecord orphanAfter = readStyleAs(owner, so);
			assertNotNull("the orphan still exists after the second PUT", orphanAfter);
			assertEquals("the orphan was not edited by the second PUT", "warm", orphanAfter.get(OlioFieldNames.FIELD_PB_PALETTE));
			assertEquals("the orphan keeps its original name", slug + "-style", orphanAfter.get(FieldNames.FIELD_NAME));
		}
		finally {
			cleanup(book, styles);
		}
	}
}
