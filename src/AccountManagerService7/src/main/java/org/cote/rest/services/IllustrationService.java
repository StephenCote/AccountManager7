package org.cote.rest.services;

import java.util.LinkedHashMap;
import java.util.Map;

import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ServerConfigUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * IllustrationService — thin REST transport for the product-agnostic illustration service
 * (PictureBook V3 design §27), the surface reimage, PictureBook, ChapBook and chat all resolve
 * models through. Auto-registered via RestServiceConfig packages("org.cote.rest.services").
 * <p>
 * No business logic here: every route reads the principal, hands the deployment's SD server
 * values to {@link PbServiceFacade} and serializes the DTO it returns — the same split as
 * {@code PictureBookService}. The SD server URL and API type come from this WAR's init-params
 * ({@code sd.server.apiType}; {@code sd.server} through {@code ServerConfigUtil}) and travel with
 * the call; nothing is pushed into process-global state.
 *
 * Endpoints under /olio/illustration:
 *   GET /models?task=&style=   — installed checkpoints by task, with the resolver's pick (§16)
 */
@DeclareRoles({"admin", "user"})
@Path("/olio/illustration")
public class IllustrationService {

	/**
	 * GET /models?task={portrait|location|sceneDirect|inpaint|harmonize|upscale}&style={styleObjectId}
	 * <p>
	 * 200 with the models-by-task DTO; 400 unknown task; 404 unreadable or absent style; 500 when the SD
	 * server is not configured. A failed inventory read is a 200 whose {@code inventory.error} and
	 * {@code resolution.reason = INVENTORY_UNAVAILABLE} say so — the server answered, the SD server did not.
	 */
	@RolesAllowed({"admin", "user"})
	@GET
	@Path("/models")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listModels(@QueryParam("task") String task, @QueryParam("style") String style,
			@Context HttpServletRequest request, @Context ServletContext context) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		try {
			return Response.status(200).entity(JSONUtil.exportObject(
				PbServiceFacade.listModelsForTask(user, task, resolveSdApiType(context), resolveSdServer(context), style)
			)).build();
		}
		catch(PictureBookException e) {
			return errorResponse(e.getStatus(), e.getMessage());
		}
	}

	private static String resolveSdApiType(ServletContext context) {
		return context.getInitParameter("sd.server.apiType");
	}

	private static String resolveSdServer(ServletContext context) {
		return ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_SD, context.getInitParameter("sd.server"));
	}

	/// The message can echo caller input (the task name), so it goes through the JSON mapper, which
	/// escapes every control character; a hand-rolled escaper that only knows quote, backslash, LF and
	/// CR emits invalid JSON for a tab.
	private static Response errorResponse(int status, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", message);
		return Response.status(status).entity(JSONUtil.exportObject(body)).build();
	}
}
