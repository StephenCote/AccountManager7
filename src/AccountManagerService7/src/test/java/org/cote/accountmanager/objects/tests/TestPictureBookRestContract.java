package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
import org.glassfish.jersey.uri.UriTemplate;
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

	/// Org-wide operations that are deliberately admin-only at the transport layer. Anything added here
	/// must still carry "admin" and must be justified in the service method's javadoc.
	private static final List<String> ADMIN_ONLY = Arrays.asList("scanOrgOrphans", "purgeOrgOrphans",
		/// V3 Phase 6 retention (P6-3b): the org-wide scan/apply; PbRetentionUtil re-checks
		/// AccountAdministrators membership behind the annotation.
		"scanOrgRetention", "applyOrgRetention");

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
			if(ADMIN_ONLY.contains(m.getName())) {
				assertEquals(m.getName() + ": admin-only endpoint must carry exactly {\"admin\"} (got " + roles + ")",
					Arrays.asList("admin"), roles);
			}
			else {
				assertTrue(m.getName() + ": @RolesAllowed must include \"user\" (got " + roles + ")",
					roles.contains("user"));
			}
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
			{ "createChapter", "POST" },
			/// Failed-passage retry (2026-10-01): re-runs only the chunks a completed extraction could not
			/// read, so a refusal no longer forces the whole work to be resubmitted.
			{ "extractRetryFailed", "POST" },
			/// V3 Phase 1 review state machine (P1-3/P1-4, 2026-10-11): the Ux review surface. All four are
			/// book-scoped and gated by PbServiceFacade.requireBookUpdate (writes) / requireBook (the queue).
			{ "selectArtifact", "PUT" },
			{ "approveArtifact", "PUT" },
			{ "rejectArtifact", "PUT" },
			{ "listReviewQueue", "GET" },
			/// V3 Phase 1 auto-approve switch (P1-5, 2026-10-11): the book setting the review queue and the
			/// executor read. The PUT refuses a non-boolean body itself (raw-body parse, not parseParams).
			{ "getAutoApprove", "GET" },
			{ "setAutoApprove", "PUT" },
			/// V3 Phase 1 async render jobs (P1-6, 2026-10-11): 202 + {jobId, runObjectId} for the two
			/// submits, 200 for the cooperative cancel; all three book-scoped behind requireBookUpdate.
			{ "renderScene", "POST" },
			{ "renderAll", "POST" },
			{ "cancelRun", "POST" },
			/// V3 Phase 1 restart recovery (P1-7, 2026-10-11): one run read (the Resume affordance's source of
			/// truth) and the resume submit, which re-plans an INTERRUPTED run's execution set as a new run.
			{ "readRun", "GET" },
			{ "resumeRun", "POST" },
			/// V3 Phase 1 impact read (P1-10 S0, 2026-10-11): what an edit to one bound record touches in a
			/// book. A read behind requireBook, on the listStale pattern; not gated by the render-path switch.
			{ "affectedByRecord", "GET" },
			/// V3 Phase 2 style (P2-3, 2026-10-13): the §4/§4.1 book style. GET behind requireBook; PUT and the
			/// lock POST behind requireBookUpdate; neither gated by the render-path switch (the one model
			/// policy applies the style's task pins to PB1 renders too).
			{ "getStyle", "GET" },
			{ "putStyle", "PUT" },
			{ "lockStyle", "POST" },
			/// V3 Phase 6 retention (P6-3b, 2026-10-29): dry run and apply per book (requireBook / requireBookUpdate)
			/// and organization-wide (admin-only, see ADMIN_ONLY). No scheduler: both applies are manual.
			{ "scanRetention", "GET" },
			{ "applyRetention", "POST" },
			{ "scanOrgRetention", "GET" },
			{ "applyOrgRetention", "POST" }
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
			Class<? extends java.lang.annotation.Annotation> verb = verbClass(e[1]);
			assertNotNull(e[0] + " must be annotated @" + e[1], found.getAnnotation(verb));
			assertNotNull(e[0] + " must carry @RolesAllowed", found.getAnnotation(RolesAllowed.class));
			assertNotNull(e[0] + " must declare a @Path", found.getAnnotation(Path.class));
		}
	}

	private static Class<? extends java.lang.annotation.Annotation> verbClass(String verb) {
		switch(verb) {
			case "GET": return GET.class;
			case "PUT": return PUT.class;
			case "DELETE": return DELETE.class;
			default: return POST.class;
		}
	}

	/**
	 * The review routes' path contract, which the Ux and the Playwright suite address by literal path: one
	 * book-scoped {@code /artifact/{id}/<action>} per write and {@code /review-queue?filter=} for the read.
	 * A renamed segment would 404 from the client with nothing else failing, so the paths are pinned here.
	 */
	@Test
	public void TestReviewRoutePaths() {
		String[][] expected = new String[][] {
			{ "selectArtifact", "/artifact/{artifactObjectId:[0-9A-Za-z\\-]+}/select" },
			{ "approveArtifact", "/artifact/{artifactObjectId:[0-9A-Za-z\\-]+}/approve" },
			{ "rejectArtifact", "/artifact/{artifactObjectId:[0-9A-Za-z\\-]+}/reject" },
			{ "listReviewQueue", "/review-queue" },
			{ "getAutoApprove", "/settings/auto-approve" },
			{ "setAutoApprove", "/settings/auto-approve" },
			/// P1-6 async render: the scene submit sits beside the synchronous /generate, the book-wide
			/// submit is a flat segment, and cancel is addressed through the run so the book grant applies.
			{ "renderScene", "/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/render" },
			{ "renderAll", "/render-all" },
			{ "cancelRun", "/run/{runObjectId:[0-9A-Za-z\\-]+}/cancel" },
			/// P1-7: the run read is the bare run segment; resume hangs off it exactly as cancel does.
			{ "readRun", "/run/{runObjectId:[0-9A-Za-z\\-]+}" },
			{ "resumeRun", "/run/{runObjectId:[0-9A-Za-z\\-]+}/resume" },
			/// P1-10 S0(d): the impact read is addressed by model name then objectId under the book.
			{ "affectedByRecord", "/affected-by/{refModel:[a-z]+(\\.[A-Za-z0-9]+)+}/{refObjectId:[0-9A-Za-z\\-]+}" },
			/// P2-3: the book style lives under the book; GET and PUT share one path, the lock is a sub-resource.
			{ "getStyle", "/style" },
			{ "putStyle", "/style" },
			{ "lockStyle", "/style/lock" },
			/// P6-3b: the per-book retention dry run and apply hang off the book, so the book grant applies.
			{ "scanRetention", "/retention" },
			{ "applyRetention", "/retention/apply" }
		};
		for(String[] e : expected) {
			Method found = null;
			for(Method m : PictureBookService.class.getDeclaredMethods()) {
				if(m.getName().equals(e[0])) {
					found = m;
					break;
				}
			}
			assertNotNull(e[0] + " must exist", found);
			String path = found.getAnnotation(Path.class).value();
			assertTrue(e[0] + " must be book-scoped (path starts with /{bookObjectId...}): " + path,
				path.startsWith("/{bookObjectId"));
			assertTrue(e[0] + " path must end with " + e[1] + " (got " + path + ")", path.endsWith(e[1]));
		}
	}

	/**
	 * V3 Phase 6 retention (P6-3b): the four routes' shape. The two dry runs are GETs whose policy knobs are
	 * {@code String} query parameters (so a non-integer is the service's own 400 via {@code parseIntParam},
	 * not Jersey's 404 for an unconvertible {@code Integer}); the two applies are JSON-in POSTs whose knobs
	 * ride in the body. The org-wide pair is NOT book-scoped and is admin-only at the transport layer; the
	 * AccountAdministrators re-check behind it is covered by Objects7's {@code TestPbRetentionSweep.case04}.
	 */
	@Test
	public void TestRetentionRoutesShape() {
		Method scan = method("scanRetention");
		Method apply = method("applyRetention");
		Method scanOrg = method("scanOrgRetention");
		Method applyOrg = method("applyOrgRetention");

		assertEquals("/{bookObjectId:[0-9A-Za-z\\-]+}/retention", pathOf(scan));
		assertEquals("/{bookObjectId:[0-9A-Za-z\\-]+}/retention/apply", pathOf(apply));
		assertEquals("/retention/org", pathOf(scanOrg));
		assertEquals("/retention/org/apply", pathOf(applyOrg));

		for(Method get : new Method[] { scan, scanOrg }) {
			assertTrue(get.getName() + " must be @GET", get.isAnnotationPresent(GET.class));
			assertNull(get.getName() + " is a dry run: no body, so no @Consumes", get.getAnnotation(jakarta.ws.rs.Consumes.class));
			List<String> queryParams = new ArrayList<>();
			for(java.lang.reflect.Parameter p : get.getParameters()) {
				jakarta.ws.rs.QueryParam qp = p.getAnnotation(jakarta.ws.rs.QueryParam.class);
				if(qp != null) {
					assertEquals(get.getName() + " @QueryParam(" + qp.value() + ") must be a String so a non-integer reaches"
						+ " parseIntParam and 400s instead of Jersey 404ing the whole route", String.class, p.getType());
					queryParams.add(qp.value());
				}
				else {
					assertTrue(get.getName() + ": every other parameter is a @PathParam or @Context (got '" + p.getName() + "')",
						p.getAnnotation(jakarta.ws.rs.PathParam.class) != null || p.getAnnotation(jakarta.ws.rs.core.Context.class) != null);
				}
			}
			assertEquals(get.getName() + " reads exactly the two policy knobs as query parameters",
				Arrays.asList("keepAttempts", "deleteBytesAfterDays"), queryParams);
		}
		for(Method post : new Method[] { apply, applyOrg }) {
			assertTrue(post.getName() + " must be @POST", post.isAnnotationPresent(POST.class));
			jakarta.ws.rs.Consumes consumes = post.getAnnotation(jakarta.ws.rs.Consumes.class);
			assertNotNull(post.getName() + " must declare @Consumes", consumes);
			assertEquals(Arrays.asList(jakarta.ws.rs.core.MediaType.APPLICATION_JSON), Arrays.asList(consumes.value()));
			int bodies = 0;
			for(java.lang.reflect.Parameter p : post.getParameters()) {
				assertNull(post.getName() + " takes its knobs in the body, never as a @QueryParam (got '" + p.getName() + "')",
					p.getAnnotation(jakarta.ws.rs.QueryParam.class));
				if(p.getAnnotation(jakarta.ws.rs.PathParam.class) == null && p.getAnnotation(jakarta.ws.rs.core.Context.class) == null) {
					assertEquals("the only un-annotated parameter is the String JSON body", String.class, p.getType());
					bodies++;
				}
			}
			assertEquals(post.getName() + ": one JSON body", 1, bodies);
		}
		for(Method m : new Method[] { scan, apply, scanOrg, applyOrg }) {
			jakarta.ws.rs.Produces produces = m.getAnnotation(jakarta.ws.rs.Produces.class);
			assertNotNull(m.getName() + " must declare @Produces", produces);
			assertEquals(Arrays.asList(jakarta.ws.rs.core.MediaType.APPLICATION_JSON), Arrays.asList(produces.value()));
			assertEquals(m.getName() + " must return a jakarta.ws.rs.core.Response", jakarta.ws.rs.core.Response.class, m.getReturnType());
		}
		List<String> bookRoles = Arrays.asList(method("scanRetention").getAnnotation(RolesAllowed.class).value());
		assertTrue("per-book retention is for any book member: " + bookRoles, bookRoles.contains("admin") && bookRoles.contains("user"));
		assertEquals(Arrays.asList("admin"), Arrays.asList(scanOrg.getAnnotation(RolesAllowed.class).value()));
		assertEquals(Arrays.asList("admin"), Arrays.asList(applyOrg.getAnnotation(RolesAllowed.class).value()));
	}

	/**
	 * {@code parseIntParam}, the one place a retention GET decides between "absent → default", "an integer
	 * → that integer" and "anything else → 400". A private static with no container dependency, reached by
	 * reflection as {@code TestEscapeJson} reaches {@code escapeJson}. The range check (0 / negative / over
	 * the maximum → 400) is the selector's, covered by {@code TestPbRetentionSweep.case04}.
	 */
	@Test
	public void TestParseIntParamIsAbsentOrIntegerOr400() throws Exception {
		Method m = PictureBookService.class.getDeclaredMethod("parseIntParam", String.class, String.class);
		m.setAccessible(true);
		assertNull("absent → null (the selector default)", m.invoke(null, "keepAttempts", null));
		assertNull("blank → null, same as absent", m.invoke(null, "keepAttempts", "   "));
		assertEquals("an integer arrives as that integer", Integer.valueOf(7), m.invoke(null, "keepAttempts", " 7 "));
		assertEquals("zero is passed through for the selector to refuse, not treated as absent", Integer.valueOf(0),
			m.invoke(null, "deleteBytesAfterDays", "0"));
		try {
			m.invoke(null, "keepAttempts", "three");
			fail("a non-integer must be a 400");
		}
		catch(java.lang.reflect.InvocationTargetException ite) {
			assertTrue("the service's own PictureBookException, not a NumberFormatException escaping to Jersey",
				ite.getCause() instanceof org.cote.accountmanager.olio.picturebook.PictureBookException);
			org.cote.accountmanager.olio.picturebook.PictureBookException pbe =
				(org.cote.accountmanager.olio.picturebook.PictureBookException) ite.getCause();
			assertEquals(400, pbe.getStatus());
			assertTrue("the message names the parameter: " + pbe.getMessage(), pbe.getMessage().contains("keepAttempts"));
		}
	}

	private Method method(String name) {
		for(Method m : PictureBookService.class.getDeclaredMethods()) {
			if(m.getName().equals(name)) {
				return m;
			}
		}
		fail("PictureBookService." + name + " must exist");
		return null;
	}

	/**
	 * P1-10 S0(d): the {@code affected-by} route's exact template, and what its {@code refModel} segment
	 * admits. The segment regex is the first line of defence against an arbitrary string reaching the stale
	 * scan (the facade refuses an unregistered model name as the second), so it is pinned here and exercised
	 * both as a bare {@link Pattern} on the segment and through Jersey's own {@link UriTemplate} matcher,
	 * which is what parses the nested {@code (\.[A-Za-z0-9]+)+} group at runtime. This is template-level
	 * matching, not a live request: a rejected segment is a Jersey 404 in production, and that is proven
	 * against the container separately.
	 */
	@Test
	public void TestAffectedByRouteTemplateAndRefModelSegment() {
		final String template = "/{bookObjectId:[0-9A-Za-z\\-]+}/affected-by/{refModel:[a-z]+(\\.[A-Za-z0-9]+)+}/{refObjectId:[0-9A-Za-z\\-]+}";
		final String segmentRegex = "[a-z]+(\\.[A-Za-z0-9]+)+";
		Method found = null;
		for(Method m : PictureBookService.class.getDeclaredMethods()) {
			if(m.getName().equals("affectedByRecord")) {
				found = m;
				break;
			}
		}
		assertNotNull("affectedByRecord must exist", found);
		assertNotNull("affectedByRecord must be @GET", found.getAnnotation(GET.class));
		assertEquals("the exact @Path template is the client contract", template, found.getAnnotation(Path.class).value());
		assertEquals("@RolesAllowed admits user as well as admin", Arrays.asList("admin", "user"),
			Arrays.asList(found.getAnnotation(RolesAllowed.class).value()));
		for(java.lang.reflect.Parameter p : found.getParameters()) {
			boolean injected = p.getAnnotation(jakarta.ws.rs.PathParam.class) != null
				|| p.getAnnotation(jakarta.ws.rs.core.Context.class) != null;
			assertTrue("affectedByRecord is a read and takes no body (got '" + p.getName() + "')", injected);
		}

		Pattern segment = Pattern.compile(segmentRegex);
		String[] accepted = new String[] { "olio.charPerson", "olio.pb.node", "data.data", "system.user" };
		String[] rejected = new String[] { "olio", "Olio.Foo", "olio..x", "olio.", ".olio", "olio.char-Person", "olio/charPerson" };
		for(String s : accepted) {
			assertTrue("segment regex must accept a registered-style model name: " + s, segment.matcher(s).matches());
		}
		for(String s : rejected) {
			assertFalse("segment regex must reject: " + s, segment.matcher(s).matches());
		}

		/// Jersey's matcher, on the whole template, so the nested group inside the segment is parsed the way
		/// the runtime parses it - a Pattern on the segment alone would not catch a group-counting defect.
		UriTemplate ut = new UriTemplate(template);
		Map<String, String> vars = new HashMap<>();
		assertTrue("Jersey must match a well-formed path", ut.match("/book-1/affected-by/olio.charPerson/abc-123", vars));
		assertEquals("book-1", vars.get("bookObjectId"));
		assertEquals("olio.charPerson", vars.get("refModel"));
		assertEquals("abc-123", vars.get("refObjectId"));
		for(String s : rejected) {
			vars.clear();
			assertFalse("Jersey must reject the refModel segment: " + s, ut.match("/book-1/affected-by/" + s + "/abc-123", vars));
		}
		/// Noted, not a change: a digit-bearing FIRST segment (iso42001.x) is outside the regex. No olio.pb
		/// binding targets such a model, and the generic /rest/model routes are the place that admits them.
		assertFalse(ut.match("/book-1/affected-by/iso42001.testConfig/abc-123", vars));
	}

	/**
	 * The async render routes take no body and must not: the SD backend comes from the servlet context
	 * (boot-pinned {@code sd.*} init-params), never from the caller, so a client cannot point a render at a
	 * server of its choosing. Pinned here because the synchronous {@code /generate} beside them DOES read a
	 * nested {@code sdConfig} override, and the next copy-paste would carry that parameter across.
	 * <p>
	 * The one admitted extra is a primitive-boolean {@code @QueryParam} ({@code renderScene}'s
	 * {@code ?force=true}): a flag cannot name a server or carry a config. A String/record query parameter
	 * is refused for the same reason a body is.
	 */
	@Test
	public void TestAsyncRenderRoutesTakeNoBody() {
		boolean sawForce = false;
		for(String name : new String[] { "renderScene", "renderAll", "cancelRun", "resumeRun", "readRun" }) {
			Method found = null;
			for(Method m : PictureBookService.class.getDeclaredMethods()) {
				if(m.getName().equals(name)) {
					found = m;
					break;
				}
			}
			assertNotNull(name + " must exist", found);
			for(java.lang.reflect.Parameter p : found.getParameters()) {
				jakarta.ws.rs.QueryParam qp = p.getAnnotation(jakarta.ws.rs.QueryParam.class);
				boolean booleanFlag = qp != null && p.getType() == boolean.class;
				if(booleanFlag && "force".equals(qp.value()) && "renderScene".equals(name)) {
					sawForce = true;
				}
				boolean injected = p.getAnnotation(jakarta.ws.rs.PathParam.class) != null
					|| p.getAnnotation(jakarta.ws.rs.core.Context.class) != null
					|| booleanFlag;
				assertTrue(name + " must take only @PathParam/@Context parameters (or a primitive-boolean @QueryParam flag)"
					+ " - a body or a String query parameter would let the caller supply the SD server or config (got '"
					+ p.getName() + "' of " + p.getType().getSimpleName() + ")",
					injected);
			}
		}
		assertTrue("renderScene must declare the @QueryParam(\"force\") boolean flag (explicit per-scene re-render)", sawForce);
	}

	/**
	 * {@code PbRecoveryUtil.sweepInterrupted} reads raw (no principal) and then writes as each record's owner,
	 * which is acceptable only because nothing a request can reach calls it. That is a convention, so pin it:
	 * neither the REST resource nor the facade may reference the class. Checked on the compiled constant pool
	 * (every class a method body touches lands there), with the boot listener as the positive control so the
	 * scan cannot pass by seeing nothing.
	 */
	@Test
	public void TestRecoverySweepNotRequestReachable() throws Exception {
		final String sweep = "org/cote/accountmanager/olio/picturebook/PbRecoveryUtil";
		assertTrue("positive control: RestServiceEventListener must reference the sweep (it is the one caller)",
			referencesClass(classBytes(org.cote.rest.config.RestServiceEventListener.class), sweep));
		assertFalse("PictureBookService must not reference PbRecoveryUtil - the sweep's principal-less reads are boot-only",
			referencesClass(classBytes(PictureBookService.class), sweep));
		assertFalse("PbServiceFacade must not reference PbRecoveryUtil - the sweep's principal-less reads are boot-only",
			referencesClass(classBytes(org.cote.accountmanager.olio.picturebook.PbServiceFacade.class), sweep));
	}

	private static byte[] classBytes(Class<?> c) throws java.io.IOException {
		String resource = c.getName().replace('.', '/') + ".class";
		try(java.io.InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
			assertNotNull("class bytes for " + c.getName(), in);
			return in.readAllBytes();
		}
	}

	private static boolean referencesClass(byte[] classFile, String internalName) {
		/// Constant-pool CONSTANT_Utf8 entries are modified UTF-8; for an ASCII internal name that is byte-identical
		/// to ISO-8859-1, so a plain substring search over the raw bytes is exact.
		return new String(classFile, java.nio.charset.StandardCharsets.ISO_8859_1).contains(internalName);
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
			"copyRecordObjectIds",
			/// extract-scenes-only (N-series) and extract-retry-failed
			"seriesObjectId",
			/// V3 Phase 6 retention (P6-3b): POST .../retention/apply and /retention/org/apply. Undeclared
			/// when first written - the body was silently ignored and the defaults always applied.
			"keepAttempts", "deleteBytesAfterDays"
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

	/**
	 * V3 Phase 6 retention (P6-3b): the apply body. Both knobs are declared ints, so both KI-24 and KI-25
	 * apply: undeclared they were dropped and the defaults always won; declared, an omitted one reads back as
	 * 0 and {@code hasField} is the only presence signal. The service reads them through {@code optionalInt}
	 * for that reason - 0 is a legitimate {@code deleteBytesAfterDays} ("delete due bytes now") but would be
	 * catastrophic as the reading of an omitted one, and 0 is a 400 for {@code keepAttempts}.
	 */
	@Test
	public void TestRetentionApplyBodyRoundTrips() throws Exception {
		BaseRecord both = parseBody("{\"keepAttempts\": 2, \"deleteBytesAfterDays\": 0}");
		assertNotNull(both);
		assertTrue("keepAttempts must survive deserialization", both.hasField("keepAttempts"));
		assertTrue("deleteBytesAfterDays must survive deserialization", both.hasField("deleteBytesAfterDays"));
		assertEquals(2, ((Number) both.get("keepAttempts")).intValue());
		assertEquals("an explicit 0 arrives as 0 - 'delete the due bytes now'", 0, ((Number) both.get("deleteBytesAfterDays")).intValue());

		BaseRecord one = parseBody("{\"keepAttempts\": 5}");
		assertTrue(one.hasField("keepAttempts"));
		assertEquals(5, ((Number) one.get("keepAttempts")).intValue());
		assertFalse("an omitted deleteBytesAfterDays must be reported ABSENT by hasField - read any other way it"
			+ " is 0, which would delete every archived artifact's bytes immediately", one.hasField("deleteBytesAfterDays"));
		assertEquals("...and this is what the non-hasField read sees", 0, ((Number) one.get("deleteBytesAfterDays")).intValue());
		assertFalse("an omitted keepAttempts must be reported ABSENT by hasField - read any other way it is 0,"
			+ " which the selector refuses with a 400 on every body that leaves it out",
			parseBody("{\"deleteBytesAfterDays\": 7}").hasField("keepAttempts"));

		/// The service's own read path, on the real PictureBookService code rather than a restatement of it:
		/// parseParams (schema injection + deserialize) then optionalInt (hasField-guarded). Reached by
		/// reflection as TestChapBookRestContract reaches ChapBookService.parseParams.
		PictureBookService svc = new PictureBookService();
		Method parse = PictureBookService.class.getDeclaredMethod("parseParams", String.class);
		parse.setAccessible(true);
		Method optionalInt = PictureBookService.class.getDeclaredMethod("optionalInt", BaseRecord.class, String.class);
		optionalInt.setAccessible(true);

		BaseRecord sent = (BaseRecord) parse.invoke(svc, "{\"keepAttempts\": 2, \"deleteBytesAfterDays\": 0}");
		assertNotNull(sent);
		assertEquals(Integer.valueOf(2), optionalInt.invoke(svc, sent, "keepAttempts"));
		assertEquals("an explicit 0 is handed to the facade as 0, not swallowed as absent",
			Integer.valueOf(0), optionalInt.invoke(svc, sent, "deleteBytesAfterDays"));
		BaseRecord partial = (BaseRecord) parse.invoke(svc, "{\"keepAttempts\": 2}");
		assertNotNull(partial);
		assertEquals(Integer.valueOf(2), optionalInt.invoke(svc, partial, "keepAttempts"));
		assertNull("the omitted knob reaches the facade as null (= the selector default), never as 0",
			optionalInt.invoke(svc, partial, "deleteBytesAfterDays"));

		/// A literal {} body: ensureSchema splices the schema in as `{"schema":"...",}` - a trailing comma -
		/// so parseParams returns null (WARN-logged) rather than an empty record. That is pre-existing
		/// behaviour shared by every optional-body endpoint on this service and is deliberately not changed
		/// here; what matters for retention is that a null params record still reads as "both absent".
		BaseRecord empty = (BaseRecord) parse.invoke(svc, "{}");
		assertNull("a null params record (blank or unparseable body) must read as absent, not throw", optionalInt.invoke(svc, empty, "keepAttempts"));
		assertNull(optionalInt.invoke(svc, empty, "deleteBytesAfterDays"));
		assertNull(optionalInt.invoke(svc, (BaseRecord) parse.invoke(svc, ""), "keepAttempts"));
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

	/**
	 * The plan inspector (V3 §12-§15, §18; P2-4): {@code POST /{book}/scene/{scene}/plan} exists under that
	 * name, is JSON in and out, carries {@code @RolesAllowed} with both roles, and takes exactly the two path
	 * ids, the JSON body and the two injected contexts - never a query parameter, so the SD server and the
	 * API type can only come from the deployment, as the render routes already pin.
	 */
	@Test
	public void TestPlanRouteIsPostJsonWithRolesAllowed() {
		Method m = null;
		for(Method c : PictureBookService.class.getDeclaredMethods()) {
			if(c.getName().equals("planScene")) {
				m = c;
				break;
			}
		}
		assertNotNull("POST /{book}/scene/{scene}/plan must be implemented by planScene", m);
		assertTrue("planScene must be @POST", m.isAnnotationPresent(POST.class));
		assertEquals("/{bookObjectId:[0-9A-Za-z\\-]+}/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/plan", pathOf(m));
		jakarta.ws.rs.Produces produces = m.getAnnotation(jakarta.ws.rs.Produces.class);
		assertNotNull("planScene must declare @Produces", produces);
		assertEquals(Arrays.asList(jakarta.ws.rs.core.MediaType.APPLICATION_JSON), Arrays.asList(produces.value()));
		jakarta.ws.rs.Consumes consumes = m.getAnnotation(jakarta.ws.rs.Consumes.class);
		assertNotNull("planScene must declare @Consumes", consumes);
		assertEquals(Arrays.asList(jakarta.ws.rs.core.MediaType.APPLICATION_JSON), Arrays.asList(consumes.value()));
		RolesAllowed ra = m.getAnnotation(RolesAllowed.class);
		assertNotNull("planScene must carry @RolesAllowed", ra);
		List<String> roles = Arrays.asList(ra.value());
		assertTrue("planScene @RolesAllowed must include admin and user (got " + roles + ")", roles.contains("admin") && roles.contains("user"));
		assertEquals("planScene must return a jakarta.ws.rs.core.Response", jakarta.ws.rs.core.Response.class, m.getReturnType());

		int pathParams = 0;
		int contexts = 0;
		int bodies = 0;
		for(java.lang.reflect.Parameter p : m.getParameters()) {
			assertNull("planScene must not read a @QueryParam (got '" + p.getName() + "'): the SD server and API type"
				+ " come from the deployment, never the caller", p.getAnnotation(jakarta.ws.rs.QueryParam.class));
			if(p.getAnnotation(jakarta.ws.rs.PathParam.class) != null) {
				pathParams++;
			}
			else if(p.getAnnotation(jakarta.ws.rs.core.Context.class) != null) {
				contexts++;
			}
			else {
				assertEquals("the only un-annotated parameter is the String JSON body", String.class, p.getType());
				bodies++;
			}
		}
		assertEquals("bookObjectId + sceneObjectId", 2, pathParams);
		assertEquals("HttpServletRequest + ServletContext", 2, contexts);
		assertEquals("one JSON body", 1, bodies);
	}

	/**
	 * The plan executor (V3 §12, §16, §20; P2-5): {@code POST /{book}/scene/{scene}/render-plan} exists under
	 * that name, produces JSON, carries {@code @RolesAllowed} with both roles, and takes exactly the two path
	 * ids and the two injected contexts - no body (the scene's persisted text is the input) and never a query
	 * parameter, so the SD server and the API type can only come from the deployment.
	 */
	@Test
	public void TestRenderPlanRouteIsPostJsonWithRolesAllowedAndNoBody() {
		Method m = null;
		for(Method c : PictureBookService.class.getDeclaredMethods()) {
			if(c.getName().equals("renderPlanScene")) {
				m = c;
				break;
			}
		}
		assertNotNull("POST /{book}/scene/{scene}/render-plan must be implemented by renderPlanScene", m);
		assertTrue("renderPlanScene must be @POST", m.isAnnotationPresent(POST.class));
		assertEquals("/{bookObjectId:[0-9A-Za-z\\-]+}/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/render-plan", pathOf(m));
		jakarta.ws.rs.Produces produces = m.getAnnotation(jakarta.ws.rs.Produces.class);
		assertNotNull("renderPlanScene must declare @Produces", produces);
		assertEquals(Arrays.asList(jakarta.ws.rs.core.MediaType.APPLICATION_JSON), Arrays.asList(produces.value()));
		RolesAllowed ra = m.getAnnotation(RolesAllowed.class);
		assertNotNull("renderPlanScene must carry @RolesAllowed", ra);
		List<String> roles = Arrays.asList(ra.value());
		assertTrue("renderPlanScene @RolesAllowed must include admin and user (got " + roles + ")", roles.contains("admin") && roles.contains("user"));
		assertEquals("renderPlanScene must return a jakarta.ws.rs.core.Response", jakarta.ws.rs.core.Response.class, m.getReturnType());

		int pathParams = 0;
		int contexts = 0;
		for(java.lang.reflect.Parameter p : m.getParameters()) {
			assertNull("renderPlanScene must not read a @QueryParam (got '" + p.getName() + "'): the SD server and API type"
				+ " come from the deployment, never the caller", p.getAnnotation(jakarta.ws.rs.QueryParam.class));
			if(p.getAnnotation(jakarta.ws.rs.PathParam.class) != null) {
				pathParams++;
			}
			else if(p.getAnnotation(jakarta.ws.rs.core.Context.class) != null) {
				contexts++;
			}
			else {
				fail("renderPlanScene takes no body: unexpected parameter '" + p.getName() + "' of " + p.getType());
			}
		}
		assertEquals("bookObjectId + sceneObjectId", 2, pathParams);
		assertEquals("HttpServletRequest + ServletContext", 2, contexts);
	}
}
