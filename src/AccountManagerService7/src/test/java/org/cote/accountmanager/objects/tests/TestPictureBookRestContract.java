package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.junit.Test;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

/**
 * PB2 phase 4's exit criterion: the REST contract of {@code PictureBookService}, asserted two ways.
 * <p>
 * <b>1. Body deserialization, per endpoint — the KI-24/KI-25 class.</b> Every endpoint parses its JSON body
 * into a {@code LooseRecord} against {@code olio.pictureBookRequest}, and
 * {@code RecordDeserializer} <b>silently drops any property the model does not declare</b> (it logs
 * "Invalid field" at ERROR and moves on, {@code RecordDeserializer.java:250-253}). That is how KI-24
 * happened: every character the wizard sent was dropped, the endpoint returned 200 with a valid
 * bookObjectId, and the character list rendered permanently blank. A test that only checks status codes
 * cannot see it. So each case here deserializes the <b>exact body shape the endpoint reads</b> and asserts
 * the values arrive.
 * <p>
 * <b>Writing it surfaced two pre-existing defects</b>, both exactly KI-24's shape — found by reading the
 * model against the endpoint code, not by this suite failing first, which is worth stating precisely:
 * {@code sceneIndex} (PUT .../scene-tag) and {@code scenes} (PUT /{book}/scenes/order) were <b>not declared
 * on {@code olio.pictureBookRequest} at all</b>, so the deserializer dropped them. Consequences read off the
 * code: {@code tagApparelSceneIndex}'s {@code sceneIndex == null} guard fired on every request (a permanent
 * 400), and {@code reorderScenes} received an empty list and reordered nothing. Both fields are now declared
 * and both are asserted below. <b>Not reproduced over HTTP</b> — the mechanism is verified in
 * {@code RecordDeserializer} and by {@link #TestAnUndeclaredPropertyIsSilentlyDropped}, not against a
 * running Tomcat.
 * <p>
 * <b>2. Every resource method carries {@code @RolesAllowed}</b>, asserted reflectively rather than by
 * reading the file, so a new endpoint added later cannot quietly ship unauthenticated. Jersey's
 * {@code RolesAllowedDynamicFeature} only enforces the annotation where it is present — an endpoint without
 * one is open to anybody the container let through.
 * <p>
 * <b>No mocking.</b> This test never fabricates an {@code HttpServletRequest}, {@code ServletContext} or
 * {@code UserPrincipal} to drive a resource method in-process — that produces a test that passes while the
 * real transport is broken. It asserts the two things that are genuinely checkable without a container (the
 * annotation contract, and the deserialization contract the endpoints depend on), and nothing else. The
 * behaviour behind the endpoints is covered where it lives: {@code PbServiceFacade} in Objects7's
 * {@code TestPbGraph}/{@code TestPbSecurity}/{@code TestPictureBookWorkflow}.
 */
public class TestPictureBookRestContract extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestPictureBookRestContract.class);

	private static final String REQUEST_MODEL = "olio.pictureBookRequest";

	/** The JAX-RS verb annotations a resource method can carry. */
	private static final List<Class<? extends java.lang.annotation.Annotation>> VERBS = Arrays.asList(
		GET.class, POST.class, PUT.class, DELETE.class, HEAD.class, OPTIONS.class);

	private BaseRecord parseBody(String json) {
		OlioModelNames.use();
		/// Exactly what PictureBookService.parseParams does, including the schema injection its
		/// ensureSchema() performs - a body without a schema property cannot be deserialized at all.
		String withSchema = json.trim().startsWith("{")
			? "{\"schema\":\"" + REQUEST_MODEL + "\"," + json.trim().substring(1)
			: json;
		return JSONUtil.importObject(withSchema, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. @RolesAllowed on every resource method
	// ═══════════════════════════════════════════════════════════════════

	@Test
	public void TestEveryResourceMethodCarriesRolesAllowed() {
		List<String> missing = new ArrayList<>();
		int checked = 0;
		for(Method m : PictureBookService.class.getDeclaredMethods()) {
			boolean isResource = false;
			for(Class<? extends java.lang.annotation.Annotation> verb : VERBS) {
				if(m.isAnnotationPresent(verb)) {
					isResource = true;
					break;
				}
			}
			if(!isResource) {
				continue;
			}
			checked++;
			RolesAllowed ra = m.getAnnotation(RolesAllowed.class);
			if(ra == null || ra.value().length == 0) {
				missing.add(m.getName() + " " + pathOf(m));
				continue;
			}
			List<String> roles = Arrays.asList(ra.value());
			assertTrue(m.getName() + ": @RolesAllowed must include \"admin\" (got " + roles + ")",
				roles.contains("admin"));
			assertTrue(m.getName() + ": @RolesAllowed must include \"user\" (got " + roles + ")",
				roles.contains("user"));
		}
		logger.info("Checked @RolesAllowed on " + checked + " PictureBookService resource methods");
		/// The count matters as much as the emptiness: a reflection bug that matched nothing would report
		/// zero missing annotations and pass while asserting nothing at all.
		assertTrue("Reflection must have found the resource methods - 20+ are expected, found " + checked,
			checked >= 20);
		assertTrue("Every PictureBookService resource method must carry @RolesAllowed({\"admin\",\"user\"})."
			+ " Jersey's RolesAllowedDynamicFeature only enforces the annotation where it is present, so a"
			+ " method without one is reachable by anyone the container admitted. Missing: " + missing,
			missing.isEmpty());
	}

	private String pathOf(Method m) {
		Path p = m.getAnnotation(Path.class);
		return (p != null ? p.value() : "(class-level path)");
	}

	@Test
	public void TestPhase4EndpointsExistAndAreAnnotated() {
		/// Named explicitly, so a phase-4 endpoint being deleted or renamed fails here rather than being
		/// silently absent from the reflective sweep above (which only checks what it finds).
		String[][] expected = new String[][] {
			{ "getWorkflow", "GET" },
			{ "getWorkflowNode", "GET" },
			{ "getArtifact", "GET" },
			{ "listStale", "GET" },
			{ "regenerateNode", "POST" },
			{ "pinNode", "POST" },
			{ "addMembers", "POST" },
			{ "createChapter", "POST" }
		};
		for(String[] e : expected) {
			Method found = null;
			for(Method m : PictureBookService.class.getDeclaredMethods()) {
				if(m.getName().equals(e[0])) {
					found = m;
					break;
				}
			}
			assertNotNull("Phase-4 endpoint method " + e[0] + " must exist on PictureBookService", found);
			Class<? extends java.lang.annotation.Annotation> verb = "GET".equals(e[1]) ? GET.class : POST.class;
			assertNotNull(e[0] + " must be annotated @" + e[1], found.getAnnotation(verb));
			assertNotNull(e[0] + " must carry @RolesAllowed", found.getAnnotation(RolesAllowed.class));
			assertNotNull(e[0] + " must declare a @Path", found.getAnnotation(Path.class));
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. Body deserialization, per endpoint
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * Every property any endpoint reads off the parsed body must be DECLARED on
	 * {@code olio.pictureBookRequest}. This is the generic form of KI-24: an undeclared property is dropped
	 * with an ERROR log line and the endpoint sees null/0.
	 */
	@Test
	public void TestEveryBodyPropertyTheEndpointsReadIsDeclared() {
		ModelSchema ms = RecordFactory.getSchema(REQUEST_MODEL);
		assertNotNull(REQUEST_MODEL + " must be registered - call OlioModelNames.use()", ms);
		String[] read = new String[] {
			/// PB1
			"count", "chatConfig", "genre", "bookName", "promptOverride", "promptTemplate", "isBook",
			"sdConfig", "compositeSdConfig", "sdConfigOverride", "sceneList", "characters", "status",
			"sceneObjectIds",
			/// PB1, undeclared until 2026-08-17 - both were live KI-24-class defects
			"sceneIndex", "scenes",
			/// PB2 phase 4
			"pinned", "userNames", "asAdmin", "fromBookObjectId", "slug", "title", "copyRecordModel",
			"copyRecordObjectIds"
		};
		List<String> undeclared = new ArrayList<>();
		for(String f : read) {
			if(ms.getFieldSchema(f) == null) {
				undeclared.add(f);
			}
		}
		assertTrue("These properties are read off the request body by PictureBookService but are NOT declared"
			+ " on " + REQUEST_MODEL + ", so RecordDeserializer drops them and the endpoint silently sees"
			+ " null/0 (KI-24's exact mechanism): " + undeclared, undeclared.isEmpty());
	}

	/**
	 * The mechanism itself, demonstrated rather than asserted from the code: a property the model does not
	 * declare simply is not there afterwards. This is what made KI-24 invisible and what made
	 * {@code sceneIndex}/{@code scenes} dead on arrival, and it is why
	 * {@link #TestEveryBodyPropertyTheEndpointsReadIsDeclared} exists.
	 */
	@Test
	public void TestAnUndeclaredPropertyIsSilentlyDropped() {
		BaseRecord r = parseBody("{\"chatConfig\":\"cfg\",\"totallyUndeclaredProperty\":\"value\"}");
		assertNotNull(r);
		assertEquals("A declared property arrives", "cfg", r.get("chatConfig"));
		assertFalse("An UNDECLARED property must not be present on the deserialized record - this is KI-24's"
			+ " mechanism, and the only trace it leaves is an ERROR-level 'Invalid field' log line",
			r.hasField("totallyUndeclaredProperty"));
	}

	@Test
	public void TestSceneTagBodyRoundTrips() {
		/// PUT /character/{objectId}/apparel/{apparelObjectId}/scene-tag
		BaseRecord r = parseBody("{\"sceneIndex\": 2}");
		assertNotNull("The scene-tag body must deserialize", r);
		assertTrue("sceneIndex must survive deserialization - it was dropped entirely before it was declared"
			+ " on olio.pictureBookRequest, so every real scene-tag request 400'd", r.hasField("sceneIndex"));
		assertEquals("sceneIndex must arrive with the value sent", 2,
			((Number) r.get("sceneIndex")).intValue());

		/// KI-25's distinction, which is the whole reason the endpoint uses hasField: an ABSENT int field
		/// reads back as 0, not null. A `== null` guard can never fire, and 0 is a legitimate scene index.
		BaseRecord absent = parseBody("{\"chatConfig\":\"x\"}");
		assertFalse("An omitted sceneIndex must be reported ABSENT by hasField, which is the only way to"
			+ " distinguish it from a deliberate 0 (KI-25)", absent.hasField("sceneIndex"));
		assertEquals("An omitted int field reads back as 0 - this is exactly why the null check was wrong",
			0, ((Number) absent.get("sceneIndex")).intValue());
	}

	@Test
	public void TestReorderScenesBodyRoundTrips() {
		/// PUT /{bookObjectId}/scenes/order
		BaseRecord r = parseBody("{\"scenes\": [\"oid-a\", \"oid-b\", \"oid-c\"]}");
		assertNotNull(r);
		List<?> scenes = r.get("scenes");
		assertNotNull("scenes must survive deserialization - it was dropped entirely before it was declared,"
			+ " so every reorder request arrived as an empty list and reordered nothing", scenes);
		assertEquals("all three scene objectIds must arrive", 3, scenes.size());
		assertEquals("oid-a", scenes.get(0));
		assertEquals("oid-c", scenes.get(2));
	}

	@Test
	public void TestPinNodeBodyRoundTrips() {
		/// POST /{bookObjectId}/node/{nodeObjectId}/pin
		BaseRecord unpin = parseBody("{\"pinned\": false}");
		assertNotNull(unpin);
		assertTrue("pinned must be present when sent", unpin.hasField("pinned"));
		assertEquals("An explicit false must arrive as false - the endpoint defaults to pinning, so a lost"
			+ " false would silently pin instead of unpinning", Boolean.FALSE, unpin.get("pinned"));

		BaseRecord pin = parseBody("{\"pinned\": true}");
		assertEquals(Boolean.TRUE, pin.get("pinned"));
	}

	@Test
	public void TestAddMembersBodyRoundTrips() {
		/// POST /{bookObjectId}/members
		BaseRecord r = parseBody("{\"userNames\": [\"alice\", \"bob\"], \"asAdmin\": true}");
		assertNotNull(r);
		List<?> names = r.get("userNames");
		assertNotNull("userNames must survive deserialization", names);
		assertEquals("both names must arrive", 2, names.size());
		assertEquals("alice", names.get(0));
		assertEquals("bob", names.get(1));
		assertEquals("asAdmin must arrive as sent - a dropped true would silently enrol a Writer instead of"
			+ " an Admin, and only an Admin can enrol anybody else", Boolean.TRUE, r.get("asAdmin"));

		/// The default matters: asAdmin defaults FALSE, so an omitted flag must not escalate.
		BaseRecord defaulted = parseBody("{\"userNames\": [\"carol\"]}");
		assertEquals("An omitted asAdmin must default to false, never true", Boolean.FALSE,
			defaulted.get("asAdmin"));
	}

	@Test
	public void TestCreateChapterBodyRoundTrips() {
		/// POST /chapter
		BaseRecord r = parseBody("{\"fromBookObjectId\": \"book-oid-1\", \"slug\": \"chapter-two\","
			+ " \"title\": \"Chapter Two\", \"copyRecordModel\": \"olio.narrative\","
			+ " \"copyRecordObjectIds\": [\"rec-1\", \"rec-2\"]}");
		assertNotNull(r);
		assertEquals("book-oid-1", r.get("fromBookObjectId"));
		assertEquals("chapter-two", r.get("slug"));
		assertEquals("Chapter Two", r.get("title"));
		assertEquals("olio.narrative", r.get("copyRecordModel"));
		List<?> ids = r.get("copyRecordObjectIds");
		assertNotNull("copyRecordObjectIds must survive deserialization - dropping it would silently create"
			+ " an EMPTY chapter and report success", ids);
		assertEquals(2, ids.size());
		assertEquals("rec-1", ids.get(0));
	}

	@Test
	public void TestGenerateSceneBodyRoundTripsTheNestedSdConfig() {
		/// POST /scene/{sceneObjectId}/generate — the nested olio.sd.config is the field KI-24's mechanism
		/// would break most expensively: a dropped sdConfig means the whole render runs on schema defaults
		/// (including a checkpoint that is almost certainly not installed, which returns an EMPTY image list
		/// rather than an error — KI-39).
		BaseRecord r = parseBody("{\"chatConfig\": \"cfg\", \"isBook\": true, \"sdConfig\":"
			+ " {\"schema\": \"olio.sd.config\", \"style\": \"photograph\", \"seed\": 987654321,"
			+ " \"hires\": false, \"compositeMode\": \"flux2\", \"flux2IncludeLandscapeRef\": true}}");
		assertNotNull(r);
		assertEquals("cfg", r.get("chatConfig"));
		assertEquals(Boolean.TRUE, r.get("isBook"));
		Object sdc = r.get("sdConfig");
		assertTrue("sdConfig must deserialize as a nested olio.sd.config RECORD, not a map or null",
			sdc instanceof BaseRecord);
		BaseRecord cfg = (BaseRecord) sdc;
		assertEquals("photograph", cfg.get("style"));
		assertEquals("flux2", cfg.get("compositeMode"));
		assertEquals(Boolean.FALSE, cfg.get("hires"));
		assertEquals(Boolean.TRUE, cfg.get("flux2IncludeLandscapeRef"));
		assertEquals("the seed must survive as sent, or level 2's differential compares two different seeds",
			987654321L, ((Number) cfg.get("seed")).longValue());
	}

	@Test
	public void TestPrepareImagesBodyRoundTrips() {
		/// POST /{bookObjectId}/prepare-images
		BaseRecord r = parseBody("{\"sceneObjectIds\": [\"s1\", \"s2\"], \"chatConfig\": \"cfg\","
			+ " \"sdConfig\": {\"schema\": \"olio.sd.config\", \"style\": \"comic\"}}");
		assertNotNull(r);
		List<?> ids = r.get("sceneObjectIds");
		assertNotNull(ids);
		assertEquals(2, ids.size());
		assertTrue(r.get("sdConfig") instanceof BaseRecord);
		assertEquals("comic", ((BaseRecord) r.get("sdConfig")).get("style"));
	}

	@Test
	public void TestSceneStatusBodyRoundTrips() {
		/// PUT /scene/{sceneObjectId}/status
		BaseRecord r = parseBody("{\"status\": \"accepted\"}");
		assertNotNull(r);
		assertEquals("accepted", r.get("status"));
	}

	@Test
	public void TestExtractBodyCountAbsenceIsDistinguishable() {
		/// POST /{workObjectId}/extract-scenes-only — KI-25 itself. An omitted count reads back as 0, and 0
		/// asks the LLM for "the 0 most notable scenes", which it cheerfully returns as an empty array with a
		/// 200. hasField is the only thing that separates absent from a deliberate 0.
		BaseRecord absent = parseBody("{\"chatConfig\": \"cfg\"}");
		assertFalse("An omitted count must be reported absent, or the intended default of 10 is silently"
			+ " overwritten with 0 (KI-25)", absent.hasField("count"));
		assertEquals(0, ((Number) absent.get("count")).intValue());

		BaseRecord present = parseBody("{\"count\": 5}");
		assertTrue(present.hasField("count"));
		assertEquals(5, ((Number) present.get("count")).intValue());
	}

	@Test
	public void TestCreateFromScenesCharacterStubsRoundTrip() {
		/// POST /{workObjectId}/create-from-scenes — KI-24's original shape: characters must deserialize as
		/// olio.pictureBookCharacterStub, not as the SCENE model. When it pointed at the scene shape, every
		/// character field was dropped and the wizard reported success with zero characters.
		BaseRecord r = parseBody("{\"characters\": [{\"schema\": \"olio.pictureBookCharacterStub\","
			+ " \"name\": \"Jideon de Rosa\", \"gender\": \"male\", \"role\": \"father\"}]}");
		assertNotNull(r);
		List<?> chars = r.get("characters");
		assertNotNull("characters must survive deserialization (KI-24)", chars);
		assertEquals(1, chars.size());
		assertTrue(chars.get(0) instanceof BaseRecord);
		BaseRecord stub = (BaseRecord) chars.get(0);
		assertEquals("Jideon de Rosa", stub.get("name"));
		assertEquals("father", stub.get("role"));
	}
}
