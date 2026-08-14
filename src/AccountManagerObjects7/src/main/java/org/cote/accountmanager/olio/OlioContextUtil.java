package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.rules.ArenaEvolveRule;
import org.cote.accountmanager.olio.rules.ArenaInitializationRule;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GenericStateRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioStateRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.AuditUtil;

public class OlioContextUtil {
	public static final Logger logger = LogManager.getLogger(OlioContextUtil.class);

	public static final String DEFAULT_UNIVERSE_NAME = "My Grid Universe";
	public static final String DEFAULT_WORLD_NAME = "My Grid World";

	/** Approximate LRU bound. Contexts retain population/demographic/realm maps, so they are not cheap. */
	private static final int MAX_CACHED_CONTEXTS = 32;

	private static final Map<String, CacheEntry> contextMap = new ConcurrentHashMap<>();
	private static final Map<String, Object> lockMap = new ConcurrentHashMap<>();

	private OlioContextUtil() {
		/// static utility
	}

	private static class CacheEntry {
		private final OlioContext context;
		private volatile long lastAccess;
		private CacheEntry(OlioContext context) {
			this.context = context;
			this.lastAccess = System.nanoTime();
		}
	}

	/**
	 * Cache key for a resolved Olio context.
	 * <p>
	 * <b>This is a NAME key, not an id key.</b> The universe and world objectIds are not known until
	 * the context has been built, so phase 1 keys on the names the caller asked for. Phase 1b, which
	 * threads {@code universeObjectId}/{@code worldObjectId} through the REST surface, is responsible
	 * for mapping those ids to this key - the key shape is deliberately not part of any wire
	 * contract.
	 * <p>
	 * {@code organizationId} is mandatory and first: {@code system.user}'s uniqueness constraint is
	 * {@code (name, organizationId)}, so a key without the organization lets user {@code steve} in
	 * org A and {@code steve} in org B share one context - carrying org A's olioUser, world and roles
	 * into org B's requests.
	 */
	private static String cacheKey(long organizationId, String userName, String universeName, String worldName) {
		return organizationId + "/" + userName + "/" + universeName + "/" + worldName;
	}

	/**
	 * Admin/test only. Drops EVERY cached Olio context process-wide.
	 * <p>
	 * This is deliberately NOT wired to {@code CacheService.clearCaches()} / {@code GET
	 * /cache/clearAll}, which is reachable by any authenticated {@code user}-role caller. Use the
	 * admin-gated targeted evict ({@link #evictByWorld(long, String)}) for anything request-driven.
	 */
	public static void clearCache() {
		contextMap.clear();
		lockMap.clear();
	}

	/**
	 * Remove every cached context for the given world <b>within one organization</b>.
	 * <p>
	 * The cache key includes the user name, so one world has N cached contexts - one per user that
	 * opened it. Deleting a single key would leave every other user holding a context whose world has
	 * been reset or deleted.
	 * <p>
	 * <b>{@code organizationId} is a filter, not decoration.</b> The cache is process-wide and holds
	 * every tenant's contexts; an unscoped scan lets an administrator of organization A evict
	 * organization B's contexts. The key is {@code organizationId + "/" + ...}, so the org is matched
	 * on the key prefix, and the cached world's own {@code organizationId} is checked as well - a
	 * context whose key and world disagree is not evicted by this call, because that pairing means the
	 * key is wrong and blindly matching it would again cross the tenant boundary.
	 *
	 * @param organizationId the organization of the caller; only its entries are considered
	 * @param worldObjectId the {@code olio.world} objectId
	 * @return the number of entries removed
	 */
	public static int evictByWorld(long organizationId, String worldObjectId) {
		if(worldObjectId == null) {
			return 0;
		}
		String prefix = organizationId + "/";
		int removed = 0;
		for(Map.Entry<String, CacheEntry> e : contextMap.entrySet()) {
			if(!e.getKey().startsWith(prefix)) {
				continue;
			}
			OlioContext ctx = e.getValue().context;
			BaseRecord wrld = (ctx != null ? ctx.getWorld() : null);
			if(wrld == null) {
				continue;
			}
			String oid = wrld.get(FieldNames.FIELD_OBJECT_ID);
			Long worldOrg = wrld.get(FieldNames.FIELD_ORGANIZATION_ID);
			if(worldObjectId.equals(oid) && worldOrg != null && worldOrg.longValue() == organizationId) {
				contextMap.remove(e.getKey());
				lockMap.remove(e.getKey());
				removed++;
				logger.info("Evicted Olio context " + e.getKey() + " for world " + worldObjectId);
			}
		}
		return removed;
	}

	/**
	 * Remove one specific cached context.
	 * <p>
	 * The CONTEXT is removed before its lock. The other order has a window in which the context is
	 * still published while its lock is gone, so a concurrent {@link #getCachedContext} mints a second
	 * lock object for the same key and two threads can build the same context at once.
	 *
	 * @return true when an entry was removed
	 */
	public static boolean evict(long organizationId, String userName, String universeName, String worldName) {
		String key = cacheKey(organizationId, userName, universeName, worldName);
		boolean removed = (contextMap.remove(key) != null);
		lockMap.remove(key);
		if(removed) {
			logger.info("Evicted Olio context " + key);
		}
		return removed;
	}

	/**
	 * Get-or-build a cached context for {@code (organizationId, user, universeName, worldName)}.
	 * <p>
	 * Shared by {@link #getOlioContext(BaseRecord, String, String, String)} and
	 * {@code PbOlioContextUtil}, so there is exactly ONE Olio context cache in the process.
	 * <p>
	 * Uses per-key lock objects with double-checked locking rather than
	 * {@code ConcurrentHashMap.computeIfAbsent}: building a context runs re-entrant IO (and can
	 * itself touch this cache), and {@code computeIfAbsent} would hold a bin lock across all of it.
	 *
	 * A null build does NOT leave its lock behind: {@code getCachedContext(..., () -> null)} is a
	 * legitimate "cache read only" idiom (the tests use it), and without the cleanup every probe for a
	 * key that is not cached would add a permanent entry to {@code lockMap}.
	 *
	 * @param builder invoked only on a miss; may return null, which is not cached
	 */
	public static OlioContext getCachedContext(BaseRecord user, String universeName, String worldName, Supplier<OlioContext> builder) {
		long organizationId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		String key = cacheKey(organizationId, user.get(FieldNames.FIELD_NAME), universeName, worldName);

		CacheEntry entry = contextMap.get(key);
		if(entry != null) {
			entry.lastAccess = System.nanoTime();
			return entry.context;
		}
		Object lock = lockMap.computeIfAbsent(key, k -> new Object());
		OlioContext octx = null;
		try {
			synchronized(lock) {
				entry = contextMap.get(key);
				if(entry != null) {
					entry.lastAccess = System.nanoTime();
					return entry.context;
				}
				octx = builder.get();
				if(octx != null) {
					contextMap.put(key, new CacheEntry(octx));
					trim();
				}
				return octx;
			}
		}
		finally {
			/// Nothing was cached under this key, so nothing can be waiting on this lock for a context
			/// that will never appear. Remove only THIS lock instance: a concurrent builder that has
			/// already replaced it must not be dropped.
			if(octx == null && contextMap.get(key) == null) {
				lockMap.remove(key, lock);
			}
		}
	}

	/**
	 * Approximate LRU trim. Approximate because {@code lastAccess} is read without a global lock and
	 * a concurrent build can push the map one over the bound before this runs.
	 */
	private static void trim() {
		while(contextMap.size() > MAX_CACHED_CONTEXTS) {
			String oldestKey = null;
			long oldest = Long.MAX_VALUE;
			for(Map.Entry<String, CacheEntry> e : contextMap.entrySet()) {
				long la = e.getValue().lastAccess;
				if(la < oldest) {
					oldest = la;
					oldestKey = e.getKey();
				}
			}
			if(oldestKey == null) {
				break;
			}
			contextMap.remove(oldestKey);
			lockMap.remove(oldestKey);
			logger.info("Evicted Olio context " + oldestKey + " (cache bound " + MAX_CACHED_CONTEXTS + " exceeded)");
		}
	}

	/**
	 * Default grid context for the acting user. Unchanged behaviour: it resolves the hardcoded
	 * {@code My Grid Universe}/{@code My Grid World} pair.
	 */
	public static OlioContext getOlioContext(BaseRecord user, String dataPath) {
		return getOlioContext(user, dataPath, DEFAULT_UNIVERSE_NAME, DEFAULT_WORLD_NAME);
	}

	/**
	 * Grid context for a named universe/world pair, cached per
	 * {@code (organizationId, user, universe, world)}.
	 */
	public static OlioContext getOlioContext(BaseRecord user, String dataPath, String universeName, String worldName) {
		return getCachedContext(user, universeName, worldName, () -> getGridContext(user, dataPath, universeName, worldName, false));
	}

	public static OlioContext getGridContext(BaseRecord user, String dataPath, String universeName, String worldName, boolean resetWorld) {
		AuditUtil.setLogToConsole(false);

		OlioContextConfiguration cfg = new OlioContextConfiguration(
				user,
				dataPath,
				universeName,
				worldName,
				new String[] {},
				1,
				50,
				resetWorld,
				false
			);
			/// The game path depends on context construction enrolling the acting user in the Olio
			/// user role (KI-35). enrolActingUser now defaults to false, so this is explicit.
			cfg.setEnrolActingUser(true);

			/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
			///
			cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
				new GridSquareLocationInitializationRule(),
				new LocationPlannerRule(),
				new GenericItemDataLoadRule()
			}));

			// Increment24HourRule incRule = new Increment24HourRule();
			// incRule.setIncrementType(TimeEnumType.HOUR);
			cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new Increment24HourRule(),
				new HierarchicalNeedsEvolveRule()
			}));

			cfg.getStateRules().addAll(Arrays.asList(new IOlioStateRule[] {
				new GenericStateRule()
			}));


			OlioContext octx = new OlioContext(cfg);

			logger.info("Initialize olio context - Grid");
			octx.initialize();

			AuditUtil.setLogToConsole(true);

			return octx;
	}
	public static OlioContext getArenaContext(BaseRecord user, String dataPath, String universeName, String worldName, boolean resetWorld) {
		/// Currently using the 'Arena' setup with minimal locations and small, outfitted squads
		///
		AuditUtil.setLogToConsole(false);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			user,
			dataPath,
			universeName,
			worldName,
			new String[] {},
			1,
			50,
			resetWorld,
			false
		);
		/// See getGridContext - explicit opt-in, behaviour unchanged.
		cfg.setEnrolActingUser(true);

		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new ArenaInitializationRule(),
			new GenericItemDataLoadRule()
		}));
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new ArenaEvolveRule()
			}));
		OlioContext octx = new OlioContext(cfg);

		logger.info("Initialize olio context - Arena");
		octx.initialize();

		AuditUtil.setLogToConsole(true);

		return octx;
	}

	/** Test/diagnostic view of the currently cached keys. */
	public static List<String> getCachedKeys() {
		return new ArrayList<>(contextMap.keySet());
	}

	/**
	 * Test/diagnostic view of the currently held per-key build locks. A key here with no corresponding
	 * {@link #getCachedKeys()} entry (outside an in-flight build) is a leak.
	 */
	public static List<String> getLockKeys() {
		return new ArrayList<>(lockMap.keySet());
	}
}
