package org.cote.accountmanager.olio.illustration;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Visual QA through Swarm's face detector (design §17 fallback; ledger §6.C 220 f; §15 P8-3). One raw
/// graph on the renderer - `SwarmLoadImageB64 → SwarmYoloDetection (face_yolov8m.pt, index 0 = every
/// detection in one mask) → MaskToImage → SwarmSaveImageWS` - returns a white-boxes-on-black mask at page
/// size; the boxes are counted here as 4-connected components and placed against the plan's actor boxes.
/// Measured on `.39` 2026-10-30: 13.5 s cold, 1.7 s warm, one box per face on the P5-2 two-actor page.
///
/// What it can and cannot say: PERSON_COUNT from the number of faces; ACTOR_PRESENT per referenced
/// foreground actor when the plan gave the actor a box (a face centroid inside it, each face attributed
/// to one actor); IDENTITY / OUTFIT are UNAVAILABLE - a detector has no opinion on who the face belongs
/// to - and LOCATION / COMPOSITION are not emitted. A face turned away or hidden is not detected, so a
/// count shortfall here is a weaker signal than the VLM's; the selector prefers the VLM when it answers.
///
/// Never a failure: no detector in `object_info`, an unreachable renderer, a backend error, no mask or
/// an undecodable one all yield {@link QaReport#unavailable} with the reason, and {@link #check} does not
/// throw for any of them.
public final class SwarmQaProvider implements QaProvider {

	private static final Logger logger = LogManager.getLogger(SwarmQaProvider.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() { };

	public static final String NAME = "swarmYolo";
	public static final String CLASS_YOLO = "SwarmYoloDetection";
	public static final String CLASS_MASK_TO_IMAGE = "MaskToImage";
	public static final String DETECTOR_MODEL = "face_yolov8m.pt";
	/// Graph node ids (the V3 prefix keeps them apart from Swarm's numeric ids).
	public static final String NODE_LOAD = ComfyGraph.ID_PREFIX + "qa_load";
	public static final String NODE_YOLO = ComfyGraph.ID_PREFIX + "qa_yolo";
	public static final String NODE_MASK_IMAGE = ComfyGraph.ID_PREFIX + "qa_mask_image";
	/// The image tag the page is sent under.
	public static final String INPUT_PAGE = "qaPage";
	/// YOLO confidence floor; the node's own default.
	static final double THRESHOLD = 0.25;
	/// A mask pixel is lit when its red channel reaches this (the mask is white on black).
	static final int LIT_LEVEL = 128;
	/// Components smaller than this are noise, not faces.
	static final int MIN_COMPONENT_PIXELS = 16;

	/// The Swarm calls the provider makes - {@link SwarmGraphClient} in production, scripted in tests.
	interface Backend {
		String objectInfoJson(String classType);
		SwarmGraphClient.RawResponse generateRaw(ComfyGraph graph, GraphInputs inputs, String model, int images);
		byte[] fetchImage(String relativePath);
	}

	/// One lit region of the mask: pixel count, bounds and centroid as canvas fractions (0..1).
	public static final class Face {
		private final int pixels;
		private final double cx;
		private final double cy;
		private final double x;
		private final double y;
		private final double w;
		private final double h;

		Face(int pixels, double cx, double cy, double x, double y, double w, double h) {
			this.pixels = pixels;
			this.cx = cx;
			this.cy = cy;
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
		}

		public int getPixels() { return pixels; }
		public double getCx() { return cx; }
		public double getCy() { return cy; }
		public double getX() { return x; }
		public double getY() { return y; }
		public double getW() { return w; }
		public double getH() { return h; }

		public boolean centroidIn(RenderPlan.Box b) {
			return b != null && cx >= b.getX() && cx <= b.getX() + b.getW() && cy >= b.getY() && cy <= b.getY() + b.getH();
		}

		@Override
		public String toString() {
			return String.format("(%.2f,%.2f)", cx, cy);
		}
	}

	private final Backend backend;
	private final String server;
	/// The checkpoint the raw post names: Swarm's model check still runs on a raw graph (design §38),
	/// so the caller passes the one the render used, which is known to be installed.
	private final String model;
	private Boolean available;
	private String probeNote;

	public SwarmQaProvider(SwarmGraphClient client, String model) {
		this(new ClientBackend(client), client.getServer(), model);
	}

	SwarmQaProvider(Backend backend, String server, String model) {
		if(backend == null) {
			throw new IllegalArgumentException("A backend is required");
		}
		this.backend = backend;
		this.server = server;
		this.model = model;
	}

	public String getServer() {
		return server;
	}

	public String getModel() {
		return model;
	}

	@Override
	public String name() {
		return NAME;
	}

	/// True when the renderer's `object_info` lists {@link #CLASS_YOLO} with {@link #DETECTOR_MODEL} among
	/// its `model_name` choices. Probed once per instance.
	@Override
	public boolean isAvailable() {
		if(available != null) {
			return available.booleanValue();
		}
		String json = null;
		try {
			json = backend.objectInfoJson(CLASS_YOLO);
		}
		catch(RuntimeException e) {
			logger.warn("SwarmQaProvider: object_info fetch threw: " + e.getMessage());
		}
		List<String> models = detectorModels(json);
		if(models == null) {
			probeNote = (json == null || json.isBlank() ? "no object_info from " + server : CLASS_YOLO + " not listed by " + server);
			available = Boolean.FALSE;
			return false;
		}
		boolean has = models.contains(DETECTOR_MODEL);
		probeNote = (has ? null : DETECTOR_MODEL + " not installed on " + server + " (" + models + ")");
		available = Boolean.valueOf(has);
		return has;
	}

	@Override
	public String availabilityNote() {
		return probeNote;
	}

	/// The `model_name` choices of {@link #CLASS_YOLO} in an `object_info` body (either the single-type
	/// body or the full one), or null when the type is absent or the body does not parse.
	@SuppressWarnings("unchecked")
	public static List<String> detectorModels(String objectInfoJson) {
		if(objectInfoJson == null || objectInfoJson.isBlank()) {
			return null;
		}
		Map<String, Object> parsed;
		try {
			parsed = MAPPER.readValue(objectInfoJson, MAP_TYPE);
		}
		catch(Exception e) {
			return null;
		}
		Object type = parsed.get(CLASS_YOLO);
		if(!(type instanceof Map)) {
			return null;
		}
		Object input = ((Map<String, Object>) type).get("input");
		Object required = (input instanceof Map ? ((Map<String, Object>) input).get("required") : null);
		Object modelName = (required instanceof Map ? ((Map<String, Object>) required).get("model_name") : null);
		List<String> out = new ArrayList<>();
		/// object_info encodes an enum input as [[choices...], {options}]
		if(modelName instanceof List && !((List<?>) modelName).isEmpty() && ((List<?>) modelName).get(0) instanceof List) {
			for(Object o : (List<?>) ((List<?>) modelName).get(0)) {
				if(o != null) {
					out.add(String.valueOf(o));
				}
			}
		}
		return out;
	}

	@Override
	public QaReport check(RenderPlan plan, byte[] imageBytes) {
		long start = System.currentTimeMillis();
		try {
			if(plan == null) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, "no render plan", 0L);
			}
			if(imageBytes == null || imageBytes.length == 0) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, "no image bytes", 0L);
			}
			if(!isAvailable()) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, probeNote, System.currentTimeMillis() - start);
			}
			GraphInputs inputs = new GraphInputs().image(INPUT_PAGE, imageBytes);
			SwarmGraphClient.RawResponse resp = backend.generateRaw(qaGraph(), inputs, model, 1);
			long ms = System.currentTimeMillis() - start;
			if(resp == null || resp.isUnreachable()) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, "renderer unreachable: " + server, ms);
			}
			if(resp.getError() != null) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, "renderer error: " + resp.getError(), ms);
			}
			if(resp.getImagePaths().isEmpty()) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, "renderer returned no mask", ms);
			}
			byte[] mask = backend.fetchImage(resp.getImagePaths().get(0));
			ms = System.currentTimeMillis() - start;
			if(mask == null || mask.length == 0) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, "mask image could not be fetched", ms);
			}
			List<Face> faces = faces(mask);
			if(faces == null) {
				return QaReport.unavailable(NAME, DETECTOR_MODEL, "mask image did not decode", ms);
			}
			QaReport rep = mapFaces(plan, faces, ms);
			logger.info("SwarmQaProvider: " + rep + " in " + ms + " ms on " + server);
			return rep;
		}
		catch(RuntimeException e) {
			long ms = System.currentTimeMillis() - start;
			logger.error("SwarmQaProvider: check failed: " + e.getMessage(), e);
			return QaReport.unavailable(NAME, DETECTOR_MODEL, "provider error: " + e.getMessage(), ms);
		}
	}

	// ─── graph ───────────────────────────────────────────────────────────────────

	/// The four-node detection graph (220 f). `index` 0 is every detection merged into one mask; the
	/// page arrives through the {@link #INPUT_PAGE} image tag.
	public static ComfyGraph qaGraph() {
		ComfyGraph g = ComfyGraph.fromMap(new LinkedHashMap<>());
		g.addNode(NODE_LOAD, ComfyGraph.CLASS_LOAD_IMAGE_B64, Map.of(ComfyGraph.INPUT_IMAGE_BASE64, ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, INPUT_PAGE)));
		Map<String, Object> yolo = new LinkedHashMap<>();
		yolo.put("image", List.of(NODE_LOAD, Integer.valueOf(0)));
		yolo.put("model_name", DETECTOR_MODEL);
		yolo.put("index", Integer.valueOf(0));
		yolo.put("class_filter", "");
		yolo.put("sort_order", "left-right");
		yolo.put("threshold", Double.valueOf(THRESHOLD));
		g.addNode(NODE_YOLO, CLASS_YOLO, yolo);
		g.addNode(NODE_MASK_IMAGE, CLASS_MASK_TO_IMAGE, Map.of("mask", List.of(NODE_YOLO, Integer.valueOf(0))));
		g.addNode(ComfyGraph.SAVE_FINAL, ComfyGraph.CLASS_SAVE_IMAGE_WS, Map.of("images", List.of(NODE_MASK_IMAGE, Integer.valueOf(0))));
		return g;
	}

	// ─── mask ────────────────────────────────────────────────────────────────────

	/// The lit 4-connected components of a mask PNG, left to right, with bounds and centroids as canvas
	/// fractions; components under {@link #MIN_COMPONENT_PIXELS} are dropped. Null when the bytes do not
	/// decode. Two faces whose boxes touch merge into one component - a known under-count.
	public static List<Face> faces(byte[] maskPng) {
		BufferedImage img = MaskImage.decode(maskPng);
		if(img == null) {
			return null;
		}
		int w = img.getWidth();
		int h = img.getHeight();
		boolean[] lit = new boolean[w * h];
		for(int y = 0; y < h; y++) {
			for(int x = 0; x < w; x++) {
				lit[y * w + x] = (((img.getRGB(x, y) >> 16) & 0xFF) >= LIT_LEVEL);
			}
		}
		boolean[] seen = new boolean[w * h];
		int[] stack = new int[w * h];
		List<Face> out = new ArrayList<>();
		for(int start = 0; start < lit.length; start++) {
			if(!lit[start] || seen[start]) {
				continue;
			}
			int sp = 0;
			stack[sp++] = start;
			seen[start] = true;
			int count = 0;
			long sx = 0L;
			long sy = 0L;
			int minX = w, minY = h, maxX = -1, maxY = -1;
			while(sp > 0) {
				int idx = stack[--sp];
				int x = idx % w;
				int y = idx / w;
				count++;
				sx += x;
				sy += y;
				if(x < minX) minX = x;
				if(x > maxX) maxX = x;
				if(y < minY) minY = y;
				if(y > maxY) maxY = y;
				if(x > 0) sp = push(lit, seen, stack, sp, idx - 1);
				if(x < w - 1) sp = push(lit, seen, stack, sp, idx + 1);
				if(y > 0) sp = push(lit, seen, stack, sp, idx - w);
				if(y < h - 1) sp = push(lit, seen, stack, sp, idx + w);
			}
			if(count >= MIN_COMPONENT_PIXELS) {
				out.add(new Face(count, (sx / (double) count + 0.5) / w, (sy / (double) count + 0.5) / h,
					minX / (double) w, minY / (double) h, (maxX - minX + 1) / (double) w, (maxY - minY + 1) / (double) h));
			}
		}
		out.sort((a, b) -> Double.compare(a.cx, b.cx));
		return out;
	}

	/// Mark and stage an unseen lit neighbour; returns the new stack pointer.
	private static int push(boolean[] lit, boolean[] seen, int[] stack, int sp, int idx) {
		if(lit[idx] && !seen[idx]) {
			seen[idx] = true;
			stack[sp] = idx;
			return sp + 1;
		}
		return sp;
	}

	// ─── mapping ─────────────────────────────────────────────────────────────────

	/// Faces → report (220 b thresholds via the builder). Each face is attributed to at most one actor:
	/// actors are taken in plan order and claim the unclaimed face whose centroid lies in their box.
	static QaReport mapFaces(RenderPlan plan, List<Face> faces, long ms) {
		List<RenderPlan.Actor> fg = new ArrayList<>();
		for(RenderPlan.Actor a : plan.getActors()) {
			if(a != null && a.isForeground()) {
				fg.add(a);
			}
		}
		QaReport.Builder b = QaReport.builder(NAME).model(DETECTOR_MODEL).durationMs(ms);
		b.personCount(fg.size(), Integer.valueOf(faces.size()),
			faces.size() + " face(s) detected by " + DETECTOR_MODEL + (faces.isEmpty() ? "" : " at " + faces));
		boolean[] claimed = new boolean[faces.size()];
		for(RenderPlan.Actor a : fg) {
			if(!a.isReferenced()) {
				continue;
			}
			String name = (a.getName() == null || a.getName().isBlank() ? a.getActorId() : a.getName());
			RenderPlan.Box box = a.getBox();
			if(box == null) {
				b.actorPresent(a.getActorId(), name, null, "no planned box; a face cannot be attributed to this actor");
			}
			else {
				int hit = -1;
				for(int i = 0; i < faces.size(); i++) {
					if(!claimed[i] && faces.get(i).centroidIn(box)) {
						hit = i;
						break;
					}
				}
				if(hit >= 0) {
					claimed[hit] = true;
					b.actorPresent(a.getActorId(), name, Boolean.TRUE, "face " + faces.get(hit) + " inside the planned box " + boxText(box));
				}
				else {
					b.actorPresent(a.getActorId(), name, Boolean.FALSE, "no unclaimed face inside the planned box " + boxText(box));
				}
			}
			b.soft(QaReport.KIND_IDENTITY, a.getActorId(), name, null, null, "not judged by face detection");
			if(a.getOutfit() != null && a.getOutfit().getText() != null && !a.getOutfit().getText().isBlank()) {
				b.soft(QaReport.KIND_OUTFIT, a.getActorId(), a.getOutfit().getText(), null, null, "not judged by face detection");
			}
		}
		return b.build();
	}

	private static String boxText(RenderPlan.Box b) {
		return String.format("[x %.2f y %.2f w %.2f h %.2f]", b.getX(), b.getY(), b.getW(), b.getH());
	}

	// ─── backend ─────────────────────────────────────────────────────────────────

	/// Production backend over {@link SwarmGraphClient}.
	static final class ClientBackend implements Backend {
		private final SwarmGraphClient client;

		ClientBackend(SwarmGraphClient client) {
			if(client == null) {
				throw new IllegalArgumentException("A SwarmGraphClient is required");
			}
			this.client = client;
		}

		@Override
		public String objectInfoJson(String classType) {
			return client.objectInfoJson(classType);
		}

		@Override
		public SwarmGraphClient.RawResponse generateRaw(ComfyGraph graph, GraphInputs inputs, String model, int images) {
			return client.generateRaw(graph, inputs, model, images, null);
		}

		@Override
		public byte[] fetchImage(String relativePath) {
			return client.fetchImage(relativePath);
		}
	}
}
