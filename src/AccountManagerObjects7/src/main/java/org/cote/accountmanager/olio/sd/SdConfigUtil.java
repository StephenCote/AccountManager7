package org.cote.accountmanager.olio.sd;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;

/**
 * Find-only read path and authorized write path for {@code olio.sd.config}.
 * <p>
 * Read paths must not create (see {@code architecture.md} "Read paths must not create, and never
 * as the org admin"). {@link #findConfig} returns null on a miss; {@link #getOrDefaultConfig}
 * returns a fresh schema-defaults instance that is never persisted. Only
 * {@link #createOrUpdateConfig} ever writes a record, and only when called explicitly by an
 * authorized caller.
 */
public class SdConfigUtil {
	public static final Logger logger = LogManager.getLogger(SdConfigUtil.class);

	private SdConfigUtil() {
		/// static utility
	}

	/**
	 * Find a named {@code olio.sd.config} in the specified group. Never creates one.
	 * <p>
	 * The query is uncached ({@code cache:false}) so callers that just wrote a config see the
	 * persisted record immediately, not a stale entry.
	 *
	 * @param user    the context user (PBAC is enforced by {@code AccessPoint.find})
	 * @param name    the config name (lookup key within its group)
	 * @param groupId the numeric directory id the config lives in
	 * @param orgId   the organization
	 * @return the found record, or {@code null} when no match exists in the database
	 */
	public static BaseRecord findConfig(BaseRecord user, String name, long groupId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Return the named config if it exists in the database, otherwise return a fresh
	 * schema-defaults instance.
	 * <p>
	 * The default instance is created by {@code RecordFactory.newInstance("olio.sd.config")} — it
	 * carries no id, objectId, or urn and is never written. Callers that need a durable record must
	 * call {@link #createOrUpdateConfig} explicitly.
	 *
	 * @return the found record, or an ephemeral schema-defaults instance — never {@code null}
	 */
	public static BaseRecord getOrDefaultConfig(BaseRecord user, String name, long groupId, long orgId) {
		BaseRecord found = findConfig(user, name, groupId, orgId);
		if (found != null) {
			return found;
		}
		try {
			return RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		}
		catch (FieldException | ModelNotFoundException e) {
			logger.error("Failed to instantiate " + OlioModelNames.MODEL_SD_CONFIG + ": " + e.getMessage(), e);
			throw new IllegalStateException("Failed to instantiate " + OlioModelNames.MODEL_SD_CONFIG, e);
		}
	}

	/**
	 * Authorized write path: create or update an {@code olio.sd.config} record.
	 * <p>
	 * When {@code config} carries no {@code objectId} (new record), calls
	 * {@code AccessPoint.create}. When it already has one, calls {@code AccessPoint.update} —
	 * only fields already present on the record are written (identity + all set fields). The
	 * caller is responsible for including {@code name} and any other required fields.
	 * <p>
	 * Never discards the return value: a {@code false} from {@code update} is the only signal that
	 * the write silently failed, and swallowing it converts that into a no-op the caller cannot see.
	 *
	 * @return the created record (on create), the same {@code config} reference (on update), or
	 *         {@code null} when the write failed
	 */
	public static BaseRecord createOrUpdateConfig(BaseRecord user, BaseRecord config) {
		if (config == null) {
			logger.error("createOrUpdateConfig called with null config");
			return null;
		}
		String objectId = config.get(FieldNames.FIELD_OBJECT_ID);
		if (objectId == null || objectId.trim().isEmpty()) {
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, config);
			if (created == null) {
				logger.error("Failed to create " + OlioModelNames.MODEL_SD_CONFIG + " (name="
					+ config.get(FieldNames.FIELD_NAME) + ")");
			}
			return created;
		}
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, config);
		if (updated == null) {
			logger.error("Failed to update " + OlioModelNames.MODEL_SD_CONFIG + " objectId=" + objectId);
			return null;
		}
		return updated;
	}
}
