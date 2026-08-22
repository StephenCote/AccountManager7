package org.cote.accountmanager.olio.picturebook;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.Flux2Defaults;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;

/**
 * PictureBook 2 image-configuration resolution: the four-tier precedence chain, the sparse node
 * override, and the {@code configHash} that {@code PbGraphUtil.computeInputHash} folds in.
 * <p>
 * <b>Precedence (§2.4), highest first:</b> node {@code configOverride} &rarr; book {@code sdConfig}
 * (or {@code compositeSdConfig} for the composite step) &rarr; {@code olio/sd/flux2Defaults.json}
 * &rarr; the {@code Flux2Defaults} emergency constants. {@link #resolveEffectiveConfig} walks it and
 * returns the merged record; nothing else in PB2 is allowed to merge config by hand, because the
 * merge order is an input to a hash that decides staleness.
 * <p>
 * <b>{@code configOverride} is a SPARSE JSON STRING and stays one</b>, even if {@code olio.sd.config}
 * is later promoted to a persisted model (plan §6c, decision 6c.3.2). 30 of the model's 80 fields
 * carry a schema default, so {@code RecordFactory.newInstance("olio.sd.config")} materialises every
 * one of them and a <i>record</i> therefore cannot express "only these three fields were set". A
 * sparse string can.
 * <p>
 * <b>Known bound of the sparse mechanism, stated because it is silent.</b> {@code RecordSerializer}
 * omits a numeric value that equals the field's <i>schema default</i>
 * (see the skip-when-equals-default branch in {@code RecordSerializer.serialize}), so
 * "explicitly overridden to the default value" and "not overridden" serialize identically. That is
 * exactly why convention rule 3 forbids a schema {@code default} on config-ish fields, and why the
 * six FLUX.2 knobs {@link #applyFlux2Defaults} fills declare none.
 * <p>
 * <b>The S6 seam.</b> Every read of a book's config goes through {@link #bookConfig(BaseRecord, boolean)}.
 * Plan §6c step S6 (turning {@code book.sdConfig} / {@code book.compositeSdConfig} into foreign
 * references) then changes one method body rather than every caller. The serialized shape stands for
 * now - recorded as a decision in Appendix D, not left implicit.
 */
public class PbConfigUtil {
	public static final Logger logger = LogManager.getLogger(PbConfigUtil.class);

	/**
	 * Named at the call site, deliberately. {@code CryptoUtil.defaultHashAlgorithm} is a <b>mutable
	 * static</b> currently holding SHA-512, and {@code CryptoUtil.getDigestAsString(String)} encodes
	 * with the <b>platform default charset</b> - so a hash taken through it is neither stable across
	 * deployments nor stable against another caller reassigning the static.
	 */
	public static final String HASH_ALGORITHM = "SHA-256";

	/**
	 * The canonical rendering of a null. <b>Never {@code ""} and never {@code "null"}</b>: an empty
	 * string collides with a field genuinely set to empty, and the literal text {@code "null"} collides
	 * with a field genuinely set to that text - a real hazard here, because LLM extraction is known to
	 * emit the string "null" as a value.
	 */
	public static final String NULL_TOKEN = "-";

	/** Separator between {@code name=value} pairs in a canonical string. Not legal in a field name. */
	public static final String PAIR_SEPARATOR = "\n";

	/**
	 * Fields excluded from {@link #configHash(BaseRecord)}.
	 * <p>
	 * <b>This is a policy decision, not a derivation</b> - the same kind of declaration as
	 * {@link PbWatchedFields}, and it must be read as one. A field listed here can change without
	 * marking any node stale, so the list is deliberately tiny and holds only values that provably do
	 * not reach the image generator:
	 * <ul>
	 * <li>{@code imagePath} / {@code imageName} - the output <i>destination</i> (the field renamed from
	 * {@code groupPath} in plan §6c step S1). Where an image is filed does not change what it looks
	 * like.</li>
	 * <li>{@code description} / {@code shared} - metadata about the config record itself.</li>
	 * </ul>
	 * Note what is deliberately NOT excluded: {@code seed} is an input (pinning a seed must invalidate),
	 * and so is {@code referenceImageId}.
	 */
	public static final List<String> CONFIG_HASH_EXCLUDE = Collections.unmodifiableList(Arrays.asList(
		"imagePath", "imageName", FieldNames.FIELD_DESCRIPTION, "shared"
	));

	/**
	 * The six FLUX.2 knobs {@code flux2Defaults.json} governs, paired with their resource key. Each
	 * declares <b>no schema default on purpose</b>: a default is never null, so it would always beat the
	 * resource and make the file dead (the {@code flux2Cfg} field's own description records that this
	 * happened live once already).
	 */
	private static final String[][] FLUX2_DEFAULTED_FIELDS = new String[][] {
		{"flux2Cfg", "cfgScale"},
		{"flux2Steps", "steps"},
		{"flux2Width", "width"},
		{"flux2Height", "height"},
		{"flux2ReferenceSize", "referenceSize"},
		{"flux2IncludeLandscapeRef", "includeLandscapeRef"}
	};

	private PbConfigUtil() {
		/// static utility
	}

	// ───────────────────────────── sparse overrides ─────────────────────────────

	/**
	 * Serialize ONLY {@code changedFieldNames} of {@code cfg} as the sparse JSON string that
	 * {@code olio.pb.node.configOverride} holds.
	 * <p>
	 * Uses the existing mechanism the plan ratified - {@code cfg.copyRecord(names).toString()}. The copy
	 * carries no other field at all, so {@code toString()} (which serialises only what is present)
	 * cannot leak a model default into the override.
	 *
	 * @return the sparse JSON, or null when there is nothing to override
	 */
	public static String sparseOverride(BaseRecord cfg, Collection<String> changedFieldNames) {
		if(cfg == null || changedFieldNames == null || changedFieldNames.isEmpty()) {
			return null;
		}
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
		List<String> keep = new ArrayList<>();
		for(String n : changedFieldNames) {
			if(ms.getFieldSchema(n) == null) {
				logger.warn("Ignoring unknown " + OlioModelNames.MODEL_SD_CONFIG + " field in a config override: " + n);
				continue;
			}
			if(!keep.contains(n)) {
				keep.add(n);
			}
		}
		if(keep.isEmpty()) {
			return null;
		}
		BaseRecord sparse = cfg.copyRecord(keep.toArray(new String[0]));
		return (sparse != null ? sparse.toString() : null);
	}

	/**
	 * Parse a sparse {@code configOverride} string back into a partial {@code olio.sd.config} record.
	 * <p>
	 * The AM7 serializer emits unquoted field names ({@code JsonWriteFeature.QUOTE_FIELD_NAMES} is
	 * disabled for {@code toString()}); {@code JSONUtil.importObject} enables
	 * {@code ALLOW_UNQUOTED_FIELD_NAMES}, so both that shape and ordinary quoted JSON from a client
	 * parse here.
	 *
	 * @return the partial record, or null when the string is absent, blank or unparseable
	 */
	public static BaseRecord parseOverride(String json) {
		if(json == null || json.trim().length() == 0) {
			return null;
		}
		BaseRecord rec = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(rec == null) {
			logger.error("Failed to parse a configOverride: " + json);
			return null;
		}
		if(!OlioModelNames.MODEL_SD_CONFIG.equals(rec.getSchema())) {
			logger.error("A configOverride must carry schema " + OlioModelNames.MODEL_SD_CONFIG + ", got " + rec.getSchema());
			return null;
		}
		return rec;
	}

	// ───────────────────────────── precedence chain ─────────────────────────────

	/**
	 * The book's own config tier.
	 * <p>
	 * After S6, {@code book.sdConfig} / {@code book.compositeSdConfig} are {@code foreign: true} FK
	 * references. The deserializer resolves them using only the {@code olio.sd.config} default query
	 * fields — which do NOT include {@code style}. {@link #ensureFullSdConfig} detects a FK-partial
	 * record and does a follow-up {@code planMost} fetch to fill the missing fields.
	 *
	 * @param composite when true prefer {@code compositeSdConfig}, falling back to {@code sdConfig}
	 * @return the book's config record (fully populated), or null when the book carries none
	 */
	public static BaseRecord bookConfig(BaseRecord book, boolean composite) {
		if(book == null) {
			return null;
		}
		if(composite && book.hasField(OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG)) {
			BaseRecord cfg = book.get(OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG);
			if(cfg != null) {
				return ensureFullSdConfig(cfg);
			}
		}
		if(book.hasField(OlioFieldNames.FIELD_PB_SD_CONFIG)) {
			return ensureFullSdConfig(book.get(OlioFieldNames.FIELD_PB_SD_CONFIG));
		}
		return null;
	}

	/**
	 * Ensure {@code cfg} is fully populated (includes {@code style} and other non-query fields).
	 * <p>
	 * The FK deserializer fetches a foreign {@code olio.sd.config} using only its default query
	 * fields ({@code id, groupId, objectId, ownerId, organizationId, urn, name}) — {@code style} is
	 * absent. A FK-partial record is detected by the absence of the {@code style} field; when absent,
	 * a follow-up {@code planMost} fetch is done via the search layer (no PBAC — the book was already
	 * authorized by the caller's read).
	 */
	private static BaseRecord ensureFullSdConfig(BaseRecord cfg) {
		if(cfg == null) {
			return null;
		}
		if(!cfg.hasField("style")) {
			Long id = cfg.get(FieldNames.FIELD_ID);
			if(id == null || id <= 0L) {
				return cfg;
			}
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_ID, id);
			q.planMost(false);
			q.setCache(false);
			BaseRecord full = IOSystem.getActiveContext().getSearch().findRecord(q);
			return (full != null ? full : cfg);
		}
		return cfg;
	}

	/**
	 * The fields a caller must {@code request} on an {@code olio.pb.book} read before
	 * {@link #resolveEffectiveConfig} can see the book tier at all.
	 * <p>
	 * {@code name}, {@code urn} and the identity fields are projected by default; the two config columns
	 * are not, and a missing config tier fails <b>silently</b> - the merge simply produces the resource
	 * defaults. Callers that project nothing get a valid-looking but wrong effective config, and the
	 * {@code configHash} derived from it is wrong in a way no assertion downstream can see.
	 */
	public static String[] requestFields() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_PB_SLUG,
			OlioFieldNames.FIELD_PB_SD_CONFIG, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG
		};
	}

	/**
	 * Walk the whole §2.4 precedence chain and return the <b>merged effective config</b> - the record
	 * that would actually be sent to the backend, and the one {@link #configHash(BaseRecord)} must be
	 * taken over.
	 * <p>
	 * Order, and why each step is where it is:
	 * <ol>
	 * <li>a fresh {@code olio.sd.config} instance, i.e. the schema defaults (the lowest tier that can
	 * carry the 50 fields the resource says nothing about);</li>
	 * <li>the book tier overlaid with {@link SDUtil#applyOverrides} - sparse-safe, so a book config that
	 * leaves a field unset cannot clobber the schema default with null;</li>
	 * <li>the node's sparse {@code configOverride}, overlaid the same way - highest precedence;</li>
	 * <li>{@link #applyFlux2Defaults} last, filling <b>only</b> the six FLUX.2 knobs still unset. Last
	 * because it must not overwrite an explicit override, and it can only ever fill a hole.</li>
	 * </ol>
	 * <b>Consequence to keep stated (§2.3):</b> because {@code configHash} folds this record in, editing
	 * {@code olio/sd/flux2Defaults.json} invalidates every node in every book. That is intended, and it
	 * is logged at INFO by {@link #applyFlux2Defaults} for exactly that reason.
	 *
	 * @param book the book, read with at least {@link #requestFields()} - may be null
	 * @param node the {@code olio.pb.node} whose {@code configOverride} applies - may be null
	 * @param composite prefer the book's {@code compositeSdConfig} tier
	 * @return a new, fully merged {@code olio.sd.config}; never null
	 */
	public static BaseRecord resolveEffectiveConfig(BaseRecord book, BaseRecord node, boolean composite) {
		BaseRecord effective = null;
		try {
			effective = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		}
		catch(FieldException | ModelNotFoundException e) {
			logger.error("Failed to instantiate " + OlioModelNames.MODEL_SD_CONFIG + ": " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to instantiate " + OlioModelNames.MODEL_SD_CONFIG);
		}

		SDUtil.applyOverrides(effective, bookConfig(book, composite));

		if(node != null && node.hasField(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE)) {
			SDUtil.applyOverrides(effective, parseOverride(node.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE)));
		}

		applyFlux2Defaults(effective);
		return effective;
	}

	/**
	 * Fill the six FLUX.2 knobs from {@code flux2Defaults.json} (then the {@code Flux2Defaults}
	 * constants) wherever the merged record still has nothing.
	 * <p>
	 * Mirrors what {@code SWUtil}/{@code SceneCompositeUtil} do at request-build time - the point of
	 * doing it here as well is that the <i>hash</i> has to see the same value the backend will, or a
	 * resource edit would change the image without changing {@code configHash}.
	 *
	 * @return the number of fields filled
	 */
	public static int applyFlux2Defaults(BaseRecord cfg) {
		if(cfg == null) {
			return 0;
		}
		int filled = 0;
		List<String> names = new ArrayList<>();
		for(String[] pair : FLUX2_DEFAULTED_FIELDS) {
			String field = pair[0];
			if(!cfg.hasField(field)) {
				continue;
			}
			Object cur = cfg.get(field);
			if(cur != null && !(cur instanceof Number && ((Number) cur).doubleValue() <= 0d)) {
				continue;
			}
			try {
				cfg.set(field, flux2Default(pair[1]));
				names.add(field);
				filled++;
			}
			catch(FieldException | ModelNotFoundException | org.cote.accountmanager.exceptions.ValueException e) {
				logger.error("Failed to apply the FLUX.2 default for " + field + ": " + e.getMessage(), e);
			}
		}
		if(filled > 0) {
			/// Loud on purpose: these values come from a resource file, and because configHash folds the
			/// merged config in, editing that file marks every node in every book stale.
			logger.info("Applied " + filled + " FLUX.2 resource default(s) to the effective config: " + names
				+ " - a change to olio/sd/flux2Defaults.json changes configHash for every node that folds these in");
		}
		return filled;
	}

	private static Object flux2Default(String resourceKey) {
		switch(resourceKey) {
			case "cfgScale":             return Double.valueOf(Flux2Defaults.cfgScale());
			case "steps":                return Integer.valueOf(Flux2Defaults.steps());
			case "width":                return Integer.valueOf(Flux2Defaults.width());
			case "height":               return Integer.valueOf(Flux2Defaults.height());
			case "referenceSize":        return Integer.valueOf(Flux2Defaults.referenceSize());
			case "includeLandscapeRef":  return Boolean.valueOf(Flux2Defaults.includeLandscapeRef());
			default:
				throw new PictureBookException(500, "Unmapped FLUX.2 default key " + resourceKey);
		}
	}

	// ───────────────────────────── hashing ─────────────────────────────

	/**
	 * Stable SHA-256 over the <b>merged effective</b> config from
	 * {@link #resolveEffectiveConfig(BaseRecord, BaseRecord, boolean)} - never over the sparse override.
	 * §2.3 is explicit about that: the hash has to see what the backend will see.
	 *
	 * @return the lower-case hex digest, or null when {@code effective} is null
	 */
	public static String configHash(BaseRecord effective) {
		if(effective == null) {
			return null;
		}
		return sha256Hex(canonicalConfig(effective));
	}

	/**
	 * The exact string {@link #configHash(BaseRecord)} hashes. Public so a test can pin it, and so a
	 * hash mismatch can be diagnosed by diffing two canonical strings rather than two digests.
	 * <p>
	 * Iterates the <b>schema's</b> field order rather than the record's, and renders a field the record
	 * does not carry as {@link #NULL_TOKEN}. So a projected record and a fully materialised one with the
	 * same values canonicalise identically, and field order cannot drift with serialisation.
	 */
	public static String canonicalConfig(BaseRecord effective) {
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
		Set<String> skip = new HashSet<>(CONFIG_HASH_EXCLUDE);
		StringBuilder sb = new StringBuilder();
		sb.append("sdConfig/v1");
		for(FieldSchema fs : ms.getFields()) {
			String n = fs.getName();
			if(skip.contains(n) || fs.isVirtual() || fs.isEphemeral() || fs.isIdentity()) {
				continue;
			}
			Object v = (effective != null && effective.hasField(n) ? effective.get(n) : null);
			sb.append(PAIR_SEPARATOR).append(n).append('=').append(token(v));
		}
		return sb.toString();
	}

	/**
	 * Canonical rendering of one value, shared with {@code PbGraphUtil.computeInputHash} so the two
	 * hashes cannot drift apart on the null and floating-point conventions.
	 * <p>
	 * <b>Locale-free by construction</b>, which is the whole reason it exists as a method:
	 * {@code String.format}, {@code toLowerCase()} and {@code toUpperCase()} are all locale-sensitive
	 * (Turkish dotless-i being the classic case), and a hash that changes with the JVM's default locale
	 * would mark every node in every book stale on a differently-configured host.
	 * <ul>
	 * <li>null &rarr; {@link #NULL_TOKEN}, never {@code ""} and never {@code "null"};</li>
	 * <li>double/float &rarr; {@code BigDecimal.valueOf(..).stripTrailingZeros().toPlainString()}, so
	 * {@code 2.0} and {@code 2.00} render identically and no exponent notation can appear;</li>
	 * <li>everything else &rarr; its own locale-independent {@code toString}.</li>
	 * </ul>
	 */
	public static String token(Object v) {
		if(v == null) {
			return NULL_TOKEN;
		}
		if(v instanceof Double || v instanceof Float) {
			return doubleToken(((Number) v).doubleValue());
		}
		if(v instanceof ZonedDateTime) {
			return ((ZonedDateTime) v).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
		}
		if(v instanceof Collection) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for(Object o : (Collection<?>) v) {
				if(!first) {
					sb.append('|');
				}
				sb.append(token(o));
				first = false;
			}
			return sb.append(']').toString();
		}
		if(v instanceof BaseRecord) {
			/// A nested record has no canonical order of its own here; hash its own canonical form
			/// instead of its toString, which varies with what happens to be materialised.
			return sha256Hex(((BaseRecord) v).getSchema() + PAIR_SEPARATOR + ((BaseRecord) v).hash());
		}
		return v.toString();
	}

	/** {@link #token(Object)}'s floating-point rule, exposed for callers holding a primitive double. */
	public static String doubleToken(double d) {
		return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
	}

	/**
	 * SHA-256 of {@code s} encoded as <b>explicit UTF-8</b>, hex-encoded lower-case without
	 * {@code String.format} (which is locale-sensitive).
	 * <p>
	 * A fresh {@code MessageDigest} per call: the instance is stateful and not thread-safe, and this is
	 * reached from request threads.
	 */
	public static String sha256Hex(String s) {
		if(s == null) {
			return null;
		}
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance(HASH_ALGORITHM);
		}
		catch(NoSuchAlgorithmException e) {
			/// SHA-256 is mandatory in every conformant JRE, so this is a broken platform, not a data error
			throw new PictureBookException(500, HASH_ALGORITHM + " is unavailable: " + e.getMessage());
		}
		byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for(byte b : digest) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	/** Field type of a declared {@code olio.sd.config} field, for callers assembling an override. */
	public static FieldEnumType configFieldType(String fieldName) {
		FieldSchema fs = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG).getFieldSchema(fieldName);
		return (fs != null ? fs.getFieldType() : null);
	}
}
