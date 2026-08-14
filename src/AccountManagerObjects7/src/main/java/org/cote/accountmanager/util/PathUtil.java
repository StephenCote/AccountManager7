package org.cote.accountmanager.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;

/**
 * Hierarchy path resolution (get-or-create) for parent-keyed models such as {@code auth.group},
 * {@code auth.role} and {@code auth.permission}.
 *
 * <p><b>KI-60 is OPEN.</b> The reported condition — a type-filtered per-segment lookup failing to
 * see a row that is present, so the create collides on the
 * {@code (name, parentId, organizationId)} unique constraint — has never reproduced on demand; the
 * {@code TestPathUtilBehavior} characterization suite eliminated the search-cache query-key theory
 * and could only reproduce the type-mismatch variants. Because it cannot be reproduced, it is
 * instrumented instead: every time this class adopts a pre-existing node that its own type-filtered
 * lookup did not return, it emits one structured line tagged with the marker
 * {@value #KI60_WATCH_MARKER}, including a one-shot uncached re-probe of the exact lookup that
 * missed.
 *
 * <p>Grepping {@value #KI60_WATCH_MARKER} in production logs yields:
 * <ul>
 *   <li>{@code trigger=} which branch adopted (pre-create conflict detection, or the KI-42
 *       write-lost recovery);</li>
 *   <li>the requested model/name/parent/org plus BOTH the requested type and the effective lookup
 *       type (they differ when the home/owner segment override fires);</li>
 *   <li>the adopted row's id, name, parent, org, type and urn;</li>
 *   <li>{@code ANOMALY} — an ERROR-level escalation — when the adopted row's NAME differs from the
 *       requested segment name. That is KI-60's exact reported signature (a 'Narratives' request
 *       answered with an 'Apparel' row) and must never pass unremarked;</li>
 *   <li>the uncached re-probe verdict, in words: if the uncached read FINDS the row the cached
 *       lookup missed, the search cache is implicated after all (which would contradict the
 *       characterization suite); if it ALSO MISSES, the miss is below the cache in the query/DB
 *       layer.</li>
 * </ul>
 * The watch is read-only: it never writes, creates or repairs anything, it costs one extra read on
 * an already-rare branch, and it never alters the outcome of the operation it observes.
 */
public abstract class PathUtil implements IPath {

	public static final Logger logger = LogManager.getLogger(PathUtil.class);

	/** Greppable marker for the KI-60 collision-recovery watch. See the class javadoc. */
	public static final String KI60_WATCH_MARKER = "KI60-WATCH";

	/** The pre-create conflict check found a row the type-filtered lookup did not return. */
	private static final String TRIGGER_PRECREATE = "pre-create-conflict";

	/** The insert lost (KI-42) and the constraint-key re-read adopted the winner. */
	private static final String TRIGGER_WRITE_LOST = "write-lost-recovery";

	private final IReader reader;
	private final IWriter writer;
	private final ISearch search;
	private boolean trace = false;

	public PathUtil(IReader reader, ISearch search) {
		this(reader, null, search);
	}
	public PathUtil(IReader reader, IWriter writer, ISearch search) {
		this.reader = reader;
		this.writer = writer;
		this.search = search;
	}



	public void clearCache() {
		// no-op
	}

	public boolean isTrace() {
		return trace;
	}
	public void setTrace(boolean trace) {
		this.trace = trace;
	}
	public BaseRecord findPath(BaseRecord owner, String model, String path, String type, long organizationId) {
		return makePath(owner, model, path, type, organizationId, false);
	}

	/// Synchronized make path - when concurrent sessions hit the hierarchical create method, it's possible that the same object can be created twice, violating any constraint condition
	/// This in turn MAY result in a corrupted cache entry (still looking into that currency issue)
	///
	public synchronized BaseRecord makePath(BaseRecord owner, String model, String path, String type, long organizationId) {
		return makePath(owner, model, path, type, organizationId, true);
	}
	private BaseRecord makePath(BaseRecord owner, String model, String path, String type, long organizationId, boolean doCreate) {
		BaseRecord node = null;

		if(owner != null) {
			IOSystem.getActiveContext().getRecordUtil().populate(owner);
		}

		if(path.startsWith("~/")) {
			if(owner != null) {
				String homePath = owner.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH);
				if(homePath == null || homePath.length() == 0) {
					logger.warn("Invalid home directory path - constructing from owner");
					homePath = "/home/" + owner.get(FieldNames.FIELD_NAME);
				}
				path = expandHomePath(path, homePath);
				if(trace) {
					logger.info("Path: " + path);
				}
			}
			else {
				logger.error("Cannot resolve a relative user path without a user reference");
				return null;
			}
		}

		String[] pathE = path.split("/");
		long parentId = 0L;

		/// Determine if the model uses parentId or groupId hierarchy.
		/// Models like data.data use groupId (directory-based), not parentId (parent-based).
		/// For these models, intermediate path segments must be walked using auth.group,
		/// and only the final segment is searched via findByNameInGroup.
		ModelSchema ms = RecordFactory.getSchema(model);
		boolean modelHasParentId = ms != null && ms.getFieldSchema(FieldNames.FIELD_PARENT_ID) != null;
		boolean modelHasGroupId = !modelHasParentId && ms != null && ms.getFieldSchema(FieldNames.FIELD_GROUP_ID) != null;
		/// Whether the per-segment lookup below is the parent-keyed one on THIS model, which is the
		/// only shape the unique-constraint re-read can speak about.
		boolean parentKeyed = !modelHasGroupId;
		boolean modelHasType = ms != null && ms.getFieldSchema(FieldNames.FIELD_TYPE) != null;
		/// CRITICAL: the constraint is per-model, not universal. auth.group constrains
		/// (name, parentId, organizationId) — type is NOT in it, so a type-filtered lookup can miss a
		/// row the insert then collides with. auth.role and auth.permission constrain
		/// (parentId, name, type, organizationId) — type IS in it, so same-named siblings of
		/// different types are LEGAL and a type-less re-read there would hijack an unrelated row.
		boolean typeInConstraint = constraintIncludesType(ms);

		/// Pre-collect non-empty segments to know which is the last one
		String[] segments = java.util.Arrays.stream(pathE).filter(e -> e != null && e.length() > 0).toArray(String[]::new);

		try {
			for(int si = 0; si < segments.length; si++) {
				String e = segments[si];
				String utype = type;
				boolean isLastSegment = (si == segments.length - 1);

				/// When trying to get type specific paths, allow to build off a singular base such as /home/{name} vs. duplicating /home/{name}
				/// TODO: This needs to be configurable because it would also be helpful in the Community layout
				///
				if(owner != null && (e.equals("home") || e.equals(owner.get(FieldNames.FIELD_NAME)))) {
					if(model.equals(ModelNames.MODEL_GROUP)) {
						utype = "DATA";
					}
					else if(model.equals(ModelNames.MODEL_PERMISSION) || model.equals(ModelNames.MODEL_ROLE)) {
						utype = "USER";
					}
				}

				BaseRecord[] nodes;
				if(modelHasGroupId && !isLastSegment) {
					/// Intermediate segments: walk auth.group hierarchy to find the container group
					nodes = search.findByNameInParent(ModelNames.MODEL_GROUP, parentId, e, "DATA", organizationId);
				} else if(modelHasGroupId) {
					/// Final segment: find the model object within the resolved group
					nodes = search.findByNameInGroup(model, parentId, e, organizationId);
				} else {
					nodes = search.findByNameInParent(model, parentId, e, utype, organizationId);
				}
				if(trace) {
					logger.info("Found " + nodes.length + " " + model + " named " + e + " in #" + parentId);
				}
				if(nodes.length == 0) {
					if(trace) {
						logger.info("Create in parent #" + parentId);
					}
					if(!doCreate) {
						if(trace) {
							logger.warn("Failed to find '" + e + "' " + (type != null ? "of type (" + type + ") " : "") + "in parent " + parentId + " in path " + path + ", create = false");
						}
						node = null;
						break;
					}
					else {
						/// The unique constraint on a parent-keyed hierarchy node is
						/// (name, parentId, organizationId) and does NOT include type, while the lookup
						/// above IS type-filtered. So a type-filtered miss does not mean the row is
						/// absent — it may only mean the row that is there carries another type, in
						/// which case the insert below is GUARANTEED to collide. Left undetected that
						/// collision is re-attempted on every single call (KI-60's "the duplicate-key
						/// INSERT is still ATTEMPTED on every run"), each attempt burning a sequence
						/// value and logging a duplicate-key SQLException.
						///
						/// Re-read on the constraint's own key BEFORE writing. This is one extra read on
						/// the create branch only — the resolve/hit path is untouched — and it replaces a
						/// failed INSERT + exception with a plain SELECT.
						/// Only for models whose unique constraint EXCLUDES type. Where type is part of
						/// the constraint (auth.role, auth.permission) a same-named sibling of another
						/// type is legal, the insert below will not collide with it, and adopting it
						/// would hand back an unrelated node.
						BaseRecord conflict = null;
						if(parentKeyed && modelHasType && utype != null && !typeInConstraint) {
							conflict = findExistingNode(model, parentId, e, null, organizationId);
						}
						if(conflict != null) {
							node = watchAndAdopt(TRIGGER_PRECREATE, model, path, si, e, parentId,
								organizationId, type, utype, conflict);
							parentId = node.get(FieldNames.FIELD_ID);
							continue;
						}

						node = RecordFactory.model(model).newInstance();
						node.set(FieldNames.FIELD_NAME, e);
						node.set(FieldNames.FIELD_PARENT_ID, parentId);
						node.set(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
						/// Write the EFFECTIVE type — the same one the lookup above used. When the
						/// home/owner segment override at the top of this loop forces a structural type
						/// (DATA for auth.group, USER for auth.role/auth.permission), the created node
						/// must carry that same type or this code can never find its own node again: the
						/// next resolution looks for the override type, misses, and re-attempts an insert
						/// that collides on (name, parentId, organizationId).
						if(utype != null && node.hasField(FieldNames.FIELD_TYPE)) {
							node.set(FieldNames.FIELD_TYPE, utype);
						}
						if(owner != null) {
							node.set(FieldNames.FIELD_OWNER_ID, owner.get(FieldNames.FIELD_ID));
						}

						writer.translate(RecordOperation.READ, node);
						PolicyResponseType prr = null;
						if(IOSystem.getActiveContext().isEnforceAuthorization()
							&& (
								owner == null
								||
								(prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(owner, PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT, owner, node)).getType() != PolicyResponseEnumType.PERMIT)
						) {
							logger.error("Not authorized to create " + model + " " + (type != null ? "of type (" + type + ") " : "") + "node " + e + " with parent #" + parentId + " in path " + path);
							return null;
						}

						boolean wrote = writer.write(node);
						writer.flush();
						if(!wrote) {
							/// KI-42. The write LOST — overwhelmingly because the row it was trying to
							/// create already exists: the unique constraint is (name, parentId,
							/// organizationId) and does NOT include type, so a type-filtered lookup can
							/// miss a row the insert then collides with.
							///
							/// This must not be ignored. DBWriter catches the SQLException, logs it and
							/// returns 0 — but it has ALREADY stamped a sequence-allocated id onto the
							/// in-memory record. Reading that id back yields an ordinary-looking group
							/// whose id matches no row in the database, and every record subsequently
							/// persisted against it fails PBAC with "Group could not be found: <id>".
							/// That is how a PictureBook character silently vanishes from a book, and
							/// why the reported id differed on every run while the collision key did
							/// not — each run burns a fresh sequence value on an insert that never lands.
							///
							/// A get-or-create has to be robust to its create losing, so re-read on the
							/// constraint's OWN key (name + parent + org, no type filter) and adopt the
							/// winner. Only if nothing is there is this a genuine failure, and then the
							/// caller must see null rather than a phantom.
							/// Re-read on the constraint's OWN key: type-less where type is not part of
							/// the constraint (auth.group), type-filtered where it is (auth.role,
							/// auth.permission) — otherwise the re-read can return a legal same-named
							/// sibling of another type, or several of them.
							BaseRecord lostTo = findExistingNode(model, parentId, e,
								(typeInConstraint ? utype : null), organizationId);
							if(lostTo == null) {
								logger.error("Failed to write " + model + " node " + e + " with parent #" + parentId
									+ " in path " + path + ", and no existing record could be resolved for it");
								return null;
							}
							logger.warn("Write of " + model + " node " + e + " in parent #" + parentId
								+ " lost to an existing record (#" + lostTo.get(FieldNames.FIELD_ID)
								+ "); adopting it rather than returning an unpersisted node");
							node = watchAndAdopt(TRIGGER_WRITE_LOST, model, path, si, e, parentId,
								organizationId, type, utype, lostTo);
						}
						parentId = node.get(FieldNames.FIELD_ID);
					}
				}
				else if(nodes.length == 1) {
					node = nodes[0];
					parentId = node.get(FieldNames.FIELD_ID);
					if(type == null) {
						type = node.get(FieldNames.FIELD_TYPE);
					}
				}
				else {
					logger.error("Invalid search for " + model + " type " + type + " parent " + parentId + " org " + organizationId + " from '" + e + "' with " + nodes.length + " results");
				}
			}
			if(doCreate) {
				writer.flush();
			}

		}
		catch(ValueException | WriterException | ReaderException | FieldException | ModelNotFoundException e) {
			logger.error(e.getMessage());
			node = null;
		}

		return node;

	}

	/**
	 * Expands a leading {@code "~/"} against the owner's home directory path.
	 *
	 * <p>D1: the expansion used to be a bare textual substitution, so a caller that prefixed
	 * {@code "~/"} onto a path which was ALREADY home-qualified got the whole home tree re-emitted
	 * beneath itself ({@code ~//home/u/X} and {@code ~/home/u/X} both resolved to
	 * {@code /home/u/home/u/X}), silently, with an ordinary non-null group handed back. That input
	 * shape is produced by ordinary round-tripping: {@code PathProvider.provide} and
	 * {@code RecordUtil.resolveUserPath} write the EXPANDED absolute path back, while ~15 production
	 * sites build paths as {@code "~/" + value}.
	 *
	 * <p>Two remainders are therefore treated as already-absolute rather than nested:
	 * <ul>
	 *   <li>{@code ~//...} — a doubled separator means the remainder was itself absolute;</li>
	 *   <li>a remainder that equals the home path or begins with {@code homePath + "/"}.</li>
	 * </ul>
	 * The second test is on the home PREFIX with a separator boundary, not on a segment name, so a
	 * folder genuinely named {@code home} (or named after the user) deeper in a tree still nests
	 * normally: with a home of {@code /home/jane}, {@code ~/home/photos} still resolves to
	 * {@code /home/jane/home/photos}.
	 *
	 * <p>Every normalization that actually fires is logged at WARN naming both the input and the
	 * resolved path, so the offending call site is findable in production logs rather than being
	 * silently corrected.
	 *
	 * @param path the raw path, known to start with {@code "~/"}
	 * @param homePath the owner's absolute home directory path
	 * @return the absolute path to resolve
	 */
	protected static String expandHomePath(String path, String homePath) {
		/// substring(1) keeps the leading separator: "~/A" -> "/A"
		String remainder = path.substring(1);
		String collapsed = collapseSeparators(remainder);
		String home = collapseSeparators(homePath);
		boolean absoluteRemainder = remainder.startsWith("//");
		boolean alreadyHomeQualified = home != null && home.length() > 1
			&& (collapsed.equals(home) || collapsed.startsWith(home + "/"));
		if(absoluteRemainder || alreadyHomeQualified) {
			logger.warn("Normalized a '~/' path whose remainder was already absolute"
				+ (alreadyHomeQualified ? " and already home-qualified" : "")
				+ " - it was NOT nested under the home directory. input=[" + path + "] home=[" + homePath
				+ "] resolved=[" + collapsed + "]. Check the call site: prefixing \"~/\" onto a path that"
				+ " is already resolved (PathProvider/RecordUtil.resolveUserPath write back the expanded"
				+ " absolute path) would otherwise re-emit the whole path beneath itself.");
			return collapsed;
		}
		return home + collapsed;
	}

	/** Collapses runs of separators and drops a trailing separator; "/" is left as-is. */
	private static String collapseSeparators(String value) {
		if(value == null) {
			return null;
		}
		String out = value.replaceAll("/{2,}", "/");
		while(out.length() > 1 && out.endsWith("/")) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	/**
	 * KI-60 watch. Emits one structured, greppable line every time an EXISTING node is adopted that
	 * this class's own type-filtered lookup did not return, then returns that node unchanged.
	 *
	 * <p>Read-only and outcome-preserving by construction: it performs a single extra uncached read
	 * and returns exactly the record it was given. It runs only on the two rare adoption branches
	 * (pre-create conflict detection and the KI-42 write-lost recovery), never on the resolve path.
	 *
	 * @see #KI60_WATCH_MARKER
	 */
	private BaseRecord watchAndAdopt(String trigger, String model, String path, int segmentIndex,
			String name, long parentId, long organizationId, String requestedType, String lookupType,
			BaseRecord adopted) {
		String adoptedName = null;
		if(adopted.hasField(FieldNames.FIELD_NAME)) {
			adoptedName = adopted.get(FieldNames.FIELD_NAME);
		}
		String adoptedType = null;
		if(adopted.hasField(FieldNames.FIELD_TYPE)) {
			Object at = adopted.get(FieldNames.FIELD_TYPE);
			adoptedType = (at == null ? null : at.toString());
		}
		String adoptedUrn = null;
		if(adopted.hasField(FieldNames.FIELD_URN)) {
			adoptedUrn = adopted.get(FieldNames.FIELD_URN);
		}
		long adoptedId = 0L;
		if(adopted.hasField(FieldNames.FIELD_ID)) {
			adoptedId = adopted.get(FieldNames.FIELD_ID);
		}
		long adoptedParent = 0L;
		if(adopted.hasField(FieldNames.FIELD_PARENT_ID)) {
			adoptedParent = adopted.get(FieldNames.FIELD_PARENT_ID);
		}
		long adoptedOrg = 0L;
		if(adopted.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
			adoptedOrg = adopted.get(FieldNames.FIELD_ORGANIZATION_ID);
		}

		boolean typeOverridden = (requestedType == null ? lookupType != null : !requestedType.equals(lookupType));
		boolean nameMismatch = (adoptedName == null || !adoptedName.equals(name));
		boolean typeMismatch = (adoptedType != null && lookupType != null && !adoptedType.equalsIgnoreCase(lookupType));

		String line = KI60_WATCH_MARKER + " trigger=" + trigger
			+ " path=[" + path + "] segment=" + segmentIndex + ":'" + name + "'"
			+ " requested={model=" + model + ", name=" + name + ", parentId=" + parentId
			+ ", organizationId=" + organizationId + ", type=" + requestedType
			+ ", lookupType=" + lookupType + ", typeOverride=" + typeOverridden + "}"
			+ " adopted={id=" + adoptedId + ", name=" + adoptedName + ", parentId=" + adoptedParent
			+ ", organizationId=" + adoptedOrg + ", type=" + adoptedType + ", urn=" + adoptedUrn + "}"
			+ " nameMismatch=" + nameMismatch + " typeMismatch=" + typeMismatch;

		if(nameMismatch) {
			/// KI-60's exact reported signature: a request for one sibling answered with another.
			logger.error(line + " ANOMALY the adopted record is NOT the requested node: asked for '" + name
				+ "' and adopted '" + adoptedName + "' (#" + adoptedId + "). This is the KI-60 signature"
				+ " (#151 'Apparel' returned for a 'Narratives' request).");
		}
		else {
			logger.warn(line);
		}

		if(typeMismatch) {
			/// F3. Unresolvable in code: the unique constraint on this model is
			/// (name, parentId, organizationId) and does NOT include type, so the requested type
			/// cannot be created alongside the row that is already there, and the row that is already
			/// there cannot be retyped (that would be an unauthorized in-place mutation of somebody
			/// else's record from what is nominally a path resolution). The caller therefore receives
			/// a node of a type it did not ask for and MUST check the returned type if it matters.
			logger.error(KI60_WATCH_MARKER + " CONFLICT " + model + " '" + name + "' in parent #" + parentId
				+ " (organization " + organizationId + ", path [" + path + "]) was requested as type ("
				+ lookupType + ") but #" + adoptedId + " already occupies the unique key"
				+ " (name, parentId, organizationId) with type (" + adoptedType + "). The requested type"
				+ " CANNOT be created alongside it. Returning the (" + adoptedType + ") node - the caller"
				+ " did NOT get the (" + lookupType + ") node it asked for. Fix the call site, or add"
				+ " 'type' to the " + model + " unique constraint (which requires dropping the existing"
				+ " index and is not an automatic schema patch).");
		}

		logger.warn(KI60_WATCH_MARKER + " " + probeUncached(model, parentId, name, lookupType, organizationId));

		return adopted;
	}

	/**
	 * One-shot uncached re-run of the exact lookup that missed, for the KI-60 watch. Distinguishes
	 * "the cache hid a row that is really there" from "the miss is below the cache". Never throws:
	 * a diagnostic must not break a real operation.
	 */
	private String probeUncached(String model, long parentId, String name, String type, long organizationId) {
		try {
			Query q = QueryUtil.createQuery(model, FieldNames.FIELD_PARENT_ID, parentId);
			if(organizationId > 0L) {
				q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
			}
			if(name != null) {
				q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);
			}
			if(type != null && !type.equalsIgnoreCase("unknown")) {
				q.field(FieldNames.FIELD_TYPE, ComparatorEnumType.EQUALS, type.toUpperCase());
			}
			q.setCache(false);
			BaseRecord[] recs = search.findRecords(q);
			if(recs.length > 0) {
				StringBuilder ids = new StringBuilder();
				for(int i = 0; i < recs.length; i++) {
					if(i > 0) {
						ids.append(",");
					}
					long rid = recs[i].get(FieldNames.FIELD_ID);
					ids.append("#").append(rid);
				}
				return "reprobe=FOUND count=" + recs.length + " ids=[" + ids + "]"
					+ " verdict: the UNCACHED read FINDS the row the cached type-filtered lookup missed,"
					+ " so the search cache IS implicated - this contradicts the TestPathUtilBehavior"
					+ " finding that eliminated the CacheDBSearch query-key theory and is a significant"
					+ " KI-60 result.";
			}
			return "reprobe=MISSED verdict: the UNCACHED read ALSO returns nothing for the same"
				+ " type-filtered lookup, so the cache is NOT implicated - the miss is below the cache,"
				+ " in the query/DB layer (most commonly the row exists with a different type, which the"
				+ " type-less constraint-key re-read then adopts).";
		}
		catch(Exception ex) {
			return "reprobe=ERROR the KI-60 diagnostic re-probe itself failed (the operation it observed"
				+ " is unaffected): " + ex;
		}
	}

	/**
	 * Does this model's unique constraint include {@code type}?
	 *
	 * <p>This is per-model and getting it wrong is dangerous in both directions.
	 * {@code auth.group} constrains {@code (name, parentId, organizationId)} — a type-filtered
	 * lookup can miss a row the insert then collides with, so the re-read must drop the type filter.
	 * {@code auth.role} and {@code auth.permission} constrain
	 * {@code (parentId, name, type, organizationId)} — same-named siblings of different types are
	 * legal and expected there, so a type-less re-read would adopt an unrelated node (and, for a
	 * path segment, resolve the rest of the path under it).
	 */
	private static boolean constraintIncludesType(ModelSchema ms) {
		if(ms == null || ms.getConstraints() == null) {
			return false;
		}
		for(String constraint : ms.getConstraints()) {
			if(constraint == null) {
				continue;
			}
			for(String field : constraint.split(",")) {
				if(FieldNames.FIELD_TYPE.equalsIgnoreCase(field.trim())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * KI-42 helper: re-read a hierarchy node on the unique constraint's own key. The caller supplies
	 * the type to filter on, which must mirror the model's constraint — {@code null} where type is
	 * not part of it (a type-filtered read is exactly what can miss the row a create then collides
	 * with), and the effective type where it is. Returns null (rather than throwing) if nothing is
	 * there, letting the caller report a real failure.
	 */
	private BaseRecord findExistingNode(String model, long parentId, String name, String type, long organizationId) {
		try {
			BaseRecord[] existing = search.findByNameInParent(model, parentId, name, type, organizationId);
			if(existing.length == 1) {
				return existing[0];
			}
			if(existing.length > 1) {
				logger.error("Ambiguous re-read for " + model + " '" + name + "' in parent #" + parentId
					+ ": " + existing.length + " results");
			}
		}
		catch(ReaderException re) {
			logger.error("Failed to re-read " + model + " '" + name + "' in parent #" + parentId + ": " + re.getMessage());
		}
		return null;
	}
}
