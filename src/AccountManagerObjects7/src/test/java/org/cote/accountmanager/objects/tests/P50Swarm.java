package org.cote.accountmanager.objects.tests;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Placement;
import org.cote.accountmanager.olio.sd.swarm.SWImageInfo;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.util.JSONUtil;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/// Test-tree helper for the Phase 5 P5-0 Swarm spike (PictureBookV3Design.md §38). Talks to SwarmUI's
/// HTTP API directly so the spike can post parameters the product's SWTxt2Img does not carry
/// (maskimage, useipadapter, comfyworkflowraw, ...). Every request and response is written under
/// C:/tmp/p50-spike/<item>/ so the measurements survive the JVM. Not product code; no DB; no user.
final class P50Swarm {
	static final Logger logger = LogManager.getLogger(P50Swarm.class);
	static final String ROOT = "C:/tmp/p50-spike/";
	static final String TIMINGS_ITEM = "05-timings";

	private final String server;
	private final HttpClient http;
	private final ObjectMapper om;
	private String session = null;

	P50Swarm(String server) {
		this.server = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
		this.om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	}

	String server() { return server; }

	String wsUrl(String path) {
		return server.replaceFirst("^http", "ws") + path;
	}

	static final class Result {
		String label;
		int status = -1;
		String body;
		Map<String, Object> json;
		String error;
		String imagePath;
		byte[] png;
		long ms;
		boolean ok() { return status == 200 && error == null && png != null; }
		Object param(String key) { return json != null ? json.get(key) : null; }
	}

	// ---- session -------------------------------------------------------------------------------

	synchronized String session() {
		if (session == null) {
			session = SWUtil.getAnonymousSession(server);
			if (session == null || session.isEmpty()) {
				throw new IllegalStateException("Swarm at " + server + " did not issue an anonymous session");
			}
		}
		return session;
	}

	private synchronized void dropSession() { session = null; }

	// ---- raw HTTP ------------------------------------------------------------------------------

	// Generous on purpose: render time is never a threshold here, and a 15-minute ceiling cut off a progressing
	// 5-reference 4-region FLUX.2 render in run 2 (the server kept rendering; the client just stopped waiting).
	static final Duration POST_CEILING = Duration.ofMinutes(Long.getLong("p50.post.minutes", 90L));

	Result postJson(String path, Object body) {
		Result r = new Result();
		long t0 = System.currentTimeMillis();
		try {
			byte[] bytes = om.writeValueAsBytes(body);
			HttpRequest req = HttpRequest.newBuilder(URI.create(server + path))
				.timeout(POST_CEILING)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			r.ms = System.currentTimeMillis() - t0;
			r.status = resp.statusCode();
			r.body = resp.body();
			r.json = parseObject(r.body);
			if (r.json != null && r.json.get("error") != null) {
				r.error = String.valueOf(r.json.get("error"));
			}
			else if (r.status != 200) {
				r.error = "HTTP " + r.status;
			}
		}
		catch (Exception e) {
			r.ms = System.currentTimeMillis() - t0;
			r.error = e.getClass().getSimpleName() + ": " + e.getMessage() + " after " + r.ms + " ms";
			logger.error("POST " + path + " failed after " + r.ms + " ms", e);
		}
		return r;
	}

	Result getJson(String path) {
		Result r = new Result();
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(server + path))
				.timeout(Duration.ofMinutes(2)).GET().build();
			long t0 = System.currentTimeMillis();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			r.ms = System.currentTimeMillis() - t0;
			r.status = resp.statusCode();
			r.body = resp.body();
			r.json = parseObject(r.body);
			if (r.status != 200) r.error = "HTTP " + r.status;
		}
		catch (Exception e) {
			r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
			logger.error("GET " + path + " failed", e);
		}
		return r;
	}

	/// Swarm returns image paths with spaces in the file name (the prompt prefix); JAX-RS encodes
	/// them for the product, URI.create does not.
	String imageUrl(String imagePath) {
		try {
			return URI.create(server + "/").resolve(new URI(null, null, imagePath, null)).toString();
		}
		catch (URISyntaxException e) {
			logger.error("Could not encode image path " + imagePath, e);
			return server + "/" + imagePath;
		}
	}

	byte[] getBytes(String url) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
			HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (resp.statusCode() != 200) {
				logger.error("GET " + url + " -> HTTP " + resp.statusCode());
				return null;
			}
			return resp.body();
		}
		catch (Exception e) {
			logger.error("GET " + url + " failed", e);
			return null;
		}
	}

	Map<String, Object> parseObject(String s) {
		if (s == null || s.isEmpty()) return null;
		try {
			return om.readValue(s, new TypeReference<Map<String, Object>>() {});
		}
		catch (Exception e) {
			return null;
		}
	}

	// ---- generation ----------------------------------------------------------------------------

	/// POST /API/GenerateText2Image with session_id + images:1 merged in. Writes
	/// <label>.request.json (redacted), <label>.response.json (verbatim), <label>.png (first image),
	/// <label>.params.json (PNG tEXt parameters via SWUtil.extractInfo) and <label>.error.txt on any error.
	/// Retries exactly once with a fresh session when Swarm reports a session error.
	Result generate(String item, String label, Map<String, Object> params) {
		Map<String, Object> body = new LinkedHashMap<>(params);
		body.put("session_id", session());
		body.putIfAbsent("images", 1);
		save(item, label, "request.json", pretty(redact(body)).getBytes(StandardCharsets.UTF_8));

		Result r = postJson("/API/GenerateText2Image", body);
		if (r.error != null && r.error.toLowerCase(Locale.ROOT).contains("session")) {
			logger.warn(label + ": session error (" + r.error + ") — retrying once with a fresh session");
			dropSession();
			body.put("session_id", session());
			r = postJson("/API/GenerateText2Image", body);
		}
		return finish(item, label, r, params);
	}

	/// POST /API/GenerateText2Image carrying comfyworkflowraw = workflowJson plus any ${comfyrawworkflowinput...}
	/// values and the usual model/images.
	Result rawWorkflow(String item, String label, String workflowJson, Map<String, Object> extra) {
		Map<String, Object> params = new LinkedHashMap<>(extra);
		params.put("comfyworkflowraw", workflowJson);
		return generate(item, label, params);
	}

	private Result finish(String item, String label, Result r, Map<String, Object> params) {
		r.label = label;
		save(item, label, "response.json", (r.body != null ? r.body : "").getBytes(StandardCharsets.UTF_8));
		if (r.error == null && r.json != null) {
			Object imgs = r.json.get("images");
			if (imgs instanceof List && !((List<?>) imgs).isEmpty()) {
				r.imagePath = String.valueOf(((List<?>) imgs).get(0));
				if (r.imagePath.startsWith("data:")) {
					int comma = r.imagePath.indexOf(',');
					r.png = Base64.getDecoder().decode(r.imagePath.substring(comma + 1));
				}
				else {
					r.png = getBytes(imageUrl(r.imagePath));
				}
				if (r.png == null) r.error = "image fetch failed: " + r.imagePath;
			}
			else {
				r.error = "200 but no images in response";
			}
		}
		if (r.png != null) {
			save(item, label, "png", r.png);
			try {
				SWImageInfo info = SWUtil.extractInfo(r.png);
				if (info != null) {
					save(item, label, "params.json", JSONUtil.exportObject(info).getBytes(StandardCharsets.UTF_8));
				}
			}
			catch (Exception e) {
				save(item, label, "params.error.txt", String.valueOf(e).getBytes(StandardCharsets.UTF_8));
			}
		}
		if (r.error != null) {
			save(item, label, "error.txt", (r.error + "\nstatus=" + r.status + "\n" + (r.body != null ? r.body : "")).getBytes(StandardCharsets.UTF_8));
			logger.error(item + "/" + label + ": " + r.error);
		}
		else {
			try {
				Files.deleteIfExists(dir(item).resolve(label + ".error.txt"));
			}
			catch (IOException e) {
				logger.warn(item + "/" + label + ": stale error.txt not removed: " + e);
			}
			logger.info(item + "/" + label + ": " + r.ms + " ms -> " + r.imagePath);
		}
		return r;
	}

	/// POST /API/ComfyGetGeneratedWorkflow with the T2I params flat next to session_id. Returns the
	/// parsed API-format graph (node id -> {class_type, inputs}) and writes <label>.workflow.json
	/// pretty-printed plus <label>.workflow.response.json verbatim. Null when Swarm refused.
	Map<String, Object> generatedWorkflow(String item, String label, Map<String, Object> params) {
		Map<String, Object> body = new LinkedHashMap<>(params);
		body.put("session_id", session());
		save(item, label, "workflow.request.json", pretty(redact(body)).getBytes(StandardCharsets.UTF_8));
		Result r = postJson("/API/ComfyGetGeneratedWorkflow", body);
		save(item, label, "workflow.response.json", (r.body != null ? r.body : "").getBytes(StandardCharsets.UTF_8));
		if (r.error != null || r.json == null || r.json.get("workflow") == null) {
			save(item, label, "workflow.error.txt", (String.valueOf(r.error) + "\nstatus=" + r.status).getBytes(StandardCharsets.UTF_8));
			logger.error(item + "/" + label + ": ComfyGetGeneratedWorkflow -> " + r.error);
			return null;
		}
		String wf = String.valueOf(r.json.get("workflow"));
		Map<String, Object> graph = parseObject(wf);
		if (graph == null) {
			save(item, label, "workflow.error.txt", ("workflow string did not parse as a JSON object\n" + wf).getBytes(StandardCharsets.UTF_8));
			return null;
		}
		save(item, label, "workflow.json", pretty(redact(graph)).getBytes(StandardCharsets.UTF_8));
		logger.info(item + "/" + label + ": generated workflow has " + graph.size() + " nodes: " + nodeSummary(graph));
		return graph;
	}

	static String nodeSummary(Map<String, Object> graph) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> e : graph.entrySet()) {
			Object ct = (e.getValue() instanceof Map) ? ((Map<?, ?>) e.getValue()).get("class_type") : null;
			if (sb.length() > 0) sb.append(", ");
			sb.append(e.getKey()).append('=').append(ct);
		}
		return sb.toString();
	}

	String toJson(Object o) {
		try {
			return om.writer().withoutFeatures(SerializationFeature.INDENT_OUTPUT).writeValueAsString(o);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	String pretty(Object o) {
		try {
			return om.writeValueAsString(o);
		}
		catch (Exception e) {
			return String.valueOf(o);
		}
	}

	// ---- backend probes ------------------------------------------------------------------------

	/// GET /ComfyBackendDirect/system_stats, saved as <item>/system_stats.<suffix>.json. Returns the
	/// parsed body (devices[0].vram_free / vram_total are what the timings table reads) or null.
	Map<String, Object> systemStats(String item, String suffix) {
		Result r = getJson("/ComfyBackendDirect/system_stats");
		save(item, "system_stats", suffix + ".json", (r.body != null ? r.body : String.valueOf(r.error)).getBytes(StandardCharsets.UTF_8));
		return r.json;
	}

	@SuppressWarnings("unchecked")
	static Object vram(Map<String, Object> stats, String key) {
		if (stats == null) return null;
		Object devs = stats.get("devices");
		if (devs instanceof List && !((List<?>) devs).isEmpty() && ((List<?>) devs).get(0) instanceof Map) {
			return ((Map<String, Object>) ((List<?>) devs).get(0)).get(key);
		}
		return null;
	}

	/// True when /ComfyBackendDirect/object_info/<type> answers 200 AND the body has <type> as a top-level key.
	boolean hasNode(String type) {
		Result r = getJson("/ComfyBackendDirect/object_info/" + type);
		return r.status == 200 && r.json != null && r.json.containsKey(type);
	}

	Result objectInfo(String item, String type) {
		Result r = getJson("/ComfyBackendDirect/object_info/" + type);
		save(item, "object_info." + type, "json", (r.body != null ? r.body : String.valueOf(r.error)).getBytes(StandardCharsets.UTF_8));
		return r;
	}

	// ---- geometry → Swarm syntax ----------------------------------------------------------------

	static String regionTag(Placement p, double strength) {
		return String.format(Locale.ROOT, "<region:%.4f,%.4f,%.4f,%.4f,%.2f>", p.x(), p.y(), p.w(), p.h(), strength);
	}

	static String objectTag(Placement p, double strength, double strength2) {
		return String.format(Locale.ROOT, "<object:%.4f,%.4f,%.4f,%.4f,%.2f,%.2f>", p.x(), p.y(), p.w(), p.h(), strength, strength2);
	}

	/// White rectangle (the placement box) on black, PNG, as a data URI — Swarm's maskimage input.
	static String rectMaskDataUri(List<Placement> boxes, int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, w, h);
		g.setColor(Color.WHITE);
		for (Placement p : boxes) {
			g.fillRect((int) Math.round(p.x() * w), (int) Math.round(p.y() * h), (int) Math.round(p.w() * w), (int) Math.round(p.h() * h));
		}
		g.dispose();
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes(img));
	}

	static byte[] pngBytes(BufferedImage img) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(img, "png", baos);
			return baos.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static String pngDataUri(byte[] png) {
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
	}

	static Map<String, Object> placementJson(String name, Placement p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("actor", name);
		m.put("x", p.x()); m.put("y", p.y()); m.put("w", p.w()); m.put("h", p.h());
		m.put("depth", p.depth());
		m.put("horizontal", String.valueOf(p.horizontal()));
		m.put("plane", String.valueOf(p.plane()));
		m.put("shotType", String.valueOf(p.shotType()));
		m.put("toString", p.toString());
		return m;
	}

	// ---- product request → flat map ----------------------------------------------------------

	/// The product's SWTxt2Img serialised exactly as SDUtil.doTxt2Img would send it, as a mutable map
	/// the spike can add Swarm-only parameters to.
	Map<String, Object> toMap(SWTxt2Img req) {
		Map<String, Object> m = parseObject(JSONUtil.exportObject(req));
		if (m == null) throw new IllegalStateException("SWTxt2Img did not serialise");
		m.remove("session_id");
		return new LinkedHashMap<>(m);
	}

	// ---- artefacts -----------------------------------------------------------------------------

	Path dir(String item) {
		Path d = Paths.get(ROOT, item);
		try {
			Files.createDirectories(d);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return d;
	}

	Path save(String item, String label, String suffix, byte[] bytes) {
		Path f = dir(item).resolve(label + "." + suffix);
		try {
			Files.write(f, bytes);
		}
		catch (IOException e) {
			throw new IllegalStateException("cannot write " + f, e);
		}
		return f;
	}

	byte[] load(String item, String label, String suffix) {
		Path f = Paths.get(ROOT, item, label + "." + suffix);
		if (!Files.exists(f)) return null;
		try {
			return Files.readAllBytes(f);
		}
		catch (IOException e) {
			return null;
		}
	}

	void note(String item, String label, String text) {
		save(item, label, "txt", text.getBytes(StandardCharsets.UTF_8));
		logger.info(item + "/" + label + ": " + text);
	}

	private boolean runMarked = false;

	/// Appends one CSV row to 05-timings/timings.txt (header written once; a `# run <timestamp>` comment line
	/// precedes the first row each JVM writes, so re-runs into the same directory stay distinguishable).
	void timing(String label, Object seed, Object steps, Object w, Object h, long ms, Object vramFreeBefore, Object vramFreeAfter, Object vramTotal, String note) {
		Path f = dir(TIMINGS_ITEM).resolve("timings.txt");
		try {
			if (!Files.exists(f)) {
				Files.write(f, "label,seed,steps,w,h,ms,vram_free_before,vram_free_after,vram_total,note\n".getBytes(StandardCharsets.UTF_8));
			}
			if (!runMarked) {
				String only = System.getProperty("p50.only");
				String mark = "# run " + java.time.ZonedDateTime.now() + (only != null ? " p50.only=" + only : "") + " post.ceiling=" + POST_CEILING.toMinutes() + "m\n";
				Files.write(f, mark.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
				runMarked = true;
			}
			String row = String.join(",", label, s(seed), s(steps), s(w), s(h), Long.toString(ms), s(vramFreeBefore), s(vramFreeAfter), s(vramTotal), note == null ? "" : note.replace(',', ';')) + "\n";
			Files.write(f, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
		}
		catch (IOException e) {
			logger.error("cannot append timing row", e);
		}
	}

	private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

	void writeReadme(String text) {
		try {
			Files.createDirectories(Paths.get(ROOT));
			Files.write(Paths.get(ROOT, "README.txt"), text.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---- redaction -----------------------------------------------------------------------------

	/// Deep copy with session_id replaced, every data:image… string (alone or in a list) replaced by its
	/// byte count, and image_base64 values inside a comfyworkflowraw string elided. Applied to the
	/// saved request copies only; the bytes actually posted are untouched.
	@SuppressWarnings("unchecked")
	static Object redact(Object o) {
		if (o instanceof Map) {
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				if ("session_id".equals(k)) out.put(k, "<redacted>");
				else if ("comfyworkflowraw".equals(k) && v instanceof String) out.put(k, elideBase64InWorkflow((String) v));
				else out.put(k, redact(v));
			}
			return out;
		}
		if (o instanceof List) {
			List<Object> out = new ArrayList<>();
			for (Object v : (List<Object>) o) out.add(redact(v));
			return out;
		}
		if (o instanceof String) {
			String str = (String) o;
			if (str.startsWith("data:image")) return "<" + str.length() + " chars data-uri>";
			if (str.length() > 4096 && str.matches("^[A-Za-z0-9+/=\\r\\n]+$")) return "<" + str.length() + " chars base64>";
		}
		return o;
	}

	/// Deep copy of a parsed JSON tree (maps, lists, scalars) with nothing redacted.
	@SuppressWarnings("unchecked")
	static Object deepCopy(Object o) {
		if (o instanceof Map) {
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) out.put(e.getKey(), deepCopy(e.getValue()));
			return out;
		}
		if (o instanceof List) {
			List<Object> out = new ArrayList<>();
			for (Object v : (List<Object>) o) out.add(deepCopy(v));
			return out;
		}
		return o;
	}

	static String elideBase64InWorkflow(String wf) {
		return wf.replaceAll("\"image_base64\"\\s*:\\s*\"[^\"]*\"", "\"image_base64\":\"<elided>\"");
	}
}
