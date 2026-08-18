package org.cote.accountmanager.olio.picturebook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;

/**
 * Where a PictureBook character's foreign sub-records live, and who creates them.
 * <p>
 * <b>This replaces {@code PictureBookUtil.prepareForeignSubModelGroups} and
 * {@code createPersistedForeignInstance}, which are deleted.</b> Both hard-coded the destination as
 * {@code "~/" + schema.getGroup()} - the <b>acting user's home</b> - which had three consequences:
 * <ol>
 * <li>every PictureBook character's statistics, store, profile, instinct, personality and state landed
 * in the user's home directory rather than in the book's own compartment, so a book was not actually
 * self-contained and could not be shared, copied to a chapter, or deleted cleanly;</li>
 * <li>{@code ~/Narratives} is <b>KI-60's collision target</b>. The reported failure is a foreign
 * sub-record write to {@code ~/Narratives} recovering onto the wrong group ({@code #151 Apparel} for
 * {@code #1049 Narratives}). Moving the destination into {@code {world}/Narratives} does not fix KI-60 -
 * the wrong-group recovery is still reachable from any {@code makePath} caller - but it does remove this
 * pipeline from the set of callers that produce it;</li>
 * <li>the same get-or-create ran 13 x N times for an N-character book, which is what
 * {@code prepareForeignSubModelGroups} existed to paper over.</li>
 * </ol>
 * <p>
 * <b>Narratives go through {@code NarrativeUtil.getCreateNarrative}</b>, the canonical Olio utility,
 * rather than being hand-rolled. It already does the whole job: populate the existing narrative, build a
 * fresh one <i>in {@code ctx.getWorld().get("narratives.path")}</i>, create-or-{@code RecordUtil.patch},
 * link it back onto the person via {@code Queue.queueUpdate}, and flush. Standing guidance in
 * {@code llm-conduct.md} names repairing a hand-rolled path instead of adopting the util that works as a
 * recorded failure mode; this is that adoption.
 * <p>
 * <b>Destination resolution is parameterised, not hard-coded, and falls back to the legacy path.</b> With
 * an {@code OlioContext} the sub-record lands in the world group for its model; without one it lands where
 * it always did. That fallback is deliberate: {@code createCharPerson} runs on the flag-off path too, and
 * the phase-3 exit criterion is that {@code TestPictureBookCustom#TestPictureBookCustomPipeline} passes
 * <b>unchanged</b> with {@code picturebook.v2} off.
 */
public class PbSubRecordUtil {
	public static final Logger logger = LogManager.getLogger(PbSubRecordUtil.class);

	/**
	 * Foreign sub-model -> the {@code olio.world} group field it belongs in.
	 * <p>
	 * Read off {@code worldModel.json}'s own {@code auth.group} fields, not invented: the world already
	 * declares a group for every one of these, which is why "route sub-records into world groups" is a
	 * destination change rather than a schema change.
	 */
	public static final Map<String, String> WORLD_GROUP_FIELD;
	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(OlioModelNames.MODEL_NARRATIVE, "narratives");
		m.put(ModelNames.MODEL_PROFILE, "profiles");
		m.put(OlioModelNames.MODEL_CHAR_STATISTICS, "statistics");
		m.put(OlioModelNames.MODEL_STORE, "stores");
		m.put(OlioModelNames.MODEL_INSTINCT, "instincts");
		m.put(ModelNames.MODEL_PERSONALITY, "personalities");
		m.put(OlioModelNames.MODEL_CHAR_STATE, "states");
		WORLD_GROUP_FIELD = Collections.unmodifiableMap(m);
	}

	private PbSubRecordUtil() {
		/// static utility
	}

	/**
	 * The group path a sub-record of {@code modelName} should be created in.
	 * <p>
	 * {@code {world}/{group}} when {@code octx} has a world that declares one; otherwise the legacy
	 * {@code ~/{schemaGroup}} in the acting user's home - see the class javadoc on why that fallback
	 * exists rather than being an oversight.
	 */
	public static String groupPathFor(BaseRecord user, OlioContext octx, String modelName) {
		String field = WORLD_GROUP_FIELD.get(modelName);
		if(octx != null && octx.getWorld() != null && field != null) {
			String path = octx.getWorld().get(field + ".path");
			if(path != null && path.length() > 0) {
				return path;
			}
			logger.warn("World declares no resolvable '" + field + "' group; falling back to the home path for "
				+ modelName);
		}
		ModelSchema ms = RecordFactory.getSchema(modelName);
		if(ms == null || ms.getGroup() == null) {
			return null;
		}
		return "~/" + ms.getGroup();
	}

	/**
	 * Resolve every sub-record destination group ONCE, up front.
	 * <p>
	 * This is the surviving half of KI-42's stated direction: collapsing 13 x N repeated get-or-creates to
	 * one per group per request means the create branch is entered at most once per group, so the
	 * duplicate-key race {@code PathUtil.makePath} used to lose has almost nothing left to run against.
	 * <p>
	 * Best-effort: a group that cannot be made is logged and skipped rather than aborting the book, because
	 * {@link #createSubRecord} will try again for itself and report the failure with the character's name
	 * attached - the more useful error.
	 */
	public static void prepareGroups(BaseRecord user, OlioContext octx) {
		long organizationId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		Set<String> seen = new LinkedHashSet<>();
		for(String modelName : WORLD_GROUP_FIELD.keySet()) {
			String path = groupPathFor(user, octx, modelName);
			if(path == null || !seen.add(path)) {
				continue;
			}
			try {
				BaseRecord grp = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
					path, GroupEnumType.DATA.toString(), organizationId);
				if(grp == null) {
					logger.warn("Could not pre-resolve '" + path + "' for " + modelName
						+ " - per-character creation will retry and report its own failure");
				}
			}
			catch(Exception e) {
				logger.warn("Failed to pre-resolve '" + path + "' for " + modelName + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Create and persist one foreign sub-model instance in its resolved destination group.
	 * <p>
	 * Goes through {@code AccessPoint} rather than {@code RecordUtil.createRecord}, so PBAC decides and the
	 * record gets a real id/objectId immediately - which is what lets it be linked onto the parent by a
	 * PATCH-shaped update.
	 * <p>
	 * <b>Correction, measured 2026-08-17.</b> An earlier version of this javadoc claimed
	 * {@code CharPersonFactory}'s in-memory placeholders "are never cascaded" because
	 * {@code olio.charPerson} does not set {@code autoCreateForeignReference}. That is backwards:
	 * {@code ModelSchema.autoCreateForeignReference} <b>defaults to true</b> ({@code ModelSchema.java:60}),
	 * so {@code DBWriter.applyAutoCreateList} ({@code :367-403}) auto-creates those placeholders <b>in the
	 * acting user's home</b> on create - silently, through {@code RecordUtil.createRecords}, which bypasses
	 * PBAC and emits no audit line. Six of the seven call sites in {@code createCharPerson} were therefore
	 * unreachable until it began detaching the placeholders before create. Anything else adopting this
	 * utility has to do the same, or its sub-records will already exist by the time it asks.
	 *
	 * @return the created record, or null (logged) on denial or failure
	 */
	public static BaseRecord createSubRecord(BaseRecord user, OlioContext octx, String modelName) {
		return createSubRecord(user, octx, modelName, null);
	}

	/**
	 * As {@link #createSubRecord(BaseRecord, OlioContext, String)}, but seeds the instance's own content
	 * fields from {@code baselineSource} <b>before</b> persisting (KI-30), so the one persisted record
	 * carries {@code CharacterUtil.randomPerson()}'s randomized values instead of an empty placeholder.
	 * <p>
	 * Before, not after: a copy after the create would need a second update, and a discarded update result
	 * there would silently lose the whole baseline.
	 */
	public static BaseRecord createSubRecord(BaseRecord user, OlioContext octx, String modelName,
			BaseRecord baselineSource) {
		String path = groupPathFor(user, octx, modelName);
		if(path == null) {
			logger.error("No destination group could be resolved for " + modelName);
			return null;
		}
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
			BaseRecord inst = IOSystem.getActiveContext().getFactory().newInstance(modelName, user, null, plist);
			if(baselineSource != null) {
				copyBaselineFieldValues(baselineSource, inst);
			}
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, inst);
			if(created == null) {
				logger.error("Failed to persist a new " + modelName + " in " + path
					+ " - AccessPoint.create returned null (denied or persist failure)");
				return null;
			}
			return created;
		}
		catch(Exception e) {
			logger.error("Failed to create a persisted " + modelName + " in " + path + ": " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Copy {@code source}'s plain content field values onto {@code target}.
	 * <p>
	 * Field-by-field rather than {@code copyRecord}, which would wipe the target's own
	 * path/groupId/name - already set correctly by the factory call, and the whole point of routing the
	 * record into a world group.
	 * <p>
	 * Deliberately excludes identity/path/groupId/parentId/name (the target has its own correctly-scoped
	 * values) and <b>foreign</b> fields (which would cross-link to the baseline's own in-memory,
	 * unpersisted sub-sub-records - {@code store.apparel} being the one that bites).
	 */
	static void copyBaselineFieldValues(BaseRecord source, BaseRecord target) {
		if(source == null || target == null) {
			return;
		}
		try {
			ModelSchema ms = RecordFactory.getSchema(target.getSchema());
			for(org.cote.accountmanager.schema.FieldSchema fs : ms.getFields()) {
				String n = fs.getName();
				if(fs.isIdentity() || fs.isVirtual() || fs.isEphemeral() || fs.isForeign()) {
					continue;
				}
				if(FieldNames.FIELD_NAME.equals(n) || FieldNames.FIELD_PATH.equals(n)
					|| FieldNames.FIELD_GROUP_ID.equals(n) || FieldNames.FIELD_PARENT_ID.equals(n)) {
					continue;
				}
				try {
					Object val = source.get(n);
					if(val != null) {
						target.set(n, val);
					}
				}
				catch(Exception ignoredFieldCopyFailure) {
					/// Not present/settable on one side for this model - skip it; not fatal to seeding.
				}
			}
		}
		catch(Exception e) {
			logger.warn("copyBaselineFieldValues failed for " + target.getSchema() + ": " + e.getMessage());
		}
	}

	/**
	 * The character's narrative, through the canonical Olio utility.
	 * <p>
	 * {@code NarrativeUtil.getCreateNarrative} builds it in {@code {world}/Narratives}, creates it or
	 * patches the existing one, links it back onto the person and flushes the queue. It needs a real
	 * {@code OlioContext} - with none, there is no world to build into, so this returns null and the caller
	 * falls back to {@link #createSubRecord}.
	 *
	 * @return the narrative, or null when there is no usable context
	 */
	public static BaseRecord getCreateNarrative(OlioContext octx, BaseRecord charPerson, String setting) {
		if(octx == null || octx.getWorld() == null || charPerson == null) {
			return null;
		}
		try {
			List<BaseRecord> made = NarrativeUtil.getCreateNarrative(octx,
				Collections.singletonList(charPerson), setting);
			if(made == null || made.isEmpty()) {
				logger.warn("NarrativeUtil.getCreateNarrative produced no narrative for "
					+ charPerson.get(FieldNames.FIELD_NAME));
				return null;
			}
			return made.get(0);
		}
		catch(Exception e) {
			logger.warn("NarrativeUtil.getCreateNarrative failed for "
				+ charPerson.get(FieldNames.FIELD_NAME) + ": " + e.getMessage(), e);
			return null;
		}
	}
}
