package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbPageFormatEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.LibraryUtil;

/**
 * PictureBook V3 §4.1 style inheritance and lock resolution (P2-3; ⚠ §6.C 156).
 * <p>
 * {@link #resolve(BaseRecord, BaseRecord)} walks a book's style chain <b>as the caller, reading only,
 * writing nothing and throwing nothing</b>: {@code book.style} → its {@code parent} chain →
 * {@code book.series.style} → its {@code parent} chain → the organization's LIBRARY style
 * ({@code /Library/PictureBookStyles}, first by id, 156 c) → its {@code parent} chain → none. Every hop
 * is read through {@link PbStyleUtil#readStyle} ({@code AccessPoint.find}), so a hop the caller may not
 * read ends the walk and is reported as {@link EffectiveStyle#truncatedAt()}; a revisited style ends
 * it as {@link EffectiveStyle#cycleDetected()}; more than {@link #MAX_DEPTH} hops is a truncation too.
 * {@code find} authorizes the style, not the config it names, so the nearest {@code sdConfig} is read
 * separately as the caller and, when absent or unreadable, is a truncation at {@code olio.sd.config}
 * ({@link EffectiveStyle#truncatedModel()}) — never silently "no style default".
 * An incomplete chain is a narrowed view and is never cached or persisted from
 * ({@code PbStaleUtil}, 156 h).
 * <p>
 * Per-field <b>nearest-set-wins</b> (§4.1): strings are "set" when {@code NarrativeUtil.isMeaningful},
 * enums when present and not {@code UNKNOWN}, {@code customWidth}/{@code customHeight} when {@code > 0},
 * {@code sdConfig} when its FK id is {@code > 0} (nearest <i>whole</i> config, 156 b — read fully as the
 * caller; a deleted or unreadable config truncates the chain, see above); {@code taskModels} merges <b>per key</b>, child key
 * wins (156 a); {@code photoreal} is a BOOLEAN column with DDL default {@code false}, so "unset" is
 * unobservable and the nearest hop always wins (documented limitation). {@code locked} never pins a value:
 * {@link EffectiveStyle#lockedBy()} is the nearest locked hop and only the write path consults it (156 d).
 * <p>
 * The §29 no-style fallbacks are {@link #DEFAULT_QUALITY_PRESET} and {@link #DEFAULT_PAGE_FORMAT} (156 l).
 */
public final class PbStyleResolver {
	public static final Logger logger = LogManager.getLogger(PbStyleResolver.class);

	/** Maximum number of style hops in one chain (book + parents + series + parents + library + parents). */
	public static final int MAX_DEPTH = 8;

	/** §29: a book with no style renders at STANDARD quality … */
	public static final PbQualityPresetEnumType DEFAULT_QUALITY_PRESET = PbQualityPresetEnumType.STANDARD;

	/** … on a SQUARE page. */
	public static final PbPageFormatEnumType DEFAULT_PAGE_FORMAT = PbPageFormatEnumType.SQUARE;

	/** Directory under {@code /Library} holding LIBRARY-scope styles (156 c). Never created by a read. */
	public static final String LIBRARY_STYLES = "PictureBookStyles";

	/**
	 * The §4.1 style-field set: what a lock protects and what a {@code PUT /{book}/style} may change. Not
	 * {@code name}, {@code scope}, {@code parent} or {@code locked} — those are the style's own identity
	 * and wiring, not its look.
	 */
	public static final List<String> STYLE_FIELDS = Collections.unmodifiableList(Arrays.asList(
		OlioFieldNames.FIELD_PB_SD_CONFIG,
		FieldNames.FIELD_DESCRIPTION,
		OlioFieldNames.FIELD_PB_EXCLUSIONS,
		OlioFieldNames.FIELD_PB_PALETTE,
		OlioFieldNames.FIELD_PB_TASK_MODELS,
		OlioFieldNames.FIELD_PB_PAGE_FORMAT,
		OlioFieldNames.FIELD_PB_CUSTOM_WIDTH,
		OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT,
		OlioFieldNames.FIELD_PB_QUALITY_PRESET,
		OlioFieldNames.FIELD_PB_PHOTOREAL
	));

	/** The non-config style fields that form the {@code style=} component of {@code inputHash} (156 g). */
	static final List<String> HASHED_FIELDS = Collections.unmodifiableList(Arrays.asList(
		FieldNames.FIELD_DESCRIPTION,
		OlioFieldNames.FIELD_PB_EXCLUSIONS,
		OlioFieldNames.FIELD_PB_PALETTE,
		OlioFieldNames.FIELD_PB_TASK_MODELS,
		OlioFieldNames.FIELD_PB_PAGE_FORMAT,
		OlioFieldNames.FIELD_PB_CUSTOM_WIDTH,
		OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT,
		OlioFieldNames.FIELD_PB_QUALITY_PRESET,
		OlioFieldNames.FIELD_PB_PHOTOREAL
	));

	private PbStyleResolver() {
		/// static utility
	}

	public static boolean isStyleField(String name) {
		return name != null && STYLE_FIELDS.contains(name);
	}

	// ─────────────────────────────── result ───────────────────────────────

	/**
	 * The resolved chain and the per-field winners. Immutable once built; {@link #sdConfig()} is the
	 * nearest hop's config read fully as the user who resolved, so a holder must not be shared across
	 * users (a narrower reader's view would be served to the owner).
	 */
	public static final class EffectiveStyle {
		private final List<BaseRecord> chain;
		private final Map<String, Object> values;
		private final Map<String, BaseRecord> sources;
		private final Map<PbModelTaskEnumType, String> taskModels;
		private final Map<PbModelTaskEnumType, BaseRecord> taskModelSources;
		private final BaseRecord sdConfig;
		private final BaseRecord sdConfigSource;
		private final BaseRecord lockedBy;
		private final String truncatedAt;
		private final String truncatedModel;
		private final boolean cycleDetected;

		private EffectiveStyle(List<BaseRecord> chain, Map<String, Object> values, Map<String, BaseRecord> sources,
				Map<PbModelTaskEnumType, String> taskModels, Map<PbModelTaskEnumType, BaseRecord> taskModelSources,
				BaseRecord sdConfig, BaseRecord sdConfigSource, BaseRecord lockedBy,
				String truncatedAt, String truncatedModel, boolean cycleDetected) {
			this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
			this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
			this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
			this.taskModels = (taskModels.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(new EnumMap<>(taskModels)));
			this.taskModelSources = (taskModelSources.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(new EnumMap<>(taskModelSources)));
			this.sdConfig = sdConfig;
			this.sdConfigSource = sdConfigSource;
			this.lockedBy = lockedBy;
			this.truncatedAt = truncatedAt;
			this.truncatedModel = truncatedModel;
			this.cycleDetected = cycleDetected;
		}

		/** The hops that were read, nearest first (the book's own style at index 0 when it has one). */
		public List<BaseRecord> chain() { return chain; }

		/** True when no hop was read at all — the §29 fallbacks apply and nothing enters {@code inputHash}. */
		public boolean isEmpty() { return chain.isEmpty(); }

		/**
		 * The nearest set value of a style field (see the class comment for "set"), or null when no hop
		 * sets it. {@code taskModels} answers the merged map's canonical JSON; {@code sdConfig} answers the
		 * nearest FK <i>id</i> — use {@link #sdConfig()} for the record.
		 */
		public Object value(String field) { return values.get(field); }

		/** The hop that supplied {@link #value(String)}, or null. For {@code taskModels}: the nearest hop that sets any key. */
		public BaseRecord source(String field) { return sources.get(field); }

		/** Task → checkpoint, merged per key with the nearest hop winning (156 a). Never null. */
		public Map<PbModelTaskEnumType, String> taskModels() { return taskModels; }

		/** The hop that pinned {@code task}, or null. */
		public BaseRecord taskModelSource(PbModelTaskEnumType task) { return taskModelSources.get(task); }

		/**
		 * The nearest hop's {@code olio.sd.config}, fully read as the resolving user; null when no hop
		 * names one, or the nearest one that does was deleted or is unreadable (both "no style default").
		 */
		public BaseRecord sdConfig() { return sdConfig; }

		/** The hop whose {@code sdConfig} reference won (even when the config itself could not be read). */
		public BaseRecord sdConfigSource() { return sdConfigSource; }

		/** The nearest locked hop (the book's own style included), or null. Never pins a value. */
		public BaseRecord lockedBy() { return lockedBy; }

		/** objectId (or {@code id:<n>}) of the hop that could not be read or exceeded {@link #MAX_DEPTH}; null when none. */
		public String truncatedAt() { return truncatedAt; }

		/** The model of the truncating hop ({@code olio.pb.style} or {@code olio.pb.series}); null when none. */
		public String truncatedModel() { return truncatedModel; }

		public boolean cycleDetected() { return cycleDetected; }

		/** A complete chain: every hop readable, no cycle, within depth. Only a complete chain may be cached or persisted from. */
		public boolean isComplete() { return truncatedAt == null && !cycleDetected; }

		public PbQualityPresetEnumType qualityPreset() {
			Object v = values.get(OlioFieldNames.FIELD_PB_QUALITY_PRESET);
			PbQualityPresetEnumType p = enumOf(PbQualityPresetEnumType.class, v);
			return (p != null && p != PbQualityPresetEnumType.UNKNOWN ? p : DEFAULT_QUALITY_PRESET);
		}

		public PbPageFormatEnumType pageFormat() {
			Object v = values.get(OlioFieldNames.FIELD_PB_PAGE_FORMAT);
			PbPageFormatEnumType f = enumOf(PbPageFormatEnumType.class, v);
			return (f != null && f != PbPageFormatEnumType.UNKNOWN ? f : DEFAULT_PAGE_FORMAT);
		}

		/**
		 * The {@code style=} hash component (156 g): the effective non-config style fields, canonically
		 * rendered with {@link PbConfigUtil#token(Object)}. Values only — which style supplied them is not
		 * hashed, so swapping to a style with identical values re-renders nothing. The config values travel
		 * through the config tier and are already inside {@code configHash}; the runtime checkpoint pick is
		 * never hashed. Empty string for an empty chain.
		 */
		public String canonicalStyle() {
			if(chain.isEmpty()) {
				return "";
			}
			StringBuilder sb = new StringBuilder();
			for(String f : HASHED_FIELDS) {
				if(sb.length() > 0) {
					sb.append(PbConfigUtil.PAIR_SEPARATOR);
				}
				sb.append(f).append('=').append(PbConfigUtil.token(values.get(f)));
			}
			return sb.toString();
		}

		@Override
		public String toString() {
			List<String> ids = new ArrayList<>();
			for(BaseRecord s : chain) {
				ids.add(s.get(FieldNames.FIELD_NAME) + "/" + s.get(FieldNames.FIELD_OBJECT_ID));
			}
			return "EffectiveStyle{chain=" + ids + ", truncatedAt=" + truncatedAt + ", cycle=" + cycleDetected
				+ ", lockedBy=" + (lockedBy != null ? lockedBy.get(FieldNames.FIELD_OBJECT_ID) : null) + "}";
		}
	}

	private static final EffectiveStyle EMPTY = new EffectiveStyle(Collections.emptyList(), Collections.emptyMap(),
		Collections.emptyMap(), new EnumMap<>(PbModelTaskEnumType.class), new EnumMap<>(PbModelTaskEnumType.class),
		null, null, null, null, null, false);

	/** The empty chain: §29 fallbacks, nothing hashed, complete. What {@code resolve(user, null)} returns. */
	public static EffectiveStyle empty() {
		return EMPTY;
	}

	// ─────────────────────────────── resolution ───────────────────────────────

	/**
	 * Resolve the book's effective style as {@code user}. Reads only; never throws; a null or unreadable
	 * input is the empty chain. The book must have been read with {@link PbBookUtil#bookRequest()} (which
	 * projects {@code style} and {@code series}) — a book projected without them resolves as style-less,
	 * which is the same silent bound {@link PbConfigUtil#requestFields()} documents for the config columns.
	 */
	public static EffectiveStyle resolve(BaseRecord user, BaseRecord book) {
		if(user == null || book == null) {
			return EMPTY;
		}
		long orgId = orgOf(book, user);
		Walk w = new Walk(user, orgId);
		try {
			if(book.hasField(OlioFieldNames.FIELD_STYLE)) {
				w.walk(book.get(OlioFieldNames.FIELD_STYLE));
			}
			if(w.open() && book.hasField(OlioFieldNames.FIELD_PB_SERIES)) {
				BaseRecord seriesRef = book.get(OlioFieldNames.FIELD_PB_SERIES);
				if(seriesRef != null) {
					BaseRecord series = readSeries(user, seriesRef, orgId);
					if(series == null) {
						w.truncate(identity(seriesRef), OlioModelNames.MODEL_PB_SERIES);
					}
					else if(series.hasField(OlioFieldNames.FIELD_STYLE)) {
						w.walk(series.get(OlioFieldNames.FIELD_STYLE));
					}
				}
			}
			if(w.open()) {
				w.walkLibrary();
			}
		}
		catch(RuntimeException e) {
			/// A resolver fault must never fail a render or a scan; it is reported as a truncation so
			/// nothing is cached or persisted from the partial chain.
			logger.error("Style resolution failed for book " + book.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage(), e);
			w.truncate("error", OlioModelNames.MODEL_PB_STYLE);
		}
		return w.result();
	}

	/**
	 * Resolve from a style the caller already read (its {@code parent} chain, then the library), with no
	 * book and no series — what a style-rooted read uses. Null is the empty chain.
	 */
	public static EffectiveStyle resolveFromStyle(BaseRecord user, BaseRecord style) {
		if(user == null || style == null) {
			return EMPTY;
		}
		long orgId = orgOf(style, user);
		Walk w = new Walk(user, orgId);
		try {
			w.walk(style);
			if(w.open()) {
				w.walkLibrary();
			}
		}
		catch(RuntimeException e) {
			logger.error("Style resolution failed for style " + style.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage(), e);
			w.truncate("error", OlioModelNames.MODEL_PB_STYLE);
		}
		return w.result();
	}

	/**
	 * The organization's LIBRARY style, if any: {@code /Library/PictureBookStyles} found (never created)
	 * through {@link ChatLibraryUtil#findLibraryDir}, then {@code olio.pb.style} rows in that group with
	 * {@code scope = LIBRARY}, first by id, re-read through {@link PbStyleUtil#readStyle} as the caller
	 * (156 c). Returns null when there is no directory or no such style; returns a reference with
	 * {@code objectId} only (and {@code readable=false} is signalled by a null from the re-read, which the
	 * walk reports as a truncation).
	 */
	static BaseRecord findLibraryStyleRef(BaseRecord user, long orgId) {
		BaseRecord dir = findLibraryDir(user, orgId);
		if(dir == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_STYLE, FieldNames.FIELD_GROUP_ID, (long) dir.get(FieldNames.FIELD_ID));
		q.field(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.LIBRARY.toString());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME});
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setRequestRange(0L, 1);
		q.setCache(false);
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		BaseRecord[] recs = (qr != null ? qr.getResults() : null);
		return (recs != null && recs.length > 0 ? recs[0] : null);
	}

	/**
	 * Find-only lookup of the library directory. Goes through {@link ChatLibraryUtil#findLibraryDir} when
	 * the user record carries {@code organizationPath} (what that helper keys on); otherwise resolves the
	 * organization context by id and runs the same {@code findPath} — never {@code getCreateSharedLibrary}.
	 */
	static BaseRecord findLibraryDir(BaseRecord user, long orgId) {
		try {
			String orgPath = (user.hasField(FieldNames.FIELD_ORGANIZATION_PATH) ? user.get(FieldNames.FIELD_ORGANIZATION_PATH) : null);
			if(orgPath != null && !orgPath.isBlank()) {
				return ChatLibraryUtil.findLibraryDir(user, LIBRARY_STYLES);
			}
			IOContext ctx = IOSystem.getActiveContext();
			OrganizationContext octx = ctx.findOrganizationContext(user);
			if(octx == null || octx.getAdminUser() == null) {
				return null;
			}
			return ctx.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_GROUP, LibraryUtil.basePath + "/" + LIBRARY_STYLES,
				GroupEnumType.DATA.toString(), (orgId > 0L ? orgId : octx.getOrganizationId()));
		}
		catch(RuntimeException e) {
			logger.warn("Library style directory lookup failed: " + e.getMessage());
			return null;
		}
	}

	private static BaseRecord readSeries(BaseRecord user, BaseRecord ref, long orgId) {
		String oid = (ref.hasField(FieldNames.FIELD_OBJECT_ID) ? ref.get(FieldNames.FIELD_OBJECT_ID) : null);
		if(oid == null || oid.isBlank()) {
			return null;
		}
		return PbSeriesUtil.readSeries(user, oid, orgId);
	}

	// ─────────────────────────────── the walk ───────────────────────────────

	private static final class Walk {
		private final BaseRecord user;
		private final long orgId;
		private final List<BaseRecord> chain = new ArrayList<>();
		private final Set<String> visited = new LinkedHashSet<>();
		private String truncatedAt;
		private String truncatedModel;
		private boolean cycle;

		Walk(BaseRecord user, long orgId) {
			this.user = user;
			this.orgId = orgId;
		}

		boolean open() {
			return truncatedAt == null && !cycle;
		}

		void truncate(String at, String model) {
			if(open()) {
				truncatedAt = at;
				truncatedModel = model;
			}
		}

		/** Follow {@code ref} and its parents until null, a cycle, an unreadable hop, or the depth cap. */
		void walk(BaseRecord ref) {
			BaseRecord cur = ref;
			while(cur != null && open()) {
				String key = identity(cur);
				if(key == null) {
					return;
				}
				if(visited.contains(key)) {
					cycle = true;
					return;
				}
				if(chain.size() >= MAX_DEPTH) {
					truncate(key, OlioModelNames.MODEL_PB_STYLE);
					return;
				}
				BaseRecord style = (isFullStyle(cur) ? cur : PbStyleUtil.readStyle(user, cur, orgId));
				if(style == null) {
					truncate(key, OlioModelNames.MODEL_PB_STYLE);
					return;
				}
				visited.add(key);
				String oid = style.get(FieldNames.FIELD_OBJECT_ID);
				if(oid != null) {
					visited.add(oid);
				}
				chain.add(style);
				cur = (style.hasField(OlioFieldNames.FIELD_PB_PARENT) ? style.get(OlioFieldNames.FIELD_PB_PARENT) : null);
			}
		}

		void walkLibrary() {
			BaseRecord ref = findLibraryStyleRef(user, orgId);
			if(ref == null) {
				return;
			}
			String key = identity(ref);
			if(key != null && visited.contains(key)) {
				/// Already in the chain through a parent reference - not a cycle, just reached.
				return;
			}
			walk(ref);
		}

		EffectiveStyle result() {
			if(chain.isEmpty() && open()) {
				return EMPTY;
			}
			Map<String, Object> values = new LinkedHashMap<>();
			Map<String, BaseRecord> sources = new LinkedHashMap<>();
			Map<PbModelTaskEnumType, String> taskModels = new EnumMap<>(PbModelTaskEnumType.class);
			Map<PbModelTaskEnumType, BaseRecord> taskModelSources = new EnumMap<>(PbModelTaskEnumType.class);
			BaseRecord lockedBy = null;
			BaseRecord sdConfigSource = null;
			for(BaseRecord s : chain) {
				for(String f : STYLE_FIELDS) {
					if(OlioFieldNames.FIELD_PB_TASK_MODELS.equals(f) || OlioFieldNames.FIELD_PB_SD_CONFIG.equals(f)) {
						continue;
					}
					if(values.containsKey(f)) {
						continue;
					}
					Object v = setValueOf(s, f);
					if(v != null) {
						values.put(f, v);
						sources.put(f, s);
					}
				}
				if(sdConfigSource == null && PbStyleUtil.sdConfigId(s) > 0L) {
					sdConfigSource = s;
				}
				Map<PbModelTaskEnumType, String> tm = ModelTaskKeys.parseTaskModels(
					s.hasField(OlioFieldNames.FIELD_PB_TASK_MODELS) ? s.get(OlioFieldNames.FIELD_PB_TASK_MODELS) : null);
				for(Map.Entry<PbModelTaskEnumType, String> e : tm.entrySet()) {
					if(!taskModels.containsKey(e.getKey())) {
						taskModels.put(e.getKey(), e.getValue());
						taskModelSources.put(e.getKey(), s);
					}
				}
				if(lockedBy == null && s.hasField(OlioFieldNames.FIELD_PB_LOCKED) && Boolean.TRUE.equals(s.get(OlioFieldNames.FIELD_PB_LOCKED))) {
					lockedBy = s;
				}
			}
			if(!chain.isEmpty()) {
				/// photoreal: BOOLEAN with DDL default false - unset is unobservable, nearest hop wins.
				BaseRecord own = chain.get(0);
				Object pr = (own.hasField(OlioFieldNames.FIELD_PB_PHOTOREAL) ? own.get(OlioFieldNames.FIELD_PB_PHOTOREAL) : null);
				values.put(OlioFieldNames.FIELD_PB_PHOTOREAL, Boolean.TRUE.equals(pr));
				sources.put(OlioFieldNames.FIELD_PB_PHOTOREAL, own);
			}
			if(!taskModels.isEmpty()) {
				values.put(OlioFieldNames.FIELD_PB_TASK_MODELS, canonicalTaskModels(taskModels));
				BaseRecord first = null;
				for(BaseRecord s : chain) {
					if(taskModelSources.containsValue(s)) {
						first = s;
						break;
					}
				}
				sources.put(OlioFieldNames.FIELD_PB_TASK_MODELS, first);
			}
			BaseRecord sdConfig = null;
			if(sdConfigSource != null) {
				values.put(OlioFieldNames.FIELD_PB_SD_CONFIG, PbStyleUtil.sdConfigId(sdConfigSource));
				sources.put(OlioFieldNames.FIELD_PB_SD_CONFIG, sdConfigSource);
				sdConfig = PbStyleUtil.readSdConfig(user, sdConfigSource);
				if(sdConfig == null) {
					/// find authorizes the style, not the config it points at, so a readable style can name a
					/// config this user cannot read. The config tier is then unknown to this reader: the chain is
					/// incomplete, never "no style default" (a narrower view that hashes differently and must not
					/// render or persist from). A DELETED config does not reach here - its reference projects as
					/// id 0 (PbStyleUtil.readSdConfig), so sdConfigSource stays null and the chain resolves with no
					/// config, the hash moves, and the nodes go STALE (§6.C 160). This branch is the unreadable
					/// case, plus the race of a config deleted between the projection and this read.
					String cid = String.valueOf(PbStyleUtil.sdConfigId(sdConfigSource));
					logger.warn("Style " + sdConfigSource.get(FieldNames.FIELD_NAME) + " references sdConfig #" + cid
						+ " which this user cannot read (or which was deleted mid-resolve) - chain incomplete");
					truncate(cid, OlioModelNames.MODEL_SD_CONFIG);
				}
			}
			return new EffectiveStyle(chain, values, sources, taskModels, taskModelSources, sdConfig, sdConfigSource,
				lockedBy, truncatedAt, truncatedModel, cycle);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/** A record that already carries the projected style fields (vs. a bare FK reference). */
	private static boolean isFullStyle(BaseRecord rec) {
		return rec != null && OlioModelNames.MODEL_PB_STYLE.equals(rec.getSchema())
			&& rec.hasField(OlioFieldNames.FIELD_PB_SCOPE) && rec.hasField(OlioFieldNames.FIELD_PB_LOCKED)
			&& rec.hasField(OlioFieldNames.FIELD_PB_PARENT);
	}

	static String identity(BaseRecord ref) {
		if(ref == null) {
			return null;
		}
		String oid = (ref.hasField(FieldNames.FIELD_OBJECT_ID) ? ref.get(FieldNames.FIELD_OBJECT_ID) : null);
		if(oid != null && !oid.isBlank()) {
			return oid;
		}
		if(ref.hasField(FieldNames.FIELD_ID) && ref.get(FieldNames.FIELD_ID) != null) {
			long id = ((Number) ref.get(FieldNames.FIELD_ID)).longValue();
			return (id > 0L ? "id:" + id : null);
		}
		return null;
	}

	/**
	 * The field's value when the style "sets" it per the class comment, else null. {@code sdConfig},
	 * {@code taskModels} and {@code photoreal} are handled by the walk, not here.
	 */
	static Object setValueOf(BaseRecord style, String field) {
		if(style == null || !style.hasField(field)) {
			return null;
		}
		Object v = style.get(field);
		if(v == null) {
			return null;
		}
		switch(field) {
			case OlioFieldNames.FIELD_PB_CUSTOM_WIDTH:
			case OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT:
				return (v instanceof Number && ((Number) v).intValue() > 0 ? Integer.valueOf(((Number) v).intValue()) : null);
			case OlioFieldNames.FIELD_PB_PAGE_FORMAT:
			case OlioFieldNames.FIELD_PB_QUALITY_PRESET: {
				String s = v.toString().trim();
				return (!s.isEmpty() && !"UNKNOWN".equalsIgnoreCase(s) ? s.toUpperCase() : null);
			}
			default:
				return (v instanceof String && NarrativeUtil.isMeaningful((String) v) ? ((String) v).trim() : null);
		}
	}

	/** Canonical JSON of a task map: keys lower-camel (§16), sorted, no whitespace. Stable for hashing. */
	public static String canonicalTaskModels(Map<PbModelTaskEnumType, String> taskModels) {
		if(taskModels == null || taskModels.isEmpty()) {
			return null;
		}
		TreeMap<String, String> sorted = new TreeMap<>();
		for(Map.Entry<PbModelTaskEnumType, String> e : taskModels.entrySet()) {
			if(e.getKey() == null || e.getKey() == PbModelTaskEnumType.UNKNOWN || e.getValue() == null) {
				continue;
			}
			sorted.put(ModelTaskKeys.key(e.getKey()), e.getValue());
		}
		if(sorted.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for(Map.Entry<String, String> e : sorted.entrySet()) {
			if(!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(jsonEscape(e.getKey())).append("\":\"").append(jsonEscape(e.getValue())).append('"');
		}
		return sb.append('}').toString();
	}

	private static String jsonEscape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch(c) {
				case '"':  sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if(c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
			}
		}
		return sb.toString();
	}

	static <E extends Enum<E>> E enumOf(Class<E> type, Object v) {
		if(v == null) {
			return null;
		}
		if(type.isInstance(v)) {
			return type.cast(v);
		}
		String s = v.toString().trim();
		for(E e : type.getEnumConstants()) {
			if(e.name().equalsIgnoreCase(s)) {
				return e;
			}
		}
		return null;
	}

	private static long orgOf(BaseRecord rec, BaseRecord user) {
		if(rec != null && rec.hasField(FieldNames.FIELD_ORGANIZATION_ID) && rec.get(FieldNames.FIELD_ORGANIZATION_ID) != null) {
			return ((Number) rec.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		}
		if(user != null && user.hasField(FieldNames.FIELD_ORGANIZATION_ID) && user.get(FieldNames.FIELD_ORGANIZATION_ID) != null) {
			return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		}
		return 0L;
	}
}
