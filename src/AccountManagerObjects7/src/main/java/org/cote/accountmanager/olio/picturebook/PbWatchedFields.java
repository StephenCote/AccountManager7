package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.AttributeUtil;

/**
 * The <b>declared watched field set</b> per referenced model, and the {@code refHash} computed over it.
 * <p>
 * <b>Why this class exists at all.</b> §2.3's staleness rule chains artifact revisions, which cannot see
 * an edit to an ordinary AM7 record: {@code olio.charPerson} carries no revision and no content hash
 * (journaling is WIP and not enabled on it). Editing a character - the <i>headline</i> use case - would
 * therefore mark nothing stale. So {@code olio.pb.binding.refHash} holds a stable hash over a declared
 * set of the referenced record's fields, taken at bind time and recomputed by
 * {@code PbGraphUtil.recomputeStatus}; a node is stale when any binding's recomputed hash differs from
 * the stored one.
 * <p>
 * <b>The set is a POLICY DECISION, not a derivation, and this class is the one place it is declared.</b>
 * A field outside the set can change without marking anything stale. That is a deliberate trade - the
 * alternative, hashing the whole record, would make an unrelated {@code state}/{@code statistics} tick
 * invalidate every portrait in the book - but it means the set has to be visible, documented and covered
 * by a test that edits a watched field and an unwatched field and asserts the two different outcomes.
 * <p>
 * <b>Cost, so nobody wires this into a request path.</b> {@link #computeRefHash} is one projected read
 * per referenced record. {@code recomputeStatus} over a whole workflow is therefore O(bindings) reads
 * and is a deliberate operation - invoked when a book's workflow view is opened, and after a character
 * edit - never per request.
 */
public class PbWatchedFields {
	public static final Logger logger = LogManager.getLogger(PbWatchedFields.class);

	/**
	 * Bumped when the watched sets or the canonical form below change. Folded into every
	 * {@code refHash}, so a change here invalidates stored hashes <b>on purpose</b> and loudly, rather
	 * than silently comparing hashes computed under two different policies.
	 */
	public static final String WATCHED_SET_VERSION = "watched/v1";

	/**
	 * {@code olio.charPerson}: identity and appearance, the fields a portrait or a character description
	 * is actually derived from.
	 * <p>
	 * {@code hairColor} and {@code eyeColor} are foreign {@code data.color} references, so they are
	 * projected and rendered by name - see {@link #canonicalRef}. {@code alignment} comes from
	 * {@code common.alignment}, {@code race}/{@code ethnicity}/{@code age}/{@code gender}/the names from
	 * {@code identity.person}.
	 */
	private static final List<String> CHAR_PERSON = Collections.unmodifiableList(Arrays.asList(
		FieldNames.FIELD_NAME, "firstName", "lastName", "gender", "age", "race", "ethnicity",
		"hairColor", "eyeColor", "hairStyle", "alignment"
	));

	/**
	 * {@code olio.apparel}: what the garment IS and whether it is being worn. The field is spelled
	 * {@code inuse} in {@code apparelModel.json} - lower-case 'u' - which the plan body renders as
	 * {@code inUse}; the model spelling is the one that resolves.
	 */
	private static final List<String> APPAREL = Collections.unmodifiableList(Arrays.asList(
		FieldNames.FIELD_NAME, "type", "category", "gender", "inuse"
	));

	/** {@code data.data}: a source document. Identity plus the content hash the store already keeps. */
	private static final List<String> DATA = Collections.unmodifiableList(Arrays.asList(
		FieldNames.FIELD_NAME, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_SIZE
	));

	private static final Map<String, List<String>> WATCHED;
	static {
		Map<String, List<String>> m = new LinkedHashMap<>();
		m.put(OlioModelNames.MODEL_CHAR_PERSON, CHAR_PERSON);
		m.put(OlioModelNames.MODEL_APPAREL, APPAREL);
		m.put(ModelNames.MODEL_DATA, DATA);
		WATCHED = Collections.unmodifiableMap(m);
	}

	/**
	 * Attributes watched on {@code olio.charPerson}. Attributes use <b>referenced</b> storage - a
	 * separate table keyed by {@code referenceModel}/{@code referenceId} - so they are not fields and
	 * cannot be projected; they are read through {@link AttributeUtil} after the record is loaded.
	 * <p>
	 * {@code pbDescription} ({@code PictureBookUtil.ATTR_DESCRIPTION}) is the hand-written or extracted
	 * character description the portrait prompt is built from, so an edit to it must invalidate the
	 * portrait.
	 */
	private static final List<String> CHAR_PERSON_ATTRIBUTES = Collections.unmodifiableList(Arrays.asList(
		PictureBookUtil.ATTR_DESCRIPTION
	));

	private PbWatchedFields() {
		/// static utility
	}

	/** The models a {@code binding.refModel} may name and have staleness detection for. */
	public static List<String> watchedModels() {
		return new ArrayList<>(WATCHED.keySet());
	}

	/**
	 * The declared watched field set for {@code model}, or an empty list when the model has none.
	 * <p>
	 * An empty list is the honest answer, and callers must treat it as one: a binding referencing an
	 * unwatched model gets a null {@code refHash} and <b>cannot be detected as stale</b>. It is not an
	 * error, it is a gap, and {@link #computeRefHash} logs it.
	 */
	public static List<String> watchedFields(String model) {
		List<String> l = WATCHED.get(model);
		return (l != null ? l : Collections.emptyList());
	}

	/** The declared watched attribute names for {@code model} (referenced storage, not fields). */
	public static List<String> watchedAttributes(String model) {
		if(OlioModelNames.MODEL_CHAR_PERSON.equals(model)) {
			return CHAR_PERSON_ATTRIBUTES;
		}
		return Collections.emptyList();
	}

	/**
	 * Read {@code refModel}/{@code refObjectId} as {@code user} and hash its watched set.
	 * <p>
	 * <b>Through {@code AccessPoint}</b>, so a caller who may not read the referenced record gets null
	 * rather than a hash - and a null is treated as "cannot determine", never as "unchanged". The read
	 * is an explicit projection of the watched fields only (plus identity), because foreign fields are
	 * not populated by default and a default projection would silently render every one of them as the
	 * null token, producing a hash that is stable, wrong, and identical for every character.
	 *
	 * @return the hex digest, or null when the model is unwatched or the record cannot be read
	 */
	public static String computeRefHash(BaseRecord user, String refModel, String refObjectId) {
		if(user == null || refModel == null || refObjectId == null || refObjectId.trim().length() == 0) {
			return null;
		}
		List<String> fields = watchedFields(refModel);
		if(fields.isEmpty()) {
			logger.warn("No watched field set is declared for " + refModel
				+ " - a binding referencing it can never be detected as stale. Declare one in PbWatchedFields"
				+ " if edits to it must invalidate downstream nodes.");
			return null;
		}
		BaseRecord rec = readProjected(user, refModel, refObjectId, fields);
		if(rec == null) {
			logger.warn("Could not read " + refModel + " " + refObjectId + " to compute a refHash");
			return null;
		}
		return sha256(canonicalRef(rec, refModel));
	}

	/**
	 * Hash the watched set of an <b>already-loaded</b> record. Use only when the record was read with at
	 * least {@link #projection(String)}; otherwise prefer
	 * {@link #computeRefHash(BaseRecord, String, String)}, which controls the projection itself.
	 */
	public static String refHashOf(BaseRecord rec) {
		if(rec == null) {
			return null;
		}
		if(watchedFields(rec.getSchema()).isEmpty()) {
			return null;
		}
		return sha256(canonicalRef(rec, rec.getSchema()));
	}

	/**
	 * The {@code request} projection needed to compute a {@code refHash} for {@code model}: the watched
	 * fields plus the identity fields a caller will want back.
	 */
	public static String[] projection(String model) {
		List<String> req = new ArrayList<>(Arrays.asList(
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ORGANIZATION_ID));
		ModelSchema ms = RecordFactory.getSchema(model);
		for(String f : watchedFields(model)) {
			if(ms != null && ms.getFieldSchema(f) == null) {
				/// A watched field that does not exist on the model would render as the null token
				/// forever, i.e. a permanently unnoticed edit. Loud, and skipped.
				logger.error("Watched field " + model + "." + f + " does not exist on the model - edits to it can"
					+ " never be detected. Fix the declaration in PbWatchedFields.");
				continue;
			}
			if(!req.contains(f)) {
				req.add(f);
			}
		}
		return req.toArray(new String[0]);
	}

	private static BaseRecord readProjected(BaseRecord user, String model, String objectId, List<String> fields) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.setRequest(projection(model));
		/// Staleness detection must never read a cached projection of a record that was just edited:
		/// that is precisely the change it exists to notice.
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * The exact string {@link #computeRefHash} hashes. Public so a hash mismatch can be diagnosed by
	 * diffing two canonical strings rather than two digests.
	 * <p>
	 * Rendering rules, all shared with {@link PbConfigUtil#token(Object)} so the PB2 hashes cannot drift
	 * apart on the null and floating-point conventions:
	 * <ul>
	 * <li>fields in <b>declared order</b> (the order in this class, not the model's), each as
	 * {@code name=token};</li>
	 * <li>a foreign {@code model} value rendered by its <b>name</b>, not its row id, so the hash stays
	 * portable across systems (the same reason ratification 8 adopted urn);</li>
	 * <li>watched attributes appended after the fields, prefixed {@code @};</li>
	 * <li>the {@code store.apparel} in-use set appended last for {@code olio.charPerson} - see
	 * {@link #apparelToken}.</li>
	 * </ul>
	 */
	public static String canonicalRef(BaseRecord rec, String model) {
		StringBuilder sb = new StringBuilder(WATCHED_SET_VERSION);
		sb.append(PbConfigUtil.PAIR_SEPARATOR).append("model=").append(model);
		for(String f : watchedFields(model)) {
			Object v = (rec.hasField(f) ? rec.get(f) : null);
			if(v instanceof BaseRecord) {
				/// data.color and friends: the NAME is the portable identity, the row id is not
				v = ((BaseRecord) v).get(FieldNames.FIELD_NAME);
			}
			sb.append(PbConfigUtil.PAIR_SEPARATOR).append(f).append('=').append(PbConfigUtil.token(v));
		}
		for(String a : watchedAttributes(model)) {
			sb.append(PbConfigUtil.PAIR_SEPARATOR).append('@').append(a).append('=')
				.append(PbConfigUtil.token(attribute(rec, a)));
		}
		if(OlioModelNames.MODEL_CHAR_PERSON.equals(model)) {
			sb.append(PbConfigUtil.PAIR_SEPARATOR).append("store.apparel=").append(apparelToken(rec));
		}
		return sb.toString();
	}

	private static Object attribute(BaseRecord rec, String name) {
		try {
			BaseRecord attr = AttributeUtil.getAttribute(rec, name);
			if(attr == null) {
				return null;
			}
			return AttributeUtil.getAttributeValue(rec, name, (String) null);
		}
		catch(Exception e) {
			logger.warn("Failed to read attribute " + name + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * The in-use apparel set of a character, as a sorted list of {@code name:inuse} pairs.
	 * <p>
	 * <b>Sorted, because participation order is not guaranteed</b> - an unsorted rendering would produce
	 * a different hash for the same wardrobe depending on the order the join returned, marking nodes
	 * stale at random. {@code store} is a foreign model and {@code store.apparel} a foreign list, so both
	 * must have been planned; an unpopulated store renders as the null token, which is why
	 * {@link #computeRefHash} does not attempt the apparel leg from a projected read and callers who need
	 * it must pass a record loaded with {@code OlioUtil.planMost}.
	 */
	public static String apparelToken(BaseRecord person) {
		if(person == null || !person.hasField("store")) {
			return PbConfigUtil.NULL_TOKEN;
		}
		BaseRecord store = person.get("store");
		if(store == null || !store.hasField("apparel")) {
			return PbConfigUtil.NULL_TOKEN;
		}
		List<BaseRecord> apparel = store.get("apparel");
		if(apparel == null) {
			return PbConfigUtil.NULL_TOKEN;
		}
		List<String> parts = new ArrayList<>();
		for(BaseRecord a : apparel) {
			Object inuse = (a.hasField("inuse") ? a.get("inuse") : null);
			parts.add(PbConfigUtil.token(a.get(FieldNames.FIELD_NAME)) + ":" + PbConfigUtil.token(inuse));
		}
		Collections.sort(parts);
		return parts.toString();
	}

	private static String sha256(String canonical) {
		return PbConfigUtil.sha256Hex(canonical);
	}
}
