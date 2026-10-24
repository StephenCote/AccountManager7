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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CivilUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.CreateResult;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.MigrationResult;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.OutfitSource;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.PinResult;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.ReferenceResult;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.ResolvedOutfit;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.SceneOutfits;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.SetDefaultResult;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.Wardrobe;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil.WardrobeEntry;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-5: wardrobe alternates and carry-forward ({@link PbWardrobeUtil}, the facade wrappers and the
 * apparel-reference job). Every case writes a throwaway book in {@code /Development/World Building} as
 * {@code testUser1} against the live database; compositions come from the dead-LLM MIGRATED fallback
 * ({@code OlioTestUtil.getUnreachableOllamaConfig}) so the pin / carry / default chain is exercised without an
 * LLM, while {@link #createAlternateLiveLlmDedupesAndIsOlioOwned} really calls the resolved LLM tier and
 * {@link #referenceImageLiveSdLandsInGallery} / {@link #facadeDtosAndJobSubmit} really render on the Swarm SD
 * server from {@code test.swarm.server}. Nothing is mocked. Timings are logged ({@code [p45]}), never asserted.
 */
public class TestPbWardrobe extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SECOND_USER = "testUser2";
	private static final String DEAD_CFG_NAME = "P45 Dead Cfg";
	private static final String LIVE_CFG_NAME = "P45 Live Cfg";
	private static final String DEAD_URL = "http://127.0.0.1:1";
	private static final long JOB_CEILING_MS = 15L * 60L * 1000L;
	private static final long POLL_MS = 1000L;
	private static final long PHASE_POLL_MS = 2000L;
	private static final String LIVE_DESCRIPTION = "a long green wool travelling cloak over a grey linen tunic and dark boots";

	private long t0;

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		t0 = System.currentTimeMillis();
	}

	// ─────────────────────────────── 0. catalog normalisation ───────────────────────────────

	/// The guess-apparel model answers "travelling cloak" for "cloak": the reply is mapped onto the catalog
	/// getEmbeddedOutfit matches verbatim, and what the catalog cannot express is reported, not dropped silently.
	@Test
	public void catalogNormalisation() {
		List<String> catalog = ApparelUtil.getApparelCatalogNames("female");
		assertTrue("catalog has boots", catalog.contains("boots"));
		assertTrue("catalog has trench coat", catalog.contains("trench coat"));
		assertTrue("catalog has thigh-high heeled boots", catalog.contains("thigh-high heeled boots"));
		assertFalse("catalog has no cloak", catalog.contains("cloak"));

		List<String> unmatched = new ArrayList<>();
		List<String> items = PbWardrobeUtil.catalogItems(Arrays.asList(
			"travelling cloak", "grey linen tunic", "dark boots", "Trench Coat", "boots", "thigh-high heeled boots", "a dress"),
			catalog, unmatched);
		log("catalogItems -> " + items + " unmatched=" + unmatched);
		assertEquals(Arrays.asList("boots", "trench coat", "thigh-high heeled boots", "dress"), items);
		assertEquals(Arrays.asList("travelling cloak", "grey linen tunic"), unmatched);

		unmatched.clear();
		assertTrue("nothing in the catalog", PbWardrobeUtil.catalogItems(Arrays.asList("cloak", "tunic", ""), catalog, unmatched).isEmpty());
		assertEquals(Arrays.asList("cloak", "tunic", ""), unmatched);

		/// LLM item strings are echoed into error bodies: control characters and run-on whitespace are normalised at the source.
		assertEquals("dark boots x", PbWardrobeUtil.llmItem("  dark\tboots \u0001\u0002  x\r\n"));
		assertNull("control-only item is nothing", PbWardrobeUtil.llmItem("\t\u0007\n"));
		assertNull("null stays null", PbWardrobeUtil.llmItem(null));
		String longItem = "x".repeat(PbWardrobeUtil.MAX_NAME_CHARS + 20);
		assertEquals(PbWardrobeUtil.MAX_NAME_CHARS, PbWardrobeUtil.llmItem(longItem).length());
		List<String> many = new ArrayList<>();
		for(int i = 0; i < PbWardrobeUtil.MAX_ECHO_ITEMS + 3; i++) {
			many.add("item" + i);
		}
		String echoed = PbWardrobeUtil.echo(many);
		log("echo -> " + echoed);
		assertTrue("echo is capped", echoed.endsWith("(+3 more)"));
		assertFalse("capped echo omits the tail", echoed.contains("item" + (PbWardrobeUtil.MAX_ECHO_ITEMS + 2)));
		assertEquals("[a, b]", PbWardrobeUtil.echo(Arrays.asList("a", "b")));
	}

	// ─────────────────────────────── 1. list + refusals ───────────────────────────────

	@Test
	public void listWardrobeAndRefusals() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "list");
		BaseRecord homeChar = null;
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Mira Vale");
			BaseRecord cp1 = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			String a = giveApparel(u, fx.octx, c1, true);
			log("fixture: char1=" + c1 + " A=" + a);

			Wardrobe w = PbWardrobeUtil.listWardrobe(u, fx.book, c1);
			assertEquals("one outfit", 1, w.entries.size());
			assertTrue("the only outfit is the default", w.entries.get(0).isDefault);
			assertNotNull("defaultEntry", w.defaultEntry);
			assertEquals("defaultEntry is A", a, oidOf(w.defaultEntry.apparel));
			assertTrue("no warnings: " + w.warnings, w.warnings.isEmpty());
			assertNotNull("store", w.store);
			assertEquals("character", c1, oidOf(w.character));

			String b = giveApparel(u, fx.octx, c1, false);
			w = PbWardrobeUtil.listWardrobe(u, fx.book, c1);
			assertEquals("two outfits", 2, w.entries.size());
			int defaults = 0;
			for(WardrobeEntry e : w.entries) {
				if(e.isDefault) {
					defaults++;
				}
				log("entry " + oidOf(e.apparel) + " default=" + e.isDefault + " wearables=" + e.wearables.size() + " gallery=" + e.gallery.size());
			}
			assertEquals("exactly one default", 1, defaults);
			assertEquals("defaultEntry still A", a, oidOf(w.defaultEntry.apparel));
			assertTrue("no warnings: " + w.warnings, w.warnings.isEmpty());

			refused("unknown character", new int[] {404}, () -> PbWardrobeUtil.requireCharacterOfBook(u, fx.book, "nope-" + shortId()));
			refused("unknown character via listWardrobe", new int[] {404}, () -> PbWardrobeUtil.listWardrobe(u, fx.book, "nope-" + shortId()));

			homeChar = createHomeCharPerson(u, "~/Characters", "P45 Home " + shortId());
			final String homeOid = oidOf(homeChar);
			refused("character outside the book world", new int[] {404}, () -> PbWardrobeUtil.listWardrobe(u, fx.book, homeOid));

			String c2 = fx.character("Tess Arden");
			String a2 = giveApparel(u, fx.octx, c2, true);
			refused("another character's apparel", new int[] {400}, () -> PbWardrobeUtil.requireApparelOfCharacter(u, cp1, a2));
			refused("unknown apparel", new int[] {404}, () -> PbWardrobeUtil.requireApparelOfCharacter(u, cp1, "nope-" + shortId()));
			assertNotNull("own apparel accepted", PbWardrobeUtil.requireApparelOfCharacter(u, cp1, b));

			BaseRecord def = PbWardrobeUtil.defaultApparel(u, cp1);
			assertNotNull("defaultApparel", def);
			assertEquals("defaultApparel is the inuse one", a, oidOf(def));
			log("listWardrobeAndRefusals done");
		}
		finally {
			deleteQuietly(u, homeChar);
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 2. pin / carry / unpin ───────────────────────────────

	@Test
	public void pinCarryForwardAndUnpin() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "pin");
		BaseRecord bk2 = null;
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Mira Vale");
			String c2 = fx.character("Tess Arden");
			BaseRecord cp1 = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			BaseRecord cp2 = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c2);
			long char1 = idOf(cp1);
			String a = giveApparel(u, fx.octx, c1, true);
			String b = giveApparel(u, fx.octx, c1, false);
			String c = giveApparel(u, fx.octx, c1, false);
			String a2 = giveApparel(u, fx.octx, c2, true);

			BaseRecord[] scenes = new BaseRecord[5];
			BaseRecord[] comps = new BaseRecord[5];
			BaseRecord[] nodes = new BaseRecord[5];
			BaseRecord deadCfg = OlioTestUtil.getUnreachableOllamaConfig(u, DEAD_CFG_NAME, DEAD_URL, "qwen3:8b", 5);
			assertNotNull("dead chat config", deadCfg);
			BaseRecord wf = workflow(u, bk);
			for(int i = 1; i <= 4; i++) {
				scenes[i] = addScene(u, bk, i, "Scene " + i);
				enrol(u, scenes[i], cp1);
				if(i == 2) {
					enrol(u, scenes[i], cp2);
				}
				comps[i] = composeDead(u, fx.book, scenes[i], deadCfg);
				nodes[i] = ensurePageNode(u, bk, wf, oidOf(scenes[i]));
				setStatus(u, nodes[i], PbNodeStatusEnumType.DONE, fx.orgId);
			}
			log("fixture: 4 scenes composed (MIGRATED), page nodes DONE");

			List<SceneOutfits> all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			assertEquals("4 scenes resolved", 4, all.size());
			for(int i = 1; i <= 4; i++) {
				ResolvedOutfit ro = outfitOf(all, i, char1);
				assertEquals("scene " + i + " source", OutfitSource.DEFAULT, ro.source);
				assertEquals("scene " + i + " apparel", a, oidOf(ro.apparel));
				assertEquals("scene " + i + " label", "(default)", ro.label());
			}
			ResolvedOutfit ro2 = outfitOf(all, 2, idOf(cp2));
			assertEquals("char2 on scene 2 wears her default", a2, oidOf(ro2.apparel));

			String actor1in2 = actorOf(u, comps[2], char1);
			PinResult pin = PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), actor1in2, b);
			log("pin B on scene 2: pinned=" + pin.pinned + " affected=" + pin.affectedSceneIndexes + " staled=" + pin.staled);
			assertTrue("pinned", pin.pinned);
			assertEquals("pinned apparel", b, oidOf(pin.apparel));
			assertEquals("affected scenes", Arrays.asList(2, 3, 4), pin.affectedSceneIndexes);
			assertEquals("staled", 3, pin.staled);
			assertNodeStatus(u, nodes[1], PbNodeStatusEnumType.DONE, fx.orgId);
			for(int i = 2; i <= 4; i++) {
				assertNodeStatus(u, nodes[i], PbNodeStatusEnumType.STALE, fx.orgId);
				setStatus(u, nodes[i], PbNodeStatusEnumType.DONE, fx.orgId);
			}

			all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(all, 1, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(all, 2, char1, OutfitSource.PINNED, b, -1, "(pinned)");
			expect(all, 3, char1, OutfitSource.CARRIED, b, 2, "(from scene 2)");
			expect(all, 4, char1, OutfitSource.CARRIED, b, 2, "(from scene 2)");
			List<ResolvedOutfit> one = PbWardrobeUtil.resolveOutfits(u, fx.book, comps[3]);
			assertEquals("single-composition resolve agrees", OutfitSource.CARRIED, outfitOf(one, char1).source);

			String actor1in4 = actorOf(u, comps[4], char1);
			PinResult pin4 = PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[4]), actor1in4, c);
			log("pin C on scene 4: affected=" + pin4.affectedSceneIndexes + " staled=" + pin4.staled);
			assertEquals("affected scenes", Arrays.asList(4), pin4.affectedSceneIndexes);
			assertEquals("staled", 1, pin4.staled);
			assertNodeStatus(u, nodes[3], PbNodeStatusEnumType.DONE, fx.orgId);
			assertNodeStatus(u, nodes[4], PbNodeStatusEnumType.STALE, fx.orgId);
			setStatus(u, nodes[4], PbNodeStatusEnumType.DONE, fx.orgId);

			all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(all, 3, char1, OutfitSource.CARRIED, b, 2, "(from scene 2)");
			expect(all, 4, char1, OutfitSource.PINNED, c, -1, "(pinned)");

			PinResult unpin = PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), actor1in2, null);
			log("unpin scene 2: pinned=" + unpin.pinned + " affected=" + unpin.affectedSceneIndexes + " staled=" + unpin.staled);
			assertFalse("unpinned", unpin.pinned);
			assertNull("no apparel on unpin", unpin.apparel);
			assertEquals("affected scenes", Arrays.asList(2, 3), unpin.affectedSceneIndexes);
			assertEquals("staled", 2, unpin.staled);
			assertNodeStatus(u, nodes[2], PbNodeStatusEnumType.STALE, fx.orgId);
			assertNodeStatus(u, nodes[3], PbNodeStatusEnumType.STALE, fx.orgId);
			assertNodeStatus(u, nodes[4], PbNodeStatusEnumType.DONE, fx.orgId);

			all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(all, 2, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(all, 3, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(all, 4, char1, OutfitSource.PINNED, c, -1, "(pinned)");

			BaseRecord comp2 = PbComposeUtil.readComposition(u, oidOf(comps[2]), fx.orgId);
			assertNotNull("composition 2 re-read", comp2);
			assertEquals("pin/unpin never bumps revision", 1, ((Number) comp2.get(OlioFieldNames.FIELD_PB_REVISION)).intValue());
			assertFalse("pin/unpin never flags userEdited", Boolean.TRUE.equals(comp2.get(OlioFieldNames.FIELD_PB_USER_EDITED)));

			// refusals
			refused("another character's apparel on char1's actor", new int[] {400},
				() -> PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), actor1in2, a2));
			refused("actor of another composition", new int[] {400},
				() -> PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), actor1in4, b));
			refused("unknown composition", new int[] {404},
				() -> PbWardrobeUtil.pinActorApparel(u, fx.book, "nope-" + shortId(), actor1in2, b));
			refused("unknown actor", new int[] {404},
				() -> PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), "nope-" + shortId(), b));

			bk2 = newBook(u, "pinx");
			Fixture fx2 = fixture(u, bk2);
			String x1 = fx2.character("Mira Vale");
			BaseRecord xp1 = PbWardrobeUtil.requireCharacterOfBook(u, fx2.book, x1);
			giveApparel(u, fx2.octx, x1, true);
			BaseRecord xs = addScene(u, bk2, 1, "Other book scene");
			enrol(u, xs, xp1);
			BaseRecord xc = composeDead(u, fx2.book, xs, deadCfg);
			String xActor = actorOf(u, xc, idOf(xp1));
			refused("composition of another book", new int[] {400, 404},
				() -> PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(xc), xActor, b));
			refused("this book's composition against the other book", new int[] {400, 404},
				() -> PbWardrobeUtil.pinActorApparel(u, fx2.book, oidOf(comps[2]), actor1in2, b));
			log("pinCarryForwardAndUnpin done");
		}
		finally {
			deleteBook(u, bk2);
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 3. set default ───────────────────────────────

	@Test
	public void setDefaultStalesOnlyDefaultResolvedScenes() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "def");
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Mira Vale");
			BaseRecord cp1 = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			long char1 = idOf(cp1);
			String a = giveApparel(u, fx.octx, c1, true);
			String b = giveApparel(u, fx.octx, c1, false);
			String c = giveApparel(u, fx.octx, c1, false);
			BaseRecord deadCfg = OlioTestUtil.getUnreachableOllamaConfig(u, DEAD_CFG_NAME, DEAD_URL, "qwen3:8b", 5);
			BaseRecord wf = workflow(u, bk);
			BaseRecord[] scenes = new BaseRecord[5];
			BaseRecord[] comps = new BaseRecord[5];
			BaseRecord[] nodes = new BaseRecord[5];
			for(int i = 1; i <= 4; i++) {
				scenes[i] = addScene(u, bk, i, "Scene " + i);
				enrol(u, scenes[i], cp1);
				comps[i] = composeDead(u, fx.book, scenes[i], deadCfg);
				nodes[i] = ensurePageNode(u, bk, wf, oidOf(scenes[i]));
				setStatus(u, nodes[i], PbNodeStatusEnumType.DONE, fx.orgId);
			}
			PinResult pin = PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), actorOf(u, comps[2], char1), b);
			assertEquals("pin affects 2..4", Arrays.asList(2, 3, 4), pin.affectedSceneIndexes);
			for(int i = 1; i <= 4; i++) {
				setStatus(u, nodes[i], PbNodeStatusEnumType.DONE, fx.orgId);
			}
			List<SceneOutfits> all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(all, 1, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(all, 3, char1, OutfitSource.CARRIED, b, 2, "(from scene 2)");
			expect(all, 4, char1, OutfitSource.CARRIED, b, 2, "(from scene 2)");

			SetDefaultResult sd = PbWardrobeUtil.setDefault(u, fx.book, c1, c);
			log("setDefault(C): changed=" + sd.changed + " previous=" + sd.previous.size() + " scanned=" + sd.scenesScanned + " staled=" + sd.staled + " warnings=" + sd.warnings);
			assertTrue("changed", sd.changed);
			assertEquals("new default", c, oidOf(sd.apparel));
			assertEquals("one previous default", 1, sd.previous.size());
			assertEquals("previous is A", a, oidOf(sd.previous.get(0)));
			assertEquals("all 4 scenes scanned", 4, sd.scenesScanned);
			assertEquals("only the DEFAULT-resolved scene (1) is staled", 1, sd.staled);
			assertNodeStatus(u, nodes[1], PbNodeStatusEnumType.STALE, fx.orgId);
			for(int i = 2; i <= 4; i++) {
				assertNodeStatus(u, nodes[i], PbNodeStatusEnumType.DONE, fx.orgId);
			}
			assertEquals("A fresh inuse", Boolean.FALSE, readApparel(u, a).get(OlioFieldNames.FIELD_IN_USE));
			assertEquals("B fresh inuse", Boolean.FALSE, readApparel(u, b).get(OlioFieldNames.FIELD_IN_USE));
			assertEquals("C fresh inuse", Boolean.TRUE, readApparel(u, c).get(OlioFieldNames.FIELD_IN_USE));
			Wardrobe w = PbWardrobeUtil.listWardrobe(u, fx.book, c1);
			assertEquals("defaultEntry is C", c, oidOf(w.defaultEntry.apparel));
			assertTrue("no warnings: " + w.warnings, w.warnings.isEmpty());

			SetDefaultResult again = PbWardrobeUtil.setDefault(u, fx.book, c1, c);
			log("setDefault(C) again: changed=" + again.changed + " scanned=" + again.scenesScanned + " staled=" + again.staled);
			assertFalse("idempotent: not changed", again.changed);
			assertEquals("idempotent: nothing staled", 0, again.staled);
			assertEquals("idempotent: nothing scanned", 0, again.scenesScanned);
			assertNodeStatus(u, nodes[2], PbNodeStatusEnumType.DONE, fx.orgId);

			all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(all, 1, char1, OutfitSource.DEFAULT, c, -1, "(default)");
			expect(all, 2, char1, OutfitSource.PINNED, b, -1, "(pinned)");
			refused("unknown apparel", new int[] {404}, () -> PbWardrobeUtil.setDefault(u, fx.book, c1, "nope-" + shortId()));
			log("setDefaultStalesOnlyDefaultResolvedScenes done");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 4. migrate scene tags ───────────────────────────────

	@Test
	public void migrateSceneTagsPinsAndIsIdempotent() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "mig");
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Mira Vale");
			BaseRecord cp1 = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			long char1 = idOf(cp1);
			String a = giveApparel(u, fx.octx, c1, true);
			String b = giveApparel(u, fx.octx, c1, false);
			String c = giveApparel(u, fx.octx, c1, false);
			String d = giveApparel(u, fx.octx, c1, false);
			BaseRecord deadCfg = OlioTestUtil.getUnreachableOllamaConfig(u, DEAD_CFG_NAME, DEAD_URL, "qwen3:8b", 5);
			BaseRecord[] scenes = new BaseRecord[4];
			BaseRecord[] comps = new BaseRecord[4];
			for(int i = 1; i <= 3; i++) {
				scenes[i] = addScene(u, bk, i, "Scene " + i);
				enrol(u, scenes[i], cp1);
				if(i <= 2) {
					comps[i] = composeDead(u, fx.book, scenes[i], deadCfg);
				}
			}
			assertTrue("tag B -> 2", PictureBookUtil.tagApparelSceneIndex(u, c1, b, 2));
			assertTrue("tag C -> 3", PictureBookUtil.tagApparelSceneIndex(u, c1, c, 3));
			assertTrue("tag D -> 9", PictureBookUtil.tagApparelSceneIndex(u, c1, d, 9));
			assertEquals("B tagged", Integer.valueOf(2), readSceneIndex(u, b));
			assertNull("A untagged", readSceneIndex(u, a));

			MigrationResult m = PbWardrobeUtil.migrateSceneTags(u, fx.book);
			log("migrate #1: " + describe(m));
			assertEquals("scanned", 3, m.scanned);
			assertEquals("pinned", 1, m.pinned);
			assertEquals("skippedNoComposition", 1, m.skippedNoComposition);
			assertEquals("skippedNoScene", 1, m.skippedNoScene);
			assertEquals("skippedPinned", 0, m.skippedPinned);
			assertEquals("skippedNoActor", 0, m.skippedNoActor);
			assertEquals("ambiguous", 0, m.ambiguous);
			List<SceneOutfits> all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(all, 1, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(all, 2, char1, OutfitSource.PINNED, b, -1, "(pinned)");
			assertEquals("tag kept on B", Integer.valueOf(2), readSceneIndex(u, b));
			Wardrobe w = PbWardrobeUtil.listWardrobe(u, fx.book, c1);
			for(WardrobeEntry e : w.entries) {
				if(b.equals(oidOf(e.apparel))) {
					assertEquals("entry carries the tag", Integer.valueOf(2), e.sceneIndex);
				}
			}

			MigrationResult m2 = PbWardrobeUtil.migrateSceneTags(u, fx.book);
			log("migrate #2: " + describe(m2));
			assertEquals("idempotent: pinned", 0, m2.pinned);
			assertEquals("idempotent: skippedPinned", 1, m2.skippedPinned);
			assertEquals("idempotent: scanned", 3, m2.scanned);

			String actor1in2 = actorOf(u, comps[2], char1);
			assertFalse("unpin", PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), actor1in2, null).pinned);
			assertTrue("pin C by hand", PbWardrobeUtil.pinActorApparel(u, fx.book, oidOf(comps[2]), actor1in2, c).pinned);
			MigrationResult m3 = PbWardrobeUtil.migrateSceneTags(u, fx.book);
			log("migrate #3: " + describe(m3));
			assertEquals("hand pin wins: pinned", 0, m3.pinned);
			assertEquals("hand pin wins: skippedPinned", 1, m3.skippedPinned);
			all = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(all, 2, char1, OutfitSource.PINNED, c, -1, "(pinned)");
			log("migrateSceneTagsPinsAndIsIdempotent done");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// P4-12: a legacy tag pin changes the resolved outfit of the tagged scene AND of every scene the pin carries
	/// into, so both scenes' DONE render nodes are marked STALE and the pass reports affected / staled. A scene the
	/// pin does not reach keeps its DONE node. The second pass changes nothing and stales nothing. The same counters
	/// ride on PbMigrationUtil.CompositionMigrationResult (tagsAffected / tagsStaled) through the real wiring and toMap().
	@Test
	public void migrateSceneTagsStalesTheAffectedScenesNodes() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "mst");
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Mira Vale");
			BaseRecord cp1 = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			long char1 = idOf(cp1);
			String a = giveApparel(u, fx.octx, c1, true);
			String b = giveApparel(u, fx.octx, c1, false);
			BaseRecord deadCfg = OlioTestUtil.getUnreachableOllamaConfig(u, DEAD_CFG_NAME, DEAD_URL, "qwen3:8b", 5);
			BaseRecord wf = workflow(u, bk);
			BaseRecord[] scenes = new BaseRecord[4];
			BaseRecord[] nodes = new BaseRecord[4];
			for(int i = 1; i <= 3; i++) {
				scenes[i] = addScene(u, bk, i, "Scene " + i);
				enrol(u, scenes[i], cp1);
				composeDead(u, fx.book, scenes[i], deadCfg);
				nodes[i] = setStatus(u, ensurePageNode(u, bk, wf, oidOf(scenes[i])), PbNodeStatusEnumType.DONE, fx.orgId);
			}
			assertTrue("tag B -> 2", PictureBookUtil.tagApparelSceneIndex(u, c1, b, 2));

			List<SceneOutfits> before = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(before, 1, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(before, 2, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(before, 3, char1, OutfitSource.DEFAULT, a, -1, "(default)");

			long t = System.currentTimeMillis();
			MigrationResult res = PbWardrobeUtil.migrateSceneTags(u, fx.book);
			log("[p412-tags] migrate #1 (" + (System.currentTimeMillis() - t) + " ms): " + describe(res) + " affected=" + res.affected + " staled=" + res.staled);
			assertEquals("scanned", 1, res.scanned);
			assertEquals("pinned", 1, res.pinned);
			assertTrue("P4-12: the tagged scene and the carried scene after it both changed: affected=" + res.affected, res.affected >= 2);
			assertEquals("P4-12: exactly the tagged scene and the carried scene changed", 2, res.affected);
			assertEquals("P4-12: both affected scenes' DONE nodes were marked stale", 2, res.staled);
			assertNodeStatus(u, nodes[2], PbNodeStatusEnumType.STALE, fx.orgId);
			assertNodeStatus(u, nodes[3], PbNodeStatusEnumType.STALE, fx.orgId);
			assertNodeStatus(u, nodes[1], PbNodeStatusEnumType.DONE, fx.orgId);

			List<SceneOutfits> after = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(after, 1, char1, OutfitSource.DEFAULT, a, -1, "(default)");
			expect(after, 2, char1, OutfitSource.PINNED, b, -1, "(pinned)");
			expect(after, 3, char1, OutfitSource.CARRIED, b, 2, "(from scene 2)");

			/// Second pass: the tag is already pinned, nothing changes, nothing is staled.
			nodes[2] = setStatus(u, nodes[2], PbNodeStatusEnumType.DONE, fx.orgId);
			nodes[3] = setStatus(u, nodes[3], PbNodeStatusEnumType.DONE, fx.orgId);
			t = System.currentTimeMillis();
			MigrationResult res2 = PbWardrobeUtil.migrateSceneTags(u, fx.book);
			log("[p412-tags] migrate #2 (" + (System.currentTimeMillis() - t) + " ms): " + describe(res2) + " affected=" + res2.affected + " staled=" + res2.staled);
			assertEquals("idempotent: pinned", 0, res2.pinned);
			assertEquals("idempotent: skippedPinned", 1, res2.skippedPinned);
			assertEquals("idempotent: affected", 0, res2.affected);
			assertEquals("idempotent: staled", 0, res2.staled);
			assertNodeStatus(u, nodes[1], PbNodeStatusEnumType.DONE, fx.orgId);
			assertNodeStatus(u, nodes[2], PbNodeStatusEnumType.DONE, fx.orgId);
			assertNodeStatus(u, nodes[3], PbNodeStatusEnumType.DONE, fx.orgId);

			/// Real wiring: PbMigrationUtil.migrateCompositions runs the tag pass after the scenes and copies the
			/// counters. A new tag C -> 3 affects scene 3 only (scene 2 keeps its pin B); only node 3 goes STALE.
			String c = giveApparel(u, fx.octx, c1, false);
			assertTrue("tag C -> 3", PictureBookUtil.tagApparelSceneIndex(u, c1, c, 3));
			t = System.currentTimeMillis();
			PbMigrationUtil.CompositionMigrationResult cm = PbMigrationUtil.migrateCompositions(u, fx.book, PbBookUtil.listScenes(u, fx.book));
			log("[p412-tags] migrateCompositions (" + (System.currentTimeMillis() - t) + " ms): " + cm);
			assertEquals("scanned", 3, cm.scanned);
			assertEquals("created", 0, cm.created);
			assertEquals("skippedExisting", 3, cm.skippedExisting);
			assertEquals("tagsPinned", 1, cm.tagsPinned);
			assertEquals("tagsSkippedPinned", 1, cm.tagsSkippedPinned);
			assertEquals("P4-12: tagsAffected rides on the composition migration result", 1, cm.tagsAffected);
			assertEquals("P4-12: tagsStaled rides on the composition migration result", 1, cm.tagsStaled);
			assertNodeStatus(u, nodes[3], PbNodeStatusEnumType.STALE, fx.orgId);
			assertNodeStatus(u, nodes[2], PbNodeStatusEnumType.DONE, fx.orgId);
			assertNodeStatus(u, nodes[1], PbNodeStatusEnumType.DONE, fx.orgId);
			Map<String, Object> m = cm.toMap();
			assertEquals("toMap tagsAffected", Integer.valueOf(1), m.get("tagsAffected"));
			assertEquals("toMap tagsStaled", Integer.valueOf(1), m.get("tagsStaled"));
			assertEquals("toMap tagsPinned", Integer.valueOf(1), m.get("tagsPinned"));
			List<SceneOutfits> third = PbWardrobeUtil.resolveOutfits(u, fx.book);
			expect(third, 2, char1, OutfitSource.PINNED, b, -1, "(pinned)");
			expect(third, 3, char1, OutfitSource.PINNED, c, -1, "(pinned)");

			/// The DTO carries the two counters verbatim.
			PbMigrationUtil.CompositionMigrationResult hand = new PbMigrationUtil.CompositionMigrationResult();
			hand.tagsAffected = 2;
			hand.tagsStaled = 1;
			Map<String, Object> hm = hand.toMap();
			assertEquals("hand-built toMap tagsAffected", Integer.valueOf(2), hm.get("tagsAffected"));
			assertEquals("hand-built toMap tagsStaled", Integer.valueOf(1), hm.get("tagsStaled"));
			log("migrateSceneTagsStalesTheAffectedScenesNodes done");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 5. live LLM alternate ───────────────────────────────

	@Test
	public void createAlternateLiveLlmDedupesAndIsOlioOwned() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "llm");
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Mira Vale");
			String a = giveApparel(u, fx.octx, c1, true);
			BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(u, LIVE_CFG_NAME, testProperties);
			assertNotNull("live chat config", cfg);
			log("llm route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
				+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + cfg.get("model"));

			long t = System.currentTimeMillis();
			CreateResult cr = PbWardrobeUtil.createAlternate(u, fx.book, c1, LIVE_DESCRIPTION, LIVE_CFG_NAME, dataPath());
			log("createAlternate: " + (System.currentTimeMillis() - t) + " ms (elapsedMs=" + cr.elapsedMs + ") reused=" + cr.reused
				+ " items=" + cr.items + " wearables=" + cr.wearables + " colors=" + cr.colorsResolved + " desc=" + cr.description);
			assertNotNull("apparel", cr.apparel);
			assertFalse("not reused", cr.reused);
			assertFalse("items from the LLM", cr.items.isEmpty());
			assertTrue("wearables > 0", cr.wearables > 0);
			String alt = oidOf(cr.apparel);

			BaseRecord fresh = readApparel(u, alt);
			assertEquals("alternate is not the default", Boolean.FALSE, fresh.get(OlioFieldNames.FIELD_IN_USE));
			String desc = fresh.get(FieldNames.FIELD_DESCRIPTION);
			assertNotNull("description", desc);
			assertTrue("description starts with the request: " + desc, desc.startsWith(LIVE_DESCRIPTION));
			assertTrue("description capped", desc.length() <= PbWardrobeUtil.MAX_DESCRIPTION_CHARS);
			long olioUserId = idOf(fx.octx.getOlioUser());
			assertEquals("olio-principal owned", olioUserId, ((Number) fresh.get(FieldNames.FIELD_OWNER_ID)).longValue());
			BaseRecord apparelGroup = fx.bctx.getGroup(OlioFieldNames.FIELD_APPAREL);
			long gid = ((Number) fresh.get(FieldNames.FIELD_GROUP_ID)).longValue();
			if(apparelGroup != null && idOf(apparelGroup) > 0L) {
				assertEquals("in the world's Apparel group", idOf(apparelGroup), gid);
			}
			else {
				log("world apparel group not resolvable from BookContext; asserting groupId > 0 only (" + gid + ")");
				assertTrue("groupId set", gid > 0L);
			}
			BaseRecord full = readApparelFull(u, alt);
			List<BaseRecord> wears = full.get(OlioFieldNames.FIELD_WEARABLES);
			assertEquals("wearables persisted", cr.wearables, wears.size());
			for(BaseRecord w : wears) {
				BaseRecord wf = readWearable(u, idOf(w));
				assertEquals("wearable " + wf.get(FieldNames.FIELD_NAME) + " inuse", Boolean.TRUE, wf.get(OlioFieldNames.FIELD_IN_USE));
			}

			Wardrobe w = PbWardrobeUtil.listWardrobe(u, fx.book, c1);
			assertEquals("two outfits", 2, w.entries.size());
			assertEquals("default still A", a, oidOf(w.defaultEntry.apparel));

			t = System.currentTimeMillis();
			CreateResult dup = PbWardrobeUtil.createAlternate(u, fx.book, c1,
				"  A LONG green wool   travelling cloak over a grey linen tunic and dark boots  ", LIVE_CFG_NAME, dataPath());
			log("createAlternate dedupe: " + (System.currentTimeMillis() - t) + " ms reused=" + dup.reused);
			assertTrue("reused", dup.reused);
			assertEquals("same apparel", alt, oidOf(dup.apparel));
			assertEquals("still two outfits", 2, PbWardrobeUtil.listWardrobe(u, fx.book, c1).entries.size());

			refused("blank description", new int[] {400}, () -> PbWardrobeUtil.createAlternate(u, fx.book, c1, "   ", LIVE_CFG_NAME, dataPath()));
			log("createAlternateLiveLlmDedupesAndIsOlioOwned done");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 5b. braced text is not a template placeholder ───────────────────────────────

	/// P4-13 (d2). A user's outfit request or a character's pbDescription can carry {braces}; both are
	/// substituted into the guess-apparel template, whose UNSUBSTITUTED_PLACEHOLDER guard refuses the whole
	/// LLM call on any {word} left in the rendered prompt. normalizeRequest and physicalAppearance now run
	/// through PbLocationUtil.sanitizeForPrompt, which turns braces into parentheses.
	@Test
	public void createAlternateBracedDescriptionsAreNotPlaceholders() throws Exception {
		assertEquals("a cloak with a (style) hood and (color) trim",
			PbWardrobeUtil.normalizeRequest("a cloak with a {style} hood and {color} trim"));
		assertEquals("(hood)", PbWardrobeUtil.normalizeRequest("  {hood}  "));
		assertNull("blank stays nothing", PbWardrobeUtil.normalizeRequest("   "));
		String longBraced = "{" + "y".repeat(PbWardrobeUtil.MAX_DESCRIPTION_CHARS + 40) + "}";
		String capped = PbWardrobeUtil.normalizeRequest(longBraced);
		assertEquals("capped after substitution", PbWardrobeUtil.MAX_DESCRIPTION_CHARS, capped.length());
		assertFalse("no brace survives the cap", capped.contains("{") || capped.contains("}"));

		BaseRecord u = user();
		BaseRecord bk = newBook(u, "brace");
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Oriel Thane");
			giveApparel(u, fx.octx, c1, true);
			BaseRecord cp = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			String bracedLook = "a tall woman with {hair} hair, {eyes} eyes and a scar across one brow";
			assertTrue("pbDescription persisted", IOSystem.getActiveContext().getRecordUtil().createRecord(
				AttributeUtil.addAttribute(cp, PictureBookUtil.ATTR_DESCRIPTION, bracedLook)));
			BaseRecord reread = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			String appearance = PbWardrobeUtil.physicalAppearance(reread);
			log("physicalAppearance -> " + appearance);
			assertEquals("braces in the character's look become parentheses",
				"a tall woman with (hair) hair, (eyes) eyes and a scar across one brow", appearance);

			BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(u, LIVE_CFG_NAME, testProperties);
			assertNotNull("live chat config", cfg);
			log("llm route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
				+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + cfg.get("model"));
			String bracedRequest = "a {colour} wool cloak with a {style} hood over a plain tunic and boots";
			long t = System.currentTimeMillis();
			/// Before (d2) the braced appearance and request reached the template verbatim and the guard refused
			/// the call: callLlmForChapBook returned null and this threw 502 "The LLM returned nothing".
			CreateResult cr = PbWardrobeUtil.createAlternate(u, fx.book, c1, bracedRequest, LIVE_CFG_NAME, dataPath());
			log("createAlternate (braced): " + (System.currentTimeMillis() - t) + " ms reused=" + cr.reused + " items=" + cr.items
				+ " wearables=" + cr.wearables + " desc=" + cr.description);
			assertNotNull("apparel", cr.apparel);
			assertFalse("not reused", cr.reused);
			assertTrue("wearables > 0", cr.wearables > 0);
			String desc = readApparel(u, oidOf(cr.apparel)).get(FieldNames.FIELD_DESCRIPTION);
			assertNotNull("description", desc);
			assertTrue("stored description starts with the substituted request: " + desc,
				desc.startsWith("a (colour) wool cloak with a (style) hood over a plain tunic and boots"));
			assertFalse("no brace is stored: " + desc, desc.contains("{") || desc.contains("}"));
			log("createAlternateBracedDescriptionsAreNotPlaceholders done");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 6. live SD reference image ───────────────────────────────

	@Test
	public void referenceImageLiveSdLandsInGallery() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "ref");
		try {
			Fixture fx = fixture(u, bk);
			String c1 = fx.character("Mira Vale");
			String a = giveApparel(u, fx.octx, c1, true);
			String server = testProperties.getProperty("test.swarm.server");
			assertNotNull("test.swarm.server", server);
			SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
			log("sd " + sdu.getApiType() + " " + sdu.getServer());

			long t = System.currentTimeMillis();
			ReferenceResult rr = PbWardrobeUtil.referenceImage(u, fx.book, c1, a, sdu);
			log("referenceImage: " + (System.currentTimeMillis() - t) + " ms (elapsedMs=" + rr.elapsedMs + ") images=" + rr.images.size()
				+ " reference=" + rr.referenceObjectId + " groupPath=" + rr.groupPath);
			assertFalse("images", rr.images.isEmpty());
			assertNotNull("referenceObjectId", rr.referenceObjectId);
			assertEquals("reference is the last image", oidOf(rr.images.get(rr.images.size() - 1)), rr.referenceObjectId);
			BaseRecord apparel = readApparel(u, a);
			String expectedPath = PbWardrobeUtil.GALLERY_PATH_PREFIX + PbWardrobeUtil.pathSegment(apparel.get(FieldNames.FIELD_NAME), idOf(apparel));
			assertEquals("gallery path", expectedPath, rr.groupPath);

			List<BaseRecord> members = ioContext.getMemberUtil().findMembers(apparel, OlioFieldNames.FIELD_GALLERY, ModelNames.MODEL_DATA, 0L);
			List<Long> pids = new ArrayList<>();
			for(BaseRecord m : members) {
				pids.add(((Number) m.get(FieldNames.FIELD_PARTICIPANT_ID)).longValue());
			}
			for(BaseRecord img : rr.images) {
				assertTrue("gallery participation for " + oidOf(img) + " in " + pids, pids.contains(idOf(img)));
			}
			Wardrobe w = PbWardrobeUtil.listWardrobe(u, fx.book, c1);
			WardrobeEntry entry = null;
			for(WardrobeEntry e : w.entries) {
				if(a.equals(oidOf(e.apparel))) {
					entry = e;
				}
			}
			assertNotNull("entry", entry);
			assertTrue("gallery on the entry", entry.gallery.size() >= 1);
			boolean found = false;
			for(BaseRecord g : entry.gallery) {
				if(rr.referenceObjectId.equals(oidOf(g))) {
					found = true;
				}
			}
			assertTrue("reference image is in the entry gallery", found);

			int i = 0;
			for(BaseRecord img : rr.images) {
				BaseRecord data = freshReadData(u, oidOf(img), fx.orgId);
				byte[] bytes = ByteModelUtil.getValue(data);
				assertNotNull("bytes", bytes);
				assertTrue("image bytes > 1024: " + bytes.length, bytes.length > 1024);
				String path = "/c/tmp/p45-reference-ref-" + i + (isPng(bytes) ? ".png" : ".bin");
				assertTrue("emit " + path, FileUtil.emitFile("c:/tmp/p45-reference-ref-" + i + (isPng(bytes) ? ".png" : ".bin"), bytes));
				log("image " + i + " " + oidOf(img) + " contentType=" + data.get(FieldNames.FIELD_CONTENT_TYPE) + " bytes=" + bytes.length
					+ " dims=" + dims(bytes) + " -> " + path);
				if(isPng(bytes)) {
					assertEquals("mannequin width", SDUtil.MANNEQUIN_IMAGE_WIDTH, pngWidth(bytes));
					assertEquals("mannequin height", SDUtil.MANNEQUIN_IMAGE_HEIGHT, pngHeight(bytes));
				}
				i++;
			}
			refused("unknown apparel", new int[] {404}, () -> PbWardrobeUtil.referenceImage(u, fx.book, c1, "nope-" + shortId(), sdu));
			refused("no SD client", new int[] {500}, () -> PbWardrobeUtil.referenceImage(u, fx.book, c1, a, null));
			log("referenceImageLiveSdLandsInGallery done");
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── 7. facade DTOs + job ───────────────────────────────

	@Test
	public void facadeDtosAndJobSubmit() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = newBook(u, "fac");
		String jobId = null;
		String jobId2 = null;
		BaseRecord u2 = null;
		try {
			Fixture fx = fixture(u, bk);
			String bookOid = oidOf(bk);
			String c1 = fx.character("Mira Vale");
			BaseRecord cp1 = PbWardrobeUtil.requireCharacterOfBook(u, fx.book, c1);
			long char1 = idOf(cp1);
			String a = giveApparel(u, fx.octx, c1, true);
			String b = giveApparel(u, fx.octx, c1, false);
			BaseRecord deadCfg = OlioTestUtil.getUnreachableOllamaConfig(u, DEAD_CFG_NAME, DEAD_URL, "qwen3:8b", 5);
			BaseRecord[] scenes = new BaseRecord[3];
			BaseRecord[] comps = new BaseRecord[3];
			for(int i = 1; i <= 2; i++) {
				scenes[i] = addScene(u, bk, i, "Scene " + i);
				enrol(u, scenes[i], cp1);
				comps[i] = composeDead(u, fx.book, scenes[i], deadCfg);
			}

			Map<String, Object> lw = PbServiceFacade.listWardrobe(u, bookOid, c1);
			log("facade listWardrobe: " + JSONUtil.exportObject(lw));
			assertEquals("bookObjectId", bookOid, lw.get("bookObjectId"));
			assertEquals("characterObjectId", c1, lw.get("characterObjectId"));
			assertEquals("defaultApparelObjectId", a, lw.get("defaultApparelObjectId"));
			List<Map<String, Object>> entries = entries(lw);
			assertEquals("entries", 2, entries.size());
			assertEquals("entryCount", 2, ((Number) lw.get("entryCount")).intValue());
			for(Map<String, Object> e : entries) {
				assertTrue("entry keys: " + e.keySet(), e.keySet().containsAll(Arrays.asList("objectId", "name", "isDefault", "wearables", "gallery")));
				assertTrue("referenceObjectId key present", e.containsKey("referenceObjectId"));
			}

			String actor1in1 = actorOf(u, comps[1], char1);
			Map<String, Object> pin = PbServiceFacade.pinActorApparel(u, bookOid, oidOf(comps[1]), actor1in1, b);
			log("facade pin: " + JSONUtil.exportObject(pin));
			assertEquals("pinned", Boolean.TRUE, pin.get("pinned"));
			assertEquals("apparelObjectId", b, pin.get("apparelObjectId"));
			assertEquals("compositionObjectId", oidOf(comps[1]), pin.get("compositionObjectId"));
			assertEquals("actorObjectId", actor1in1, pin.get("actorObjectId"));
			assertEquals("affectedSceneIndexes", Arrays.asList(1, 2), pin.get("affectedSceneIndexes"));
			assertTrue("staled is an int", pin.get("staled") instanceof Integer);

			Map<String, Object> out = PbServiceFacade.compositionOutfits(u, bookOid, oidOf(comps[2]));
			log("facade outfits scene 2: " + JSONUtil.exportObject(out));
			assertEquals("sceneObjectId", oidOf(scenes[2]), out.get("sceneObjectId"));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> actors = (List<Map<String, Object>>) out.get("actors");
			assertEquals("one actor", 1, actors.size());
			Map<String, Object> ad = actors.get(0);
			assertEquals("source lowercase", "carried", ad.get("source"));
			assertEquals("label", "(from scene 1)", ad.get("label"));
			assertEquals("fromSceneIndex", 1, ((Number) ad.get("fromSceneIndex")).intValue());
			assertEquals("apparelObjectId", b, ad.get("apparelObjectId"));
			assertEquals("characterObjectId", c1, ad.get("characterObjectId"));
			assertTrue("actor keys: " + ad.keySet(), ad.keySet().containsAll(Arrays.asList("actorObjectId", "actorName", "apparelName", "warning")));

			Map<String, Object> unpin = PbServiceFacade.pinActorApparel(u, bookOid, oidOf(comps[1]), actor1in1, "  ");
			assertEquals("blank apparel unpins", Boolean.FALSE, unpin.get("pinned"));
			assertNull("no apparel after unpin", unpin.get("apparelObjectId"));
			out = PbServiceFacade.compositionOutfits(u, bookOid, oidOf(comps[2]));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> actors2 = (List<Map<String, Object>>) out.get("actors");
			assertEquals("back to default", "default", actors2.get(0).get("source"));
			refused("facade: composition of unknown book", new int[] {404}, () -> PbServiceFacade.compositionOutfits(u, bookOid, "nope-" + shortId()));
			refused("facade: blank composition", new int[] {400}, () -> PbServiceFacade.pinActorApparel(u, bookOid, " ", actor1in1, b));
			refused("facade: blank actor", new int[] {400}, () -> PbServiceFacade.pinActorApparel(u, bookOid, oidOf(comps[1]), " ", b));

			Map<String, Object> sd = PbServiceFacade.setDefaultApparel(u, bookOid, c1, b);
			log("facade setDefault: " + JSONUtil.exportObject(sd));
			assertEquals("changed", Boolean.TRUE, sd.get("changed"));
			assertEquals("apparelObjectId", b, sd.get("apparelObjectId"));
			assertEquals("previousApparelObjectIds", Arrays.asList(a), sd.get("previousApparelObjectIds"));
			assertTrue("staled is an int", sd.get("staled") instanceof Integer);
			assertTrue("scenesScanned is an int", sd.get("scenesScanned") instanceof Integer);
			assertEquals("default flipped", b, PbServiceFacade.listWardrobe(u, bookOid, c1).get("defaultApparelObjectId"));

			Map<String, Object> mig = PbServiceFacade.migrateSceneTags(u, bookOid);
			log("facade migrate: " + JSONUtil.exportObject(mig));
			assertTrue("migrate keys: " + mig.keySet(), mig.keySet().containsAll(Arrays.asList("bookObjectId", "scanned", "pinned", "skippedPinned",
				"skippedNoActor", "skippedNoScene", "skippedNoComposition", "ambiguous", "warnings")));
			assertEquals("nothing tagged", 0, ((Number) mig.get("scanned")).intValue());

			String server = testProperties.getProperty("test.swarm.server");
			assertNotNull("test.swarm.server", server);
			int activeBefore = AsyncJobRegistry.activeCount(u);
			refused("facade reference: unknown apparel", new int[] {400, 404},
				() -> PbServiceFacade.requestApparelReference(u, bookOid, c1, "nope", "SWARM", server));
			assertEquals("no job for a refused reference", activeBefore, AsyncJobRegistry.activeCount(u));
			assertNull("no claim for a refused reference", PbComposeJobUtil.inflightJobId("nope"));

			SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
			OrganizationContext org = getTestOrganization(ORG_A);
			u2 = ioContext.getFactory().getCreateUser(org.getAdminUser(), SECOND_USER, org.getOrganizationId());
			assertNotNull("second principal", u2);
			long t = System.currentTimeMillis();
			Map<String, Object> sub = PbComposeJobUtil.submitApparelReference(u, bk, c1, b, sdu, server);
			log("submitApparelReference: " + JSONUtil.exportObject(sub));
			jobId = (String) sub.get("jobId");
			assertNotNull("jobId", jobId);
			assertEquals("submitted", Boolean.TRUE, sub.get("submitted"));
			assertEquals("not reused", Boolean.FALSE, sub.get("reused"));
			assertEquals("kind", PbRenderJobUtil.KIND_APPAREL_REFERENCE, sub.get("kind"));
			assertEquals("claim on the apparel", jobId, PbComposeJobUtil.inflightJobId(b));

			final BaseRecord other = u2;
			try {
				Map<String, Object> sub2 = PbComposeJobUtil.submitApparelReference(other, bk, c1, b, sdu, server);
				jobId2 = (String) sub2.get("jobId");
				fail("a second principal must be refused while the wardrobe entry is claimed; got " + JSONUtil.exportObject(sub2));
			}
			catch(PictureBookException e) {
				log("second principal refused: " + e.getStatus() + " " + e.getMessage());
				assertEquals("409", 409, e.getStatus());
				assertTrue("mentions the wardrobe entry: " + e.getMessage(), e.getMessage().contains("wardrobe entry"));
			}
			Map<String, Object> resub = PbComposeJobUtil.submitApparelReference(u, bk, c1, b, sdu, server);
			log("same-user resubmit: " + JSONUtil.exportObject(resub));
			assertEquals("reused", Boolean.TRUE, resub.get("reused"));
			assertEquals("same job", jobId, resub.get("jobId"));

			AsyncJob job = awaitTerminalLoggingPhases(u, jobId, JOB_CEILING_MS, "reference");
			log("job " + jobId + " " + job.getStatus() + " after " + (System.currentTimeMillis() - t) + " ms error=" + job.getError());
			assertEquals("job completed: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			assertNull("claim released", PbComposeJobUtil.inflightJobId(b));
			@SuppressWarnings("unchecked")
			Map<String, Object> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull("result", result);
			log("job result: " + JSONUtil.exportObject(result));
			String refOid = (String) result.get("referenceObjectId");
			assertNotNull("referenceObjectId", refOid);
			assertTrue("imageCount >= 1", ((Number) result.get("imageCount")).intValue() >= 1);
			assertNotNull("groupPath", result.get("groupPath"));
			assertNotNull("renderMs", result.get("renderMs"));
			@SuppressWarnings("unchecked")
			List<String> imageIds = (List<String>) result.get("imageObjectIds");
			assertTrue("imageObjectIds contains the reference", imageIds.contains(refOid));

			lw = PbServiceFacade.listWardrobe(u, bookOid, c1);
			Map<String, Object> entryB = null;
			for(Map<String, Object> e : entries(lw)) {
				if(b.equals(e.get("objectId"))) {
					entryB = e;
				}
			}
			assertNotNull("entry B", entryB);
			assertEquals("entry referenceObjectId", refOid, entryB.get("referenceObjectId"));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> gallery = (List<Map<String, Object>>) entryB.get("gallery");
			boolean found = false;
			for(Map<String, Object> g : gallery) {
				if(refOid.equals(g.get("objectId"))) {
					found = true;
				}
			}
			assertTrue("gallery grew with the reference", found);
			BaseRecord data = freshReadData(u, refOid, fx.orgId);
			byte[] bytes = ByteModelUtil.getValue(data);
			assertTrue("image bytes > 1024", bytes != null && bytes.length > 1024);
			assertTrue("emit", FileUtil.emitFile("c:/tmp/p45-reference-job" + (isPng(bytes) ? ".png" : ".bin"), bytes));
			log("job image " + refOid + " bytes=" + bytes.length + " dims=" + dims(bytes) + " -> /c/tmp/p45-reference-job" + (isPng(bytes) ? ".png" : ".bin"));
			log("facadeDtosAndJobSubmit done");
		}
		finally {
			if(jobId2 != null && u2 != null) {
				AsyncJobRegistry.cancel(u2, jobId2);
				awaitTerminalQuietly(u2, jobId2, 60_000L);
			}
			if(jobId != null) {
				AsyncJobRegistry.cancel(u, jobId);
				awaitTerminalQuietly(u, jobId, JOB_CEILING_MS);
			}
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── fixtures ───────────────────────────────

	private final class Fixture {
		BaseRecord book;
		BookContext bctx;
		OlioContext octx;
		BaseRecord popGroup;
		String popPath;
		long orgId;
		BaseRecord owner;

		String character(String name) throws Exception {
			return createCharPersonIn(owner, popGroup, popPath, name + " " + shortId());
		}
	}

	private Fixture fixture(BaseRecord u, BaseRecord bk) throws Exception {
		Fixture f = new Fixture();
		f.owner = u;
		f.orgId = orgOf(u);
		f.book = PbBookUtil.readBook(u, oidOf(bk), f.orgId);
		assertNotNull("readBook", f.book);
		f.bctx = PbBookUtil.openBookContext(u, f.book);
		assertNotNull("book context", f.bctx);
		f.popGroup = f.bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
		f.popPath = f.bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("population group", f.popGroup);
		assertNotNull("population path", f.popPath);
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		f.octx = PbOlioContextUtil.getCreateBookContext(u, dataPath(), slug);
		assertNotNull("olio context", f.octx);
		return f;
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

	private BaseRecord createHomeCharPerson(BaseRecord u, String groupPath, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, "Home");
		cp.set(FieldNames.FIELD_GENDER, "female");
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create home character", created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
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

	private BaseRecord composeDead(BaseRecord u, BaseRecord book, BaseRecord scene, BaseRecord deadCfg) {
		long t = System.currentTimeMillis();
		ComposeResult r = PbComposeUtil.composeScene(u, book, scene, deadCfg, false);
		log("compose scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX) + ": " + (System.currentTimeMillis() - t) + " ms " + TestPbComposeFallback.describe(r));
		assertTrue("dead-LLM compose falls back to MIGRATED", r.migratedFallback);
		assertFalse("not a hard failure", r.hardFailure);
		assertTrue("composition written", r.isWritten());
		assertEquals("source", PbCompositionSourceEnumType.MIGRATED, r.source);
		assertNotNull("composition", r.composition);
		return r.composition;
	}

	private String actorOf(BaseRecord u, BaseRecord comp, long charId) {
		for(BaseRecord row : PbComposeUtil.listActors(u, comp)) {
			if(fkId(row, OlioFieldNames.FIELD_PB_CHARACTER) == charId) {
				return oidOf(row);
			}
		}
		fail("no actor for character #" + charId + " in composition " + oidOf(comp));
		return null;
	}

	private static ResolvedOutfit outfitOf(List<SceneOutfits> all, int sceneIndex, long charId) {
		for(SceneOutfits so : all) {
			Integer idx = so.scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			if(idx != null && idx.intValue() == sceneIndex) {
				return outfitOf(so.actors, charId);
			}
		}
		fail("scene " + sceneIndex + " not resolved");
		return null;
	}

	private static ResolvedOutfit outfitOf(List<ResolvedOutfit> actors, long charId) {
		for(ResolvedOutfit ro : actors) {
			if(ro.character != null && idOf(ro.character) == charId) {
				return ro;
			}
		}
		fail("character #" + charId + " has no resolved outfit in " + actors.size() + " actors");
		return null;
	}

	private void expect(List<SceneOutfits> all, int sceneIndex, long charId, OutfitSource source, String apparelOid, int fromIdx, String label) {
		ResolvedOutfit ro = outfitOf(all, sceneIndex, charId);
		log("scene " + sceneIndex + " char #" + charId + ": " + ro.source + " " + (ro.apparel != null ? oidOf(ro.apparel) : null) + " " + ro.label()
			+ (ro.warning != null ? " warning=" + ro.warning : ""));
		assertEquals("scene " + sceneIndex + " source", source, ro.source);
		assertNotNull("scene " + sceneIndex + " apparel", ro.apparel);
		assertEquals("scene " + sceneIndex + " apparel", apparelOid, oidOf(ro.apparel));
		assertEquals("scene " + sceneIndex + " fromSceneIndex", fromIdx, ro.fromSceneIndex);
		assertEquals("scene " + sceneIndex + " label", label, ro.label());
	}

	private static String describe(MigrationResult m) {
		return "scanned=" + m.scanned + " pinned=" + m.pinned + " skippedPinned=" + m.skippedPinned + " skippedNoActor=" + m.skippedNoActor
			+ " skippedNoScene=" + m.skippedNoScene + " skippedNoComposition=" + m.skippedNoComposition + " ambiguous=" + m.ambiguous + " warnings=" + m.warnings;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> entries(Map<String, Object> wardrobe) {
		return (List<Map<String, Object>>) wardrobe.get("entries");
	}

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	private void refused(String what, int[] statuses, Call c) throws Exception {
		try {
			c.run();
		}
		catch(PictureBookException e) {
			log("refused (" + what + "): " + e.getStatus() + " " + e.getMessage());
			for(int s : statuses) {
				if(s == e.getStatus()) {
					return;
				}
			}
			fail(what + ": expected " + Arrays.toString(statuses) + " but got " + e.getStatus() + " " + e.getMessage());
		}
		fail(what + ": expected PictureBookException " + Arrays.toString(statuses) + " but the call succeeded");
	}

	// ─────────────────────────────── reads ───────────────────────────────

	private BaseRecord readApparel(BaseRecord u, String oid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, OlioFieldNames.FIELD_IN_USE, FieldNames.FIELD_ATTRIBUTES});
		q.setCache(false);
		BaseRecord rec = ioContext.getAccessPoint().find(u, q);
		assertNotNull("apparel " + oid, rec);
		return rec;
	}

	private BaseRecord readApparelFull(BaseRecord u, String oid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.planMost(true);
		q.setCache(false);
		BaseRecord rec = ioContext.getAccessPoint().find(u, q);
		assertNotNull("apparel (full) " + oid, rec);
		return rec;
	}

	private BaseRecord readWearable(BaseRecord u, long id) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_WEARABLE, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_IN_USE});
		q.setCache(false);
		BaseRecord rec = ioContext.getAccessPoint().find(u, q);
		assertNotNull("wearable #" + id, rec);
		return rec;
	}

	private static Integer readSceneIndex(BaseRecord user, String apparelOid) throws Exception {
		Query aq = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, apparelOid);
		aq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		aq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ATTRIBUTES});
		aq.setCache(false);
		BaseRecord fresh = IOSystem.getActiveContext().getAccessPoint().find(user, aq);
		assertNotNull("apparel " + apparelOid, fresh);
		return AttributeUtil.getAttributeValue(fresh, PbWardrobeUtil.ATTR_SCENE_INDEX, null);
	}

	private BaseRecord freshReadData(BaseRecord u, String oid, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES, FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED, FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID,
			FieldNames.FIELD_VAULT_ID});
		q.setCache(false);
		BaseRecord rec = ioContext.getAccessPoint().find(u, q);
		assertNotNull("data " + oid, rec);
		return rec;
	}

	private static boolean isPng(byte[] b) {
		return b != null && b.length > 24 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
	}

	private static int pngWidth(byte[] b) {
		return ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
	}

	private static int pngHeight(byte[] b) {
		return ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
	}

	private static String dims(byte[] b) {
		if(isPng(b)) {
			return pngWidth(b) + "x" + pngHeight(b) + " (png)";
		}
		if(b != null && b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
			return "jpeg (dimensions not parsed)";
		}
		return "unknown format";
	}

	// ─────────────────────────────── graph / nodes ───────────────────────────────

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord ensurePageNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String sceneOid) throws Exception {
		long orgId = orgOf(u);
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = readScene(u, sceneOid, orgId);
		long linked = fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE);
		if(linked > 0L) {
			BaseRecord existing = PbGraphUtil.readNodeById(u, linked, orgId);
			assertNotNull("linked page node", existing);
			return existing;
		}
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(u, wf, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.LANDSCAPE, PbBookUtil.workflowGroupPath(slug), idx);
			assertNotNull("page node " + handle, node);
			assertTrue("page node scope patch", PbPipelineUtil.patchNodeScope(u, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
		BaseRecord back = readScene(u, sceneOid, orgId);
		assertEquals("the page links its node", idOf(node), fkId(back, OlioFieldNames.FIELD_PB_SCENE_NODE));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("page node re-read", fresh);
		return fresh;
	}

	private BaseRecord setStatus(BaseRecord u, BaseRecord node, PbNodeStatusEnumType to, long orgId) {
		assertTrue("persistStatus " + to, PbGraphUtil.persistStatus(u, node, to));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("node re-read", fresh);
		assertEquals("precondition: node is " + to, to.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	private void assertNodeStatus(BaseRecord u, BaseRecord node, PbNodeStatusEnumType expected, long orgId) {
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("node re-read", fresh);
		assertEquals("node " + fresh.get(FieldNames.FIELD_NAME) + " status", expected.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v == null ? null : String.valueOf(v).toUpperCase());
	}

	private BaseRecord readScene(BaseRecord u, String oid, long orgId) {
		BaseRecord s = PbBookUtil.readScene(u, oid, orgId);
		assertNotNull("scene " + oid, s);
		return s;
	}

	// ─────────────────────────────── book / scene ───────────────────────────────

	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		List<String> fields = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		put(fields, values, OlioFieldNames.FIELD_PB_SETTING, "a rain-dark harbour street at dusk");
		put(fields, values, OlioFieldNames.FIELD_PB_ACTION, "walking toward the tavern door");
		put(fields, values, OlioFieldNames.FIELD_PB_MOOD, "wary");
		put(fields, values, FieldNames.FIELD_DESCRIPTION, "Scene " + index + " of the P4-5 wardrobe fixture");
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
		for(Map.Entry<String, Object> e : values.entrySet()) {
			patch.set(e.getKey(), e.getValue());
		}
		assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		return readScene(u, oidOf(scene), orgOf(u));
	}

	private static void put(List<String> fields, Map<String, Object> values, String field, Object value) {
		if(value != null) {
			fields.add(field);
			values.put(field, value);
		}
	}

	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME) + " in scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX),
			ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p45" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-5 " + tag + " " + slug);
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
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("cleanup: could not delete " + rec.get(FieldNames.FIELD_NAME));
			}
		}
		catch(Exception e) {
			logger.error("cleanup failed: " + e.getMessage());
		}
	}

	// ─────────────────────────────── jobs ───────────────────────────────

	private AsyncJob awaitTerminalLoggingPhases(BaseRecord u, String jobId, long maxMs, String tag) throws InterruptedException {
		long start = System.currentTimeMillis();
		String last = null;
		while(System.currentTimeMillis() - start < maxMs) {
			AsyncJob job = AsyncJobRegistry.get(u, jobId);
			assertNotNull("job " + jobId + " visible to its owner", job);
			String phase = (job.getProgress() != null ? job.getProgress().getPhase() + " " + job.getProgress().getCurrent() + "/" + job.getProgress().getTotal() : "-");
			String line = job.getStatus() + " " + phase;
			if(!line.equals(last)) {
				log(tag + " job " + jobId + ": " + line);
				last = line;
			}
			if(job.isTerminal()) {
				return job;
			}
			Thread.sleep(PHASE_POLL_MS);
		}
		fail(tag + " job " + jobId + " did not reach a terminal state within " + maxMs + " ms");
		return null;
	}

	private void awaitTerminalQuietly(BaseRecord u, String jobId, long maxMs) {
		long start = System.currentTimeMillis();
		try {
			while(System.currentTimeMillis() - start < maxMs) {
				AsyncJob job = AsyncJobRegistry.get(u, jobId);
				if(job == null || job.isTerminal()) {
					return;
				}
				Thread.sleep(POLL_MS);
			}
			logger.warn("job " + jobId + " still not terminal after " + maxMs + " ms");
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ─────────────────────────────── misc ───────────────────────────────

	private void log(String msg) {
		logger.info("[p45] +" + (System.currentTimeMillis() - t0) + " ms " + msg);
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
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}
}
