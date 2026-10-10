package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.rest.services.ChapBookService;
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
 * REST contract of {@code ChapBookService}, asserted the same two ways as
 * {@link TestPictureBookRestContract}: every resource method carries {@code @RolesAllowed} (reflectively,
 * so a later endpoint cannot ship open), and the body shapes the endpoints read deserialize against
 * {@code olio.pictureBookRequest} with the values intact (KI-24's mechanism — an undeclared property is
 * dropped silently).
 * <p>
 * Added with V3 Phase 0 P0-10c (the {@code /settings} pair). <b>No mocking</b> — no fabricated
 * {@code HttpServletRequest}/principal drives a resource method here; the behaviour behind the endpoints
 * is covered in Objects7 ({@code TestChapBookBookSettings}, {@code TestChapBookRenderStyle}) and the
 * transport is exercised by the Ux752 Playwright suite against the Docker stack.
 */
public class TestChapBookRestContract extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestChapBookRestContract.class);

	private static final String REQUEST_MODEL = "olio.pictureBookRequest";

	private static final List<Class<? extends java.lang.annotation.Annotation>> VERBS = Arrays.asList(
		GET.class, POST.class, PUT.class, DELETE.class, HEAD.class, OPTIONS.class);

	/// Drives the REAL ChapBookService.parseParams (ensureSchema + schema-driven import) rather than a
	/// copy of it, so a change to how the service injects the request schema is tested here, not mirrored.
	private BaseRecord parseBody(String json) {
		OlioModelNames.use();
		try {
			Method pp = ChapBookService.class.getDeclaredMethod("parseParams", String.class);
			pp.setAccessible(true);
			return (BaseRecord) pp.invoke(new ChapBookService(), json);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("ChapBookService.parseParams(String) is not reachable", e);
		}
	}

	private static Method methodNamed(String name) {
		for(Method m : ChapBookService.class.getDeclaredMethods()) {
			if(m.getName().equals(name)) {
				return m;
			}
		}
		return null;
	}

	private static String pathOf(Method m) {
		Path p = m.getAnnotation(Path.class);
		return (p != null ? p.value() : "(class-level path)");
	}

	// ═══════════════════════════════════════════════════════════════════
	// 1. @RolesAllowed on every resource method
	// ═══════════════════════════════════════════════════════════════════

	@Test
	public void TestEveryResourceMethodCarriesRolesAllowed() {
		List<String> missing = new ArrayList<>();
		int checked = 0;
		for(Method m : ChapBookService.class.getDeclaredMethods()) {
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
			assertTrue(m.getName() + ": @RolesAllowed must include \"admin\" (got " + roles + ")", roles.contains("admin"));
			assertTrue(m.getName() + ": @RolesAllowed must include \"user\" (got " + roles + ")", roles.contains("user"));
		}
		logger.info("Checked @RolesAllowed on " + checked + " ChapBookService resource methods");
		/// The count guards the sweep itself: a reflection bug that matched nothing would pass while
		/// asserting nothing. 16 existed before P0-10c added two (18 measured 2026-10-09).
		assertTrue("Reflection must have found the resource methods - 18+ are expected, found " + checked, checked >= 18);
		assertTrue("Every ChapBookService resource method must carry @RolesAllowed({\"admin\",\"user\"}). Missing: " + missing,
			missing.isEmpty());
	}

	@Test
	public void TestSettingsEndpointsExistAndAreAnnotated() {
		/// P0-10c: GET and PUT /{bookObjectId}/settings — named explicitly so a rename or deletion fails
		/// here instead of vanishing from the reflective sweep.
		Method get = methodNamed("getBookSdConfig");
		assertNotNull("ChapBookService.getBookSdConfig must exist", get);
		assertNotNull("getBookSdConfig must be @GET", get.getAnnotation(GET.class));
		assertNull("getBookSdConfig must not also be @PUT", get.getAnnotation(PUT.class));
		assertNotNull("getBookSdConfig must carry @RolesAllowed", get.getAnnotation(RolesAllowed.class));
		assertEquals("/{bookObjectId:[0-9A-Za-z\\-]+}/settings", pathOf(get));

		Method put = methodNamed("setBookSdConfig");
		assertNotNull("ChapBookService.setBookSdConfig must exist", put);
		assertNotNull("setBookSdConfig must be @PUT", put.getAnnotation(PUT.class));
		assertNull("setBookSdConfig must not also be @GET", put.getAnnotation(GET.class));
		assertNotNull("setBookSdConfig must carry @RolesAllowed", put.getAnnotation(RolesAllowed.class));
		assertEquals("/{bookObjectId:[0-9A-Za-z\\-]+}/settings", pathOf(put));

		/// Same path template as deleteChapBook's parent, so the objectId regex is identical and an id
		/// that reaches the util cannot carry a quote into errorResponse's unescaped JSON.
		Method del = methodNamed("deleteChapBook");
		assertNotNull(del);
		assertEquals("/{bookObjectId:[0-9A-Za-z\\-]+}", pathOf(del));
	}

	// ═══════════════════════════════════════════════════════════════════
	// 2. Body deserialization
	// ═══════════════════════════════════════════════════════════════════

	@Test
	public void TestEveryBodyPropertyTheEndpointsReadIsDeclared() {
		ModelSchema ms = RecordFactory.getSchema(REQUEST_MODEL);
		assertNotNull(REQUEST_MODEL + " must be registered - call OlioModelNames.use()", ms);
		/// The properties the create / render / settings endpoints read off parseParams(...). sdPrompt,
		/// promptOverride, sources and userNames are read via Jackson directly for the documented KI-24
		/// reasons and are not part of this contract.
		/// NOT listed, deliberately: POST /poem reads author/text/groupPath/noteObjectId/dataObjectId and
		/// POST /set reads name/description/groupPath, none of which olio.pictureBookRequest declares -
		/// a pre-existing KI-24-class defect on two endpoints nothing in Ux752 calls (the UI uses POST
		/// /poems, Jackson-parsed). Recorded in PictureBookV3Implementation.md §6 for Stephen; not fixed
		/// here (scope), and not asserted as "expected" either.
		String[] read = new String[] { "chatConfig", "sdConfig", "slug", "title", "poemObjectIds", "maxLinesPerPage" };
		List<String> undeclared = new ArrayList<>();
		for(String f : read) {
			if(ms.getFieldSchema(f) == null) {
				undeclared.add(f);
			}
		}
		assertTrue("Read by ChapBookService but NOT declared on " + REQUEST_MODEL + " (RecordDeserializer drops them"
			+ " silently - KI-24): " + undeclared, undeclared.isEmpty());
	}

	@Test
	public void TestNestedSchemaDoesNotSuppressTheRequestSchema() {
		/// Found live 2026-10-09 running the P0-10c Playwright spec: ensureSchema decided "the body already
		/// has a schema" by SUBSTRING, so a body whose only "schema" was the NESTED sdConfig's was imported
		/// with no top-level model ("Invalid model name") and parseParams returned null - the endpoint
		/// answered 400 "Request body is required". The Ux752 render dialog sends exactly this shape
		/// (chapBook.js sets entity.schema = 'olio.sd.config'), so /render and /scene/{id}/generate were
		/// silently dropping the client's SD config too. The request schema must be injected whenever the
		/// ROOT object lacks one.
		BaseRecord r = parseBody("{\"sdConfig\": {\"schema\": \"olio.sd.config\", \"steps\": 33}}");
		assertNotNull("a body whose only schema is the nested sdConfig's must still parse", r);
		assertEquals(REQUEST_MODEL, r.getSchema());
		Object sdc = r.get("sdConfig");
		assertTrue(sdc instanceof BaseRecord);
		assertEquals(33, ((Number) ((BaseRecord) sdc).get("steps")).intValue());

		/// The render-dialog shape: chatConfig name + nested config.
		BaseRecord render = parseBody("{\"chatConfig\": \"contentAnalysis\", \"sdConfig\": {\"schema\": \"olio.sd.config\", \"cfg\": 4}}");
		assertNotNull(render);
		assertEquals("contentAnalysis", render.get("chatConfig"));
		assertEquals(4, ((Number) ((BaseRecord) render.get("sdConfig")).get("cfg")).intValue());

		/// A body that DOES carry its own root schema is left alone (not double-injected).
		BaseRecord explicit = parseBody("{\"schema\": \"" + REQUEST_MODEL + "\", \"slug\": \"s\"}");
		assertNotNull(explicit);
		assertEquals(REQUEST_MODEL, explicit.getSchema());
		assertEquals("s", explicit.get("slug"));
	}

	@Test
	public void TestSettingsBodyRoundTripsSparseSdConfig() {
		/// PUT /{bookObjectId}/settings — the body the Ux752 book panel sends: a sparse olio.sd.config.
		BaseRecord r = parseBody("{\"sdConfig\": {\"schema\": \"olio.sd.config\", \"steps\": 33, \"cfg\": 5,"
			+ " \"style\": \"art\", \"artStyle\": \"watercolor\", \"negativePrompt\": \"blurry\"}}");
		assertNotNull(r);
		Object sdc = r.get("sdConfig");
		assertTrue("sdConfig must deserialize as a nested olio.sd.config RECORD", sdc instanceof BaseRecord);
		BaseRecord cfg = (BaseRecord) sdc;
		assertEquals("olio.sd.config", cfg.getSchema());
		assertEquals(33, ((Number) cfg.get("steps")).intValue());
		assertEquals(5, ((Number) cfg.get("cfg")).intValue());
		assertEquals("art", cfg.get("style"));
		assertEquals("watercolor", cfg.get("artStyle"));
		assertEquals("blurry", cfg.get("negativePrompt"));
		/// Sparse means sparse: a field the client did not send must be ABSENT, not materialised at its
		/// schema default - otherwise PbBookUtil.setBookSdConfig would overwrite every unsent field.
		assertFalse("width was not sent and must not be present on the parsed record", cfg.hasField("width"));
		assertFalse("description was not sent and must not be present on the parsed record", cfg.hasField("description"));
	}

	@Test
	public void TestSettingsBodyCarriesForeignIdentityThroughToTheUtil() {
		/// The architect's PUT case: a client config carrying another row's identity. Transport passes it
		/// through unchanged; it is PbBookUtil.setBookSdConfig that discards it (SD_OVERRIDE_SKIP), which
		/// TestChapBookBookSettings#setBookSdConfigRoundTrip proves against the live DB. This test pins the
		/// transport half: the fields ARRIVE, so the util-side discard is load-bearing, not accidental.
		BaseRecord r = parseBody("{\"sdConfig\": {\"schema\": \"olio.sd.config\", \"objectId\": \"not-this-books-row\","
			+ " \"groupId\": 999999, \"name\": \"not-the-book-config\", \"steps\": 44}}");
		BaseRecord cfg = (BaseRecord) r.get("sdConfig");
		assertNotNull(cfg);
		assertEquals("not-this-books-row", cfg.get("objectId"));
		assertEquals(999999L, ((Number) cfg.get("groupId")).longValue());
		assertEquals("not-the-book-config", cfg.get("name"));
		assertEquals(44, ((Number) cfg.get("steps")).intValue());
	}

	@Test
	public void TestSettingsBodyWithoutSdConfigIsDistinguishable() {
		/// setBookSdConfig 400s when sdConfig is absent; the check is `instanceof BaseRecord`, so an
		/// omitted property must read back as null (not an empty record) or the guard can never fire.
		BaseRecord r = parseBody("{\"chatConfig\": \"cfg\"}");
		assertNotNull(r);
		assertFalse("An omitted sdConfig must be reported absent", r.hasField("sdConfig"));
		assertFalse("An omitted sdConfig must not read back as a record", r.get("sdConfig") instanceof BaseRecord);
	}

	@Test
	public void TestErrorBodyIsValidJsonForHostileMessages() throws Exception {
		/// Several branches interpolate e.getMessage() into the error body. A message carrying quotes,
		/// backslashes or newlines (a helpful-NPE text, a PSQL message) must still produce one JSON object
		/// whose "error" round-trips the original text.
		Method er = ChapBookService.class.getDeclaredMethod("errorResponse", int.class, String.class);
		er.setAccessible(true);
		String hostile = "Cannot invoke \"Object.toString()\" because \"x\" is null\n\tat C:\\path\\Foo.java";
		jakarta.ws.rs.core.Response resp = (jakarta.ws.rs.core.Response) er.invoke(new ChapBookService(), 500, hostile);
		assertEquals(500, resp.getStatus());
		String body = String.valueOf(resp.getEntity());
		com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
		assertTrue("Error body must be a JSON object, got: " + body, node.isObject());
		assertEquals("The error text must survive escaping intact", hostile, node.get("error").asText());

		jakarta.ws.rs.core.Response plain = (jakarta.ws.rs.core.Response) er.invoke(new ChapBookService(), 400, "Request body is required");
		assertEquals("Plain messages keep the exact wire form the Playwright spec matches on",
			"{\"error\":\"Request body is required\"}", String.valueOf(plain.getEntity()));
	}
}
