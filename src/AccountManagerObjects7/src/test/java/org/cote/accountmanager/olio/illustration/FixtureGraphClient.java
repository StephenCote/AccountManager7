package org.cote.accountmanager.olio.illustration;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;

/**
 * A {@link SwarmGraphClient} whose generator answers from the captured fixtures
 * ({@code src/test/resources/olio/illustration/p50/*.workflow.json}, captured by P5-0 and refreshed from the
 * live generator by {@code TestP51GraphLive.t00}) instead of the server: the fixture graph is loaded and the
 * values a real generator derives from the request are substituted in - each
 * {@code SwarmLoadImageB64.image_base64} from the request's {@code promptimages} / {@code initimage} /
 * {@code maskimage} (raw base64, no data-URI prefix, as Swarm writes it), the prompt and negative in place of
 * the {@link WorkflowCompiler} markers the fixture's text inputs carry, {@code seed} into {@code noise_seed},
 * {@code steps} into the sampler and {@code start_at_step} from {@code initimagecreativity}. Nothing else in
 * the fixture changes, so the patch step in {@link WorkflowCompiler} runs against the real node shapes
 * {@code .39} produced.
 * <p>
 * Which fixture answers is decided by the request: {@code initimage} present selects the inpaint graph
 * for the model family, otherwise the base graph. No network: the {@link SDUtil} points at a closed port
 * and is never called.
 */
class FixtureGraphClient extends SwarmGraphClient {

	static final String FIXTURE_DIR = "/olio/illustration/p50/";
	static final String BASE_FLUX2 = "base_flux2.workflow.json";
	static final String INPAINT_FLUX2 = "inpaint_A_flux2.workflow.json";
	static final String INPAINT_SDXL = "inpaint_A_sdxl.workflow.json";
	static final String OFFLINE_SERVER = "http://127.0.0.1:9";

	/** When set, substituted images are decoded and re-encoded so their base64 differs from the request's. */
	private boolean reencodeImages = false;
	/** When set, the sampler gets a literal seed that is not the marker (a generator that ignored the seed). */
	private boolean dropSeed = false;
	/** When set, the scene marker is replaced by literal text instead of the request's prompt (a generator that ignored the prompt). */
	private boolean dropPrompt = false;
	/** When set, every save node is removed from the answer. */
	private boolean dropSave = false;
	/** When set, the sampler keeps the fixture's {@code steps} / {@code start_at_step} literals. */
	private boolean keepFixtureSteps = false;
	/** Overrides fixture selection when non-null. */
	private String fixture = null;
	/** Every params map the generator was asked for, in order. */
	final List<Map<String, Object>> requests = new ArrayList<>();
	final List<ComfyGraph> answers = new ArrayList<>();

	FixtureGraphClient() {
		super(new SDUtil(SDAPIEnumType.SWARM, OFFLINE_SERVER));
	}

	FixtureGraphClient reencodeImages() { this.reencodeImages = true; return this; }
	FixtureGraphClient dropSeed() { this.dropSeed = true; return this; }
	FixtureGraphClient dropPrompt() { this.dropPrompt = true; return this; }
	FixtureGraphClient dropSave() { this.dropSave = true; return this; }
	FixtureGraphClient keepFixtureSteps() { this.keepFixtureSteps = true; return this; }
	FixtureGraphClient fixture(String name) { this.fixture = name; return this; }

	static String fixtureText(String name) {
		try(InputStream in = FixtureGraphClient.class.getResourceAsStream(FIXTURE_DIR + name)) {
			if(in == null) {
				throw new IllegalStateException("Fixture " + FIXTURE_DIR + name + " is not on the test classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch(IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static ComfyGraph fixtureGraph(String name) {
		return ComfyGraph.fromJson(fixtureText(name));
	}

	static boolean isSdxl(Object model) {
		String m = String.valueOf(model).toLowerCase();
		return m.contains("xl") || m.contains("juggernaut");
	}

	@Override
	public ComfyGraph generatedWorkflow(Map<String, Object> params) {
		requests.add(new LinkedHashMap<>(params));
		boolean inpaint = params.containsKey("initimage");
		String name = fixture;
		if(name == null) {
			name = (inpaint ? (isSdxl(params.get("model")) ? INPAINT_SDXL : INPAINT_FLUX2) : BASE_FLUX2);
		}
		ComfyGraph g = fixtureGraph(name);

		/// Images, in the slot order the captured graphs carry them: mask, init, then the references.
		List<String> slots = new ArrayList<>();
		if(inpaint) {
			slots.add(stripUri(params.get("maskimage")));
			slots.add(stripUri(params.get("initimage")));
		}
		Object refs = params.get("promptimages");
		if(refs instanceof List) {
			for(Object o : (List<?>) refs) {
				slots.add(stripUri(o));
			}
		}
		List<String> loaders = g.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64);
		for(int i = 0; i < loaders.size() && i < slots.size(); i++) {
			String b64 = slots.get(i);
			if(reencodeImages) {
				b64 = reencode(b64);
			}
			g.setInput(loaders.get(i), ComfyGraph.INPUT_IMAGE_BASE64, b64);
		}

		/// Prompts. The generator echoes the request's prompt / negativeprompt into its text inputs, whatever
		/// the encoder class (CLIPTextEncode.text on flux2, CLIPTextEncodeSDXL.text_g + text_l on SDXL). The
		/// captured fixtures hold the compiler's markers there because that is what the capturing request
		/// sent, so the echo is a literal replacement of each marker by the request value; a request without
		/// a negative gets "" where the fixture had the negative marker.
		String prompt = (dropPrompt ? "a generator that ignored the prompt" : String.valueOf(params.get("prompt")));
		String negative = (params.containsKey("negativeprompt") ? String.valueOf(params.get("negativeprompt")) : "");
		for(String id : g.nodeIds()) {
			Map<String, Object> in = g.inputs(id);
			for(Map.Entry<String, Object> e : new ArrayList<>(in.entrySet())) {
				if(e.getValue() instanceof String) {
					String s = (String) e.getValue();
					in.put(e.getKey(), s.replace(WorkflowCompiler.MARKER_SCENE, prompt).replace(WorkflowCompiler.MARKER_NEGATIVE, negative));
				}
			}
		}

		/// Sampler literals. The fixture's noise_seed is already the marker (the capturing request sent it), so a
		/// generator that ignored the seed is simulated by writing some other literal, not by leaving it alone.
		for(String id : g.nodesOf(WorkflowCompiler.CLASS_KSAMPLER)) {
			if(dropSeed) {
				g.setInput(id, WorkflowCompiler.INPUT_NOISE_SEED, Long.valueOf(WorkflowCompiler.MARKER_SEED + 1L));
			}
			else if(params.get("seed") instanceof Number) {
				g.setInput(id, WorkflowCompiler.INPUT_NOISE_SEED, Long.valueOf(((Number) params.get("seed")).longValue()));
			}
			int steps = (params.get("steps") instanceof Number ? ((Number) params.get("steps")).intValue() : 0);
			if(steps > 0 && !keepFixtureSteps) {
				g.setInput(id, WorkflowCompiler.INPUT_STEPS, Integer.valueOf(steps));
				if(inpaint && params.get("initimagecreativity") instanceof Number) {
					g.setInput(id, WorkflowCompiler.INPUT_START_AT_STEP,
						Integer.valueOf(WorkflowCompiler.startAtStep(steps, ((Number) params.get("initimagecreativity")).doubleValue())));
				}
			}
			if(params.get("cfgscale") instanceof Number) {
				g.setInput(id, "cfg", Double.valueOf(((Number) params.get("cfgscale")).doubleValue()));
			}
		}
		if(dropSave) {
			for(String id : g.saveNodeIds()) {
				g.removeNode(id);
			}
		}
		answers.add(g.copy());
		return g;
	}

	static String stripUri(Object v) {
		return WorkflowCompiler.stripDataUri(String.valueOf(v));
	}

	/// Same pixels, different bytes: decode and write back through an ARGB buffer.
	static String reencode(String base64) {
		try {
			BufferedImage src = MaskImage.decode(Base64.getDecoder().decode(base64));
			BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
			dst.getGraphics().drawImage(src, 0, 0, null);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(dst, "png", baos);
			return Base64.getEncoder().encodeToString(baos.toByteArray());
		}
		catch(IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
