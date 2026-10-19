package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P2-5 (PictureBook V3 design §12, §16, §20): {@code PbNodeExecutor} renders a scene <b>from its plan</b>
 * through the facade's synchronous {@code requestRenderPlanScene} (what
 * {@code POST /rest/pictureBook/{book}/scene/{scene}/render-plan} calls), as a non-admin owner, against
 * {@code am7db} and the live Swarm node ({@code test.swarm.server}). The SD backend is never mocked.
 * <ol>
 * <li>{@link #renderPlanCreatesNodeAndBaseArtifact}: the first call creates the scene's RENDER_PLAN node
 *     lazily, persists the plan as the node's {@code renderPlan} artifact, renders exactly the BASE stage
 *     and records the image as the selected {@code base} artifact (snapshot frozen with the compiled prompt
 *     and the installed checkpoint). The scene was never composed, so the first render composes it itself
 *     (P4-4 lazy fallback, §9.1) and reports exactly one {@code COMPOSED_OUT_OF_ORDER} degradation. A second
 *     call with an installed node override re-uses the SAME node, supersedes the artifact (revision 2) and
 *     reports no degradation (the composition now exists). Two real renders.</li>
 * <li>{@link #degradedOverrideStillRendersWithSubstitutedModel}: an uninstalled node override is rejected,
 *     the deployment default is substituted, the render proceeds and the DTO reports
 *     {@code MODEL_SUBSTITUTED}. One real render.</li>
 * <li>{@link #noInstalledModelIsReportedBeforeRendering}: nothing requested is installed -> 409 carrying
 *     the resolver's per-step reasons and the plan artifact id; the plan was persisted, no {@code base}
 *     artifact exists, the node is FAILED with that message, and nothing was sent to the renderer. Needs
 *     the inventory (Swarm reachable), no render.</li>
 * <li>{@link #unreachableRendererIsA503BeforeRendering}: an unreachable renderer -> 503 "inventory
 *     unavailable", plan persisted, no base artifact, no server URL in the message. No SD.</li>
 * <li>{@link #sceneWithoutTextIsA409BeforeRendering}: a scene with no setting/action/mood/sdPrompt/summary/
 *     title compiles to nothing -> 409 "has no text to render" after the model check. Needs the inventory,
 *     no render.</li>
 * <li>{@link #strangerAndV1AreRefusedBeforeAnyWork}: a stranger -> 404; a book whose product is pinned to
 *     the V1 render path -> 409 {@link PbRenderPath#REFUSAL_PREFIX}. No SD.</li>
 * <li>{@link #configNotAppliedReportsWhatThePlanPathDropsAndReviewQueueOmitsThePlan} (P2-6, §24): the DTO
 *     reports exactly the {@code HIRES} / {@code STYLE_CLAUSE} / {@code DEFAULT_NEGATIVE} codes the
 *     effective config calls for, each checked against the persisted plan and the request Swarm received,
 *     never as a degradation; the review queue lists the {@code base} artifact and never the
 *     {@code renderPlan}, which {@code nodeView} still exposes. The scene was never composed, so render 1
 *     reports exactly one {@code COMPOSED_OUT_OF_ORDER} degradation (P4-4 lazy fallback) and render 2
 *     reports none. Two real renders.</li>
 * </ol>
 * SD-gated cases skip visibly through {@link SdTestGate} when {@code .39} is unreachable or lists no
 * usable checkpoint; a skip is reported as a skip, never counted as a pass.
 */
public class TestPbRenderPlanExecutor extends BaseTest {

	private static final String SETTING = "a lighthouse on a basalt headland at dusk";
	private static final String ACTION = "gulls wheel over the breakwater";
	private static final String MOOD = "windswept and expectant";
	private static final String DEAD_SERVER = "http://127.0.0.1:1";
	private static final String BOGUS_OVERRIDE = "p25-bogus-override.safetensors";
	private static final String BOGUS_DEPLOYMENT = "p25-nonexistent-deployment.safetensors";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// P2-6: a node override that sets every knob §24 says the plan path does not apply.
	private static final String OVERRIDE_STYLE = "photograph";
	private static final String OVERRIDE_NEGATIVE = "blurry, low quality, text";
	/// Each configNotApplied entry is "CODE: text" for one of the three published codes.
	private static final Pattern NOT_APPLIED_SHAPE = Pattern.compile("^(" + PbNodeExecutor.NOT_APPLIED_HIRES + "|"
		+ PbNodeExecutor.NOT_APPLIED_STYLE_CLAUSE + "|" + PbNodeExecutor.NOT_APPLIED_DEFAULT_NEGATIVE + "): \\S");

	private String server;

	@Before
	public void executorSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SwarmModelInventory.invalidateAll();
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null ? rec.get(field) : null);
		return (v != null ? v.toString().toUpperCase() : null);
	}

	/// A V3 book with one scene, owned by {@code owner}. No graph node is created here on purpose: the
	/// facade must create the scene's RENDER_PLAN node itself.
	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord scene;
		String slug;
		String bookOid;
		String sceneOid;
	}

	private Fixture fixture(String prefix, boolean withText) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Render plan " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = f.book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, (withText ? "Scene 0" : null), PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("scene", scene);
		f.sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		if(withText) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD);
			patch.set(OlioFieldNames.FIELD_PB_SETTING, SETTING);
			patch.set(OlioFieldNames.FIELD_PB_ACTION, ACTION);
			patch.set(OlioFieldNames.FIELD_PB_MOOD, MOOD);
			assertNotNull("scene text patch must persist", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		}
		f.scene = PbBookUtil.readScene(f.owner, f.sceneOid, orgOf(f.owner));
		assertNotNull("re-read scene", f.scene);
		if(withText) {
			assertEquals(SETTING, f.scene.get(OlioFieldNames.FIELD_PB_SETTING));
		}
		return f;
	}

	private void cleanup(Fixture f) {
		if(f == null || f.book == null) return;
		try {
			PbDeleteUtil.deleteBookComplete(f.owner, f.bookOid);
		}
		catch(Exception e) {
			logger.warn("Cleanup of " + f.slug + ": " + e.getMessage());
		}
	}

	/// Pre-create the scene's RENDER_PLAN node under the deterministic handle the facade looks up, with an
	/// optional configOverride - the only way to put an OVERRIDE candidate in front of the resolver here.
	private BaseRecord preCreateRenderPlanNode(Fixture f, String configOverride) throws Exception {
		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		assertNotNull("workflow", wf);
		String handle = PbPipelineUtil.renderPlanHandle(f.sceneOid);
		BaseRecord n = PbGraphUtil.addNode(f.owner, wf, handle, PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
		assertNotNull("render-plan node " + handle, n);
		List<String> changed = new ArrayList<>(Arrays.asList(OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF));
		if(configOverride != null) {
			changed.add(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE);
		}
		BaseRecord patch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, changed.toArray(new String[0]));
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, f.sceneOid);
		if(configOverride != null) {
			patch.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, configOverride);
		}
		assertNotNull("node scope patch must be written", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		BaseRecord fresh = PbGraphUtil.readNode(f.owner, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(f.owner));
		assertNotNull(fresh);
		assertEquals(f.sceneOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		return fresh;
	}

	/// A sparse olio.sd.config override naming {@code model} at 12 steps, no hires.
	private static String modelOverride(String model) throws Exception {
		BaseRecord override = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG,
			new String[] { OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires" });
		override.set(OlioFieldNames.FIELD_SD_MODEL, model);
		override.set(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(12));
		override.set("hires", Boolean.FALSE);
		String json = PbConfigUtil.sparseOverride(override, Arrays.asList(OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires"));
		assertNotNull("sparse override", json);
		return json;
	}

	private BaseRecord freshReadData(BaseRecord u, String objectId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(u, q);
	}

	/// The artifact's bytes through the data ref, read fresh and decoded through ByteModelUtil.
	private byte[] artifactBytes(BaseRecord u, BaseRecord artifact) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("base artifact points at a data.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = freshReadData(u, dataOid);
		assertNotNull("data.data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		return bytes;
	}

	@SuppressWarnings("unchecked")
	private static List<String> strings(Map<String, Object> out, String key) {
		Object v = out.get(key);
		assertTrue(key + " is a list: " + v, v instanceof List);
		return (List<String>) v;
	}

	/// workflowView is a 404 for a book that has no workflow yet, so the find-only lookup answers "no
	/// workflow" as zero nodes instead of throwing.
	private int nodeCount(Fixture f) {
		if(PbGraphUtil.findWorkflow(f.owner, f.book) == null) {
			return 0;
		}
		Map<String, Object> view = PbServiceFacade.workflowView(f.owner, f.bookOid);
		Object nodes = view.get("nodes");
		return (nodes instanceof List ? ((List<?>) nodes).size() : 0);
	}

	/// Everything a successful render-plan execution must have left behind, checked against the DB, not
	/// the DTO alone.
	private BaseRecord assertRendered(Fixture f, Map<String, Object> out, String installed, int expectedRevision, String pngTag) throws Exception {
		logger.info("P2-5 DTO: " + JSONUtil.exportObject(out));
		assertEquals(Boolean.TRUE, out.get("executed"));
		assertEquals("v3", out.get("renderPath"));
		assertEquals(f.sceneOid, out.get("sceneObjectId"));
		assertEquals(Integer.valueOf(0), out.get("sceneIndex"));
		assertEquals("DONE_UNVERIFIED", out.get("nodeStatus"));
		assertEquals(PbStrategyEnumType.TEXT_ONLY.name(), out.get("strategy"));
		assertEquals(List.of(RenderPlan.STAGE_BASE), out.get("stagesPlanned"));
		assertEquals(List.of(RenderPlan.STAGE_BASE), out.get("stagesRendered"));
		assertEquals(PbPipelineUtil.renderPlanHandle(f.sceneOid), out.get("handle"));
		assertEquals(Integer.valueOf(expectedRevision), out.get("artifactRevision"));
		assertEquals(Integer.valueOf(0), out.get("downstreamMarked"));
		assertEquals("COMPLETED", String.valueOf(out.get("runStatus")).toUpperCase());
		assertNotNull("runObjectId", out.get("runObjectId"));
		assertTrue("byteLength > 0", out.get("byteLength") instanceof Number && ((Number) out.get("byteLength")).longValue() > 0L);

		long orgId = orgOf(f.owner);
		String nodeOid = (String) out.get("nodeObjectId");
		BaseRecord node = PbGraphUtil.readNode(f.owner, nodeOid, orgId);
		assertNotNull("the node the DTO names", node);
		assertEquals(PbNodeTypeEnumType.RENDER_PLAN.name(), status(node, OlioFieldNames.FIELD_PB_NODE_TYPE));
		assertEquals(PbPipelineUtil.SCOPE_SCENE, node.get(OlioFieldNames.FIELD_PB_SCOPE));
		assertEquals(f.sceneOid, node.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));

		/// The plan the DTO names is the node's selected renderPlan artifact and hashes to what the DTO says.
		String planOid = (String) out.get("planArtifactObjectId");
		assertNotNull("planArtifactObjectId", planOid);
		BaseRecord planArtifact = PbArtifactUtil.readArtifact(f.owner, planOid, orgId);
		assertNotNull("plan artifact readable as the owner", planArtifact);
		assertEquals(PbPipelineUtil.ROLE_RENDER_PLAN, planArtifact.get(OlioFieldNames.FIELD_PB_ROLE));
		RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(planArtifact);
		assertNotNull("the plan artifact parses", plan);
		assertEquals("planContentHash is the persisted plan's hash", out.get("planContentHash"), plan.contentHash());
		assertEquals(PbStrategyEnumType.TEXT_ONLY, plan.getStrategy().getName());
		BaseRecord selectedPlan = PbArtifactUtil.findSelected(f.owner, node, PbPipelineUtil.ROLE_RENDER_PLAN);
		assertNotNull(selectedPlan);
		assertEquals(planOid, selectedPlan.get(FieldNames.FIELD_OBJECT_ID));
		assertFalse("the plan never carries the server URL", plan.toJson().contains(server));

		/// The base artifact: selected, this revision, stamped, snapshot frozen with the prompt sent.
		String artifactOid = (String) out.get("artifactObjectId");
		assertNotNull("artifactObjectId", artifactOid);
		BaseRecord selectedBase = PbArtifactUtil.findSelected(f.owner, node, PbPipelineUtil.ROLE_BASE);
		assertNotNull("the base artifact is the node's selected `base`", selectedBase);
		assertEquals(artifactOid, selectedBase.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord artifact = PbArtifactUtil.readArtifact(f.owner, artifactOid, orgId);
		assertNotNull(artifact);
		assertEquals(PbPipelineUtil.ROLE_BASE, artifact.get(OlioFieldNames.FIELD_PB_ROLE));
		assertEquals(Integer.valueOf(expectedRevision), artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		assertEquals(Boolean.TRUE, artifact.get(OlioFieldNames.FIELD_PB_SELECTED));
		assertEquals("backend is stamped", SDAPIEnumType.SWARM.name(), status(artifact, OlioFieldNames.FIELD_PB_BACKEND));
		assertEquals("image/png", artifact.get(OlioFieldNames.FIELD_PB_MIME_TYPE));
		Object w = artifact.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH);
		Object h = artifact.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT);
		assertTrue("imageWidth recorded: " + w, w instanceof Number && ((Number) w).intValue() > 0);
		assertTrue("imageHeight recorded: " + h, h instanceof Number && ((Number) h).intValue() > 0);
		assertEquals("the image is the plan's canvas width", plan.getCanvas().getWidth(), ((Number) w).intValue());
		assertEquals("the image is the plan's canvas height", plan.getCanvas().getHeight(), ((Number) h).intValue());
		Object durationMs = artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS);
		assertTrue("durationMs is recorded (presence only, never a threshold): " + durationMs,
			durationMs instanceof Number && ((Number) durationMs).longValue() > 0L);
		String generatorRequest = artifact.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
		assertNotNull("the request sent is kept", generatorRequest);
		assertTrue("the request sent carries the compiled scene text (the prompt shown is the prompt sent)", generatorRequest.contains(SETTING));
		assertFalse("the request kept never carries a session id", generatorRequest.contains("session_id"));

		BaseRecord snapshot = artifact.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
		assertNotNull("sdConfigSnapshot is frozen on the artifact", snapshot);
		assertEquals(OlioModelNames.MODEL_SD_CONFIG, snapshot.getSchema());
		String snapModel = snapshot.get(OlioFieldNames.FIELD_SD_MODEL);
		assertEquals("the snapshot freezes the checkpoint that rendered", installed, snapModel);
		String snapDesc = snapshot.get(FieldNames.FIELD_DESCRIPTION);
		assertNotNull("the snapshot freezes the compiled prompt", snapDesc);
		assertTrue("...which carries the scene text: " + snapDesc, snapDesc.contains(SETTING));
		assertEquals("the snapshot's prompt is the plan's compiled BASE prompt", plan.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt(), snapDesc);
		assertEquals(Boolean.FALSE, snapshot.get("hires"));

		byte[] bytes = artifactBytes(f.owner, artifact);
		assertEquals("byteLength is the stored length", Long.valueOf(bytes.length), Long.valueOf(((Number) out.get("byteLength")).longValue()));
		File png = new File("target", "p25-render-plan-" + f.slug + "-" + pngTag + ".png");
		Files.write(png.toPath(), bytes);
		logger.info("MEASURED: P2-5 " + pngTag + " on " + server + " model=" + snapModel + " canvas=" + w + "x" + h
			+ " durationMs=" + durationMs + " bytes=" + bytes.length + " seed=" + artifact.get(OlioFieldNames.FIELD_PB_SEED)
			+ " png=" + png.getAbsolutePath());
		return node;
	}

	private static void assertRefusalShape(PictureBookException e, int status, String... mustContain) {
		assertEquals("status (" + e.getMessage() + ")", status, e.getStatus());
		for(String s : mustContain) {
			assertTrue("message must mention '" + s + "': " + e.getMessage(), e.getMessage().contains(s));
		}
		assertFalse("message never carries a host: " + e.getMessage(), e.getMessage().contains("127.0.0.1"));
		assertFalse("message never carries a LAN address: " + e.getMessage(), e.getMessage().contains("192.168."));
		assertFalse("message never carries a URL: " + e.getMessage(), e.getMessage().contains("http://"));
	}

	private static String planOidOf(PictureBookException e) {
		String marker = "(plan artifact ";
		int i = e.getMessage().lastIndexOf(marker);
		assertTrue("the refusal names the plan artifact: " + e.getMessage(), i >= 0);
		String rest = e.getMessage().substring(i + marker.length());
		int j = rest.indexOf(')');
		assertTrue(j > 0);
		return rest.substring(0, j);
	}

	/// After a refusal: the plan artifact persisted and is the node's selected renderPlan, no base
	/// artifact exists, and the node is FAILED carrying the message.
	private void assertRefusedBeforeRendering(Fixture f, PictureBookException e) throws Exception {
		long orgId = orgOf(f.owner);
		String planOid = planOidOf(e);
		BaseRecord planArtifact = PbArtifactUtil.readArtifact(f.owner, planOid, orgId);
		assertNotNull("the plan is persisted even when the render is refused (§12 rule 1)", planArtifact);
		assertEquals(PbPipelineUtil.ROLE_RENDER_PLAN, planArtifact.get(OlioFieldNames.FIELD_PB_ROLE));
		RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(planArtifact);
		assertNotNull(plan);

		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(f.owner, wf, PbPipelineUtil.renderPlanHandle(f.sceneOid));
		assertNotNull("the render-plan node exists", node);
		assertEquals(1, nodeCount(f));
		BaseRecord fresh = PbGraphUtil.readNode(f.owner, node.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertEquals("a refused node is FAILED", PbNodeStatusEnumType.FAILED.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertNotNull("lastError is stamped", lastError);
		assertTrue("lastError is the refusal: " + lastError, lastError.contains("plan artifact " + planOid));
		assertTrue("no base artifact was recorded", PbArtifactUtil.listChain(f.owner, fresh, PbPipelineUtil.ROLE_BASE).isEmpty());
		BaseRecord selectedPlan = PbArtifactUtil.findSelected(f.owner, fresh, PbPipelineUtil.ROLE_RENDER_PLAN);
		assertNotNull(selectedPlan);
		assertEquals(planOid, selectedPlan.get(FieldNames.FIELD_OBJECT_ID));
	}

	/// Every configNotApplied entry is "CODE: text" for a published code, no code twice; returns the codes.
	private static Set<String> notAppliedCodes(List<String> entries) {
		Set<String> codes = new HashSet<>();
		for(String e : entries) {
			assertNotNull("a null configNotApplied entry in " + entries, e);
			assertTrue("entry shape 'CODE: text' with a published code: '" + e + "'", NOT_APPLIED_SHAPE.matcher(e).find());
			String code = e.substring(0, e.indexOf(':'));
			assertTrue("code " + code + " reported twice: " + entries, codes.add(code));
		}
		return codes;
	}

	/// The §24 contract computed from the persisted plan itself: HIRES iff the plan's config asks for hires,
	/// STYLE_CLAUSE iff it carries a non-blank style, DEFAULT_NEGATIVE iff the compiled BASE negative is blank.
	private static Set<String> expectedCodesOf(RenderPlan plan) {
		Set<String> expected = new HashSet<>();
		Map<String, Object> cfg = plan.getConfig();
		if(Boolean.TRUE.equals(cfg.get("hires"))) {
			expected.add(PbNodeExecutor.NOT_APPLIED_HIRES);
		}
		Object style = cfg.get("style");
		if(style instanceof String && !((String) style).isBlank()) {
			expected.add(PbNodeExecutor.NOT_APPLIED_STYLE_CLAUSE);
		}
		String negative = plan.getCompiled().get(RenderPlan.STAGE_BASE).getNegative();
		if(negative == null || negative.isBlank()) {
			expected.add(PbNodeExecutor.NOT_APPLIED_DEFAULT_NEGATIVE);
		}
		return expected;
	}

	/// The txt2img request the base artifact keeps (what was actually sent to Swarm), as a map.
	private Map<?, ?> requestSent(Fixture f, Map<String, Object> out) {
		BaseRecord artifact = PbArtifactUtil.readArtifact(f.owner, (String) out.get("artifactObjectId"), orgOf(f.owner));
		assertNotNull(artifact);
		String json = artifact.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
		Map<?, ?> m = JSONUtil.importObject(json, Map.class);
		assertNotNull("generatorRequest parses: " + json, m);
		return m;
	}

	/// The review queue for the book: no listed artifact may be a renderPlan; returns the listed objectIds.
	private Set<String> queuedArtifactOids(Fixture f, String nodeOid) {
		Map<String, Object> queue = PbServiceFacade.listReviewQueue(f.owner, f.bookOid, PbServiceFacade.REVIEW_FILTER_ALL);
		logger.info("P2-6 review queue: " + JSONUtil.exportObject(queue));
		List<?> qNodes = (List<?>) queue.get("nodes");
		assertEquals("one node in the queue", 1, qNodes.size());
		Map<?, ?> qNode = (Map<?, ?>) qNodes.get(0);
		assertEquals(nodeOid, qNode.get("nodeObjectId"));
		Set<String> oids = new HashSet<>();
		for(Object o : (List<?>) qNode.get("artifacts")) {
			Map<?, ?> a = (Map<?, ?>) o;
			assertNotEquals("the review queue never lists a renderPlan artifact: " + a, PbPipelineUtil.ROLE_RENDER_PLAN, a.get("role"));
			oids.add((String) a.get("objectId"));
		}
		assertEquals("artifactCount is the listed count", Integer.valueOf(oids.size()), queue.get("artifactCount"));
		return oids;
	}

	// ─── 1. live render: lazy node, BASE artifact, second call supersedes ─────────

	@Test
	public void renderPlanCreatesNodeAndBaseArtifact() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P2-5 render-plan execution");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		assertEquals("precondition: the deployment default this JVM resolves is the installed checkpoint (BaseTest pins it)",
			installed, SDUtil.getDefaultModel());
		Fixture f = fixture("p25h", true);
		try {
			assertEquals("no node before the first call", 0, nodeCount(f));

			Map<String, Object> first = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, sdu);
			assertNotNull(first);
			BaseRecord node = assertRendered(f, first, installed, 1, "first");
			assertEquals("the facade created exactly one node", 1, nodeCount(f));
			List<String> degradations = strings(first, "degradations");
			assertEquals("a never-composed scene is composed by its first render, out of story order (P4-4 lazy fallback); nothing else is degraded: " + degradations,
				1, degradations.size());
			assertTrue("the one degradation is COMPOSED_OUT_OF_ORDER (a fresh book with no style resolves at DEPLOYMENT_DEFAULT with no substitution): " + degradations,
				degradations.get(0).startsWith(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER + ": "));
			String firstPlanOid = (String) first.get("planArtifactObjectId");
			String firstArtifactOid = (String) first.get("artifactObjectId");

			/// Second call: the node now carries an installed override at 12 steps. Same node (deterministic
			/// handle), the pick is the OVERRIDE, the base artifact is superseded.
			BaseRecord patch = PbGraphUtil.patchOf(node, OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE);
			patch.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, modelOverride(installed));
			assertNotNull("override patch must be written", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));

			Map<String, Object> second = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, sdu);
			assertNotNull(second);
			assertEquals("the second call re-uses the node", first.get("nodeObjectId"), second.get("nodeObjectId"));
			BaseRecord node2 = assertRendered(f, second, installed, 2, "second");
			assertEquals("still exactly one node", 1, nodeCount(f));
			assertTrue("an installed override is never degraded: " + second.get("degradations"), strings(second, "degradations").isEmpty());
			assertNotEquals("a new plan artifact per call", firstPlanOid, second.get("planArtifactObjectId"));
			assertNotEquals("a new base artifact per call", firstArtifactOid, second.get("artifactObjectId"));
			RenderPlan plan2 = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, (String) second.get("planArtifactObjectId"), orgOf(f.owner)));
			RenderPlan.ModelPick pick2 = plan2.pickForStage(RenderPlan.STAGE_BASE);
			assertEquals("the node override is the OVERRIDE pick", ModelResolver.Step.OVERRIDE.name(), pick2.getStep());
			assertEquals(installed, pick2.getModel());
			assertFalse(pick2.isDegraded());

			List<BaseRecord> baseChain = PbArtifactUtil.listChain(f.owner, node2, PbPipelineUtil.ROLE_BASE);
			assertEquals("two base revisions", 2, baseChain.size());
			BaseRecord older = PbArtifactUtil.readArtifact(f.owner, firstArtifactOid, orgOf(f.owner));
			assertEquals("revision 1 is no longer selected", Boolean.FALSE, older.get(OlioFieldNames.FIELD_PB_SELECTED));
			List<BaseRecord> planChain = PbArtifactUtil.listChain(f.owner, node2, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertEquals("two plan revisions", 2, planChain.size());
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 2. degraded: uninstalled override, deployment default substituted ───────

	@Test
	public void degradedOverrideStillRendersWithSubstitutedModel() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P2-5 degraded render-plan execution");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		assertFalse("precondition: the bogus override is really not installed", SdTestGate.isModelInstalled(sdu, BOGUS_OVERRIDE));
		Fixture f = fixture("p25d", true);
		try {
			BaseRecord pre = preCreateRenderPlanNode(f, modelOverride(BOGUS_OVERRIDE));
			Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, sdu);
			assertNotNull(out);
			assertEquals("the pre-created node under the deterministic handle is the one executed",
				pre.get(FieldNames.FIELD_OBJECT_ID), out.get("nodeObjectId"));
			assertRendered(f, out, installed, 1, "degraded");
			List<String> degradations = strings(out, "degradations");
			boolean substituted = false;
			for(String d : degradations) {
				if(d.startsWith(RenderPlan.Degradation.MODEL_SUBSTITUTED + ":")) {
					substituted = true;
					assertTrue("the degradation names the rejected override: " + d, d.contains(BOGUS_OVERRIDE));
					assertFalse("the degradation never carries a URL: " + d, d.contains("http://"));
				}
			}
			assertTrue("a rejected override is reported as MODEL_SUBSTITUTED on the DTO: " + degradations, substituted);
			RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, (String) out.get("planArtifactObjectId"), orgOf(f.owner)));
			RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
			assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT.name(), pick.getStep());
			assertEquals(installed, pick.getModel());
			assertTrue("the pick is degraded", pick.isDegraded());
			assertTrue("the trail starts with the rejected OVERRIDE: " + pick.getCandidates(),
				!pick.getCandidates().isEmpty() && ModelResolver.Step.OVERRIDE.name().equals(pick.getCandidates().get(0).get("step")));
			logger.info("MEASURED: degraded pick trail=" + pick.getCandidates() + " degradations=" + degradations);
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 3. nothing installed: 409 with the resolver's reasons, before rendering ──

	@Test
	public void noInstalledModelIsReportedBeforeRendering() throws Exception {
		SdTestGate.requireSwarmReachable(server);
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		assertFalse(SdTestGate.isModelInstalled(sdu, BOGUS_DEPLOYMENT));
		assertFalse(SdTestGate.isModelInstalled(sdu, BOGUS_OVERRIDE));
		Fixture f = fixture("p25n", true);
		String previousDefault = SDUtil.getDefaultModel();
		try {
			preCreateRenderPlanNode(f, modelOverride(BOGUS_OVERRIDE));
			/// The deployment default is the last candidate (a fresh book carries no style/book config). Point
			/// it at something not installed for this one call; restored in finally, and BaseTest re-pins it
			/// on every test anyway.
			SDUtil.setDefaultModel(BOGUS_DEPLOYMENT);
			try {
				Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, sdu);
				fail("nothing installed must be refused before rendering, got " + JSONUtil.exportObject(out));
			}
			catch(PictureBookException e) {
				logger.info("MEASURED: NO_CANDIDATE refusal: " + e.getStatus() + " " + e.getMessage());
				assertRefusalShape(e, 409, "No installed model for scene 0",
					ModelResolver.Step.OVERRIDE.name() + " '" + BOGUS_OVERRIDE + "' " + ModelResolver.Reason.NOT_INSTALLED.name(),
					ModelResolver.Step.DEPLOYMENT_DEFAULT.name() + " '" + BOGUS_DEPLOYMENT + "' " + ModelResolver.Reason.NOT_INSTALLED.name(),
					"(plan artifact ");
				assertRefusedBeforeRendering(f, e);
				RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, planOidOf(e), orgOf(f.owner)));
				RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
				assertNotNull(pick);
				assertFalse(pick.isResolved());
				assertNull("no model was guessed (§20)", pick.getModel());
				assertEquals(ModelResolver.Reason.NO_CANDIDATE.name(), pick.getReason());
			}
		}
		finally {
			SDUtil.setDefaultModel(previousDefault);
			cleanup(f);
		}
	}

	// ─── 4. unreachable renderer: 503 before rendering (no SD) ───────────────────

	@Test
	public void unreachableRendererIsA503BeforeRendering() throws Exception {
		SDUtil dead = new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER);
		Fixture f = fixture("p25u", true);
		try {
			long start = System.nanoTime();
			try {
				Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, dead);
				fail("an unreachable renderer must be refused before rendering, got " + JSONUtil.exportObject(out));
			}
			catch(PictureBookException e) {
				long ms = (System.nanoTime() - start) / 1_000_000L;
				logger.info("MEASURED: unreachable refusal in " + ms + " ms: " + e.getStatus() + " " + e.getMessage());
				assertRefusalShape(e, 503, "Renderer inventory unavailable", "scene 0", "(plan artifact ");
				assertRefusedBeforeRendering(f, e);
				RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, planOidOf(e), orgOf(f.owner)));
				RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
				assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name(), pick.getReason());
				assertFalse("the persisted plan never carries the server URL", plan.toJson().contains(DEAD_SERVER));
			}
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 5. no text: 409 after the model check, before rendering ─────────────────

	@Test
	public void sceneWithoutTextIsA409BeforeRendering() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P2-5 empty-scene refusal");
		assertNotNull(renderCfg.get(OlioFieldNames.FIELD_SD_MODEL));
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		Fixture f = fixture("p25e", false);
		try {
			assertNull(f.scene.get(OlioFieldNames.FIELD_PB_SETTING));
			assertNull(f.scene.get(OlioFieldNames.FIELD_PB_TITLE));
			/// Precondition the refusal depends on: a scene with nothing to say compiles to nothing (a fresh
			/// book has no style text, palette or photoreal boilerplate to pad it with).
			RenderPlan preview = PbRenderPlanUtil.buildScenePlan(f.owner, f.book, f.scene, PbRenderPlanUtil.PlanOptions.NONE, SDAPIEnumType.SWARM, server);
			String compiled = preview.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt();
			assertTrue("precondition: an empty scene compiles to an empty prompt, got '" + compiled + "'", compiled == null || compiled.isBlank());
			assertTrue("precondition: the model resolves, so the text check is what refuses", preview.pickForStage(RenderPlan.STAGE_BASE).isResolved());

			try {
				Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, sdu);
				fail("an empty scene must be refused before rendering, got " + JSONUtil.exportObject(out));
			}
			catch(PictureBookException e) {
				logger.info("MEASURED: empty-scene refusal: " + e.getStatus() + " " + e.getMessage());
				assertRefusalShape(e, 409, "Scene 0 has no text to render", "setting/action or sdPrompt", "(plan artifact ");
				assertRefusedBeforeRendering(f, e);
			}
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 6. stranger 404, V1 409 (no SD) ─────────────────────────────────────────

	@Test
	public void strangerAndV1AreRefusedBeforeAnyWork() throws Exception {
		SDUtil dead = new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER);
		Fixture f = fixture("p25s", true);
		BaseRecord stranger = getCreateUser("pbPlanStranger");
		assertNotNull(stranger);
		try {
			try {
				PbServiceFacade.requestRenderPlanScene(stranger, f.bookOid, f.sceneOid, dead);
				fail("a stranger must not render another owner's scene");
			}
			catch(PictureBookException e) {
				assertEquals("stranger -> 404 (" + e.getMessage() + ")", 404, e.getStatus());
			}
			try {
				PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, "no-such-scene", dead);
				fail("unknown scene");
			}
			catch(PictureBookException e) {
				assertEquals(404, e.getStatus());
			}
			assertEquals("refusals before the node gate create no node", 0, nodeCount(f));

			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
			try {
				PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, dead);
				fail("a V1-pinned product must be refused");
			}
			catch(PictureBookException e) {
				assertEquals("V1 -> 409 (" + e.getMessage() + ")", 409, e.getStatus());
				assertTrue(e.getMessage(), e.getMessage().startsWith(PbRenderPath.REFUSAL_PREFIX));
			}
			finally {
				PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			}
			assertEquals("the V1 refusal creates no node either", 0, nodeCount(f));
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 7. P2-6: configNotApplied on the plan path; the review queue omits the plan ───

	/**
	 * P2-6 (§24 as-built): what the plan path does not apply is reported under {@code configNotApplied},
	 * never dropped silently, and is not a degradation. Render 1 - a fresh book with no config tier: the
	 * effective config is the schema default ({@code hires=true}, no {@code style}, no {@code negativePrompt}),
	 * so the DTO reports exactly {HIRES, DEFAULT_NEGATIVE} and the request Swarm received carries no refiner
	 * and no negative. Render 2 - a node override with {@code hires=false}, {@code style=photograph} and a
	 * {@code negativePrompt}: exactly {STYLE_CLAUSE} naming the style, and the config's negative is what was
	 * sent. Each code is also checked against the §24 iff-rule computed from the persisted plan. Between the
	 * renders: the review queue lists the {@code base} artifact and never the {@code renderPlan}, while
	 * {@code nodeView} for the same node still exposes the {@code renderPlan} chain. Two real renders.
	 */
	@Test
	public void configNotAppliedReportsWhatThePlanPathDropsAndReviewQueueOmitsThePlan() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P2-6 configNotApplied on the plan path");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		assertEquals("precondition: the deployment default this JVM resolves is the installed checkpoint (BaseTest pins it)",
			installed, SDUtil.getDefaultModel());
		Fixture f = fixture("p26c", true);
		try {
			long orgId = orgOf(f.owner);
			PbStyleResolver.EffectiveStyle chain = PbStyleResolver.resolve(f.owner, PbBookUtil.readBook(f.owner, f.bookOid, orgId));
			assertTrue("precondition: a fresh book resolves no style hop (no LIBRARY style under /Library/"
				+ PbStyleResolver.LIBRARY_STYLES + " in this org); got " + chain.chain().size() + " hop(s)", chain.isEmpty());

			/// Render 1: schema defaults only (no book tier, no override).
			Map<String, Object> first = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, sdu);
			assertNotNull(first);
			BaseRecord node = assertRendered(f, first, installed, 1, "p26-defaults");
			String nodeOid = (String) first.get("nodeObjectId");
			String planOid1 = (String) first.get("planArtifactObjectId");
			String baseOid1 = (String) first.get("artifactObjectId");
			List<String> degradations1 = strings(first, "degradations");
			assertEquals("a never-composed scene is composed by its first render, out of story order (P4-4 lazy fallback); nothing else is degraded - configNotApplied is not a degradation: " + degradations1,
				1, degradations1.size());
			assertTrue("the one degradation is COMPOSED_OUT_OF_ORDER: " + degradations1,
				degradations1.get(0).startsWith(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER + ": "));
			List<String> notApplied1 = strings(first, PbNodeExecutor.KEY_CONFIG_NOT_APPLIED);
			Set<String> codes1 = notAppliedCodes(notApplied1);
			RenderPlan plan1 = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, planOid1, orgId));
			Map<String, Object> cfg1 = plan1.getConfig();
			assertEquals("the schema default hires=true is the effective config", Boolean.TRUE, cfg1.get("hires"));
			assertNull("no tier set a style", cfg1.get("style"));
			assertNull("no tier set a negativePrompt", cfg1.get("negativePrompt"));
			String negative1 = plan1.getCompiled().get(RenderPlan.STAGE_BASE).getNegative();
			assertTrue("no style exclusions and no negativePrompt compile to a blank negative, got '" + negative1 + "'",
				negative1 == null || negative1.isBlank());
			assertEquals("HIRES iff hires, STYLE_CLAUSE iff style, DEFAULT_NEGATIVE iff blank negative: " + notApplied1,
				expectedCodesOf(plan1), codes1);
			assertEquals(new HashSet<>(Arrays.asList(PbNodeExecutor.NOT_APPLIED_HIRES, PbNodeExecutor.NOT_APPLIED_DEFAULT_NEGATIVE)), codes1);
			Map<?, ?> sent1 = requestSent(f, first);
			assertNull("HIRES: no refiner pass was sent", sent1.get("refinermodel"));
			Object neg1 = sent1.get("negativeprompt");
			assertTrue("DEFAULT_NEGATIVE: no negative was sent (the v1 default is not applied), got " + neg1,
				neg1 == null || String.valueOf(neg1).isBlank());
			logger.info("MEASURED: P2-6 render 1 configNotApplied=" + notApplied1);

			/// The review queue: the base artifact is listed, the plan artifact is not; nodeView still has the plan.
			Set<String> queued = queuedArtifactOids(f, nodeOid);
			assertTrue("the base artifact is queued for review: " + queued, queued.contains(baseOid1));
			assertFalse("the plan artifact is not queued for review: " + queued, queued.contains(planOid1));
			assertEquals(1, queued.size());
			Map<String, Object> view = PbServiceFacade.nodeView(f.owner, f.bookOid, nodeOid);
			Map<?, ?> chains = (Map<?, ?>) view.get("artifacts");
			assertNotNull("nodeView exposes artifacts by role", chains);
			List<?> planChain = (List<?>) chains.get(PbPipelineUtil.ROLE_RENDER_PLAN);
			assertNotNull("nodeView still exposes the renderPlan chain, roles=" + chains.keySet(), planChain);
			assertEquals(1, planChain.size());
			assertEquals(planOid1, ((Map<?, ?>) planChain.get(0)).get("objectId"));
			assertEquals(PbPipelineUtil.ROLE_RENDER_PLAN, ((Map<?, ?>) planChain.get(0)).get("role"));
			List<?> baseChain = (List<?>) chains.get(PbPipelineUtil.ROLE_BASE);
			assertNotNull("nodeView exposes the base chain too", baseChain);
			assertEquals(baseOid1, ((Map<?, ?>) baseChain.get(0)).get("objectId"));

			/// Render 2: a node override that sets each knob the plan path reports.
			String[] fields = { OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires", "style", "negativePrompt" };
			BaseRecord override = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, fields);
			override.set(OlioFieldNames.FIELD_SD_MODEL, installed);
			override.set(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(12));
			override.set("hires", Boolean.FALSE);
			override.set("style", OVERRIDE_STYLE);
			override.set("negativePrompt", OVERRIDE_NEGATIVE);
			String sparse = PbConfigUtil.sparseOverride(override, Arrays.asList(fields));
			assertNotNull("sparse override", sparse);
			assertTrue("override patch must be written", PbGraphUtil.persistConfigOverride(f.owner, node, sparse));

			Map<String, Object> second = PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, f.sceneOid, sdu);
			assertNotNull(second);
			assertEquals("the second call re-uses the node", nodeOid, second.get("nodeObjectId"));
			assertRendered(f, second, installed, 2, "p26-override");
			assertTrue("an installed override is not a degradation: " + second.get("degradations"), strings(second, "degradations").isEmpty());
			List<String> notApplied2 = strings(second, PbNodeExecutor.KEY_CONFIG_NOT_APPLIED);
			Set<String> codes2 = notAppliedCodes(notApplied2);
			String planOid2 = (String) second.get("planArtifactObjectId");
			RenderPlan plan2 = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, planOid2, orgId));
			Map<String, Object> cfg2 = plan2.getConfig();
			assertEquals("the override's hires=false is the effective config", Boolean.FALSE, cfg2.get("hires"));
			assertEquals("the override's style is the effective config", OVERRIDE_STYLE, cfg2.get("style"));
			assertEquals("the override's negativePrompt is the effective config", OVERRIDE_NEGATIVE, cfg2.get("negativePrompt"));
			String negative2 = plan2.getCompiled().get(RenderPlan.STAGE_BASE).getNegative();
			assertEquals("the config's negativePrompt compiles into the BASE negative", OVERRIDE_NEGATIVE, negative2);
			assertEquals("HIRES iff hires, STYLE_CLAUSE iff style, DEFAULT_NEGATIVE iff blank negative: " + notApplied2,
				expectedCodesOf(plan2), codes2);
			assertEquals(new HashSet<>(Arrays.asList(PbNodeExecutor.NOT_APPLIED_STYLE_CLAUSE)), codes2);
			assertTrue("STYLE_CLAUSE names the style it did not apply: " + notApplied2, notApplied2.get(0).contains("'" + OVERRIDE_STYLE + "'"));
			Map<?, ?> sent2 = requestSent(f, second);
			assertNull("hires=false: no refiner pass was sent", sent2.get("refinermodel"));
			assertEquals("the compiled negative is what was sent", OVERRIDE_NEGATIVE, sent2.get("negativeprompt"));
			logger.info("MEASURED: P2-6 render 2 configNotApplied=" + notApplied2);

			/// Still no plan in the queue after a second plan revision; both plan revisions in the node view.
			Set<String> queued2 = queuedArtifactOids(f, nodeOid);
			assertFalse(queued2.contains(planOid1));
			assertFalse(queued2.contains(planOid2));
			assertTrue(queued2.contains((String) second.get("artifactObjectId")));
			Map<?, ?> chains2 = (Map<?, ?>) PbServiceFacade.nodeView(f.owner, f.bookOid, nodeOid).get("artifacts");
			assertEquals("two plan revisions in the node view", 2, ((List<?>) chains2.get(PbPipelineUtil.ROLE_RENDER_PLAN)).size());
		}
		finally {
			cleanup(f);
		}
	}
}
