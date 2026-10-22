package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.fkId;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.idOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CivilUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.CastTier;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.PromoteResult;
import org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.PlanOptions;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.OutfitSource;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.PinResult;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.ResolvedOutfit;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.cote.accountmanager.schema.type.PbImportanceEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-7: {@link PbRenderPlanUtil#buildScenePlan} reads the SCENE composition's actor and element rows
 * into the {@link RenderPlan} - no SD server is contacted (the plan is built against a dead address). Live, on
 * am7db as {@code testUser1}: actor rows become actors in priority order carrying the character's narrative text
 * as {@code identity.text}; a row whose character FK cannot be read is {@code ACTOR_UNRESOLVED} with the row's own
 * text; a readable character with no approved portrait is {@code ACTOR_TEXT_ONLY "(no approved portrait)"}; a
 * crowd row with no character is plain text with its count; elements carry {@code PbComposeUtil.elementText} (the
 * promoted one shows the item's text) and their importance; the composition's {@code promptOverride} feeds the
 * intent unless {@link PlanOptions} overrides it; a pinned apparel makes {@code outfit.text} start with
 * "wearing " and reduces {@code identity.text} to the physical description; an approved portrait clears the
 * text-only degradation; a scene with no composition yields no actors and no actor degradation; and the
 * degradations survive {@code persistPlanArtifact} / {@code readPlanArtifact}.
 */
public class TestPbRenderPlanActors extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SECOND_USER = "testUser2";
	private static final String DEAD_SERVER = "http://127.0.0.1:1";
	private static final String HERO_PHYS = "a tall woman with cropped silver hair and a scar across one brow";
	private static final String HERO_OUTFIT = "a patched oilskin coat over a fisherman's sweater";
	private static final String SUPP_PHYS = "a stooped man with a grey beard and a brass ear-trumpet";
	private static final String GHOST_TEXT = "a hooded figure half-seen in the doorway";
	private static final String COMP_OVERRIDE = "p47 composition prompt override " + UUID.randomUUID();
	private static final String CALLER_OVERRIDE = "p47 caller prompt override " + UUID.randomUUID();
	private static final String SETTING = "a rain-dark harbour street at dusk";

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
	}

	@Test
	public void case01_compositionRowsBecomeActorsElementsAndIntent() throws Exception {
		BaseRecord u = user();
		BaseRecord u2 = user(SECOND_USER);
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "a");
		BaseRecord ghost = null;
		try {
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			BookContext bctx = bookContext(u, bk);
			BaseRecord popGroup = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("population group", popGroup);
			assertNotNull("population path", popPath);

			String heroOid = createCharPersonIn(u, popGroup, popPath, "Maren Holt " + shortId());
			String suppOid = createCharPersonIn(u, popGroup, popPath, "Old Tobias " + shortId());
			BaseRecord hero = readChar(u, heroOid);
			BaseRecord supp = readChar(u, suppOid);
			giveNarrative(u, hero, HERO_PHYS, HERO_OUTFIT);
			giveNarrative(u, supp, SUPP_PHYS, null);

			/// A character testUser1 cannot read: owned by testUser2 in testUser2's home directory. (A *deleted*
			/// character reads back as a null FK on the row and is planned as a plain text row - measured 2026-10-20.)
			BaseRecord ghostGroup = ioContext.getPathUtil().makePath(u2, ModelNames.MODEL_GROUP, "~/p47ghost", GroupEnumType.DATA.toString(), orgId);
			assertNotNull("testUser2 ghost group", ghostGroup);
			String ghostOid = createCharPersonIn(u2, ghostGroup, ghostGroup.get(FieldNames.FIELD_PATH), "Ghost Row " + shortId());
			ghost = readChar(u2, ghostOid);
			assertNull("precondition: testUser1 cannot read testUser2's character", PbWardrobeUtil.readCharacterById(u, idOf(ghost), orgId));

			BaseRecord location = PbLocationUtil.getCreateLocation(u, bk, "The Gilded Tankard", "A low-beamed tavern by the quay",
				PbLocationKindEnumType.INTERIOR, Arrays.asList("the Tankard", "the taproom"));
			assertNotNull("location", location);
			BaseRecord scene = addScene(u, bk, 0, "Arrival", SETTING, COMP_OVERRIDE, location);
			enrol(u, scene, hero);
			enrol(u, scene, supp);

			BaseRecord book = PbBookUtil.readBook(u, oidOf(bk), orgId);
			assertNotNull("readBook", book);
			ComposeResult cr = PbComposeUtil.composeMigrated(u, book, scene, false);
			assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
			assertEquals("cast tier", CastTier.SCENE_MEMBERS, cr.castTier);
			BaseRecord comp = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
			assertNotNull("composition", comp);
			assertEquals("composition promptOverride copied from the scene's sdPrompt", COMP_OVERRIDE, comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("two roster rows", 2, PbComposeUtil.listActors(u, comp).size());

			BaseRecord ghostRow = newActor(u, comp, ghost, PbActorRoleEnumType.SUPPORTING, 2, GHOST_TEXT, 1);
			BaseRecord crowdRow = newActor(u, comp, null, PbActorRoleEnumType.CROWD, 3, "dockhands", 3);
			/// The compose step stores count >= 1 on every row and names every row by its handle (measured live
			/// 2026-10-20: "actor.<composition>.1, 1 a pile of belongings" reached the SD prompt).
			BaseRecord pileRow = newActor(u, comp, null, PbActorRoleEnumType.EXTRA, 4, "a pile of belongings", 1);

			BaseRecord cupEl = newElement(u, comp, "a dented pewter cup", PbElementTypeEnumType.PROP);
			PromoteResult pr = PbComposeUtil.promoteElement(u, book, oidOf(comp), oidOf(cupEl));
			assertTrue("cup promoted: " + pr.warnings, pr.promoted);
			assertNotNull("promoted item", pr.item);
			log("promoted cup: item=" + oidOf(pr.item) + " elementText=\"" + pr.elementText + "\"");
			BaseRecord lampEl = newElement(u, comp, "a cracked lantern on the wall", PbElementTypeEnumType.FURNITURE);

			List<BaseRecord> rows = PbComposeUtil.listActors(u, comp);
			assertEquals("five actor rows", 5, rows.size());
			BaseRecord freshScene = PbBookUtil.readScene(u, oidOf(scene), orgId);
			RenderPlan plan = PbRenderPlanUtil.buildScenePlan(u, book, freshScene, PlanOptions.NONE, SDAPIEnumType.SWARM, DEAD_SERVER);
			assertNotNull("plan", plan);
			log("degradations: " + plan.getDegradations());
			log("summary: " + plan.actorSummary());

			List<RenderPlan.Actor> actors = plan.getActors();
			assertEquals("five actors", 5, actors.size());
			int lastPriority = Integer.MIN_VALUE;
			for(int i = 0; i < rows.size(); i++) {
				RenderPlan.Actor a = actors.get(i);
				log("actor[" + i + "] id=" + a.getActorId() + " char=" + a.getCharacterId() + " name=" + a.getName() + " role=" + a.getRole()
					+ " priority=" + a.getPriority() + " referenced=" + a.isReferenced() + " text=\"" + a.getDescription() + "\"");
				assertTrue("priority order at " + i, a.getPriority() >= lastPriority);
				lastPriority = a.getPriority();
			}

			RenderPlan.Actor heroA = actorByCharacter(actors, heroOid);
			assertEquals("hero identity text is narrative physical + outfit description", HERO_PHYS + ". " + HERO_OUTFIT, heroA.getIdentity().getText());
			assertEquals("getDescription is identity.text", heroA.getIdentity().getText(), heroA.getDescription());
			assertNull("hero has no outfit", heroA.getOutfit());
			assertNull("hero has no portrait", heroA.getIdentity().getPortraitArtifactId());
			assertEquals("hero role", "HERO", heroA.getRole());
			assertFalse("hero is not referenced without a portrait", heroA.isReferenced());
			expectOne(plan, RenderPlan.Degradation.ACTOR_TEXT_ONLY, heroA.getActorId(), heroA.getName() + " rendered from description (no approved portrait)");

			RenderPlan.Actor suppA = actorByCharacter(actors, suppOid);
			assertEquals("supporting identity text is the physical description alone", SUPP_PHYS, suppA.getIdentity().getText());
			assertEquals("supporting role", "SUPPORTING", suppA.getRole());
			expectOne(plan, RenderPlan.Degradation.ACTOR_TEXT_ONLY, suppA.getActorId(), suppA.getName() + " rendered from description (no approved portrait)");

			BaseRecord ghostRead = rowById(rows, idOf(ghostRow));
			BaseRecord stub = ghostRead.get(OlioFieldNames.FIELD_PB_CHARACTER);
			assertNotNull("the deleted character's FK still reads back as a stub on the row", stub);
			String ghostCharOid = (stub.hasField(FieldNames.FIELD_OBJECT_ID) ? stub.get(FieldNames.FIELD_OBJECT_ID) : null);
			long ghostCharId = fkId(ghostRead, OlioFieldNames.FIELD_PB_CHARACTER);
			assertEquals("stub id is the deleted character's id", idOf(ghost), ghostCharId);
			String ghostRowName = ghostRead.get(FieldNames.FIELD_NAME);
			String expectedGhostActorId = (ghostCharOid != null ? ghostCharOid : oidOf(ghostRow));
			RenderPlan.Actor ghostA = actorById(actors, expectedGhostActorId);
			assertEquals("unresolved actor renders from the row's text", GHOST_TEXT, ghostA.getIdentity().getText());
			assertTrue("precondition: the row is named by the compose handle", ghostRowName.startsWith(PbComposeUtil.ACTOR_NAME_PREFIX));
			assertNull("a handle-named row carries no name", ghostA.getName());
			assertFalse("unresolved is never referenced", ghostA.isReferenced());
			assertNull("unresolved has no portrait", ghostA.getIdentity().getPortraitArtifactId());
			expectOne(plan, RenderPlan.Degradation.ACTOR_UNRESOLVED, expectedGhostActorId,
				GHOST_TEXT + " rendered from the row's text (character " + (ghostCharOid != null ? ghostCharOid : "#" + ghostCharId) + " could not be read)");

			RenderPlan.Actor crowdA = actorById(actors, oidOf(crowdRow));
			assertNull("crowd row has no character", crowdA.getCharacterId());
			assertNull("crowd row carries no name (the handle is not one)", crowdA.getName());
			assertEquals("crowd role", "CROWD", crowdA.getRole());
			assertEquals("crowd text is count + description", "3 dockhands", crowdA.getIdentity().getText());
			assertEquals("getDescription of a text actor is its text alone", "3 dockhands", crowdA.getDescription());
			assertFalse("crowd is background", crowdA.isForeground());
			assertFalse("crowd is not referenced", crowdA.isReferenced());
			assertTrue("no degradation names the crowd row",
				plan.getDegradations().stream().noneMatch(d -> oidOf(crowdRow).equals(d.getActorId())));

			RenderPlan.Actor pileA = actorById(actors, oidOf(pileRow));
			assertNull("single extra carries no name", pileA.getName());
			assertEquals("a count of 1 is not folded into the text", "a pile of belongings", pileA.getIdentity().getText());
			assertEquals("extra role", "EXTRA", pileA.getRole());
			assertFalse("extra is not referenced", pileA.isReferenced());
			assertTrue("no degradation names the extra row",
				plan.getDegradations().stream().noneMatch(d -> oidOf(pileRow).equals(d.getActorId())));
			assertFalse("the plan carries a compiled prompt", plan.getCompiled().isEmpty());
			for(CompiledPrompt cp : plan.getCompiled().values()) {
				assertFalse("no compose handle reaches a compiled prompt: " + cp.getPrompt(), cp.getPrompt().contains(PbComposeUtil.ACTOR_NAME_PREFIX + oidOf(comp)));
				assertFalse("no bare count reaches a compiled prompt: " + cp.getPrompt(), cp.getPrompt().contains("1 a pile"));
			}

			List<RenderPlan.Element> elements = plan.getElements();
			assertEquals("two elements", 2, elements.size());
			for(RenderPlan.Element e : elements) {
				log("element type=" + e.getType() + " name=" + e.getName() + " importance=" + e.getImportance() + " item=" + e.getItemId() + " text=\"" + e.getDescription() + "\"");
			}
			RenderPlan.Element cup = elementByType(elements, PbElementTypeEnumType.PROP.toString());
			BaseRecord cupRead = PbComposeUtil.readElement(u, oidOf(cupEl), orgId);
			assertEquals("promoted element description is elementText (the item's text)", PbComposeUtil.elementText(u, cupRead), cup.getDescription());
			assertEquals("promoted element description matches the promote result", pr.elementText, cup.getDescription());
			assertEquals("promoted element carries the item id", oidOf(pr.item), cup.getItemId());
			assertEquals("cup importance", PbImportanceEnumType.MEDIUM.toString(), cup.getImportance());
			RenderPlan.Element lamp = elementByType(elements, PbElementTypeEnumType.FURNITURE.toString());
			BaseRecord lampRead = PbComposeUtil.readElement(u, oidOf(lampEl), orgId);
			assertEquals("plain element description is elementText", PbComposeUtil.elementText(u, lampRead), lamp.getDescription());
			assertEquals("plain element text is its tidied description", "a cracked lantern on the wall", lamp.getDescription());
			assertNull("plain element has no item", lamp.getItemId());
			assertEquals("lamp importance", PbImportanceEnumType.MEDIUM.toString(), lamp.getImportance());

			assertEquals("intent promptOverride comes from the composition when PlanOptions has none", COMP_OVERRIDE, plan.getIntent().getPromptOverride());
			assertFalse("intent is not raw", plan.getIntent().isRaw());
			RenderPlan plan2 = PbRenderPlanUtil.buildScenePlan(u, book, freshScene, new PlanOptions(CALLER_OVERRIDE, true, null, null), SDAPIEnumType.SWARM, DEAD_SERVER);
			assertEquals("PlanOptions promptOverride wins over the composition's", CALLER_OVERRIDE, plan2.getIntent().getPromptOverride());
			assertTrue("PlanOptions raw flag carried", plan2.getIntent().isRaw());

			long referenced = actors.stream().filter(RenderPlan.Actor::isReferenced).count();
			assertEquals("actorSummary", "5 characters: " + referenced + " referenced, " + (5 - referenced) + " text, 1 unresolved", plan.actorSummary());

			BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, book, PbBookUtil.workflowGroupPath(slug));
			assertNotNull("workflow", wf);
			BaseRecord planNode = PbGraphUtil.addNode(u, wf, "p47-plan-" + shortId(), PbNodeTypeEnumType.RENDER_PLAN, PbBookUtil.workflowGroupPath(slug), 0);
			assertNotNull("plan node", planNode);
			BaseRecord artifact = PbRenderPlanUtil.persistPlanArtifact(u, planNode, PbBookUtil.artifactGroupPath(slug), plan);
			assertNotNull("plan artifact", artifact);
			RenderPlan back = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(u, oidOf(artifact), orgId));
			assertNotNull("plan read back from the artifact", back);
			assertEquals("degradations survive the artifact round trip",
				plan.getDegradations().stream().map(Object::toString).collect(Collectors.toList()),
				back.getDegradations().stream().map(Object::toString).collect(Collectors.toList()));
			assertEquals("actor ids survive the artifact round trip",
				actors.stream().map(RenderPlan.Actor::getActorId).collect(Collectors.toList()),
				back.getActors().stream().map(RenderPlan.Actor::getActorId).collect(Collectors.toList()));
			assertEquals("intent survives the artifact round trip", COMP_OVERRIDE, back.getIntent().getPromptOverride());
			assertEquals("content hash is stable across the round trip", plan.contentHash(), back.contentHash());
			log("case01 done");
		}
		finally {
			deleteBook(u, bk);
			if(ghost != null) {
				log("cleanup ghost character as testUser2: " + ioContext.getAccessPoint().delete(u2, ghost));
			}
		}
	}

	@Test
	public void case02_pinnedApparelAndApprovedPortrait() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "b");
		try {
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			BookContext bctx = bookContext(u, bk);
			BaseRecord popGroup = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			OlioContext octx = PbOlioContextUtil.getCreateBookContext(u, dataPath(), slug);
			assertNotNull("olio context", octx);

			String heroOid = createCharPersonIn(u, popGroup, popPath, "Ilsa Varne " + shortId());
			BaseRecord hero = readChar(u, heroOid);
			giveNarrative(u, hero, HERO_PHYS, HERO_OUTFIT);
			BaseRecord scene = addScene(u, bk, 0, "Dockside", SETTING, null, null);
			enrol(u, scene, hero);
			BaseRecord book = PbBookUtil.readBook(u, oidOf(bk), orgId);
			ComposeResult cr = PbComposeUtil.composeMigrated(u, book, scene, false);
			assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
			BaseRecord comp = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
			List<BaseRecord> rows = PbComposeUtil.listActors(u, comp);
			assertEquals("one actor row", 1, rows.size());
			String actorRowOid = oidOf(rows.get(0));
			BaseRecord freshScene = PbBookUtil.readScene(u, oidOf(scene), orgId);

			RenderPlan planA = PbRenderPlanUtil.buildScenePlan(u, book, freshScene, PlanOptions.NONE, SDAPIEnumType.SWARM, DEAD_SERVER);
			log("planA degradations: " + planA.getDegradations());
			assertEquals("one actor", 1, planA.getActors().size());
			RenderPlan.Actor a1 = planA.getActors().get(0);
			assertEquals("before apparel: identity is physical + outfit description", HERO_PHYS + ". " + HERO_OUTFIT, a1.getIdentity().getText());
			assertNull("before apparel: no outfit", a1.getOutfit());
			expectOne(planA, RenderPlan.Degradation.ACTOR_TEXT_ONLY, a1.getActorId(), a1.getName() + " rendered from description (no approved portrait)");
			assertEquals("summary before", "1 characters: 0 referenced, 1 text", planA.actorSummary());

			String apparelOid = giveApparel(u, octx, heroOid, false);
			PinResult pin = PbWardrobeUtil.pinActorApparel(u, book, oidOf(comp), actorRowOid, apparelOid);
			assertTrue("apparel pinned", pin.pinned);
			assertNotNull("pinned apparel", pin.apparel);
			List<ResolvedOutfit> outfits = PbWardrobeUtil.resolveOutfits(u, book, PbComposeUtil.readComposition(u, oidOf(comp), orgId));
			assertEquals("one resolved outfit", 1, outfits.size());
			assertEquals("pinned source", OutfitSource.PINNED, outfits.get(0).source);
			String expectedOutfit = PbWardrobeUtil.describeApparel(u, pin.apparel, orgId);
			log("describeApparel: \"" + expectedOutfit + "\"");
			assertNotNull("fixture apparel must describe (precondition)", expectedOutfit);
			assertTrue("describeApparel starts with 'wearing '", expectedOutfit.startsWith("wearing "));

			BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, book, PbBookUtil.workflowGroupPath(slug));
			BaseRecord pNode = PbGraphUtil.addNode(u, wf, PbPipelineUtil.portraitHandle(heroOid), PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(slug), 0);
			assertNotNull("portrait node", pNode);
			BaseRecord art = imageArtifact(u, pNode, slug, 64, 96, 0x336699);
			BaseRecord approved = PbArtifactUtil.approve(u, PbArtifactUtil.readArtifact(u, oidOf(art), orgId), "p47");
			assertNotNull("approve", approved);
			assertEquals("portrait approved", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(PbArtifactUtil.readArtifact(u, oidOf(art), orgId)));

			RenderPlan planB = PbRenderPlanUtil.buildScenePlan(u, book, freshScene, PlanOptions.NONE, SDAPIEnumType.SWARM, DEAD_SERVER);
			log("planB degradations: " + planB.getDegradations() + " summary: " + planB.actorSummary());
			assertEquals("one actor", 1, planB.getActors().size());
			RenderPlan.Actor a2 = planB.getActors().get(0);
			assertNotNull("pinned apparel yields an outfit", a2.getOutfit());
			assertEquals("outfit apparel id", apparelOid, a2.getOutfit().getApparelId());
			assertTrue("outfit text starts with 'wearing ': " + a2.getOutfit().getText(), a2.getOutfit().getText().startsWith("wearing "));
			assertEquals("outfit text is describeApparel", expectedOutfit, a2.getOutfit().getText());
			assertEquals("with an outfit, identity.text is the physical description only", HERO_PHYS, a2.getIdentity().getText());
			assertFalse("identity.text no longer carries the outfit description", a2.getIdentity().getText().contains(HERO_OUTFIT));
			assertEquals("approved portrait is the identity reference", oidOf(art), a2.getIdentity().getPortraitArtifactId());
			assertTrue("no ACTOR_TEXT_ONLY / ACTOR_UNRESOLVED once a portrait is approved: " + planB.getDegradations(),
				planB.getDegradations().stream().noneMatch(d -> RenderPlan.Degradation.ACTOR_TEXT_ONLY.equals(d.getCode()) || RenderPlan.Degradation.ACTOR_UNRESOLVED.equals(d.getCode())));
			long referenced = (a2.isReferenced() ? 1 : 0);
			assertEquals("summary after", "1 characters: " + referenced + " referenced, " + (1 - referenced) + " text", planB.actorSummary());
			log("case02 done (referenced=" + a2.isReferenced() + ")");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	@Test
	public void case03_noCompositionYieldsNoActorsAndNoActorDegradation() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "c");
		try {
			BaseRecord scene = addScene(u, bk, 0, "Empty", SETTING, null, null);
			BaseRecord book = PbBookUtil.readBook(u, oidOf(bk), orgId);
			assertNull("no composition", PbComposeUtil.findSceneComposition(u, scene, book.get(FieldNames.FIELD_GROUP_ID), orgId));
			RenderPlan plan = PbRenderPlanUtil.buildScenePlan(u, book, scene, PlanOptions.NONE, SDAPIEnumType.SWARM, DEAD_SERVER);
			assertNotNull("plan", plan);
			log("degradations: " + plan.getDegradations() + " location=\"" + plan.getLocation().getDescription() + "\"");
			assertTrue("no actors", plan.getActors().isEmpty());
			assertTrue("no elements", plan.getElements().isEmpty());
			assertTrue("no ACTOR_* degradation: " + plan.getDegradations(),
				plan.getDegradations().stream().noneMatch(d -> d.getCode() != null && d.getCode().startsWith("ACTOR_")));
			assertEquals("summary", "no characters", plan.actorSummary());
			assertNull("no promptOverride without a composition", plan.getIntent().getPromptOverride());
			log("case03 done");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── plan lookups ───────────────────────────────

	private static RenderPlan.Actor actorByCharacter(List<RenderPlan.Actor> actors, String charOid) {
		for(RenderPlan.Actor a : actors) {
			if(charOid.equals(a.getCharacterId())) {
				return a;
			}
		}
		fail("no actor for character " + charOid);
		return null;
	}

	private static RenderPlan.Actor actorById(List<RenderPlan.Actor> actors, String actorId) {
		for(RenderPlan.Actor a : actors) {
			if(actorId.equals(a.getActorId())) {
				return a;
			}
		}
		fail("no actor with id " + actorId + " among " + actors.stream().map(RenderPlan.Actor::getActorId).collect(Collectors.toList()));
		return null;
	}

	private static RenderPlan.Element elementByType(List<RenderPlan.Element> elements, String type) {
		for(RenderPlan.Element e : elements) {
			if(type.equals(e.getType())) {
				return e;
			}
		}
		fail("no element of type " + type);
		return null;
	}

	private static BaseRecord rowById(List<BaseRecord> rows, long id) {
		for(BaseRecord r : rows) {
			if(idOf(r) == id) {
				return r;
			}
		}
		fail("no actor row #" + id);
		return null;
	}

	private static void expectOne(RenderPlan plan, String code, String actorId, String message) {
		List<RenderPlan.Degradation> hits = plan.getDegradations().stream()
			.filter(d -> code.equals(d.getCode()) && actorId.equals(d.getActorId()))
			.collect(Collectors.toList());
		assertEquals("exactly one " + code + " for " + actorId + " in " + plan.getDegradations(), 1, hits.size());
		assertEquals(code + " message", message, hits.get(0).getMessage());
		assertEquals(code + " stage", RenderPlan.STAGE_BASE, hits.get(0).getStage());
	}

	// ─────────────────────────────── fixtures ───────────────────────────────

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bctx = PbBookUtil.openBookContext(u, bk);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());
		return bctx;
	}

	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String sdPrompt, BaseRecord location) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		List<String> fields = new ArrayList<>();
		fields.add(OlioFieldNames.FIELD_PB_SETTING);
		if(sdPrompt != null) {
			fields.add(OlioFieldNames.FIELD_CB_SD_PROMPT);
		}
		if(location != null) {
			fields.add(FieldNames.FIELD_LOCATION);
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
		patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
		if(sdPrompt != null) {
			patch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
		}
		if(location != null) {
			patch.set(FieldNames.FIELD_LOCATION, location);
		}
		assertNotNull("scene patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("readScene", fresh);
		if(location != null) {
			assertEquals("scene location", idOf(location), fkId(fresh, FieldNames.FIELD_LOCATION));
		}
		return fresh;
	}

	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME), ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}

	private BaseRecord newActor(BaseRecord u, BaseRecord comp, BaseRecord cp, PbActorRoleEnumType role, int priority, String description, int count) throws Exception {
		Long gid = comp.get(FieldNames.FIELD_GROUP_ID);
		BaseRecord row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ACTOR);
		ioContext.getRecordUtil().applyOwnership(u, row, orgOf(u));
		row.set(FieldNames.FIELD_GROUP_ID, gid);
		row.set(FieldNames.FIELD_NAME, PbComposeUtil.ACTOR_NAME_PREFIX + oidOf(comp) + "." + priority);
		row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
		if(cp != null) {
			row.set(OlioFieldNames.FIELD_PB_CHARACTER, cp);
		}
		row.set(OlioFieldNames.FIELD_PB_ROLE, role.toString());
		row.set(OlioFieldNames.FIELD_PB_PRIORITY, priority);
		row.set(FieldNames.FIELD_DESCRIPTION, description);
		row.set(FieldNames.FIELD_COUNT, count);
		BaseRecord created = ioContext.getAccessPoint().create(u, row);
		if(created == null && cp != null) {
			/// PBAC refuses a row whose FK the creator cannot read; the state under test (a row left pointing at a
			/// character later hidden from the viewer) is written directly, test-side only.
			log("accessPoint.create refused the row with an unreadable character FK; writing it via RecordUtil");
			assertTrue("create actor row (RecordUtil)", ioContext.getRecordUtil().createRecord(row));
			created = row;
		}
		assertNotNull("create actor row", created);
		BaseRecord fresh = rowById(PbComposeUtil.listActors(u, comp), idOf(created));
		log("actor row " + fresh.get(FieldNames.FIELD_NAME) + " " + oidOf(fresh) + " role=" + role + " priority=" + priority);
		return fresh;
	}

	private BaseRecord newElement(BaseRecord u, BaseRecord comp, String description, PbElementTypeEnumType type) throws Exception {
		Long gid = comp.get(FieldNames.FIELD_GROUP_ID);
		String name = PbComposeUtil.ELEMENT_NAME_PREFIX + oidOf(comp) + "." + shortId();
		BaseRecord row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ELEMENT);
		ioContext.getRecordUtil().applyOwnership(u, row, orgOf(u));
		row.set(FieldNames.FIELD_GROUP_ID, gid);
		row.set(FieldNames.FIELD_NAME, name);
		row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
		row.set(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, type.toString());
		row.set(FieldNames.FIELD_DESCRIPTION, description);
		row.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.MEDIUM.toString());
		BaseRecord created = ioContext.getAccessPoint().create(u, row);
		assertNotNull("create element row", created);
		BaseRecord fresh = PbComposeUtil.readElement(u, oidOf(created), orgOf(u));
		assertNotNull("readElement", fresh);
		return fresh;
	}

	private BaseRecord readChar(BaseRecord u, String oid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID });
		q.setCache(false);
		BaseRecord cp = ioContext.getAccessPoint().find(u, q);
		assertNotNull("character " + oid, cp);
		return cp;
	}

	/** The {@code TestPbNodeRun} idiom: a narrative sub-record patched, then linked to the character by a separate patch. */
	private void giveNarrative(BaseRecord u, BaseRecord cp, String physical, String outfit) throws Exception {
		BaseRecord narrative = PbSubRecordUtil.createSubRecord(u, null, OlioModelNames.MODEL_NARRATIVE);
		assertNotNull("narrative", narrative);
		List<String> fields = new ArrayList<>(Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "physicalDescription"));
		if(outfit != null) {
			fields.add("outfitDescription");
		}
		BaseRecord np = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE, fields.toArray(new String[0]));
		np.set(FieldNames.FIELD_ID, narrative.get(FieldNames.FIELD_ID));
		np.set(FieldNames.FIELD_OBJECT_ID, narrative.get(FieldNames.FIELD_OBJECT_ID));
		np.set("physicalDescription", physical);
		if(outfit != null) {
			np.set("outfitDescription", outfit);
		}
		assertNotNull("narrative patch", ioContext.getAccessPoint().update(u, np));
		BaseRecord lp = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative" });
		lp.set(FieldNames.FIELD_ID, cp.get(FieldNames.FIELD_ID));
		lp.set(FieldNames.FIELD_OBJECT_ID, cp.get(FieldNames.FIELD_OBJECT_ID));
		lp.set(FieldNames.FIELD_NAME, cp.get(FieldNames.FIELD_NAME));
		lp.set("narrative", narrative);
		assertNotNull("narrative link patch", ioContext.getAccessPoint().update(u, lp));
	}

	private static final String[] CHAR_SUB_RECORD_FIELDS = new String[] {
		FieldNames.FIELD_PROFILE, OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE, OlioFieldNames.FIELD_INSTINCT,
		FieldNames.FIELD_BEHAVIOR, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE
	};

	private static String createCharPersonIn(BaseRecord user, BaseRecord group, String groupPath, String name) throws Exception {
		long groupId = ((Number) group.get(FieldNames.FIELD_ID)).longValue();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, name.split(" ")[0]);
		cp.set(FieldNames.FIELD_GENDER, "female");
		for(String f : CHAR_SUB_RECORD_FIELDS) {
			BaseRecord sub = cp.get(f);
			if(sub != null) {
				sub.set(FieldNames.FIELD_GROUP_ID, groupId);
			}
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("create character " + name, created);
		return created.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static String giveApparel(BaseRecord user, OlioContext octx, String charOid, boolean inUse) throws Exception {
		Query cpQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
		cpQ.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		cpQ.planMost(true);
		cpQ.setCache(false);
		BaseRecord person = IOSystem.getActiveContext().getAccessPoint().find(user, cpQ);
		assertNotNull("character " + charOid, person);
		BaseRecord store = person.get(FieldNames.FIELD_STORE);
		assertNotNull("store of " + charOid, store);
		BaseRecord apparel = ApparelUtil.contextApparel(octx, person, 2, CivilUtil.ClimateType.TEMPERATE);
		assertNotNull("contextApparel", apparel);
		apparel.setValue(OlioFieldNames.FIELD_IN_USE, inUse);
		List<BaseRecord> wearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		for(BaseRecord w : wearables) {
			w.setValue(OlioFieldNames.FIELD_IN_USE, true);
		}
		assertTrue("apparel persisted", IOSystem.getActiveContext().getRecordUtil().createRecord(apparel));
		assertTrue("apparel linked", IOSystem.getActiveContext().getMemberUtil().member(user, store, OlioFieldNames.FIELD_APPAREL, apparel, null, true));
		return apparel.get(FieldNames.FIELD_OBJECT_ID);
	}

	/** The {@code TestPbAutoApprove} idiom: a solid PNG as a {@code data.data} record, persisted as a portrait IMAGE artifact. */
	private BaseRecord imageArtifact(BaseRecord u, BaseRecord node, String slug, int w, int h, int rgb) throws Exception {
		long orgId = orgOf(u);
		String groupPath = PbBookUtil.artifactGroupPath(slug);
		byte[] png = solidPng(w, h, rgb);
		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, image, "p47-portrait-" + shortId() + ".png", groupPath, orgId);
		image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(image, png);
		BaseRecord created = ioContext.getAccessPoint().create(u, image);
		assertNotNull("portrait data", created);
		BaseRecord art = PbArtifactUtil.persistArtifact(u, node, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE, groupPath, created, null, null, null);
		assertNotNull("portrait artifact", art);
		PbArtifactUtil.recordImageMetrics(u, art, png, "image/png", w, h, null);
		return art;
	}

	private static byte[] solidPng(int w, int h, int rgb) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for(int y = 0; y < h; y++) {
			for(int x = 0; x < w; x++) {
				img.setRGB(x, y, rgb);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue("png encode", ImageIO.write(img, "png", out));
		return out.toByteArray();
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p47" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-7 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		log("book " + slug + " " + oidOf(bk));
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			log("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup of " + bk.get(FieldNames.FIELD_NAME) + " failed", e);
		}
	}

	private void log(String msg) {
		logger.info("[p47] " + msg);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord user() {
		return user(TEST_USER);
	}

	private BaseRecord user(String name) {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), name, org.getOrganizationId());
		assertNotNull("Failed to resolve " + name, u);
		return u;
	}
}
