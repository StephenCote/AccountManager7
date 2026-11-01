package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// P9-0 / §6.C 212 p (PictureBook V3): PUT /{book}/node/{node}/canvas exercised through the real service
/// method -> PbServiceFacade.saveCanvas -> real AccessPoint/PBAC -> the real (H2) database, with
/// HttpServletRequestMock + UserPrincipal exactly as TestPictureBookStyleRoutes does. Nothing is mocked
/// below the servlet principal and no actor is the admin or olio principal.
///
/// THE DEFECT THIS PINS. Ux752 sends {x, y, w, h} on every card drop. None of the four was declared on
/// olio.pictureBookRequest, so RecordDeserializer dropped them (KI-24), the route read four nulls, the
/// facade set them on olio.pb.node's four int canvas columns and RecordValidator auto-unboxed a null
/// (RecordValidator:88) - a 500 and the "Save position failed: saveCanvas failed: 500" toast on every
/// drop, seen through P5-P8 and deferred to P9. Case 1 is that exact body.
///
/// FIXTURE. A bare book the owner created through AccessPoint in a group it owns, plus its workflow and
/// one node from PbGraphUtil (the authorized write path) - the minimum requireBook -> requireWorkflow ->
/// requireNodeOfBook needs. Each case creates and deletes its own fixture as the owner.
public class TestPictureBookCanvasRoute extends BaseTest {

	private static final String OWNER_NAME = "pbcanvasowner";
	private static final String GROUP_PATH = "~/PbCanvasRoute";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String REQUEST_MODEL = "olio.pictureBookRequest";

	private final PictureBookService service = new PictureBookService();

	private BaseRecord owner;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		owner = getCreateUser(OWNER_NAME);
		assertNotNull("owner user is null", owner);
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String) u.get(FieldNames.FIELD_NAME), organizationPath));
	}

	private Response put(BaseRecord u, String bookOid, String nodeOid, String json) {
		return service.saveCanvas(bookOid, nodeOid, json, requestAs(u));
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

	private Map<String, Object> ok(Response r, String what) {
		assertEquals(what + " must be 200, body: " + r.getEntity(), 200, r.getStatus());
		return body(r);
	}

	private String errorText(Response r) {
		Map<String, Object> m = body(r);
		Object msg = m.get("message");
		if(msg == null) {
			msg = m.get("error");
		}
		assertNotNull("Refusal body must carry 'error' or 'message': " + r.getEntity(), msg);
		return String.valueOf(msg);
	}

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	private int intOf(Map<String, Object> m, String key) {
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present in " + m, o);
		return ((Number) o).intValue();
	}

	private int stored(String nodeOid, String field) {
		BaseRecord n = PbGraphUtil.readNode(owner, nodeOid, orgId);
		assertNotNull("node " + nodeOid + " must be readable by its owner", n);
		Object v = n.get(field);
		assertNotNull("stored " + field + " must not be null on " + nodeOid, v);
		return ((Number) v).intValue();
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A bare book + workflow + one node, all created as the owner through the authorized write paths.
	private BaseRecord[] createBookWithNode() throws Exception {
		String slug = "cnv-" + UUID.randomUUID().toString().substring(0, 12);
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
		/// create returns identity fields only; the workflow's derived name needs book.name, so read it back.
		BaseRecord read = PbBookUtil.readBook(owner, oid(created), orgId);
		assertNotNull("the created book must read back for its owner", read);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(owner, read, GROUP_PATH);
		assertNotNull("Failed to create the workflow fixture", wf);
		BaseRecord node = PbGraphUtil.addNode(owner, wf, "canvas-" + slug, PbNodeTypeEnumType.SCENE, GROUP_PATH, 0);
		assertNotNull("Failed to create the node fixture", node);
		assertNotNull("node fixture carries no objectId", oid(node));
		return new BaseRecord[] { read, wf, node };
	}

	/// Node, workflow, book - as the owner. Failures are logged, not asserted, so teardown never masks a case.
	private void cleanup(BaseRecord[] fx) {
		if(fx == null) {
			return;
		}
		for(int i = fx.length - 1; i >= 0; i--) {
			if(fx[i] == null) {
				continue;
			}
			try {
				if(!IOSystem.getActiveContext().getAccessPoint().delete(owner, fx[i])) {
					logger.error("cleanup: failed to delete " + fx[i].getSchema() + " " + oid(fx[i]));
				}
			}
			catch(Exception e) {
				logger.error("cleanup: delete of " + fx[i].getSchema() + " threw: " + e.getMessage());
			}
		}
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// The KI-24 contract, on the exact body Ux752's saveCanvas sends: all four keys arrive and are
	/// reported present by hasField. Before P9-0 this body deserialized to an EMPTY record.
	@Test
	public void TestCanvasBodyRoundTrips() {
		OlioModelNames.use();
		String json = "{\"schema\":\"" + REQUEST_MODEL + "\",\"x\":120,\"y\":40,\"w\":200,\"h\":160}";
		BaseRecord r = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("the canvas body must deserialize", r);
		for(String k : new String[] { "x", "y", "w", "h" }) {
			assertTrue(k + " must be declared on " + REQUEST_MODEL + " and survive deserialization (KI-24)", r.hasField(k));
		}
		assertEquals(120, ((Number) r.get("x")).intValue());
		assertEquals(40, ((Number) r.get("y")).intValue());
		assertEquals(200, ((Number) r.get("w")).intValue());
		assertEquals(160, ((Number) r.get("h")).intValue());
		/// KI-25: an omitted key is ABSENT by hasField (and reads 0 any other way), which is why the route
		/// reads through optionalInt - a partial body must leave the omitted columns alone, never zero them.
		BaseRecord partial = JSONUtil.importObject("{\"schema\":\"" + REQUEST_MODEL + "\",\"x\":7}", LooseRecord.class,
			RecordDeserializerConfig.getUnfilteredModule());
		assertTrue(partial.hasField("x"));
		assertFalse("an omitted y must be reported absent (KI-25)", partial.hasField("y"));
		assertEquals("...and reads 0 when not checked by presence", 0, ((Number) partial.get("y")).intValue());
	}

	/// Case 1 - the 212 p body: PUT {x,y,w,h} is 200, echoes the four values, and the node reads them back.
	/// Then a partial PUT {x} moves X alone and the response is the merged state; a bare {} is a no-op that
	/// returns the stored position (parseParams yields null for {} - see TestPictureBookRestContract).
	@Test
	public void TestPutCanvasPersistsAndReadsBack() throws Exception {
		BaseRecord[] fx = createBookWithNode();
		try {
			String b = oid(fx[0]);
			String n = oid(fx[2]);

			Map<String, Object> dto = ok(put(owner, b, n, "{\"x\":120,\"y\":40,\"w\":200,\"h\":160}"), "PUT all four (the Ux752 drop body)");
			assertEquals(n, dto.get("nodeObjectId"));
			assertEquals(120, intOf(dto, "canvasX"));
			assertEquals(40, intOf(dto, "canvasY"));
			assertEquals(200, intOf(dto, "canvasW"));
			assertEquals(160, intOf(dto, "canvasH"));
			assertEquals("canvasX persisted", 120, stored(n, OlioFieldNames.FIELD_PB_CANVAS_X));
			assertEquals("canvasY persisted", 40, stored(n, OlioFieldNames.FIELD_PB_CANVAS_Y));
			assertEquals("canvasW persisted", 200, stored(n, OlioFieldNames.FIELD_PB_CANVAS_W));
			assertEquals("canvasH persisted", 160, stored(n, OlioFieldNames.FIELD_PB_CANVAS_H));

			dto = ok(put(owner, b, n, "{\"x\":7}"), "PUT x only");
			assertEquals("x moved", 7, intOf(dto, "canvasX"));
			assertEquals("y echoed from the stored row, not nulled", 40, intOf(dto, "canvasY"));
			assertEquals("w echoed from the stored row", 200, intOf(dto, "canvasW"));
			assertEquals("h echoed from the stored row", 160, intOf(dto, "canvasH"));
			assertEquals(7, stored(n, OlioFieldNames.FIELD_PB_CANVAS_X));
			assertEquals("an omitted key is not touched - y stays", 40, stored(n, OlioFieldNames.FIELD_PB_CANVAS_Y));
			assertEquals("an omitted key is not touched - w stays", 200, stored(n, OlioFieldNames.FIELD_PB_CANVAS_W));
			assertEquals("an omitted key is not touched - h stays", 160, stored(n, OlioFieldNames.FIELD_PB_CANVAS_H));

			/// A deliberate 0 is a value, not an absence (KI-25): x goes to 0 and nothing else moves.
			dto = ok(put(owner, b, n, "{\"x\":0,\"y\":0}"), "PUT x=0,y=0");
			assertEquals(0, intOf(dto, "canvasX"));
			assertEquals(0, intOf(dto, "canvasY"));
			assertEquals(200, intOf(dto, "canvasW"));
			assertEquals(0, stored(n, OlioFieldNames.FIELD_PB_CANVAS_X));
			assertEquals(0, stored(n, OlioFieldNames.FIELD_PB_CANVAS_Y));
			assertEquals(200, stored(n, OlioFieldNames.FIELD_PB_CANVAS_W));
			assertEquals(160, stored(n, OlioFieldNames.FIELD_PB_CANVAS_H));

			dto = ok(put(owner, b, n, "{}"), "PUT {} (no geometry)");
			assertEquals("no-op echoes the stored position", 0, intOf(dto, "canvasX"));
			assertEquals(200, intOf(dto, "canvasW"));
			assertEquals(160, intOf(dto, "canvasH"));
			assertEquals(200, stored(n, OlioFieldNames.FIELD_PB_CANVAS_W));
		}
		finally {
			cleanup(fx);
		}
	}

	/// 404s: an unknown node in a real book ("Node not found"), a node objectId that exists nowhere, and an
	/// unknown book ("Book not found") - never a 500, and the real node's position is untouched.
	@Test
	public void TestUnknownNodeOrBookIs404() throws Exception {
		BaseRecord[] fx = createBookWithNode();
		try {
			String b = oid(fx[0]);
			String n = oid(fx[2]);
			ok(put(owner, b, n, "{\"x\":5,\"y\":6,\"w\":7,\"h\":8}"), "seed position");

			Response r = put(owner, b, NIL_UUID, "{\"x\":1,\"y\":2,\"w\":3,\"h\":4}");
			assertEquals("unknown node must be 404: " + r.getEntity(), 404, r.getStatus());
			assertEquals("Node not found", errorText(r));

			r = put(owner, NIL_UUID, n, "{\"x\":1,\"y\":2,\"w\":3,\"h\":4}");
			assertEquals("unknown book must be 404: " + r.getEntity(), 404, r.getStatus());
			assertEquals("Book not found", errorText(r));

			assertEquals("the real node is untouched by the refused calls", 5, stored(n, OlioFieldNames.FIELD_PB_CANVAS_X));
			assertEquals(8, stored(n, OlioFieldNames.FIELD_PB_CANVAS_H));
		}
		finally {
			cleanup(fx);
		}
	}

	/// No principal is 401 (requireBook's first gate), as the other route suites pin.
	@Test
	public void TestNoPrincipalIs401() throws Exception {
		BaseRecord[] fx = createBookWithNode();
		try {
			Response r = service.saveCanvas(oid(fx[0]), oid(fx[2]), "{\"x\":1,\"y\":2,\"w\":3,\"h\":4}", new HttpServletRequestMock());
			assertEquals("PUT without a principal: " + r.getEntity(), 401, r.getStatus());
		}
		finally {
			cleanup(fx);
		}
	}
}
