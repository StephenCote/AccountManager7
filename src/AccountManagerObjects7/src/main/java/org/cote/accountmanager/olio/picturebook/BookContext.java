package org.cote.accountmanager.olio.picturebook;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

/**
 * A narrow, read-only view over a PictureBook book world: universe, world, organization, and the
 * groups the book's records live in.
 * <p>
 * <b>It resolves <i>where</i>, never <i>whether</i>.</b> Nothing on this class performs or implies an
 * authorization decision. Every read and write of an actual record must still go through
 * {@code AccessPoint} as the acting user. A resolved group path is not permission to use it.
 * <p>
 * It is deliberately NOT an {@code OlioContext}, and deliberately not a flag on one: a partially
 * populated {@code OlioContext} with a null clock and no realms is a trap for any Olio utility that
 * assumes {@code initialize()} ran.
 * <p>
 * The {@code olioUser} principal the assembler uses is intentionally not exposed - and, as of the
 * phase-1 sign-off, not held either: this class no longer takes or stores it. It is resolved through
 * an unauthorized find and must never reach a caller, and a stored-but-unread reference is a
 * standing invitation to add a getter for it.
 */
public final class BookContext {

	private final BaseRecord universe;
	private final BaseRecord world;
	private final long organizationId;
	private final Map<String, BaseRecord> extraGroups;

	BookContext(BaseRecord universe, BaseRecord world, Map<String, BaseRecord> extraGroups) {
		this.universe = universe;
		this.world = world;
		this.organizationId = world.get(FieldNames.FIELD_ORGANIZATION_ID);
		this.extraGroups = (extraGroups != null ? new HashMap<>(extraGroups) : Collections.emptyMap());
	}

	public BaseRecord getUniverse() {
		return universe;
	}

	public BaseRecord getWorld() {
		return world;
	}

	public long getOrganizationId() {
		return organizationId;
	}

	/**
	 * Resolve one of the book world's groups.
	 *
	 * @param worldGroupFieldName either a foreign {@code auth.group} field on {@code olio.world}
	 *        ("gallery", "narratives", "population", ...) or one of the three PictureBook groups
	 *        created by {@code BookWorldInitializationRule} ("book", "workflow", "artifacts").
	 *        Matching for the latter three is case-insensitive.
	 * @return the group record, or null when the name is not a known group
	 */
	public BaseRecord getGroup(String worldGroupFieldName) {
		if(worldGroupFieldName == null) {
			return null;
		}
		BaseRecord extra = extraGroups.get(worldGroupFieldName.toLowerCase());
		if(extra != null) {
			return extra;
		}
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_WORLD);
		FieldSchema fs = ms.getFieldSchema(worldGroupFieldName);
		if(fs == null || fs.getBaseModel() == null || !ModelNames.MODEL_GROUP.equals(fs.getBaseModel()) || !fs.isForeign()) {
			return null;
		}
		return world.get(worldGroupFieldName);
	}

	/**
	 * Group path for {@link #getGroup(String)}, suitable for a {@code ParameterList} {@code path}
	 * parameter. Null when the group cannot be resolved.
	 */
	public String getGroupPath(String worldGroupFieldName) {
		BaseRecord grp = getGroup(worldGroupFieldName);
		if(grp == null) {
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(grp);
		return grp.get(FieldNames.FIELD_PATH);
	}
}
