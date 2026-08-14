package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

/**
 * Random-selection decks (traits, patterns, colors, names, occupations) drawn from a world's corpora
 * groups.
 * <p>
 * The decks used to be seven process-global static arrays keyed to nothing, so a second world in the
 * same JVM silently served the first world's corpora. They are now held per-world in a bounded
 * {@link ConcurrentHashMap} keyed on the world's {@code objectId} - the world every caller already
 * passes. The cache is bounded at {@link #MAX_DECK_SETS} entries with approximate-LRU eviction
 * (evictions are logged).
 * <p>
 * NOTE for callers: the world passed in must be the corpus-bearing world (the universe / basis
 * world), not a derived world whose corpora groups are empty - an empty corpus caches an empty deck
 * under that key.
 */
public class Decks {
	public static final Logger logger = LogManager.getLogger(Decks.class);
	private static SecureRandom rand = new SecureRandom();
	private static int traitDeckSize = 100;
	private static int namesDeckCount = 500;
	private static boolean useSimpleColors = true;

	private static int patternDeckSize = 100;
	private static int colorDeckSize = 100;

	/** Maximum number of per-world deck sets retained before approximate-LRU eviction kicks in. */
	private static final int MAX_DECK_SETS = 16;

	/** Monotonic access counter used to approximate LRU ordering without a synchronized access list. */
	private static final AtomicLong ACCESS_TICK = new AtomicLong(0L);

	/** worldObjectId -&gt; deck set. */
	private static final Map<String, DeckSet> DECKS = new ConcurrentHashMap<>();

	/**
	 * The seven decks, scoped to one world. Fields are volatile because a deck may be reshuffled on
	 * one thread while another reads it; each field is replaced wholesale (never mutated in place),
	 * so a reader always sees a complete array, either the old one or the new one.
	 */
	private static final class DeckSet {
		private volatile BaseRecord[] traitDeck = new BaseRecord[0];
		private volatile BaseRecord[] patternDeck = new BaseRecord[0];
		private volatile BaseRecord[] colorDeck = new BaseRecord[0];
		private volatile String[] maleNamesDeck = new String[0];
		private volatile String[] femaleNamesDeck = new String[0];
		private volatile String[] surnameNamesDeck = new String[0];
		private volatile String[] occupationsDeck = new String[0];
		private volatile long lastAccess = 0L;
	}

	/**
	 * Derive the cache key for a world. Prefers {@code objectId}, falls back to the numeric id.
	 * <p>
	 * An unidentifiable world returns <b>null</b>, i.e. "not cacheable". It deliberately does NOT get
	 * a shared {@code "__unkeyed__"} bucket: that bucket is exactly the cross-world leak this class
	 * was rewritten to remove, only reached by a different route - two different unidentified worlds
	 * would serve each other's corpora. Null degrades to "no memoization", which is slow and correct
	 * rather than fast and wrong.
	 */
	private static String deckKey(BaseRecord world) {
		if(world == null) {
			logger.error("A world is required to resolve a deck set");
			return null;
		}
		String objectId = world.get(FieldNames.FIELD_OBJECT_ID);
		if(objectId != null && objectId.length() > 0) {
			return objectId;
		}
		long id = world.get(FieldNames.FIELD_ID);
		if(id > 0L) {
			return idKey(id);
		}
		logger.warn("World has neither objectId nor id - its decks will not be cached");
		return null;
	}

	/** Key form used when a world carries no objectId. Kept in one place so {@link #clear} matches it. */
	private static String idKey(long id) {
		return "id:" + id;
	}

	/**
	 * Resolve (creating if needed) the deck set for a world. When the world cannot be keyed, an
	 * uncached transient set is returned so behaviour degrades to "no memoization" rather than to a
	 * cross-world leak.
	 */
	private static DeckSet decks(BaseRecord world) {
		String key = deckKey(world);
		if(key == null) {
			return new DeckSet();
		}
		DeckSet ds = DECKS.get(key);
		if(ds == null) {
			evictIfNeeded();
			ds = DECKS.computeIfAbsent(key, k -> new DeckSet());
		}
		ds.lastAccess = ACCESS_TICK.incrementAndGet();
		return ds;
	}

	/** Approximate LRU: drop the least-recently-accessed sets until there is room for one more. */
	private static void evictIfNeeded() {
		synchronized(DECKS) {
			while(DECKS.size() >= MAX_DECK_SETS) {
				String oldestKey = null;
				long oldest = Long.MAX_VALUE;
				for(Map.Entry<String, DeckSet> e : DECKS.entrySet()) {
					if(e.getValue().lastAccess < oldest) {
						oldest = e.getValue().lastAccess;
						oldestKey = e.getKey();
					}
				}
				if(oldestKey == null) {
					break;
				}
				DECKS.remove(oldestKey);
				logger.info("Evicted deck set for world " + oldestKey + " (cache bound " + MAX_DECK_SETS + " reached)");
			}
		}
	}

	/**
	 * Drop the cached decks for one world, by {@code objectId}.
	 * <p>
	 * A world with no {@code objectId} is keyed as {@code "id:<numericId>"} by {@link #deckKey}, which
	 * this string form cannot reconstruct; it therefore also tries the {@code "id:"} form in case the
	 * caller passed a numeric id as a string. When you hold the record itself, prefer
	 * {@link #clear(BaseRecord)}, which uses the same key derivation as the cache and so always
	 * matches.
	 */
	public static void clear(String worldObjectId) {
		if(worldObjectId == null) {
			return;
		}
		boolean removed = (DECKS.remove(worldObjectId) != null);
		/// Covers a caller that passed the numeric id rather than the objectId.
		removed = (DECKS.remove("id:" + worldObjectId) != null) || removed;
		if(removed) {
			logger.info("Cleared deck set for world " + worldObjectId);
		}
	}

	/**
	 * Drop the cached decks for one world, using the record's own key derivation. This is the form
	 * that always matches what {@link #decks(BaseRecord)} stored.
	 */
	public static void clear(BaseRecord world) {
		String key = deckKey(world);
		if(key == null) {
			return;
		}
		if(DECKS.remove(key) != null) {
			logger.info("Cleared deck set for world " + key);
		}
	}

	/**
	 * Drop every cached deck set.
	 * <p>
	 * WARNING: this is NOT a free memoization drop. {@code patternDeck}, {@code colorDeck} and
	 * {@code traitDeck} self-refill when found empty, but the four name decks
	 * ({@code maleNamesDeck}, {@code femaleNamesDeck}, {@code surnameNamesDeck},
	 * {@code occupationsDeck}) have no lazy-refill guard - they are only repopulated by an explicit
	 * {@link #shuffleDecks(BaseRecord, BaseRecord)}. Emptying them mid-run yields
	 * {@code rand.nextInt(0)}, not a slow rebuild. Keep this behind an admin-only evict path; do not
	 * hang it off a general cache-clear endpoint.
	 */
	public static void clearAll() {
		int n = DECKS.size();
		DECKS.clear();
		if(n > 0) {
			logger.info("Cleared " + n + " deck set(s)");
		}
	}

	public static boolean isUseSimpleColors() {
		return useSimpleColors;
	}

	public static void setUseSimpleColors(boolean useSimpleColors) {
		Decks.useSimpleColors = useSimpleColors;
	}

	/** Male given-name deck for a world. Empty until {@link #shuffleDecks} has run for that world. */
	public static String[] getMaleNamesDeck(BaseRecord world) {
		return decks(world).maleNamesDeck;
	}

	/** Female given-name deck for a world. Empty until {@link #shuffleDecks} has run for that world. */
	public static String[] getFemaleNamesDeck(BaseRecord world) {
		return decks(world).femaleNamesDeck;
	}

	/** Surname deck for a world. Empty until {@link #shuffleDecks} has run for that world. */
	public static String[] getSurnameNamesDeck(BaseRecord world) {
		return decks(world).surnameNamesDeck;
	}

	/** Occupation deck for a world. Empty until {@link #shuffleDecks} has run for that world. */
	public static String[] getOccupationsDeck(BaseRecord world) {
		return decks(world).occupationsDeck;
	}

	public static void shuffleApparelDeck(BaseRecord user, BaseRecord world) {
		shufflePatternDeck(user, world);
		shuffleColorDeck(user, world);
	}

	private static void shufflePatternDeck(BaseRecord user, BaseRecord world) {
		long patternDir = world.get(OlioFieldNames.FIELD_PATTERNS_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, patternDir);
		q.setRequest(new String[]{FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_DESCRIPTION});
		decks(world).patternDeck = OlioUtil.randomSelections(user, q, patternDeckSize);
	}

	public static BaseRecord getRandomPattern(BaseRecord user, BaseRecord world) {
		DeckSet ds = decks(world);
		if(ds.patternDeck.length == 0) {
			shufflePatternDeck(user, world);
			/// re-resolve in case the set was evicted between the probe and the shuffle
			ds = decks(world);
		}
		BaseRecord pattern = null;
		BaseRecord[] deck = ds.patternDeck;
		if(deck.length > 0) {
			pattern = deck[rand.nextInt(deck.length)];
		}
		return pattern;
	}
	public static BaseRecord getRandomColor(BaseRecord user, BaseRecord world) {
		DeckSet ds = decks(world);
		if(ds.colorDeck.length == 0) {
			shuffleColorDeck(user, world);
			/// re-resolve in case the set was evicted between the probe and the shuffle
			ds = decks(world);
		}
		BaseRecord color = null;
		BaseRecord[] deck = ds.colorDeck;
		if(deck.length > 0) {
			color = deck[rand.nextInt(deck.length)];
		}
		return color;
	}
	private static void shuffleColorDeck(BaseRecord user, BaseRecord world) {
		BaseRecord[] cols = getRandomColors(user, world, colorDeckSize);
		if(useSimpleColors) {
			List<BaseRecord> colors = Arrays.asList(cols);
			decks(world).colorDeck = colors.stream().filter(s -> s != null && s.get(FieldNames.FIELD_NAME) != null && !((String)s.get(FieldNames.FIELD_NAME)).matches("\\s+")).collect(Collectors.toList()).toArray(new BaseRecord[0]);
		}
		else {
			decks(world).colorDeck = cols;
		}
	}

	protected static BaseRecord getRandomColors(BaseRecord user, BaseRecord world) {
		BaseRecord[] outVal = getRandomColors(user, world, 1);
		if(outVal.length > 0) {
			return outVal[0];
		}

		return null;
	}
	protected static BaseRecord[] getRandomColors(BaseRecord user, BaseRecord world, int count) {
		BaseRecord[] outVal = new BaseRecord[0];
		long groupId = world.get(OlioFieldNames.FIELD_COLORS_ID);
		if(groupId <= 0L) {
			logger.warn("Invalid group id: " + groupId);
			return outVal;
		}
		return OlioUtil.randomSelections(user, QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_GROUP_ID, groupId), count);
	}

	private static void shuffleOccupationsDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query tnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_OCCUPATIONS_ID));
		tnq.set(FieldNames.FIELD_CACHE, false);
		decks(world).occupationsDeck = OlioUtil.randomSelectionNames(user, tnq, count);
	}

	private static void shuffleMaleNamesDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query mnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_NAMES_ID));
		mnq.field(FieldNames.FIELD_GENDER, "M");
		mnq.set(FieldNames.FIELD_CACHE, false);
		decks(world).maleNamesDeck = OlioUtil.randomSelectionNames(user, mnq, count);
	}

	private static void shuffleFemaleNamesDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query mnq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_NAMES_ID));
		mnq.field(FieldNames.FIELD_GENDER, "F");
		mnq.set(FieldNames.FIELD_CACHE, false);
		decks(world).femaleNamesDeck = OlioUtil.randomSelectionNames(user, mnq, count);
	}

	private static void shuffleSurnameNamesDeck(BaseRecord user, BaseRecord world, int count) throws FieldException, ValueException, ModelNotFoundException {
		Query snq = QueryUtil.createQuery(ModelNames.MODEL_CENSUS_WORD, FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_SURNAMES_ID));
		snq.set(FieldNames.FIELD_CACHE, false);
		decks(world).surnameNamesDeck = OlioUtil.randomSelectionNames(user, snq, count);
	}

	protected static void shuffleDecks(BaseRecord user, BaseRecord world) {
		try {
			// logger.info("Shuffling decks");
			shuffleMaleNamesDeck(user, world, namesDeckCount);
			shuffleFemaleNamesDeck(user, world, namesDeckCount);
			shuffleSurnameNamesDeck(user, world, namesDeckCount * 2);
			shuffleOccupationsDeck(user, world, namesDeckCount * 2);
			shuffleApparelDeck(user, world);
			OlioUtil.dirNameCache.clear();
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

	protected static void shuffleTraitDeck(BaseRecord user, BaseRecord world) {
		long traitDir = world.get(OlioFieldNames.FIELD_TRAITS_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_TRAIT, FieldNames.FIELD_GROUP_ID, traitDir);
		q.setRequest(new String[]{FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID});
		decks(world).traitDeck = OlioUtil.randomSelections(user, q, traitDeckSize);
	}

	public static BaseRecord[] getRandomTraits(BaseRecord user, BaseRecord world, int count) {
		List<BaseRecord> traits = new ArrayList<>();
		DeckSet ds = decks(world);
		if(ds.traitDeck.length == 0) {
			shuffleTraitDeck(user, world);
			/// re-resolve in case the set was evicted between the probe and the shuffle
			ds = decks(world);
		}

		BaseRecord[] deck = ds.traitDeck;
		if(deck.length > 0) {
			for(int i = 0; i < count; i++) {
				BaseRecord trait = deck[rand.nextInt(deck.length)];
				if(!traits.contains(trait)) {
					traits.add(trait);
				}
			}
		}
		return traits.toArray(new BaseRecord[0]);
	}
}
