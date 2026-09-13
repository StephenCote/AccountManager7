package org.cote.rest.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JobService — polling transport for the background jobs registered in Objects7's
 * {@code AsyncJobRegistry}. Auto-registered via RestServiceConfig
 * {@code packages("org.cote.rest.services")}.
 *
 * <p><b>Why polling is the source of truth, not the WebSocket.</b> Server-push already exists
 * ({@code PictureBookProgressNotifier} → {@code WebSocketService.chirpUser}) and is genuinely
 * useful for latency, but it cannot be the completion channel: {@code chirpUser} silently drops the
 * message when no socket is live, {@code urnToSession} holds only ONE session per user URN (so a
 * second browser tab evicts the first), and every Playwright spec stubs {@code window.WebSocket}
 * outright. A result that is only pushed is a result that can be lost — which is the exact failure
 * this whole change removes. So the job's status and its finished payload are always retrievable
 * here, and push remains an accelerator.
 *
 * <p><b>One controller for every feature.</b> A jobId is globally unique and ownership-scoped, so
 * PictureBook and ChapBook share these routes rather than each growing a duplicate copy of the same
 * three endpoints.
 *
 * <p>Pure transport, per {@code .claude/rules/architecture.md}: every method maps a registry call
 * to a response body and holds no business logic. Ownership is enforced inside the registry — it
 * returns nothing for another principal's job, so a foreign jobId is indistinguishable from an
 * unknown one and these endpoints cannot be used to probe what other users are running.
 */
@DeclareRoles({ "admin", "user" })
@Path("/job")
public class JobService {
	public static final Logger logger = LogManager.getLogger(JobService.class);

	/**
	 * GET /job/{jobId} — status, progress and (when finished) the result.
	 *
	 * <p>Returns 404 for an unknown OR non-owned job, deliberately the same answer for both.
	 */
	@RolesAllowed({ "admin", "user" })
	@GET
	@Path("/{jobId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getJob(@PathParam("jobId") String jobId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		AsyncJob job = AsyncJobRegistry.get(user, jobId);
		if (job == null) {
			return Response.status(404).entity("{\"error\":\"Job not found\"}").build();
		}
		return Response.status(200).entity(JSONUtil.exportObject(describe(job, true))).build();
	}

	/**
	 * POST /job/{jobId}/cancel — request cooperative cancellation.
	 *
	 * <p>{@code cancelled:false} is <b>not</b> an error: it also covers a job that already
	 * finished, was already cancelled, is unknown, or belongs to someone else. All four answer
	 * identically on purpose. Partial work completed before the cancel is retained and still
	 * collectable from {@code GET /job/{jobId}} — the loops break and return what they have rather
	 * than throwing it away.
	 */
	@RolesAllowed({ "admin", "user" })
	@POST
	@Path("/{jobId:[0-9A-Za-z\\-]+}/cancel")
	@Produces(MediaType.APPLICATION_JSON)
	public Response cancelJob(@PathParam("jobId") String jobId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		boolean cancelled = AsyncJobRegistry.cancel(user, jobId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("jobId", jobId);
		out.put("cancelled", cancelled);
		return Response.status(200).entity(JSONUtil.exportObject(out)).build();
	}

	/**
	 * GET /job — the caller's own live and recently-completed jobs, newest first.
	 *
	 * <p>This is what lets a reloaded or reopened client <b>reattach</b> to a run that is still in
	 * progress instead of starting a second one against the same document. Results are omitted from
	 * the listing (a scene list is large); fetch the individual job to collect one.
	 */
	@RolesAllowed({ "admin", "user" })
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response listJobs(@QueryParam("startRecord") @DefaultValue("0") int startRecord,
			@QueryParam("recordCount") @DefaultValue("0") int recordCount,
			@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		List<AsyncJob> all = AsyncJobRegistry.list(user);

		// Paging, newest first (the registry already sorts that way). The list is bounded by
		// AsyncJobRegistry.MAX_RETAINED_JOBS so it can never be huge, but "small today" is not a
		// contract — a caller that only wants the current run should not have to receive every
		// retained job to find it. Defaults return everything, so existing callers are unaffected.
		int from = Math.max(0, startRecord);
		if (from > all.size()) {
			from = all.size();
		}
		int to = (recordCount > 0) ? Math.min(all.size(), from + recordCount) : all.size();

		List<Map<String, Object>> out = new ArrayList<>();
		for (AsyncJob job : all.subList(from, to)) {
			out.add(describe(job, false));
		}
		return Response.status(200).entity(JSONUtil.exportObject(out)).build();
	}

	/**
	 * Shape the job for the wire. {@code result} is a raw JSON string produced by the work itself,
	 * so it is parsed back into a structure here rather than embedded as an escaped string — the
	 * client should get {@code result.sceneList}, not a string it has to parse a second time.
	 */
	private Map<String, Object> describe(AsyncJob job, boolean includeResult) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("jobId", job.getJobId());
		m.put("kind", job.getKind());
		m.put("key", job.getKey());
		m.put("status", job.getStatus().name().toLowerCase());
		m.put("phase", job.getProgress().getPhase());
		m.put("current", job.getProgress().getCurrent());
		m.put("total", job.getProgress().getTotal());
		m.put("elapsed", job.getElapsedSeconds());
		m.put("cancelled", job.getProgress().isCancelled());
		m.put("terminal", job.isTerminal());
		if (job.getError() != null) {
			m.put("error", job.getError());
		}
		if (job.getFailedExtractions() != null && !job.getFailedExtractions().isEmpty()) {
			m.put("failedExtractions", job.getFailedExtractions());
		}
		if (includeResult && job.getResult() != null) {
			Object parsed = parseResult(job.getResult());
			m.put("result", parsed);
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	private Object parseResult(String json) {
		String t = json.trim();
		try {
			if (t.startsWith("[")) {
				return JSONUtil.getList(t, Object.class, null);
			}
			Map<String, Object> m = JSONUtil.getMap(t.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				String.class, Object.class);
			if (m != null) {
				return m;
			}
		} catch (Exception e) {
			logger.warn("Job result was not parseable JSON, returning it as a string: " + e.getMessage());
		}
		/// Never drop the payload just because it did not re-parse — handing back the raw string is
		/// strictly better than reporting a finished job with no result.
		return json;
	}
}
