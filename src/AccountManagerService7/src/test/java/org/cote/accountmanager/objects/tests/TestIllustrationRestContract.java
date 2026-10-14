package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.rest.services.IllustrationService;
import org.junit.Test;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST contract of {@code IllustrationService} (PictureBook V3 design §27), asserted reflectively the
 * same way {@link TestPictureBookRestContract} asserts {@code PictureBookService}: every resource method
 * carries {@code @RolesAllowed} including both {@code admin} and {@code user}, and the Phase 2 route the
 * Ux and the live curl proof depend on ({@code GET /olio/illustration/models}) exists under that name.
 * <p>
 * Pure test — no {@code BaseTest}, no IOSystem, no container, no mocked request. What the route does is
 * covered where it lives: {@code PbServiceFacade.listModelsForTask} in Objects7's
 * {@code TestModelResolverLive}, and the deployed route by the live curl recorded in the tracking doc.
 */
public class TestIllustrationRestContract {
	public static final Logger logger = LogManager.getLogger(TestIllustrationRestContract.class);

	private static final List<Class<? extends java.lang.annotation.Annotation>> VERBS = Arrays.asList(
		GET.class, POST.class, PUT.class, DELETE.class, HEAD.class, OPTIONS.class);

	@Test
	public void TestEveryResourceMethodCarriesRolesAllowed() {
		List<String> missing = new ArrayList<>();
		int checked = 0;
		for(Method m : IllustrationService.class.getDeclaredMethods()) {
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
		logger.info("Checked @RolesAllowed on " + checked + " IllustrationService resource methods");
		/// A reflection miss would report zero missing and pass while asserting nothing.
		assertTrue("Reflection must have found at least the models route, found " + checked, checked >= 1);
		assertTrue("Every IllustrationService resource method must carry @RolesAllowed({\"admin\",\"user\"})."
			+ " Jersey's RolesAllowedDynamicFeature only enforces the annotation where it is present. Missing: " + missing,
			missing.isEmpty());
	}

	@Test
	public void TestClassDeclaresRolesAndPath() {
		DeclareRoles dr = IllustrationService.class.getAnnotation(DeclareRoles.class);
		assertNotNull("IllustrationService must carry @DeclareRoles like PictureBookService", dr);
		List<String> declared = Arrays.asList(dr.value());
		assertTrue("@DeclareRoles must include admin and user (got " + declared + ")",
			declared.contains("admin") && declared.contains("user"));
		Path p = IllustrationService.class.getAnnotation(Path.class);
		assertNotNull("IllustrationService must carry a class-level @Path", p);
		assertEquals("/olio/illustration", p.value());
	}

	@Test
	public void TestModelsRouteIsGetJsonAtModels() {
		Method m = findResourceMethod("listModels");
		assertNotNull("GET /olio/illustration/models must be implemented by listModels", m);
		assertTrue("listModels must be @GET", m.isAnnotationPresent(GET.class));
		assertEquals("/models", pathOf(m));
		Produces produces = m.getAnnotation(Produces.class);
		assertNotNull("listModels must declare @Produces", produces);
		assertEquals(Arrays.asList(MediaType.APPLICATION_JSON), Arrays.asList(produces.value()));
		assertEquals("listModels must return a jakarta.ws.rs.core.Response", jakarta.ws.rs.core.Response.class, m.getReturnType());
	}

	private Method findResourceMethod(String name) {
		for(Method m : IllustrationService.class.getDeclaredMethods()) {
			if(m.getName().equals(name)) {
				return m;
			}
		}
		return null;
	}

	private String pathOf(Method m) {
		Path p = m.getAnnotation(Path.class);
		return (p != null ? p.value() : "(class-level path)");
	}
}
