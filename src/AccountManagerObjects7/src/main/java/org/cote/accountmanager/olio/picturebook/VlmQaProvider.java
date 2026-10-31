package org.cote.accountmanager.olio.picturebook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.QaProvider;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;

/**
 * Visual QA through a vision LLM (design §17; Implementation §15 P8-2). One non-streaming call on the
 * run's chat <i>route</i> - the {@code system.connection} behind the run's {@code chatConfig}, else
 * {@link ChapBookUtil#resolveDefaultChatConfig} (§6.C 220 e) - asking for {@link QualityPresets#qaModel()},
 * never the chat config's own text model. The prompt is built from the {@link RenderPlan} (expected
 * foreground count, each foreground actor's identity / outfit text and planned box, the location and
 * the camera), the rendered page goes as image 1 and up to {@link #MAX_REFERENCE_IMAGES} reference
 * portraits follow it. The answer is JSON only, salvaged through {@link PictureBookUtil#parseLlmJsonObject}
 * and read through {@link NarrativeUtil#isMeaningful}.
 * <p>
 * <b>Never a failure.</b> An unsupported dialect, a route with no server, a model the route does not
 * list, a transport error, an empty answer or unparseable JSON all yield
 * {@link QaReport#unavailable} with the reason in the note; the render is unaffected and {@link #check}
 * does not throw for any of them. Actors are labelled {@code A1..An} in the prompt and mapped back to
 * their actorIds here - no identifier leaves the process.
 * <p>
 * Lives in {@code olio.picturebook}, not {@code olio.illustration}: it needs the package-private JSON
 * salvage, the chat route getters and PBAC artifact reads, and {@code olio.illustration} stays DB/LLM-free.
 * <p>
 * Measured on .42 2026-10-30 (ledger §15.2): the default model's Modelfile caps {@code num_predict} at
 * 1024 and, being a thinking model, spends the whole cap thinking and returns empty content; on an
 * OpenAI-compatible endpoint the same model reasons ~4x longer (5.5k completion tokens for the same
 * prompt) and {@code think:false} / {@code reasoning_effort} are ignored. A {@value #MAX_OUTPUT_TOKENS}-token
 * budget is sent on every call as a model-agnostic guard, and an empty answer that stopped on
 * {@code length} is reported as UNAVAILABLE with that reason.
 */
public final class VlmQaProvider implements QaProvider {

	private static final Logger logger = LogManager.getLogger(VlmQaProvider.class);

	public static final String NAME = "vlm";
	/// Output-token budget sent on every call (Ollama num_predict / OpenAI max_tokens). Generic guard:
	/// bounds a runaway, not a per-model tuning - sized so the measured worst case (5.5k) fits.
	static final int MAX_OUTPUT_TOKENS = 8192;
	/// Reference portraits sent after the rendered page.
	static final int MAX_REFERENCE_IMAGES = 3;
	/// Longest expected/observed text kept on a check.
	private static final int MAX_CHECK_TEXT = 240;

	/// Where and how to ask. Immutable - built once per attempt from the chat route, so a reader never
	/// sees a torn url/token pair.
	public static final class Route {
		private final String serverUrl;
		private final LLMServiceEnumType serviceType;
		private final String apiKey;
		private final String model;

		public Route(String serverUrl, LLMServiceEnumType serviceType, String apiKey, String model) {
			this.serverUrl = (serverUrl != null ? serverUrl.trim().replaceAll("/+$", "") : null);
			this.serviceType = (serviceType != null ? serviceType : LLMServiceEnumType.UNKNOWN);
			this.apiKey = apiKey;
			this.model = model;
		}

		public String getServerUrl() { return serverUrl; }
		public LLMServiceEnumType getServiceType() { return serviceType; }
		public String getModel() { return model; }
		/// Dialects this provider can speak: Ollama native and the OpenAI-compatible chat shape.
		public boolean isSupported() {
			return serverUrl != null && !serverUrl.isBlank()
				&& (serviceType == LLMServiceEnumType.OLLAMA || serviceType == LLMServiceEnumType.OPENAI_COMPAT);
		}

		@Override
		public String toString() {
			return "Route{" + serviceType + " " + serverUrl + " model=" + model + (apiKey != null && !apiKey.isBlank() ? " auth" : "") + "}";
		}
	}

	/// HTTP seam so a same-package test can replay canned answers with no network. Both return the
	/// response body on 200 and null otherwise.
	interface Transport {
		String post(String url, Map<String, String> headers, String json);
		String get(String url, Map<String, String> headers);
	}

	/// Reads a reference portrait's bytes for the prompt; null means "do not send one".
	interface PortraitResolver {
		byte[] read(String artifactObjectId);
	}

	private final Route route;
	private final Transport transport;
	private final PortraitResolver portraits;
	/// Probed once per instance (= once per attempt); null until probed.
	private Boolean available = null;
	private String probeNote = null;

	/// Production: the run's / book's chat route for {@code user}, PBAC portrait reads as {@code user}.
	public VlmQaProvider(BaseRecord user, BaseRecord chatConfig) {
		this(routeFor(user, chatConfig), new ClientTransport(), oid -> {
			if(oid == null) return null;
			BaseRecord art = PbArtifactUtil.readArtifact(user, oid, PbGraphUtil.orgId(user));
			return (art != null ? PbArtifactUtil.readArtifactBytes(user, art) : null);
		});
	}

	VlmQaProvider(Route route, Transport transport, PortraitResolver portraits) {
		this.route = (route != null ? route : new Route(null, LLMServiceEnumType.UNKNOWN, null, QualityPresets.qaModel()));
		this.transport = (transport != null ? transport : new ClientTransport());
		this.portraits = (portraits != null ? portraits : oid -> null);
	}

	public Route getRoute() {
		return route;
	}

	/// The route for a chat config (§6.C 220 e): the config's connection via {@link Chat}'s getters, the
	/// model from {@link QualityPresets#qaModel()}. A null config resolves the user's default analysis
	/// config; no config at all yields an unsupported route (UNAVAILABLE at check time, never a throw).
	public static Route routeFor(BaseRecord user, BaseRecord chatConfig) {
		String model = QualityPresets.qaModel();
		BaseRecord cfg = chatConfig;
		if(cfg == null && user != null) {
			cfg = ChapBookUtil.resolveDefaultChatConfig(user);
		}
		if(cfg == null) {
			logger.warn("VlmQaProvider: no chat config resolvable; visual QA will report UNAVAILABLE");
			return new Route(null, LLMServiceEnumType.UNKNOWN, null, model);
		}
		Chat chat = new Chat(user, cfg, null);
		return new Route(chat.getServerUrl(), chat.getServiceType(), chat.getAuthorizationToken(), model);
	}

	@Override
	public String name() {
		return NAME;
	}

	/// True when the route is supported and lists the QA model. Probed once per instance.
	@Override
	public boolean isAvailable() {
		if(available != null) {
			return available.booleanValue();
		}
		if(!route.isSupported()) {
			probeNote = "unsupported route " + route;
			available = Boolean.FALSE;
			return false;
		}
		List<String> names = listModels();
		if(names == null) {
			probeNote = "model list unavailable from " + route.getServerUrl();
			available = Boolean.FALSE;
			return false;
		}
		boolean has = hasModel(names, route.getModel());
		probeNote = (has ? null : "model " + route.getModel() + " not listed by " + route.getServerUrl());
		available = Boolean.valueOf(has);
		return has;
	}

	@Override
	public String availabilityNote() {
		return probeNote;
	}

	@Override
	public QaReport check(RenderPlan plan, byte[] imageBytes) {
		long start = System.currentTimeMillis();
		try {
			if(plan == null) {
				return QaReport.unavailable(NAME, route.getModel(), "no render plan", 0L);
			}
			if(imageBytes == null || imageBytes.length == 0) {
				return QaReport.unavailable(NAME, route.getModel(), "no image bytes", 0L);
			}
			if(!isAvailable()) {
				return QaReport.unavailable(NAME, route.getModel(), probeNote, System.currentTimeMillis() - start);
			}
			Prompt prompt = buildPrompt(plan);
			List<byte[]> images = new ArrayList<>();
			images.add(imageBytes);
			images.addAll(prompt.referenceImages);
			String body = (route.getServiceType() == LLMServiceEnumType.OLLAMA
				? ollamaBody(prompt.text, images) : openAiBody(prompt.text, images));
			String url = route.getServerUrl() + (route.getServiceType() == LLMServiceEnumType.OLLAMA ? "/api/chat" : "/v1/chat/completions");
			String raw = transport.post(url, authHeaders(), body);
			long ms = System.currentTimeMillis() - start;
			if(raw == null) {
				return QaReport.unavailable(NAME, route.getModel(), "no response from " + url, ms);
			}
			Answer answer = extractAnswer(raw);
			if(!NarrativeUtil.isMeaningful(answer.content)) {
				String why = ("length".equals(answer.stopReason)
					? "token budget exhausted while thinking (stop=length, " + MAX_OUTPUT_TOKENS + " tokens)"
					: "empty answer" + (answer.stopReason != null ? " (stop=" + answer.stopReason + ")" : ""));
				return QaReport.unavailable(NAME, route.getModel(), why, ms);
			}
			boolean[] ok = new boolean[1];
			Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObject(answer.content, "visual QA", null, ok);
			if(!ok[0]) {
				logger.warn("VlmQaProvider: unparseable answer: " + abbreviate(answer.content, 300));
				return QaReport.unavailable(NAME, route.getModel(), "answer was not JSON", ms);
			}
			QaReport rep = mapAnswer(plan, prompt, parsed, ms);
			logger.info("VlmQaProvider: " + rep + " in " + ms + " ms via " + route.getServiceType() + " " + route.getServerUrl());
			return rep;
		}
		catch(RuntimeException e) {
			long ms = System.currentTimeMillis() - start;
			logger.error("VlmQaProvider: check failed: " + e.getMessage(), e);
			return QaReport.unavailable(NAME, route.getModel(), "provider error: " + e.getMessage(), ms);
		}
	}

	// ─── prompt ───────────────────────────────────────────────────────────────────

	/// The prompt text plus the A-label ↔ actor mapping and the reference images it refers to.
	static final class Prompt {
		String text;
		/// label (A1..An) → actor, in label order
		final Map<String, RenderPlan.Actor> actorsByLabel = new LinkedHashMap<>();
		/// label → 1-based image index of the reference portrait sent for it (absent when none sent)
		final Map<String, Integer> portraitImageByLabel = new HashMap<>();
		final List<byte[]> referenceImages = new ArrayList<>();
		int expectedForeground;
	}

	Prompt buildPrompt(RenderPlan plan) {
		Prompt p = new Prompt();
		List<RenderPlan.Actor> fg = new ArrayList<>();
		for(RenderPlan.Actor a : plan.getActors()) {
			if(a != null && a.isForeground()) {
				fg.add(a);
			}
		}
		p.expectedForeground = fg.size();
		StringBuilder sb = new StringBuilder();
		sb.append("You are a strict visual QA inspector for illustrated book pages. Image 1 is the rendered page.\n");
		sb.append("The page was planned as follows:\n");
		sb.append("- Expected number of people clearly visible in the foreground: ").append(fg.size()).append('\n');
		if(fg.isEmpty()) {
			sb.append("- No foreground characters are planned.\n");
		}
		else {
			sb.append("- Foreground characters:\n");
		}
		int nextImage = 2;
		for(int i = 0; i < fg.size(); i++) {
			RenderPlan.Actor a = fg.get(i);
			String label = "A" + (i + 1);
			p.actorsByLabel.put(label, a);
			sb.append("  ").append(label).append(": \"").append(safe(a.getName(), "unnamed")).append('"');
			String identity = identityText(a);
			if(identity != null) {
				sb.append(" - appearance: ").append(identity);
			}
			String outfit = outfitText(a);
			if(outfit != null) {
				sb.append("; outfit: ").append(outfit);
			}
			if(a.getBox() != null) {
				sb.append("; planned placement: ").append(placement(a.getBox()));
			}
			if(a.isReferenced() && a.getIdentity() != null && p.referenceImages.size() < MAX_REFERENCE_IMAGES) {
				byte[] portrait = portraits.read(a.getIdentity().getPortraitArtifactId());
				if(portrait != null && portrait.length > 0) {
					p.referenceImages.add(portrait);
					p.portraitImageByLabel.put(label, Integer.valueOf(nextImage));
					sb.append("; reference portrait of this character: image ").append(nextImage);
					nextImage++;
				}
			}
			sb.append(".\n");
		}
		String setting = (plan.getLocation() != null ? plan.getLocation().getDescription() : null);
		if(NarrativeUtil.isMeaningful(setting)) {
			sb.append("- Setting: ").append(setting.trim()).append('\n');
		}
		String camera = (plan.getCamera() != null ? plan.getCamera().getDescription() : null);
		if(NarrativeUtil.isMeaningful(camera)) {
			sb.append("- Camera / composition: ").append(camera.trim()).append('\n');
		}
		sb.append("Answer with ONLY a JSON object, no prose, exactly this shape:\n");
		sb.append("{\"person_count\": <integer>, \"actors\": [");
		for(int i = 0; i < fg.size(); i++) {
			if(i > 0) sb.append(", ");
			sb.append("{\"id\": \"A").append(i + 1).append("\", \"present\": true|false, \"identity_match\": true|false|null, \"outfit_match\": true|false|null, \"note\": \"<short reason>\"}");
		}
		sb.append("], \"location_match\": true|false|null, \"composition_match\": true|false|null, \"notes\": \"<one sentence>\"}\n");
		sb.append("Rules: count every distinct human figure visible in the foreground, including partial ones. ");
		sb.append("\"present\" is true only when a figure matching that character's described appearance and outfit is visible. ");
		sb.append("When a reference portrait is given, \"identity_match\" compares the figure's face and build to that portrait; otherwise to the described appearance. ");
		sb.append("\"outfit_match\" compares what the figure wears to the planned outfit. Use null when you cannot tell.");
		p.text = sb.toString();
		return p;
	}

	private static String identityText(RenderPlan.Actor a) {
		String t = (a.getIdentity() != null ? a.getIdentity().getText() : null);
		if(!NarrativeUtil.isMeaningful(t)) {
			t = a.getDescription();
		}
		return (NarrativeUtil.isMeaningful(t) ? t.trim() : null);
	}

	private static String outfitText(RenderPlan.Actor a) {
		String t = (a.getOutfit() != null ? a.getOutfit().getText() : null);
		return (NarrativeUtil.isMeaningful(t) ? t.trim() : null);
	}

	/// "left third of the frame (x 0.05-0.45, y 0.10-0.95)" - words first, fractions for precision.
	static String placement(RenderPlan.Box b) {
		double cx = b.getX() + b.getW() / 2.0;
		String where = (cx < 0.4 ? "left" : (cx > 0.6 ? "right" : "centre"));
		return where + " of the frame (x " + fmt(b.getX()) + "-" + fmt(b.getX() + b.getW())
			+ ", y " + fmt(b.getY()) + "-" + fmt(b.getY() + b.getH()) + ")";
	}

	private static String fmt(double d) {
		return String.format(Locale.ROOT, "%.2f", d);
	}

	private static String safe(String s, String def) {
		return (s != null && !s.isBlank() ? s.trim() : def);
	}

	// ─── wire bodies ──────────────────────────────────────────────────────────────

	/// Ollama native chat: images ride on the message as base64; format=json; num_predict guard.
	String ollamaBody(String prompt, List<byte[]> images) {
		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put("role", "user");
		msg.put("content", prompt);
		List<String> b64 = new ArrayList<>();
		for(byte[] img : images) {
			b64.add(Base64.getEncoder().encodeToString(img));
		}
		msg.put("images", b64);
		Map<String, Object> options = new LinkedHashMap<>();
		options.put("temperature", Integer.valueOf(0));
		options.put("num_predict", Integer.valueOf(MAX_OUTPUT_TOKENS));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", route.getModel());
		body.put("messages", List.of(msg));
		body.put("stream", Boolean.FALSE);
		body.put("format", "json");
		body.put("options", options);
		return JSONUtil.exportObject(body, null, true, false);
	}

	/// OpenAI-compatible chat: content parts with data-URL images; response_format json_object; max_tokens guard.
	String openAiBody(String prompt, List<byte[]> images) {
		List<Map<String, Object>> parts = new ArrayList<>();
		Map<String, Object> text = new LinkedHashMap<>();
		text.put("type", "text");
		text.put("text", prompt);
		parts.add(text);
		for(byte[] img : images) {
			Map<String, Object> url = new LinkedHashMap<>();
			url.put("url", "data:" + mimeOf(img) + ";base64," + Base64.getEncoder().encodeToString(img));
			Map<String, Object> part = new LinkedHashMap<>();
			part.put("type", "image_url");
			part.put("image_url", url);
			parts.add(part);
		}
		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put("role", "user");
		msg.put("content", parts);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", route.getModel());
		body.put("messages", List.of(msg));
		body.put("stream", Boolean.FALSE);
		body.put("response_format", Map.of("type", "json_object"));
		body.put("temperature", Integer.valueOf(0));
		body.put("max_tokens", Integer.valueOf(MAX_OUTPUT_TOKENS));
		return JSONUtil.exportObject(body, null, true, false);
	}

	/// PNG / JPEG / WebP by magic bytes; PNG otherwise (what the renderers emit).
	static String mimeOf(byte[] b) {
		if(b != null && b.length >= 12) {
			if((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
			if((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
			if(b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
		}
		return "image/png";
	}

	/// Bearer for both supported dialects (Ollama ignores it; OPENAI_COMPAT proxies require it); none when blank.
	Map<String, String> authHeaders() {
		if(route.apiKey == null || route.apiKey.isBlank()) {
			return null;
		}
		Map<String, String> h = new HashMap<>();
		h.put("Authorization", "Bearer " + route.apiKey);
		return h;
	}

	// ─── answer ───────────────────────────────────────────────────────────────────

	static final class Answer {
		String content;
		String stopReason;
	}

	/// Ollama: message.content + done_reason; OpenAI: choices[0].message.content + finish_reason. Thinking
	/// text (message.thinking / reasoning) is ignored; a <think> block inside the content is stripped by
	/// the JSON salvage.
	@SuppressWarnings("unchecked")
	Answer extractAnswer(String raw) {
		Answer a = new Answer();
		Map<String, Object> m = null;
		try {
			m = JSONUtil.getMap(raw.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		}
		catch(Exception e) {
			logger.warn("VlmQaProvider: response was not JSON: " + e.getMessage());
		}
		if(m == null) {
			return a;
		}
		if(route.getServiceType() == LLMServiceEnumType.OLLAMA) {
			Object msg = m.get("message");
			if(msg instanceof Map) {
				Object c = ((Map<String, Object>) msg).get("content");
				a.content = (c != null ? c.toString() : null);
			}
			Object dr = m.get("done_reason");
			a.stopReason = (dr != null ? dr.toString() : null);
		}
		else {
			Object choices = m.get("choices");
			if(choices instanceof List && !((List<?>) choices).isEmpty() && ((List<?>) choices).get(0) instanceof Map) {
				Map<String, Object> ch = (Map<String, Object>) ((List<?>) choices).get(0);
				Object msg = ch.get("message");
				if(msg instanceof Map) {
					Object c = ((Map<String, Object>) msg).get("content");
					a.content = (c != null ? c.toString() : null);
				}
				Object fr = ch.get("finish_reason");
				a.stopReason = (fr != null ? fr.toString() : null);
			}
		}
		return a;
	}

	/// Plan + parsed answer → report. PERSON_COUNT always; ACTOR_PRESENT only for referenced foreground
	/// actors (220 b - an unreferenced one is covered by the count); IDENTITY / OUTFIT only when the plan
	/// gave something to compare and the model saw the figure; LOCATION / COMPOSITION when the plan has
	/// a setting / camera. A missing or unreadable field is a null observation (UNAVAILABLE), never a guess.
	@SuppressWarnings("unchecked")
	QaReport mapAnswer(RenderPlan plan, Prompt prompt, Map<String, Object> parsed, long ms) {
		QaReport.Builder b = QaReport.builder(NAME).model(route.getModel()).durationMs(ms);
		String notes = str(parsed.get("notes"));
		b.note(notes);
		b.personCount(prompt.expectedForeground, intOf(parsed.get("person_count")), notes);

		Map<String, Map<String, Object>> byLabel = new HashMap<>();
		Object actors = parsed.get("actors");
		if(actors instanceof List) {
			for(Object o : (List<?>) actors) {
				if(o instanceof Map) {
					Map<String, Object> am = (Map<String, Object>) o;
					String id = str(am.get("id"));
					if(id != null) {
						byLabel.put(id.trim().toUpperCase(Locale.ROOT), am);
					}
				}
			}
		}
		for(Map.Entry<String, RenderPlan.Actor> e : prompt.actorsByLabel.entrySet()) {
			String label = e.getKey();
			RenderPlan.Actor a = e.getValue();
			Map<String, Object> am = byLabel.get(label);
			Boolean present = (am != null ? boolOf(am.get("present")) : null);
			String note = (am != null ? str(am.get("note")) : null);
			if(a.isReferenced()) {
				b.actorPresent(a.getActorId(), a.getName(), present, note);
			}
			if(present != null && !present.booleanValue()) {
				/// absent: the count / presence checks carry it; comparing looks to nothing is noise
				continue;
			}
			String identity = identityText(a);
			Integer portraitImage = prompt.portraitImageByLabel.get(label);
			if(identity != null || portraitImage != null) {
				Boolean im = (am != null ? boolOf(am.get("identity_match")) : null);
				String expected = (portraitImage != null ? "reference portrait (image " + portraitImage + ")" : abbreviate(identity, MAX_CHECK_TEXT));
				b.soft(QaReport.KIND_IDENTITY, a.getActorId(), expected, matchWord(im), im, note);
			}
			String outfit = outfitText(a);
			if(outfit != null) {
				Boolean om = (am != null ? boolOf(am.get("outfit_match")) : null);
				b.soft(QaReport.KIND_OUTFIT, a.getActorId(), abbreviate(outfit, MAX_CHECK_TEXT), matchWord(om), om, note);
			}
		}
		String setting = (plan.getLocation() != null ? plan.getLocation().getDescription() : null);
		if(NarrativeUtil.isMeaningful(setting)) {
			Boolean lm = boolOf(parsed.get("location_match"));
			b.soft(QaReport.KIND_LOCATION, null, abbreviate(setting.trim(), MAX_CHECK_TEXT), matchWord(lm), lm, notes);
		}
		String camera = (plan.getCamera() != null ? plan.getCamera().getDescription() : null);
		if(NarrativeUtil.isMeaningful(camera)) {
			Boolean cm = boolOf(parsed.get("composition_match"));
			b.soft(QaReport.KIND_COMPOSITION, null, abbreviate(camera.trim(), MAX_CHECK_TEXT), matchWord(cm), cm, notes);
		}
		return b.build();
	}

	private static String matchWord(Boolean m) {
		return (m == null ? null : (m.booleanValue() ? "matches" : "differs"));
	}

	/// LLM-emitted strings: the literal "null"/"none"/"n/a" are not values.
	static String str(Object o) {
		if(o == null) return null;
		String s = o.toString();
		return (NarrativeUtil.isMeaningful(s) ? s.trim() : null);
	}

	/// true/false/yes/no in any case, numbers (0 = false), JSON booleans; anything else null.
	static Boolean boolOf(Object o) {
		if(o == null) return null;
		if(o instanceof Boolean) return (Boolean) o;
		if(o instanceof Number) return Boolean.valueOf(((Number) o).intValue() != 0);
		String s = o.toString().trim().toLowerCase(Locale.ROOT);
		if(s.equals("true") || s.equals("yes")) return Boolean.TRUE;
		if(s.equals("false") || s.equals("no")) return Boolean.FALSE;
		return null;
	}

	/// Integers, doubles that are whole, numeric strings; anything else null.
	static Integer intOf(Object o) {
		if(o == null) return null;
		if(o instanceof Number) return Integer.valueOf(((Number) o).intValue());
		String s = o.toString().trim();
		try {
			return Integer.valueOf((int) Math.round(Double.parseDouble(s)));
		}
		catch(NumberFormatException e) {
			return null;
		}
	}

	static String abbreviate(String s, int max) {
		if(s == null) return null;
		return (s.length() <= max ? s : s.substring(0, max - 3) + "...");
	}

	// ─── model list ───────────────────────────────────────────────────────────────

	/// Ollama: GET /api/tags → models[].name; OpenAI-compatible: GET /v1/models → data[].id. Null when
	/// the route does not answer or the body is not the expected shape.
	@SuppressWarnings("unchecked")
	List<String> listModels() {
		boolean ollama = (route.getServiceType() == LLMServiceEnumType.OLLAMA);
		String url = route.getServerUrl() + (ollama ? "/api/tags" : "/v1/models");
		String raw = transport.get(url, authHeaders());
		if(raw == null) {
			return null;
		}
		Map<String, Object> m = null;
		try {
			m = JSONUtil.getMap(raw.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		}
		catch(Exception e) {
			logger.warn("VlmQaProvider: model list from " + url + " was not JSON: " + e.getMessage());
		}
		if(m == null) {
			return null;
		}
		Object arr = m.get(ollama ? "models" : "data");
		if(!(arr instanceof List)) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for(Object o : (List<?>) arr) {
			if(o instanceof Map) {
				Object n = ((Map<String, Object>) o).get(ollama ? "name" : "id");
				if(n != null) {
					names.add(n.toString());
				}
			}
		}
		return names;
	}

	/// Exact match tolerating a trailing ":latest" on either side (the LlmTestGate rule).
	static boolean hasModel(List<String> names, String model) {
		if(names == null || model == null) return false;
		String want = stripLatest(model);
		for(String n : names) {
			if(n != null && stripLatest(n).equals(want)) {
				return true;
			}
		}
		return false;
	}

	private static String stripLatest(String s) {
		String t = s.trim();
		return (t.endsWith(":latest") ? t.substring(0, t.length() - ":latest".length()) : t);
	}

	// ─── default transport ────────────────────────────────────────────────────────

	/// Jersey via ClientUtil (30 s connect, the shared read timeout). Non-200 is logged there and null here.
	static final class ClientTransport implements Transport {
		@Override
		public String post(String url, Map<String, String> headers, String json) {
			try {
				return ClientUtil.postJSON(String.class, ClientUtil.getResource(url), headers, json, MediaType.APPLICATION_JSON_TYPE);
			}
			catch(Exception e) {
				logger.warn("VlmQaProvider: POST " + url + " failed: " + e.getMessage());
				return null;
			}
		}

		@Override
		public String get(String url, Map<String, String> headers) {
			try {
				Invocation.Builder bld = ClientUtil.getRequestBuilder(ClientUtil.getResource(url)).accept(MediaType.APPLICATION_JSON_TYPE);
				if(headers != null) {
					for(Map.Entry<String, String> h : headers.entrySet()) {
						if(h.getKey() != null && h.getValue() != null) {
							bld.header(h.getKey(), h.getValue());
						}
					}
				}
				Response resp = bld.get();
				if(resp == null) {
					return null;
				}
				String body = resp.readEntity(String.class);
				if(resp.getStatus() != 200) {
					logger.warn("VlmQaProvider: GET " + url + " -> " + resp.getStatus());
					return null;
				}
				return body;
			}
			catch(Exception e) {
				logger.warn("VlmQaProvider: GET " + url + " failed: " + e.getMessage());
				return null;
			}
		}
	}
}
