package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.AttributeUtil;

/**
 * PictureBook V3 Phase 4 (design §6, §6.1, §6.2; P4-5): a character's wardrobe is its {@code olio.store.apparel}
 * list. Exactly one entry is the <i>default</i> ({@code apparel.inuse = true}); the rest are <i>alternates</i>
 * ({@code inuse = false}). A scene actor ({@code olio.pb.sceneActor.apparel}) may be <i>pinned</i> to one entry of
 * its own character's wardrobe; an unpinned actor wears the nearest earlier pin of the same character
 * (<i>carried</i>, "(from scene N)") or, with no earlier pin, the default ("(default)").
 * <p>
 * <b>Who writes what.</b> Alternates are world sub-records like every other apparel a PictureBook character
 * owns: instantiated in the world's Apparel/Wearables/Qualities groups and persisted as the olio principal
 * (the {@code PictureBookUtil.generateApparelFromCharData} construct/design/colour sequence, but always owned by the
 * olio principal where that path uses the character's owner - the same user for V3 olio-owned characters, not for
 * a legacy user-owned one), then attached to the character's store
 * <i>as the request user</i> through {@code AccessPoint.member} - {@code canUpdate} on the store is checked before
 * the LLM call and the olio write, so a refused caller gets a 403 and no orphan apparel. Pins are plain
 * {@code sceneActor} patches as the request user. The default flip
 * is {@code AccessPoint.update} of {@code apparel.inuse} as the request user - Stephen's shipped
 * {@code selectSceneApparel} pattern - so a user who may not update world apparel gets a 403, never a
 * silent olio-principal write. Nothing here ever mutates {@code inuse} as a side effect of reading or rendering.
 * <p>
 * <b>Carry-forward is resolved, never persisted:</b> only pins are stored. Changing the default therefore
 * changes every default-resolved scene of that character, and those scenes alone are marked stale.
 */
public class PbWardrobeUtil {

	private static final Logger logger = LogManager.getLogger(PbWardrobeUtil.class);

	/** Outfits per character; a cap on how far one store can be fanned out, not a product limit. */
	public static final int MAX_ALTERNATES = 24;
	/** {@code olio.apparel} inherits {@code common.description2K}; the request text is capped well below it. */
	public static final int MAX_DESCRIPTION_CHARS = 512;
	/** The character's physical description as handed to the outfit prompt; {@code common.description2K} width. */
	public static final int MAX_APPEARANCE_CHARS = 2048;
	public static final int MAX_NAME_CHARS = 64;
	/** The legacy per-scene apparel tag written by {@code PictureBookUtil.tagApparelSceneIndex}. */
	public static final String ATTR_SCENE_INDEX = "sceneIndex";
	/** Where reference images land, under the request user's home - the {@code OlioService.reimageApparel} path. */
	public static final String GALLERY_PATH_PREFIX = "~/Gallery/Apparel/";
	public static final String PROMPT_GUESS_APPAREL = "pictureBook.guess-apparel";
	/** Guess calls per alternate when the reply names nothing in the catalog; bounded so a stubborn model costs at most two calls. */
	public static final int GUESS_ATTEMPTS = 2;
	/** How many LLM item names an error message repeats back to the caller. */
	static final int MAX_ECHO_ITEMS = 8;
	/** Appended to the outfit notes on the second guess so the retry is not the identical prompt. */
	static final String GUESS_RETRY_HINT = " IMPORTANT: choose ONLY exact item names from the catalog list; do not invent, qualify or rename them.";
	/** Prefixed to the requested description so it outranks any clothing the character's own description mentions. */
	static final String REQUEST_FRAME = "Dress the character in THIS outfit, which replaces whatever they usually wear: ";

	private static final String[] CHARACTER_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_FIRST_NAME,
		FieldNames.FIELD_GENDER, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
		FieldNames.FIELD_STORE, OlioFieldNames.FIELD_NARRATIVE, FieldNames.FIELD_ATTRIBUTES
	};

	private static final String[] APPAREL_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
		OlioFieldNames.FIELD_IN_USE, FieldNames.FIELD_GENDER, FieldNames.FIELD_ATTRIBUTES
	};

	private PbWardrobeUtil() {}

	// ─────────────────────────────── results ───────────────────────────────

	public enum OutfitSource {
		/** The actor row names an apparel of its character's wardrobe. */
		PINNED,
		/** No pin on this row; the nearest earlier pin of the same character applies. */
		CARRIED,
		/** No pin on this row or any earlier one; the character's default ({@code inuse}) applies. */
		DEFAULT,
		/** The character has no wardrobe entry at all, or the actor has no character. */
		NONE
	}

	public static final class WardrobeEntry {
		public BaseRecord apparel;
		public boolean isDefault;
		/** The legacy {@code sceneIndex} attribute, when the apparel carries one; null otherwise. */
		public Integer sceneIndex;
		public List<BaseRecord> wearables = new ArrayList<>();
		public List<BaseRecord> gallery = new ArrayList<>();
	}

	public static final class Wardrobe {
		public BaseRecord character;
		public BaseRecord store;
		public List<WardrobeEntry> entries = new ArrayList<>();
		public WardrobeEntry defaultEntry;
		public List<String> warnings = new ArrayList<>();
	}

	public static final class ResolvedOutfit {
		public BaseRecord actor;
		public BaseRecord character;
		/// true when character is the row's FK stub because the character itself could not be read as the user
		public boolean characterUnread;
		public BaseRecord apparel;
		public OutfitSource source = OutfitSource.NONE;
		public int fromSceneIndex = -1;
		public String warning;

		public String label() {
			switch(source) {
				case PINNED: return "(pinned)";
				case CARRIED: return "(from scene " + fromSceneIndex + ")";
				case DEFAULT: return "(default)";
				default: return "(none)";
			}
		}
	}

	public static final class SceneOutfits {
		public BaseRecord scene;
		public BaseRecord composition;
		public List<ResolvedOutfit> actors = new ArrayList<>();
	}

	public static final class CreateResult {
		public BaseRecord apparel;
		public boolean reused;
		public String description;
		public List<String> items = new ArrayList<>();
		/** LLM item names the catalog cannot express (no catalog name matched, even as a contained word). */
		public List<String> unmatched = new ArrayList<>();
		public int wearables;
		public int colorsResolved;
		public long elapsedMs;
	}

	public static final class SetDefaultResult {
		public BaseRecord apparel;
		public List<BaseRecord> previous = new ArrayList<>();
		public int scenesScanned;
		public int staled;
		public boolean changed;
		public List<String> warnings = new ArrayList<>();
	}

	public static final class PinResult {
		public BaseRecord actor;
		public BaseRecord apparel;
		public boolean pinned;
		public int staled;
		public List<Integer> affectedSceneIndexes = new ArrayList<>();
	}

	public static final class MigrationResult {
		public int scanned;
		public int pinned;
		public int skippedPinned;
		public int skippedNoActor;
		public int skippedNoScene;
		public int skippedNoComposition;
		public int ambiguous;
		/** Scenes whose resolved outfits the pins changed (the tagged scene and the carried chain after it). */
		public int affected;
		/** Of {@link #affected}, the scenes whose render node was marked STALE. */
		public int staled;
		public List<String> warnings = new ArrayList<>();
	}

	public static final class ReferenceResult {
		public BaseRecord apparel;
		public List<BaseRecord> images = new ArrayList<>();
		public String referenceObjectId;
		public String groupPath;
		public long elapsedMs;
	}

	// ─────────────────────────────── gates ───────────────────────────────

	/**
	 * The character as {@code user} reads it (uncached, with {@code store}, {@code gender} and {@code narrative}),
	 * provided it belongs to the book: its group is the world's Population group, the chapter's shadow character
	 * group, or a legacy {@code <bookGroup>/Characters} group. 404 otherwise - a character of another book is
	 * indistinguishable from one that does not exist.
	 */
	public static BaseRecord requireCharacterOfBook(BaseRecord user, BaseRecord book, String characterObjectId) {
		if(user == null || book == null) {
			throw new PictureBookException(401, "No user");
		}
		if(characterObjectId == null || characterObjectId.trim().isEmpty()) {
			throw new PictureBookException(400, "No character");
		}
		long orgId = PbGraphUtil.orgId(book);
		BaseRecord cp = readCharacter(user, characterObjectId.trim(), orgId);
		if(cp == null) {
			throw new PictureBookException(404, "Character not found: " + characterObjectId);
		}
		Long gid = cp.get(FieldNames.FIELD_GROUP_ID);
		if(gid == null || gid.longValue() <= 0L || !isCharacterGroupOfBook(user, book, gid.longValue())) {
			throw new PictureBookException(404, "Character not found: " + characterObjectId);
		}
		return cp;
	}

	private static boolean isCharacterGroupOfBook(BaseRecord user, BaseRecord book, long groupId) {
		long orgId = PbGraphUtil.orgId(book);
		BookContext bctx = PbBookUtil.openBookContext(user, book);
		if(bctx != null && bctx.getWorld() != null) {
			BaseRecord pop = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			if(pop != null && idOf(pop) == groupId) {
				return true;
			}
			BaseRecord series = (book.hasField(OlioFieldNames.FIELD_PB_SERIES) ? book.get(OlioFieldNames.FIELD_PB_SERIES) : null);
			String slug = str(book.get(OlioFieldNames.FIELD_PB_SLUG));
			if(series != null && idOf(series) > 0L && slug != null) {
				String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
				String shadowPath = PbBookUtil.chapterShadowCharGroupPath(popPath, slug);
				if(shadowPath != null) {
					BaseRecord shadow = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, shadowPath,
						GroupEnumType.DATA.toString(), orgId);
					if(shadow != null && idOf(shadow) == groupId) {
						return true;
					}
				}
			}
		}
		/// Legacy (pre-world) books keep their characters in <bookGroup>/Characters.
		Long bookGid = book.get(FieldNames.FIELD_GROUP_ID);
		if(bookGid == null || bookGid.longValue() <= 0L) {
			return false;
		}
		BaseRecord grp = IOSystem.getActiveContext().getAccessPoint().findById(user, ModelNames.MODEL_GROUP, groupId);
		if(grp == null) {
			return false;
		}
		Long parentId = (grp.hasField(FieldNames.FIELD_PARENT_ID) ? grp.get(FieldNames.FIELD_PARENT_ID) : null);
		return PictureBookUtil.CHARACTERS_DIR.equals(grp.get(FieldNames.FIELD_NAME))
			&& parentId != null && parentId.longValue() == bookGid.longValue();
	}

	/**
	 * The apparel as {@code user} reads it (uncached), provided it is in {@code character}'s store. 400 when the
	 * character has no store or the apparel is not in it; 404 when the user cannot read the apparel at all.
	 */
	public static BaseRecord requireApparelOfCharacter(BaseRecord user, BaseRecord character, String apparelObjectId) {
		if(apparelObjectId == null || apparelObjectId.trim().isEmpty()) {
			throw new PictureBookException(400, "No apparel");
		}
		BaseRecord store = storeOf(user, character);
		if(store == null) {
			throw new PictureBookException(400, "Character " + character.get(FieldNames.FIELD_NAME) + " has no store");
		}
		long orgId = ((Number) character.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord apparel = readApparelByObjectId(user, apparelObjectId.trim(), orgId);
		if(apparel == null) {
			throw new PictureBookException(404, "Apparel not found: " + apparelObjectId);
		}
		if(!inStore(store, idOf(apparel))) {
			throw new PictureBookException(400, "Apparel " + apparelObjectId + " is not in the wardrobe of " + character.get(FieldNames.FIELD_NAME));
		}
		return apparel;
	}

	// ─────────────────────────────── wardrobe ───────────────────────────────

	/** The character's wardrobe as {@code user} reads it: every store apparel with its wearables and gallery, and which one is the default. */
	public static Wardrobe listWardrobe(BaseRecord user, BaseRecord book, String characterObjectId) {
		BaseRecord cp = requireCharacterOfBook(user, book, characterObjectId);
		Wardrobe w = new Wardrobe();
		w.character = cp;
		w.store = storeOf(user, cp);
		if(w.store == null) {
			w.warnings.add("Character has no store");
			return w;
		}
		long orgId = ((Number) cp.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		List<BaseRecord> appl = w.store.get(OlioFieldNames.FIELD_APPAREL);
		if(appl == null) {
			return w;
		}
		List<BaseRecord> sorted = new ArrayList<>(appl);
		sorted.sort((a, b) -> Long.compare(idOf(a), idOf(b)));
		for(BaseRecord a : sorted) {
			WardrobeEntry e = new WardrobeEntry();
			BaseRecord fresh = readApparelById(user, idOf(a), orgId);
			if(fresh == null) {
				w.warnings.add("Apparel #" + idOf(a) + " in the store could not be read");
				continue;
			}
			e.apparel = fresh;
			e.isDefault = Boolean.TRUE.equals(fresh.get(OlioFieldNames.FIELD_IN_USE));
			e.sceneIndex = sceneIndexOf(fresh);
			List<BaseRecord> wears = (a.hasField(OlioFieldNames.FIELD_WEARABLES) ? a.get(OlioFieldNames.FIELD_WEARABLES) : null);
			if(wears != null) {
				e.wearables.addAll(wears);
			}
			e.gallery.addAll(galleryOf(user, fresh));
			w.entries.add(e);
			if(e.isDefault) {
				if(w.defaultEntry == null) {
					w.defaultEntry = e;
				}
				else {
					w.warnings.add("More than one default outfit (inuse) - the lowest id wins; set a default to repair");
				}
			}
		}
		return w;
	}

	/**
	 * The character's default apparel - the store entry with {@code inuse = true} - or null. Several defaults is a
	 * data defect (logged); the lowest id wins so every reader resolves the same one.
	 */
	public static BaseRecord defaultApparel(BaseRecord user, BaseRecord character) {
		BaseRecord store = storeOf(user, character);
		if(store == null) {
			return null;
		}
		return defaultIn(user, character, store);
	}

	private static BaseRecord defaultIn(BaseRecord user, BaseRecord character, BaseRecord store) {
		List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
		if(appl == null || appl.isEmpty()) {
			return null;
		}
		long orgId = ((Number) character.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord best = null;
		int defaults = 0;
		for(BaseRecord a : appl) {
			BaseRecord fresh = readApparelById(user, idOf(a), orgId);
			if(fresh == null || !Boolean.TRUE.equals(fresh.get(OlioFieldNames.FIELD_IN_USE))) {
				continue;
			}
			defaults++;
			if(best == null || idOf(fresh) < idOf(best)) {
				best = fresh;
			}
		}
		if(defaults > 1) {
			logger.warn("Character " + character.get(FieldNames.FIELD_NAME) + " has " + defaults + " outfits marked inuse; #" + idOf(best) + " wins");
		}
		return best;
	}

	// ─────────────────────────────── alternates ───────────────────────────────

	/**
	 * Create an alternate outfit for a character of the book from a free-text description: the LLM picks
	 * catalog items for the description ({@value #PROMPT_GUESS_APPAREL}), {@link ApparelUtil} builds the apparel
	 * in the book world's groups as the olio principal with {@code inuse = false}, and it is attached to the
	 * character's store as {@code user}. A description that normalises to one already in the wardrobe is reused.
	 *
	 * @param chatConfigName the chat config for the LLM step, or null for the user's resolved default
	 * @param dataPath       the deployment's {@code datagen.path}; needed to open the book's {@link OlioContext}
	 * @throws PictureBookException 400 blank description / no store; 403 no update grant on the book; 409 wardrobe
	 *                              full or no Olio context; 502 the LLM produced nothing usable; 500 persistence
	 */
	public static CreateResult createAlternate(BaseRecord user, BaseRecord book, String characterObjectId, String description,
			String chatConfigName, String dataPath) {
		BaseRecord fresh = PbBookUtil.requireBookUpdate(user, book, "modify the wardrobe");
		BaseRecord cp = requireCharacterOfBook(user, fresh, characterObjectId);
		String desc = normalizeRequest(description);
		if(desc == null) {
			throw new PictureBookException(400, "No outfit description");
		}
		BaseRecord store = storeOf(user, cp);
		if(store == null) {
			throw new PictureBookException(400, "Character " + cp.get(FieldNames.FIELD_NAME) + " has no store");
		}
		BaseRecord chatConfig = PbComposeJobUtil.resolveChatConfig(user, chatConfigName);
		BookContext bctx = PbLocationUtil.requireBookContext(user, fresh);
		OlioContext octx = null;
		try {
			octx = resolveOlioContext(user, fresh, bctx, dataPath);
		}
		catch(OlioException e) {
			throw new PictureBookException(409, "Book " + fresh.get(OlioFieldNames.FIELD_PB_SLUG) + " has no usable Olio context: " + e.getMessage());
		}
		return createAlternateUnchecked(user, chatConfig, octx, cp, store, desc);
	}

	/**
	 * {@link #createAlternate} after every gate has passed - the compose path calls this with a character and
	 * store it already holds. Dedupes on the normalised description; caps the wardrobe at {@link #MAX_ALTERNATES}.
	 */
	static CreateResult createAlternateUnchecked(BaseRecord user, BaseRecord chatConfig, OlioContext octx, BaseRecord character,
			BaseRecord store, String description) {
		long start = System.currentTimeMillis();
		String desc = normalizeRequest(description);
		if(desc == null) {
			throw new PictureBookException(400, "No outfit description");
		}
		if(octx == null || octx.getOlioUser() == null || octx.getWorld() == null) {
			throw new PictureBookException(409, "No Olio context to create apparel in");
		}
		long orgId = ((Number) character.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String charName = str(character.get(FieldNames.FIELD_NAME));
		CreateResult res = new CreateResult();
		res.description = desc;

		List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
		int count = 0;
		if(appl != null) {
			String key = dedupeKey(desc);
			for(BaseRecord a : appl) {
				count++;
				BaseRecord fresh = readApparelById(user, idOf(a), orgId);
				if(fresh != null && key.equals(dedupeKey(str(fresh.get(FieldNames.FIELD_DESCRIPTION))))) {
					res.apparel = fresh;
					res.reused = true;
					res.elapsedMs = System.currentTimeMillis() - start;
					logger.info("Wardrobe of " + charName + " already has an outfit for \"" + desc + "\" (#" + idOf(fresh) + "); reused");
					return res;
				}
			}
		}
		if(count >= MAX_ALTERNATES) {
			throw new PictureBookException(409, "Wardrobe of " + charName + " already has " + count + " outfits (limit " + MAX_ALTERNATES + ")");
		}
		/// Checked before the LLM call and the olio-principal write so a refusal leaves no orphan apparel behind
		requireStoreUpdate(user, store, charName);

		String gender = str(character.get(FieldNames.FIELD_GENDER));
		if(gender == null) {
			gender = "unknown";
		}
		String appearance = physicalAppearance(character);
		String request = REQUEST_FRAME + desc;
		Map<String, String> vars = new HashMap<>();
		vars.put("name", (charName != null ? charName : "(unnamed)"));
		vars.put("gender", gender);
		vars.put("appearance", (NarrativeUtil.isMeaningful(appearance) ? appearance : "(none given)"));
		vars.put("clothingStyle", "(none given)");
		vars.put("outfitNotes", request);
		vars.put("role", "(none given)");
		List<String> catalog = ApparelUtil.getApparelCatalogNames(gender);
		vars.put("catalog", String.join(", ", catalog));

		/// The model does not reliably honour "catalog names only" (it answers "travelling cloak" for "cloak"), so the
		/// reply is normalised onto the catalog and, when nothing at all resolves, the guess is asked once more.
		List<String> items = Collections.emptyList();
		List<String> colors = Collections.emptyList();
		List<String> raw = Collections.emptyList();
		String llmDesc = null;
		for(int attempt = 1; attempt <= GUESS_ATTEMPTS && items.isEmpty(); attempt++) {
			if(attempt > 1) {
				vars.put("outfitNotes", request + GUESS_RETRY_HINT + (raw.isEmpty() ? "" : " Your previous reply named " + echo(raw) + ", which are not catalog names."));
			}
			String reply = PictureBookUtil.callLlmForChapBook(user, chatConfig, PROMPT_GUESS_APPAREL, vars);
			if(reply == null || reply.isBlank()) {
				throw new PictureBookException(502, "The LLM returned nothing for the outfit \"" + desc + "\" of " + charName);
			}
			boolean[] ok = new boolean[1];
			Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObject(reply, "guess-apparel:" + charName, null, ok);
			if(!ok[0]) {
				throw new PictureBookException(502, "The LLM reply for the outfit \"" + desc + "\" of " + charName + " was not JSON");
			}
			raw = meaningfulStrings(parsed.get("items"));
			res.unmatched.clear();
			items = catalogItems(raw, catalog, res.unmatched);
			colors = meaningfulStrings(parsed.get("colors"));
			llmDesc = str(parsed.get("description"));
			if(items.isEmpty() && attempt < GUESS_ATTEMPTS) {
				logger.warn("Guess-apparel attempt " + attempt + " for " + charName + (raw.isEmpty() ? " chose no items" : " named nothing in the catalog (" + echo(raw) + ")") + "; asking again");
			}
		}
		if(items.isEmpty()) {
			throw new PictureBookException(502, (raw.isEmpty()
				? "The LLM chose no catalog items for the outfit \"" + desc + "\" of " + charName
				: "None of " + echo(raw) + " is in the apparel catalog for the outfit \"" + desc + "\" of " + charName));
		}
		if(!res.unmatched.isEmpty()) {
			logger.warn("Guess-apparel items " + res.unmatched + " for " + charName + " are not in the catalog; building the outfit from " + items);
		}
		res.items.addAll(items);

		long olioUserId = idOf(octx.getOlioUser());
		BaseRecord apparel = ApparelUtil.constructApparel(octx, olioUserId, character, items.toArray(new String[0]));
		List<BaseRecord> wears = (apparel != null ? apparel.get(OlioFieldNames.FIELD_WEARABLES) : null);
		if(apparel == null || wears == null || wears.isEmpty()) {
			throw new PictureBookException(502, "No wearables could be built from " + items + " for the outfit \"" + desc + "\" of " + charName);
		}
		ApparelUtil.designApparel(apparel);
		if(!colors.isEmpty()) {
			res.colorsResolved = ApparelUtil.applyGuessedColors(octx, apparel, colors);
		}
		String stored = (NarrativeUtil.isMeaningful(llmDesc) ? desc + " - " + llmDesc.trim() : desc);
		if(stored.length() > MAX_DESCRIPTION_CHARS) {
			stored = stored.substring(0, MAX_DESCRIPTION_CHARS);
		}
		try {
			apparel.set(FieldNames.FIELD_NAME, alternateName(desc));
			apparel.set(FieldNames.FIELD_DESCRIPTION, stored);
			apparel.set(OlioFieldNames.FIELD_IN_USE, false);
			for(BaseRecord wr : wears) {
				wr.set(OlioFieldNames.FIELD_IN_USE, true);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to prepare the alternate outfit: " + e.getMessage());
		}
		if(!IOSystem.getActiveContext().getRecordUtil().createRecord(apparel)) {
			throw new PictureBookException(500, "Failed to persist the alternate outfit for " + charName);
		}
		/// AccessPoint.member: canRead(user, apparel) + canUpdate(user, store), audited - not the bare MemberUtil write
		if(!IOSystem.getActiveContext().getAccessPoint().member(user, store, OlioFieldNames.FIELD_APPAREL, apparel, null, true)) {
			throw new PictureBookException(500, "Alternate outfit #" + idOf(apparel) + " was created but could not be added to the store of " + charName);
		}
		if(appl != null) {
			appl.add(apparel);
		}
		res.apparel = apparel;
		res.wearables = wears.size();
		res.elapsedMs = System.currentTimeMillis() - start;
		logger.info("Created alternate outfit #" + idOf(apparel) + " (" + apparel.get(FieldNames.FIELD_NAME) + ") for " + charName
			+ " from " + items + " in " + res.elapsedMs + " ms");
		return res;
	}

	/**
	 * Make {@code apparelObjectId} the character's default: it gets {@code inuse = true}, every other default of the
	 * store {@code inuse = false}, as {@code user} through {@code AccessPoint.update}. Scenes whose actor for this
	 * character resolves to the default (not pinned, nothing carried) are marked stale - and only those.
	 *
	 * @throws PictureBookException 403 when the user may not update the book or the apparel; 400 when the apparel
	 *                              is not in the character's wardrobe; 500 when a flip did not persist
	 */
	public static SetDefaultResult setDefault(BaseRecord user, BaseRecord book, String characterObjectId, String apparelObjectId) {
		BaseRecord fresh = PbBookUtil.requireBookUpdate(user, book, "modify the wardrobe");
		BaseRecord cp = requireCharacterOfBook(user, fresh, characterObjectId);
		BaseRecord target = requireApparelOfCharacter(user, cp, apparelObjectId);
		BaseRecord store = storeOf(user, cp);
		long orgId = ((Number) cp.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long charId = idOf(cp);
		SetDefaultResult res = new SetDefaultResult();
		res.apparel = target;

		List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
		if(appl != null) {
			for(BaseRecord a : appl) {
				if(idOf(a) == idOf(target)) {
					continue;
				}
				BaseRecord af = readApparelById(user, idOf(a), orgId);
				if(af != null && Boolean.TRUE.equals(af.get(OlioFieldNames.FIELD_IN_USE))) {
					res.previous.add(af);
				}
			}
		}
		boolean alreadyDefault = Boolean.TRUE.equals(target.get(OlioFieldNames.FIELD_IN_USE));
		if(alreadyDefault && res.previous.isEmpty()) {
			return res;
		}

		/// Which scenes wear the default is decided before anything flips, from the chain as it stands.
		List<BaseRecord> defaultScenes = new ArrayList<>();
		for(SceneOutfits so : resolveOutfits(user, fresh)) {
			res.scenesScanned++;
			for(ResolvedOutfit ro : so.actors) {
				if(ro.character != null && idOf(ro.character) == charId && ro.source == OutfitSource.DEFAULT) {
					defaultScenes.add(so.scene);
					break;
				}
			}
		}

		requireApparelUpdate(user, target);
		for(BaseRecord p : res.previous) {
			requireApparelUpdate(user, p);
		}
		/// New default first, then the old ones: a failure midway leaves two defaults, which the lowest-id rule
		/// resolves deterministically and a retry repairs; never a character with none.
		if(!alreadyDefault) {
			flipInUse(user, target, true);
			res.changed = true;
		}
		for(BaseRecord p : res.previous) {
			flipInUse(user, p, false);
			res.changed = true;
		}
		PbStaleUtil.touch(OlioModelNames.MODEL_APPAREL, target.get(FieldNames.FIELD_OBJECT_ID));
		for(BaseRecord p : res.previous) {
			PbStaleUtil.touch(OlioModelNames.MODEL_APPAREL, p.get(FieldNames.FIELD_OBJECT_ID));
		}
		PbStaleUtil.touch(OlioModelNames.MODEL_CHAR_PERSON, cp.get(FieldNames.FIELD_OBJECT_ID));
		for(BaseRecord scene : defaultScenes) {
			if(PbBookUtil.markSceneNodeStale(user, scene)) {
				res.staled++;
			}
		}
		logger.info("Default outfit of " + cp.get(FieldNames.FIELD_NAME) + " is now #" + idOf(target) + " (" + res.previous.size()
			+ " previous cleared, " + defaultScenes.size() + " default-resolved scenes, " + res.staled + " marked stale)");
		return res;
	}

	private static void requireApparelUpdate(BaseRecord user, BaseRecord apparel) {
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, apparel);
		if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not permitted to update apparel " + apparel.get(FieldNames.FIELD_NAME)
				+ " - the default flip runs as the acting user, who needs the Olio user grant on the world's Apparel group");
		}
	}

	private static void requireStoreUpdate(BaseRecord user, BaseRecord store, String charName) {
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, store);
		if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not permitted to add to the wardrobe of " + charName
				+ " - the store attach runs as the acting user, who needs update on the character's store");
		}
	}

	private static void flipInUse(BaseRecord user, BaseRecord apparel, boolean inUse) {
		BaseRecord patch = apparel.copyRecord(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_IN_USE });
		try {
			patch.set(OlioFieldNames.FIELD_IN_USE, inUse);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to set inuse on apparel #" + idOf(apparel) + ": " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to set inuse=" + inUse + " on apparel #" + idOf(apparel));
		}
	}

	// ─────────────────────────────── pins ───────────────────────────────

	/**
	 * Pin ({@code apparelObjectId} set) or unpin (null) a scene actor's outfit. The apparel must belong to the
	 * actor's character's wardrobe - a pin to another character's outfit is refused (400). The pinned scene and
	 * every later scene that carried from it (before or after the change) are marked stale. A pin does not set
	 * {@code composition.userEdited}; a recompose preserves pins.
	 */
	public static PinResult pinActorApparel(BaseRecord user, BaseRecord book, String compositionObjectId, String actorObjectId, String apparelObjectId) {
		BaseRecord fresh = PbBookUtil.requireBookUpdate(user, book, "pin an outfit");
		long orgId = PbGraphUtil.orgId(fresh);
		BaseRecord comp = PbComposeUtil.readComposition(user, compositionObjectId, orgId);
		if(comp == null) {
			throw new PictureBookException(404, "Composition not found: " + compositionObjectId);
		}
		BaseRecord compBook = comp.get(OlioFieldNames.FIELD_PB_BOOK);
		if(compBook == null || idOf(compBook) != idOf(fresh)) {
			throw new PictureBookException(400, "Composition " + compositionObjectId + " does not belong to book " + fresh.get(OlioFieldNames.FIELD_PB_SLUG));
		}
		BaseRecord actor = readActor(user, actorObjectId, orgId);
		if(actor == null) {
			throw new PictureBookException(404, "Actor not found: " + actorObjectId);
		}
		BaseRecord actorComp = actor.get(OlioFieldNames.FIELD_PB_COMPOSITION);
		if(actorComp == null || idOf(actorComp) != idOf(comp)) {
			throw new PictureBookException(400, "Actor " + actorObjectId + " does not belong to composition " + compositionObjectId);
		}
		BaseRecord charRef = actor.get(OlioFieldNames.FIELD_PB_CHARACTER);
		if(charRef == null || idOf(charRef) <= 0L) {
			throw new PictureBookException(400, "Actor " + actor.get(FieldNames.FIELD_NAME) + " has no character; only cast actors wear a wardrobe");
		}
		BaseRecord cp = readCharacterById(user, idOf(charRef), orgId);
		if(cp == null) {
			throw new PictureBookException(404, "Character #" + idOf(charRef) + " of actor " + actorObjectId + " could not be read");
		}
		BaseRecord apparel = null;
		if(apparelObjectId != null && !apparelObjectId.trim().isEmpty()) {
			apparel = requireApparelOfCharacter(user, cp, apparelObjectId);
		}
		BaseRecord compScene = comp.get(OlioFieldNames.FIELD_PB_SCENE);
		long sceneId = (compScene != null ? idOf(compScene) : 0L);
		long charId = idOf(cp);

		Set<Long> affected = new LinkedHashSet<>();
		Map<Long, BaseRecord> scenesById = new HashMap<>();
		collectChainFrom(user, fresh, sceneId, charId, affected, scenesById);

		BaseRecord patch = PbGraphUtil.patchOf(actor, OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_APPAREL);
		try {
			patch.set(OlioFieldNames.FIELD_APPAREL, apparel);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to set apparel on actor " + actorObjectId + ": " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to " + (apparel != null ? "pin" : "unpin") + " the outfit of actor " + actorObjectId);
		}
		PbStaleUtil.touch(actor);
		collectChainFrom(user, fresh, sceneId, charId, affected, scenesById);

		PinResult res = new PinResult();
		res.actor = actor;
		res.apparel = apparel;
		res.pinned = (apparel != null);
		for(Long sid : affected) {
			BaseRecord scene = scenesById.get(sid);
			if(scene == null) {
				continue;
			}
			Integer idx = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			res.affectedSceneIndexes.add(idx != null ? idx : -1);
			if(PbBookUtil.markSceneNodeStale(user, scene)) {
				res.staled++;
			}
		}
		logger.info((apparel != null ? "Pinned outfit #" + idOf(apparel) : "Unpinned the outfit") + " on actor " + actor.get(FieldNames.FIELD_NAME)
			+ " of scene #" + sceneId + "; " + affected.size() + " scenes affected, " + res.staled + " marked stale");
		return res;
	}

	/// The scene itself plus every later scene whose outfit for charId is carried from it, as the chain stands now.
	private static void collectChainFrom(BaseRecord user, BaseRecord book, long sceneId, long charId, Set<Long> out, Map<Long, BaseRecord> scenesById) {
		if(sceneId <= 0L) {
			return;
		}
		Integer fromIdx = null;
		for(SceneOutfits so : resolveOutfits(user, book)) {
			long sid = idOf(so.scene);
			scenesById.put(sid, so.scene);
			Integer idx = so.scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			if(sid == sceneId) {
				out.add(sid);
				fromIdx = idx;
				continue;
			}
			if(fromIdx == null) {
				continue;
			}
			for(ResolvedOutfit ro : so.actors) {
				if(ro.character != null && idOf(ro.character) == charId && ro.source == OutfitSource.CARRIED && ro.fromSceneIndex == fromIdx.intValue()) {
					out.add(sid);
					break;
				}
			}
		}
	}

	// ─────────────────────────────── carry-forward ───────────────────────────────

	/**
	 * Every scene of the book in {@code sceneIndex} order with the resolved outfit of each cast actor: pinned,
	 * carried from the nearest earlier pin of the same character, the character's default, or none. Pure read;
	 * nothing is persisted. A scene with no SCENE-purpose composition yet is listed with no actors.
	 */
	public static List<SceneOutfits> resolveOutfits(BaseRecord user, BaseRecord book) {
		List<SceneOutfits> out = new ArrayList<>();
		if(user == null || book == null) {
			return out;
		}
		long orgId = PbGraphUtil.orgId(book);
		Map<Long, BaseRecord> comps = PbComposeUtil.listSceneCompositions(user, book);
		Resolver r = new Resolver(user, orgId);
		for(BaseRecord scene : PbBookUtil.listScenes(user, book)) {
			SceneOutfits so = new SceneOutfits();
			so.scene = scene;
			so.composition = comps.get(idOf(scene));
			Integer idx = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			if(so.composition != null) {
				for(BaseRecord actor : PbComposeUtil.listActors(user, so.composition)) {
					so.actors.add(r.resolve(actor, (idx != null ? idx.intValue() : 0)));
				}
			}
			out.add(so);
		}
		return out;
	}

	/**
	 * The resolved outfits of one composition's actors, in the context of the whole book's chain. A composition
	 * that is not a scene's SCENE-purpose one (a variant, a cover) has no chain position: its actors resolve to
	 * their own pin or the default only.
	 */
	public static List<ResolvedOutfit> resolveOutfits(BaseRecord user, BaseRecord book, BaseRecord composition) {
		if(composition == null) {
			return Collections.emptyList();
		}
		long compId = idOf(composition);
		for(SceneOutfits so : resolveOutfits(user, book)) {
			if(so.composition != null && idOf(so.composition) == compId) {
				return so.actors;
			}
		}
		Resolver r = new Resolver(user, PbGraphUtil.orgId(book));
		List<ResolvedOutfit> out = new ArrayList<>();
		for(BaseRecord actor : PbComposeUtil.listActors(user, composition)) {
			out.add(r.resolve(actor, -1));
		}
		return out;
	}

	/// One pass over a book's scenes in order; remembers the last pin per character and caches characters and defaults.
	private static final class Resolver {
		private final BaseRecord user;
		private final long orgId;
		private final Map<Long, BaseRecord> characters = new HashMap<>();
		private final Map<Long, BaseRecord> defaults = new HashMap<>();
		private final Map<Long, BaseRecord> apparelById = new HashMap<>();
		private final Map<Long, BaseRecord> lastPinned = new HashMap<>();
		private final Map<Long, Integer> lastPinnedIndex = new HashMap<>();

		Resolver(BaseRecord user, long orgId) {
			this.user = user;
			this.orgId = orgId;
		}

		ResolvedOutfit resolve(BaseRecord actor, int sceneIndex) {
			ResolvedOutfit ro = new ResolvedOutfit();
			ro.actor = actor;
			BaseRecord charRef = (actor.hasField(OlioFieldNames.FIELD_PB_CHARACTER) ? actor.get(OlioFieldNames.FIELD_PB_CHARACTER) : null);
			long charId = (charRef != null ? idOf(charRef) : 0L);
			if(charId <= 0L) {
				return ro;
			}
			ro.character = characters.computeIfAbsent(charId, id -> readCharacterById(user, id, orgId));
			if(ro.character == null) {
				ro.character = charRef;
				ro.characterUnread = true;
			}
			long pinnedId = pinnedApparelId(actor);
			if(pinnedId > 0L) {
				ro.source = OutfitSource.PINNED;
				ro.apparel = apparelById.computeIfAbsent(pinnedId, id -> readApparelById(user, id, orgId));
				if(ro.apparel == null) {
					ro.warning = "Pinned apparel #" + pinnedId + " could not be read; the pin is dangling";
				}
				else if(sceneIndex >= 0) {
					lastPinned.put(charId, ro.apparel);
					lastPinnedIndex.put(charId, sceneIndex);
				}
				return ro;
			}
			if(sceneIndex >= 0 && lastPinned.containsKey(charId)) {
				ro.source = OutfitSource.CARRIED;
				ro.apparel = lastPinned.get(charId);
				ro.fromSceneIndex = lastPinnedIndex.get(charId);
				return ro;
			}
			if(!defaults.containsKey(charId)) {
				defaults.put(charId, (ro.character != null && ro.character.hasField(FieldNames.FIELD_STORE) ? defaultApparel(user, ro.character) : null));
			}
			ro.apparel = defaults.get(charId);
			ro.source = (ro.apparel != null ? OutfitSource.DEFAULT : OutfitSource.NONE);
			return ro;
		}
	}

	/** The actor row's pinned apparel id, or 0 - a null FK reads back as a bigint 0, not null. */
	static long pinnedApparelId(BaseRecord actor) {
		if(actor == null || !actor.hasField(OlioFieldNames.FIELD_APPAREL)) {
			return 0L;
		}
		BaseRecord ref = actor.get(OlioFieldNames.FIELD_APPAREL);
		return (ref != null ? idOf(ref) : 0L);
	}

	// ─────────────────────────────── migration ───────────────────────────────

	/**
	 * Turn the legacy {@code sceneIndex} apparel tags of the book's characters into pins: an apparel tagged N
	 * becomes the pin of that character's actor in scene N's composition. Idempotent; never overwrites an existing
	 * pin; keeps the attribute (the render-time {@code selectSceneApparel} flip still reads it). In a chapter book
	 * only characters of the chapter's shadow group are migrated - a character of the shared series Population
	 * group has tags from several chapters' numbering and is counted {@code ambiguous}. The V3 plan path resolves
	 * outfits pin, carried, default and never reads the tag, so a pin changes the render of the tagged scene and
	 * of every later scene that carried from it: the resolved outfits are snapshotted before and after the pass
	 * and every scene whose outfits changed has its node marked STALE ({@code affected} / {@code staled}), as
	 * {@link #pinActorApparel} does for one pin.
	 */
	public static MigrationResult migrateSceneTags(BaseRecord user, BaseRecord book) {
		BaseRecord fresh = PbBookUtil.requireBookUpdate(user, book, "migrate wardrobe tags");
		long orgId = PbGraphUtil.orgId(fresh);
		BookContext bctx = PbLocationUtil.requireBookContext(user, fresh);
		MigrationResult res = new MigrationResult();

		BaseRecord series = (fresh.hasField(OlioFieldNames.FIELD_PB_SERIES) ? fresh.get(OlioFieldNames.FIELD_PB_SERIES) : null);
		boolean chapter = (series != null && idOf(series) > 0L);
		String slug = str(fresh.get(OlioFieldNames.FIELD_PB_SLUG));
		List<BaseRecord> characters = new ArrayList<>();
		if(chapter) {
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			String shadowPath = PbBookUtil.chapterShadowCharGroupPath(popPath, slug);
			BaseRecord shadow = (shadowPath != null ? IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, shadowPath,
				GroupEnumType.DATA.toString(), orgId) : null);
			if(shadow != null) {
				characters.addAll(listCharacters(user, idOf(shadow), orgId));
			}
			BaseRecord pop = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			if(pop != null) {
				int shared = listCharacters(user, idOf(pop), orgId).size();
				if(shared > 0) {
					res.ambiguous += shared;
					res.warnings.add(shared + " characters of the shared series population were not migrated: their tags span several chapters' scene numbering");
				}
			}
		}
		else {
			BaseRecord pop = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			if(pop != null) {
				characters.addAll(listCharacters(user, idOf(pop), orgId));
			}
		}

		Map<Integer, BaseRecord> scenesByIndex = new HashMap<>();
		for(BaseRecord scene : PbBookUtil.listScenes(user, fresh)) {
			Integer idx = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			if(idx != null) {
				scenesByIndex.putIfAbsent(idx, scene);
			}
		}
		Map<Long, BaseRecord> comps = PbComposeUtil.listSceneCompositions(user, fresh);
		Map<Long, List<BaseRecord>> actorsByComp = new HashMap<>();
		Map<Long, BaseRecord> scenesById = new HashMap<>();
		Map<Long, Map<Long, Long>> before = outfitSnapshot(user, fresh, scenesById);

		for(BaseRecord cp : characters) {
			BaseRecord store = storeOf(user, cp);
			List<BaseRecord> appl = (store != null ? store.get(OlioFieldNames.FIELD_APPAREL) : null);
			if(appl == null || appl.isEmpty()) {
				continue;
			}
			long charId = idOf(cp);
			for(BaseRecord a : appl) {
				BaseRecord af = readApparelById(user, idOf(a), orgId);
				Integer tag = (af != null ? sceneIndexOf(af) : null);
				if(tag == null) {
					continue;
				}
				res.scanned++;
				BaseRecord scene = scenesByIndex.get(tag);
				if(scene == null) {
					res.skippedNoScene++;
					continue;
				}
				BaseRecord comp = comps.get(idOf(scene));
				if(comp == null) {
					res.skippedNoComposition++;
					continue;
				}
				List<BaseRecord> actors = actorsByComp.computeIfAbsent(idOf(comp), id -> PbComposeUtil.listActors(user, comp));
				BaseRecord actor = null;
				for(BaseRecord row : actors) {
					BaseRecord cref = row.get(OlioFieldNames.FIELD_PB_CHARACTER);
					if(cref != null && idOf(cref) == charId) {
						actor = row;
						break;
					}
				}
				if(actor == null) {
					res.skippedNoActor++;
					continue;
				}
				if(pinnedApparelId(actor) > 0L) {
					res.skippedPinned++;
					continue;
				}
				BaseRecord patch = PbGraphUtil.patchOf(actor, OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_APPAREL);
				try {
					patch.set(OlioFieldNames.FIELD_APPAREL, af);
				}
				catch(FieldException | ValueException | ModelNotFoundException e) {
					throw new PictureBookException(500, "Failed to set apparel on actor " + actor.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
				}
				if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
					res.warnings.add("Could not pin apparel #" + idOf(af) + " on " + cp.get(FieldNames.FIELD_NAME) + " in scene " + tag);
					continue;
				}
				try {
					actor.set(OlioFieldNames.FIELD_APPAREL, af);
				}
				catch(FieldException | ValueException | ModelNotFoundException e) {
					logger.warn("Could not mirror the pin onto the in-memory actor row: " + e.getMessage());
				}
				res.pinned++;
			}
		}
		if(res.pinned > 0) {
			Map<Long, Map<Long, Long>> after = outfitSnapshot(user, fresh, scenesById);
			for(Map.Entry<Long, Map<Long, Long>> e : after.entrySet()) {
				if(e.getValue().equals(before.getOrDefault(e.getKey(), Collections.emptyMap()))) {
					continue;
				}
				res.affected++;
				BaseRecord scene = scenesById.get(e.getKey());
				if(scene != null && PbBookUtil.markSceneNodeStale(user, scene)) {
					res.staled++;
				}
			}
		}
		logger.info("Migrated wardrobe tags of book " + slug + ": " + res.scanned + " tags, " + res.pinned + " pinned, " + res.skippedPinned
			+ " already pinned, " + res.skippedNoActor + " no actor, " + res.skippedNoScene + " no scene, " + res.skippedNoComposition
			+ " no composition, " + res.ambiguous + " ambiguous; " + res.affected + " scenes' outfits changed, " + res.staled + " marked stale");
		return res;
	}

	/// sceneId -> (characterId -> resolved apparel id, 0 for none) as the chain stands now; fills scenesById.
	private static Map<Long, Map<Long, Long>> outfitSnapshot(BaseRecord user, BaseRecord book, Map<Long, BaseRecord> scenesById) {
		Map<Long, Map<Long, Long>> snap = new HashMap<>();
		for(SceneOutfits so : resolveOutfits(user, book)) {
			long sid = idOf(so.scene);
			scenesById.put(sid, so.scene);
			Map<Long, Long> byChar = new HashMap<>();
			for(ResolvedOutfit ro : so.actors) {
				if(ro.character == null) {
					continue;
				}
				byChar.put(Long.valueOf(idOf(ro.character)), Long.valueOf(ro.apparel != null ? idOf(ro.apparel) : 0L));
			}
			snap.put(Long.valueOf(sid), byChar);
		}
		return snap;
	}

	// ─────────────────────────────── reference image ───────────────────────────────

	/**
	 * Render the apparel's mannequin reference images - one per cumulative wear level, the
	 * {@code SDUtil.generateMannequinImages} contract - into {@code ~/Gallery/Apparel/<apparel name>} of
	 * {@code user} and link each into {@code apparel.gallery}. The SD config is the book's effective config with
	 * the PORTRAIT model policy applied, so the reference matches the book's style; {@code hires} is off. The last
	 * (outermost) image is reported as the reference. Synchronous; callers run it inside a job.
	 *
	 * @throws PictureBookException 409 when the book's style chain is not complete; 422 when the apparel has no
	 *                              wearables with wear levels; 502 when the SD call failed
	 */
	public static ReferenceResult referenceImage(BaseRecord user, BaseRecord book, String characterObjectId, String apparelObjectId, SDUtil sdu) {
		long start = System.currentTimeMillis();
		if(sdu == null) {
			throw new PictureBookException(500, "No SD client");
		}
		BaseRecord fresh = PbBookUtil.requireBookUpdate(user, book, "render a wardrobe reference");
		BaseRecord cp = requireCharacterOfBook(user, fresh, characterObjectId);
		BaseRecord apparel = requireApparelOfCharacter(user, cp, apparelObjectId);
		long orgId = PbGraphUtil.orgId(fresh);

		PbStyleResolver.EffectiveStyle style = PbStyleResolver.resolve(user, fresh);
		if(style != null && !style.isComplete()) {
			throw new PictureBookException(409, "Style chain of book " + fresh.get(OlioFieldNames.FIELD_PB_SLUG)
				+ (style.cycleDetected() ? " cycles" : " is not readable at " + style.truncatedModel() + " " + style.truncatedAt())
				+ "; a wardrobe reference cannot render against a partial style");
		}
		BaseRecord cfg = PbConfigUtil.resolveEffectiveConfig(fresh, null, false, null, style);
		PbModelPolicy.applyForStyle(style, PbConfigUtil.styleTierConfig(fresh, false, style), PbModelTaskEnumType.PORTRAIT, cfg, null,
			sdu.getApiType(), sdu.getServer(), false);
		try {
			cfg.set(OlioFieldNames.FIELD_HIRES, false);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to prepare the reference config: " + e.getMessage());
		}

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, apparel.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		q.setCache(false);
		BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(full == null) {
			throw new PictureBookException(404, "Apparel not readable: " + apparelObjectId);
		}
		List<BaseRecord> wears = full.get(OlioFieldNames.FIELD_WEARABLES);
		if(wears == null || wears.isEmpty()) {
			throw new PictureBookException(422, "Apparel " + full.get(FieldNames.FIELD_NAME) + " has no wearables");
		}
		String groupPath = GALLERY_PATH_PREFIX + pathSegment(str(full.get(FieldNames.FIELD_NAME)), idOf(full));
		sdu.setImageAccessUser(user);
		List<BaseRecord> images = null;
		try {
			images = sdu.generateMannequinImages(user, groupPath, full, cfg, false, -1L);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Invalid reference config: " + e.getMessage(), e);
		}
		if(images == null || images.isEmpty()) {
			throw new PictureBookException(422, "Apparel " + full.get(FieldNames.FIELD_NAME) + " has no wearables with wear levels; nothing to render");
		}
		ReferenceResult res = new ReferenceResult();
		res.apparel = apparel;
		res.images.addAll(images);
		res.referenceObjectId = str(images.get(images.size() - 1).get(FieldNames.FIELD_OBJECT_ID));
		res.groupPath = groupPath;
		res.elapsedMs = System.currentTimeMillis() - start;
		PbStaleUtil.touch(OlioModelNames.MODEL_APPAREL, apparel.get(FieldNames.FIELD_OBJECT_ID));
		logger.info("Rendered " + images.size() + " reference images for apparel #" + idOf(apparel) + " of " + cp.get(FieldNames.FIELD_NAME)
			+ " into " + groupPath + " in " + res.elapsedMs + " ms");
		return res;
	}

	// ─────────────────────────────── Olio context ───────────────────────────────

	/**
	 * The {@link OlioContext} of the book's world: the cached one by world objectId when a session already opened
	 * it, else opened by name through {@link PbOlioContextUtil} - the series context for a chapter (its world is
	 * the series world), the book context otherwise. Needed because {@link ApparelUtil} instantiates apparel into
	 * the context's world groups as the context's olio user.
	 * <p>
	 * The cached branch skips the {@code isEntitledToBook} check that {@code getCreateBookContext} performs when it
	 * opens cold, so every caller must already stand behind {@code PbBookUtil.requireBookUpdate} - this is a write-path
	 * helper and must not be reached from a read-only entry point.
	 *
	 * @throws OlioException when the book has no world, {@code dataPath} is null, or the context cannot be opened
	 */
	static OlioContext resolveOlioContext(BaseRecord user, BaseRecord book, BookContext bctx, String dataPath) throws OlioException {
		if(bctx == null || bctx.getWorld() == null) {
			throw new OlioException("Book has no world");
		}
		BaseRecord world = bctx.getWorld();
		long orgId = bctx.getOrganizationId();
		OlioContext cached = OlioContextUtil.findCachedByWorldObjectId(orgId, world.get(FieldNames.FIELD_OBJECT_ID));
		if(cached != null) {
			return cached;
		}
		if(dataPath == null || dataPath.trim().isEmpty()) {
			throw new OlioException("No datagen.path to open the world of book " + book.get(OlioFieldNames.FIELD_PB_SLUG));
		}
		String worldName = str(world.get(FieldNames.FIELD_NAME));
		if(worldName == null) {
			throw new OlioException("World of book " + book.get(OlioFieldNames.FIELD_PB_SLUG) + " has no name");
		}
		BaseRecord series = (book.hasField(OlioFieldNames.FIELD_PB_SERIES) ? book.get(OlioFieldNames.FIELD_PB_SERIES) : null);
		boolean chapter = (series != null && idOf(series) > 0L);
		OlioContext octx = (chapter ? PbOlioContextUtil.getCreateSeriesContext(user, dataPath, worldName)
			: PbOlioContextUtil.getCreateBookContext(user, dataPath, worldName));
		if(octx == null || octx.getWorld() == null) {
			throw new OlioException("Could not open the world " + worldName);
		}
		return octx;
	}

	// ─────────────────────────────── reads ───────────────────────────────

	private static BaseRecord readCharacter(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(CHARACTER_REQUEST);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	static BaseRecord readCharacterById(BaseRecord user, long id, long orgId) {
		if(id <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(CHARACTER_REQUEST);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static List<BaseRecord> listCharacters(BaseRecord user, long groupId, long orgId) {
		if(groupId <= 0L) {
			return Collections.emptyList();
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(CHARACTER_REQUEST);
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		return (recs != null ? PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_CHAR_PERSON) : Collections.emptyList());
	}

	/// The character's store with its apparel (and their wearables) populated - the selectSceneApparel read shape.
	static BaseRecord storeOf(BaseRecord user, BaseRecord character) {
		if(character == null || !character.hasField(FieldNames.FIELD_STORE)) {
			return null;
		}
		BaseRecord storeRef = character.get(FieldNames.FIELD_STORE);
		long storeId = (storeRef != null ? idOf(storeRef) : 0L);
		if(storeId <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_STORE, FieldNames.FIELD_ID, storeId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, character.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setCache(false);
		q.planMost(true);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static BaseRecord readApparelById(BaseRecord user, long id, long orgId) {
		if(id <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(APPAREL_REQUEST);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static BaseRecord readApparelByObjectId(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(APPAREL_REQUEST);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * The outfit text of one apparel for a prompt (P4-7): {@code "wearing <a>, <b>, and <c>"} over its in-use
	 * wearables ordered as {@link ApparelUtil#getWearing} orders them (outermost level first), each described by
	 * {@link NarrativeUtil#describeWearable}; the apparel's description when it has no in-use wearables; null when
	 * neither is meaningful. Read fresh ({@code planMost}, no cache) so a just-edited wardrobe is what renders.
	 */
	public static String describeApparel(BaseRecord user, BaseRecord apparel, long orgId) {
		if(apparel == null) {
			return null;
		}
		long id = idOf(apparel);
		if(id <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		q.setCache(false);
		BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(full == null) {
			return null;
		}
		List<BaseRecord> wears = new ArrayList<>();
		if(full.hasField(OlioFieldNames.FIELD_WEARABLES)) {
			List<BaseRecord> all = full.get(OlioFieldNames.FIELD_WEARABLES);
			if(all != null) {
				for(BaseRecord w : all) {
					Boolean inUse = (w.hasField(OlioFieldNames.FIELD_IN_USE) ? w.get(OlioFieldNames.FIELD_IN_USE) : null);
					if(inUse != null && inUse.booleanValue() && w.hasField(OlioFieldNames.FIELD_LEVEL) && w.get(OlioFieldNames.FIELD_LEVEL) != null) {
						wears.add(w);
					}
				}
			}
		}
		if(wears.isEmpty()) {
			String desc = (full.hasField(FieldNames.FIELD_DESCRIPTION) ? full.get(FieldNames.FIELD_DESCRIPTION) : null);
			return (NarrativeUtil.isMeaningful(desc) ? desc.trim() : null);
		}
		wears.sort((f1, f2) -> WearLevelEnumType.compareTo(f1.getEnum(OlioFieldNames.FIELD_LEVEL), f2.getEnum(OlioFieldNames.FIELD_LEVEL)));
		Collections.reverse(wears);
		StringBuilder buff = new StringBuilder("wearing");
		String andl = " ";
		for(int i = 0; i < wears.size(); i++) {
			String item = NarrativeUtil.describeWearable(wears.get(i));
			if(!NarrativeUtil.isMeaningful(item)) {
				continue;
			}
			buff.append(andl).append(item.trim());
			andl = "," + (i == wears.size() - 2 ? " and " : " ");
		}
		String text = buff.toString();
		return (text.equals("wearing") ? null : text);
	}

	private static BaseRecord readActor(BaseRecord user, String objectId, long orgId) {
		if(objectId == null || objectId.trim().isEmpty()) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE_ACTOR, FieldNames.FIELD_OBJECT_ID, objectId.trim());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbComposeUtil.actorRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// The apparel's gallery entries as the user reads them (identity and common fields; no bytes).
	private static List<BaseRecord> galleryOf(BaseRecord user, BaseRecord apparel) {
		List<BaseRecord> out = new ArrayList<>();
		try {
			List<BaseRecord> parts = IOSystem.getActiveContext().getMemberUtil().findMembers(apparel, OlioFieldNames.FIELD_GALLERY, ModelNames.MODEL_DATA, 0L);
			for(BaseRecord p : parts) {
				Long pid = p.get(FieldNames.FIELD_PARTICIPANT_ID);
				if(pid == null || pid.longValue() <= 0L) {
					continue;
				}
				BaseRecord d = IOSystem.getActiveContext().getAccessPoint().findById(user, ModelNames.MODEL_DATA, pid.longValue());
				if(d != null) {
					out.add(d);
				}
			}
		}
		catch(Exception e) {
			logger.warn("Could not read the gallery of apparel #" + idOf(apparel) + ": " + e.getMessage());
		}
		return out;
	}

	private static Integer sceneIndexOf(BaseRecord apparel) {
		try {
			Object v = AttributeUtil.getAttributeValue(apparel, ATTR_SCENE_INDEX, null);
			if(v instanceof Number) {
				return ((Number) v).intValue();
			}
			if(v instanceof String && !((String) v).isBlank()) {
				return Integer.parseInt(((String) v).trim());
			}
		}
		catch(Exception e) {
			logger.warn("Could not read " + ATTR_SCENE_INDEX + " of apparel #" + idOf(apparel) + ": " + e.getMessage());
		}
		return null;
	}

	private static boolean inStore(BaseRecord store, long apparelId) {
		List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
		if(appl == null || apparelId <= 0L) {
			return false;
		}
		for(BaseRecord a : appl) {
			if(idOf(a) == apparelId) {
				return true;
			}
		}
		return false;
	}

	// ─────────────────────────────── small helpers ───────────────────────────────

	/**
	 * Trimmed, whitespace-collapsed, capped request text with braces turned into parentheses (a {@code {word}} in
	 * user text would otherwise trip the unsubstituted-placeholder guard once it lands in the outfit prompt); null
	 * when nothing meaningful remains.
	 */
	static String normalizeRequest(String description) {
		if(!NarrativeUtil.isMeaningful(description)) {
			return null;
		}
		String d = PbLocationUtil.sanitizeForPrompt(description, MAX_DESCRIPTION_CHARS);
		return (d.isEmpty() ? null : d);
	}

	/** Lower-cased, whitespace-collapsed, trailing punctuation stripped; the first segment before " - " of a stored description. */
	static String dedupeKey(String description) {
		if(description == null) {
			return "";
		}
		String d = description;
		int cut = d.indexOf(" - ");
		if(cut > 0) {
			d = d.substring(0, cut);
		}
		d = d.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
		while(!d.isEmpty() && ".,;:!?".indexOf(d.charAt(d.length() - 1)) >= 0) {
			d = d.substring(0, d.length() - 1).trim();
		}
		return d;
	}

	/** A readable, unique apparel name: the sanitised description plus an 8-character suffix. */
	static String alternateName(String description) {
		String base = (description != null ? description : "outfit").replaceAll("[^A-Za-z0-9 _-]", " ").trim().replaceAll("\\s+", " ");
		int max = MAX_NAME_CHARS - 9;
		if(base.length() > max) {
			base = base.substring(0, max).trim();
		}
		if(base.isEmpty()) {
			base = "outfit";
		}
		return base + " " + UUID.randomUUID().toString().substring(0, 8);
	}

	/** One gallery group segment: legacy apparel names are not sanitised on creation, so a '/' must not nest groups. */
	static String pathSegment(String name, long id) {
		String seg = (name != null ? name : "").replaceAll("[^A-Za-z0-9 _.-]", " ").trim().replaceAll("\\s+", " ");
		if(seg.length() > MAX_NAME_CHARS) {
			seg = seg.substring(0, MAX_NAME_CHARS).trim();
		}
		return (seg.isEmpty() ? "apparel " + id : seg);
	}

	private static List<String> meaningfulStrings(Object v) {
		List<String> out = new ArrayList<>();
		if(v instanceof List) {
			for(Object o : (List<?>) v) {
				String s = llmItem(str(o));
				if(NarrativeUtil.isMeaningful(s) && !out.contains(s)) {
					out.add(s);
				}
			}
		}
		else if(v instanceof String) {
			for(String s : ((String) v).split(",")) {
				String c = llmItem(s);
				if(NarrativeUtil.isMeaningful(c) && !out.contains(c)) {
					out.add(c);
				}
			}
		}
		return out;
	}

	/// LLM item strings are echoed into error messages and the REST DTO; control characters and run-on whitespace
	/// would break the hand-built JSON error body, so they are normalised and capped here, at the source.
	static String llmItem(String s) {
		if(s == null) {
			return null;
		}
		String c = s.replaceAll("\\p{Cntrl}+", " ").trim().replaceAll("\\s+", " ");
		if(c.length() > MAX_NAME_CHARS) {
			c = c.substring(0, MAX_NAME_CHARS).trim();
		}
		return (c.isEmpty() ? null : c);
	}

	static String echo(List<String> items) {
		return (items.size() <= MAX_ECHO_ITEMS ? items.toString() : items.subList(0, MAX_ECHO_ITEMS) + " (+" + (items.size() - MAX_ECHO_ITEMS) + " more)");
	}

	/**
	 * Map LLM item names onto the catalog {@code ApparelUtil.getEmbeddedOutfit} matches verbatim: an exact
	 * (case-insensitive) catalog name wins, else the longest catalog name contained in the item as whole words
	 * ("dark boots" is "boots", "trench coat" stays "trench coat", "travelling cloak" matches nothing). Ordered,
	 * deduped; names nothing matched are appended to {@code unmatched}.
	 */
	static List<String> catalogItems(List<String> raw, List<String> catalog, List<String> unmatched) {
		Set<String> out = new LinkedHashSet<>();
		for(String item : raw) {
			String match = catalogMatch(item, catalog);
			if(match != null) {
				out.add(match);
			}
			else {
				unmatched.add(item);
			}
		}
		return new ArrayList<>(out);
	}

	private static String catalogMatch(String item, List<String> catalog) {
		List<String> itemWords = words(item);
		if(itemWords.isEmpty()) {
			return null;
		}
		String best = null;
		int bestWords = 0;
		for(String name : catalog) {
			List<String> nameWords = words(name);
			if(nameWords.isEmpty()) {
				continue;
			}
			if(nameWords.equals(itemWords)) {
				return name;
			}
			if(nameWords.size() > bestWords && Collections.indexOfSubList(itemWords, nameWords) >= 0) {
				best = name;
				bestWords = nameWords.size();
			}
		}
		return best;
	}

	private static List<String> words(String s) {
		List<String> out = new ArrayList<>();
		if(s == null) {
			return out;
		}
		for(String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
			if(!w.isEmpty()) {
				out.add(w);
			}
		}
		return out;
	}

	static long idOf(BaseRecord rec) {
		if(rec == null || !rec.hasField(FieldNames.FIELD_ID)) {
			return 0L;
		}
		Object v = rec.get(FieldNames.FIELD_ID);
		return (v instanceof Number ? ((Number) v).longValue() : 0L);
	}

	private static String str(Object v) {
		if(v == null) {
			return null;
		}
		String s = v.toString().trim();
		return (s.isEmpty() ? null : s);
	}

	/// Only the physical description is sent to the outfit guess so the character's own clothing cannot outrank the request;
	/// the full description (attribute, else physical + outfit) is the fallback when the narrative carries none. Both are
	/// user- or LLM-authored, so they go through the same brace-to-parenthesis sanitizer as the request (capped at the
	/// description2K width).
	/// Package-private for the test: the guess-apparel vars are not otherwise observable.
	static String physicalAppearance(BaseRecord character) {
		return PbLocationUtil.sanitizeForPrompt(rawPhysicalAppearance(character), MAX_APPEARANCE_CHARS);
	}

	private static String rawPhysicalAppearance(BaseRecord character) {
		BaseRecord narrative = (character.hasField(OlioFieldNames.FIELD_NARRATIVE) ? character.get(OlioFieldNames.FIELD_NARRATIVE) : null);
		if(narrative != null) {
			try {
				IOSystem.getActiveContext().getReader().populate(narrative, new String[] { "physicalDescription" });
			}
			catch(Exception e) {
				logger.warn("Could not populate the narrative of " + character.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			}
			String physical = (narrative.hasField("physicalDescription") ? str(narrative.get("physicalDescription")) : null);
			if(NarrativeUtil.isMeaningful(physical)) {
				return physical;
			}
		}
		return PbRenderPlanUtil.actorDescription(character);
	}
}
