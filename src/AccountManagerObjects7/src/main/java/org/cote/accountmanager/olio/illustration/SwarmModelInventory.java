package org.cote.accountmanager.olio.illustration;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;

/// A Swarm server's installed-checkpoint list as a ModelInventory, cached per server URL for
/// TTL_MS (PictureBook V3 design §16, §10.2 decision 11). The cache is keyed by server, holds only
/// SUCCESSFUL reads, lives in this class and nowhere in IOContext (a server URL is deployment
/// config, not per-org state; the list is the same for every org that asks the same server).
///
/// A failed read is never cached: it comes back as ModelInventory.unavailable so the next call asks
/// again. Within the TTL every caller gets the SAME snapshot instance, which is what makes
/// "resolve, then render against the same list" coherent for one request. invalidate() is for
/// tests and for the moment something installs a model (nothing in-process does today, so the
/// propagation bound for a checkpoint installed on the server is TTL_MS).
///
/// error() text reaches user-role callers on the wire (inventory.error, resolution.inventoryError,
/// degradationText), so it never names the server URL or the configured API type; those go to the
/// log only. server() is for in-process callers and is not serialized by the facade.
public final class SwarmModelInventory implements ModelInventory {

	private static final Logger logger = LogManager.getLogger(SwarmModelInventory.class);
	public static final long TTL_MS = 300_000L;

	private static final ConcurrentHashMap<String, SwarmModelInventory> CACHE = new ConcurrentHashMap<>();

	private final String server;
	private final List<String> installed;
	private final long fetchedAt;

	private SwarmModelInventory(String server, List<String> installed, long fetchedAt) {
		this.server = server;
		this.installed = Collections.unmodifiableList(installed);
		this.fetchedAt = fetchedAt;
	}

	/// The inventory for one SD server. A blank server or a non-Swarm API type is an unavailable
	/// inventory with a reason; a read failure is unavailable and uncached; a success is cached.
	public static ModelInventory forServer(SDAPIEnumType type, String server) {
		if(server == null || server.isBlank()) {
			return ModelInventory.unavailable(server, "SD server is not configured");
		}
		if(type != SDAPIEnumType.SWARM) {
			logger.warn("Installed-model inventory requested for a " + type + " server (" + server + "); only SWARM is supported");
			return ModelInventory.unavailable(server, "installed-model inventory is only available for SWARM servers");
		}
		String key = server.trim();
		long now = System.currentTimeMillis();
		SwarmModelInventory cached = CACHE.get(key);
		if(cached != null && (now - cached.fetchedAt) < TTL_MS) {
			return cached;
		}
		List<String> models = new SDUtil(SDAPIEnumType.SWARM, key).listModelsOrNull();
		if(models == null) {
			logger.warn("Could not read the installed-model list from the SD server at " + key);
			return ModelInventory.unavailable(key, "Could not read the installed-model list from the SD server");
		}
		SwarmModelInventory fresh = new SwarmModelInventory(key, models, System.currentTimeMillis());
		CACHE.put(key, fresh);
		logger.info("Installed-model inventory for " + key + ": " + models.size() + " checkpoints");
		return fresh;
	}

	public static void invalidate(String server) {
		if(server != null) {
			CACHE.remove(server.trim());
		}
	}

	public static void invalidateAll() {
		CACHE.clear();
	}

	@Override
	public String server() {
		return server;
	}

	@Override
	public List<String> installedModels() {
		return installed;
	}

	@Override
	public long fetchedAt() {
		return fetchedAt;
	}

	@Override
	public String error() {
		return null;
	}

	@Override
	public String toString() {
		return "SwarmModelInventory{" + server + ", " + installed.size() + " models, fetchedAt=" + fetchedAt + "}";
	}
}
