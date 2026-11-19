package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryPlan;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;

/// Administrative maintenance commands behind the Ux752 Admin panel (/admin/system):
///
///   - thumbnails: count / wipe every data.thumbnail in one organization or in all of them. Thumbnails
///     are a derived cache (ThumbnailUtil.getCreateThumbnail re-creates one on the next read), so a wipe
///     costs nothing but a regeneration.
///   - audit:      list / search / view system.audit records, trim them to the last N days, or purge them.
///
/// WHY THIS BYPASSES AccessPoint. system.audit has no groupId, no ownerId and no access block, so the
/// dynamic policy evaluator has nothing to decide with: an org+date query yields no policy responses at all
/// and any indexed condition (action, response, objectId) falls through to a per-record evaluation that
/// denies a groupless, ownerless row. The record is not reachable through PBAC by design (it IS the
/// PBAC journal). So, like PbLegacyCleanupUtil, every entry point here performs its OWN authorization -
/// {@link #requireAdministrator} - and then reads and deletes through ISearch / IWriter directly. Nothing
/// in this class ever acts as the organization admin user; the caller is the actor throughout.
///
/// TWO GATES. Service7 puts @RolesAllowed({"admin"}) on every route (the JAAS "admin" role is what
/// WEB-INF/resource/roleMap.json maps SystemAdministrators / DataAdministrators / AccountAdministrators
/// onto), and this class re-checks the same membership in-process so an in-JVM caller (Console7, a test,
/// a future MCP tool) gets the same answer.
///
/// SCOPE. Roles are per organization, so there is no built-in "cross-tenant administrator": a member of
/// /Development's SystemAdministrators role is an administrator of /Development and nothing else. The
/// all-organizations scope is therefore granted only to administrators OF THE /System ORGANIZATION
/// ({@link #isSystemOrganizationAdministrator}); every other organization's administrators - including
/// that organization's admin user and its SystemAdministrators members - are confined to their own
/// organization. The scope is resolved server-side from the principal plus one boolean; an organization
/// id is never accepted from the client.
///
/// AUDIT OF THE MAINTENANCE ITSELF. Each destructive command (thumbnail wipe, audit trim, audit purge)
/// closes a PERMIT audit naming the actor, the scope and the count, through the normal AuditUtil queue.
/// A purge therefore leaves exactly one record behind: the one that says who purged it. That is the
/// conventional behaviour for a security journal (cf. Windows event 1102) and it is deliberate.
public class AdminMaintenanceUtil {

	public static final Logger logger = LogManager.getLogger(AdminMaintenanceUtil.class);

	public static final String SCOPE_ORGANIZATION = "organization";
	public static final String SCOPE_ALL = "all";

	/// Hard ceiling on one audit page; the panel asks for 25-100.
	public static final int MAX_AUDIT_PAGE = 500;

	/// Fields projected for the audit LIST. `description` is AuditUtil.getAuditString(audit) - the readable
	/// "PERMIT <subject> to <action> <resource> (via: <contextUser>)" line - so the list needs no joins.
	/// `query` (unbounded text) and `policy` (an embedded policy.policyResponse) are detail-only.
	private static final String[] AUDIT_LIST_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_CREATED_DATE, FieldNames.FIELD_ORGANIZATION_ID,
		FieldNames.FIELD_ACTION, FieldNames.FIELD_RESPONSE, FieldNames.FIELD_RESOURCE_TYPE, FieldNames.FIELD_SUBJECT_TYPE,
		FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_MESSAGE
	};

	private static final String[] AUDIT_DETAIL_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_CREATED_DATE, FieldNames.FIELD_MODIFIED_DATE,
		FieldNames.FIELD_EXPIRY_DATE, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ACTION, FieldNames.FIELD_RESPONSE,
		FieldNames.FIELD_RESOURCE_TYPE, FieldNames.FIELD_SUBJECT_TYPE, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_MESSAGE,
		FieldNames.FIELD_QUERY, FieldNames.FIELD_CONTEXT_USER, FieldNames.FIELD_SUBJECT, FieldNames.FIELD_RESOURCE
	};

	private static final String[] REF_FIELDS = new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME };

	/// Filter for {@link #listAudit}. Every member is optional; a null / blank member is not applied.
	public static class AuditFilter {
		public String action;
		public String response;
		public String resourceType;
		public String subjectType;
		/// Case-sensitive substring over description OR message (the two bounded summary fields).
		public String text;
		public Date since;
		public Date until;
		public long startRecord = 0L;
		public int recordCount = 25;
	}

	// ───────────────────────────────── authorization ─────────────────────────────────

	/// 401 when there is no principal, 500 when the principal carries no organization, 403 when the
	/// principal is not an administrator of its organization. Returns the organization id.
	public static long requireAdministrator(BaseRecord user) {
		if(user == null) {
			throw new AdminMaintenanceException(401, "Not authenticated");
		}
		Object o = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(!(o instanceof Number) || ((Number) o).longValue() <= 0L) {
			throw new AdminMaintenanceException(500, "User record carries no organizationId");
		}
		long orgId = ((Number) o).longValue();
		if(!isAdministrator(user, orgId)) {
			throw new AdminMaintenanceException(403, "Administrative maintenance requires an administrator role");
		}
		return orgId;
	}

	/// The organization admin user, or a member of any role that WEB-INF/resource/roleMap.json maps onto
	/// the JAAS "admin" role: SystemAdministrators, DataAdministrators, AccountAdministrators.
	public static boolean isAdministrator(BaseRecord user, long orgId) {
		if(user == null) {
			return false;
		}
		if(isOrganizationAdminUser(user)) {
			return true;
		}
		return isRoleMember(user, orgId, AccessSchema.ROLE_SYSTEM_ADMINISTRATOR)
			|| isRoleMember(user, orgId, AccessSchema.ROLE_DATA_ADMINISTRATOR)
			|| isRoleMember(user, orgId, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
	}

	/// An administrator ({@link #isAdministrator}) whose organization IS /System
	/// (OrganizationContext.SYSTEM_ORGANIZATION). This is the only principal allowed the all-organizations
	/// scope: /System is the deployment-global organization, and no per-organization role - not even
	/// another organization's SystemAdministrators - reaches past its own organization. Exact path match;
	/// a sub-organization of /System is not /System.
	public static boolean isSystemOrganizationAdministrator(BaseRecord user, long orgId) {
		if(user == null || orgId <= 0L) {
			return false;
		}
		BaseRecord org = IOSystem.getActiveContext().getRecordUtil().getRecordById(null, ModelNames.MODEL_ORGANIZATION, orgId);
		if(org == null || !org.hasField(FieldNames.FIELD_PATH)) {
			return false;
		}
		if(!OrganizationContext.SYSTEM_ORGANIZATION.equals(org.get(FieldNames.FIELD_PATH))) {
			return false;
		}
		return isAdministrator(user, orgId);
	}

	private static boolean isOrganizationAdminUser(BaseRecord user) {
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		return octx != null && octx.getAdminUser() != null
			&& Objects.equals(octx.getAdminUser().get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ID));
	}

	private static boolean isRoleMember(BaseRecord user, long orgId, String roleName) {
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord role = ioContext.getPathUtil().findPath(user, ModelNames.MODEL_ROLE, "/" + roleName, RoleEnumType.USER.toString(), orgId);
		return role != null && ioContext.getMemberUtil().isMember(user, role, null);
	}

	// ───────────────────────────────── scope ─────────────────────────────────

	/// Every system.organization row, as {id, name, path, type, parentId}. Bypasses PBAC (organizations are
	/// the one model with no organizationId of their own); callers are already gated.
	public static List<BaseRecord> listOrganizations() {
		List<BaseRecord> out = new ArrayList<>();
		Query q = QueryUtil.createQuery(ModelNames.MODEL_ORGANIZATION);
		q.field(FieldNames.FIELD_ID, ComparatorEnumType.GREATER_THAN, 0L);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PATH, FieldNames.FIELD_TYPE, FieldNames.FIELD_PARENT_ID });
		setSort(q, FieldNames.FIELD_ID, OrderEnumType.ASCENDING);
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
		if(recs != null) {
			for(BaseRecord r : recs) {
				if(r.getSchema() == null) {
					r.setSchema(ModelNames.MODEL_ORGANIZATION);
				}
				out.add(r);
			}
		}
		return out;
	}

	/// The organizations a command applies to. Gated: 401/403 via requireAdministrator, and a further 403
	/// when allOrganizations is requested by someone who is not a system administrator.
	public static List<BaseRecord> resolveScope(BaseRecord user, boolean allOrganizations) {
		long orgId = requireAdministrator(user);
		if(allOrganizations) {
			if(!isSystemOrganizationAdministrator(user, orgId)) {
				throw new AdminMaintenanceException(403, "The all-organizations scope is limited to administrators of the " + OrganizationContext.SYSTEM_ORGANIZATION + " organization");
			}
			List<BaseRecord> orgs = listOrganizations();
			if(orgs.isEmpty()) {
				throw new AdminMaintenanceException(500, "No organizations found");
			}
			return orgs;
		}
		BaseRecord org = IOSystem.getActiveContext().getRecordUtil().getRecordById(null, ModelNames.MODEL_ORGANIZATION, orgId);
		if(org == null) {
			throw new AdminMaintenanceException(500, "Organization #" + orgId + " not found");
		}
		return new ArrayList<>(Arrays.asList(org));
	}

	/// What the panel needs to render its scope control: the caller's organization, whether the
	/// all-organizations option is available to them, and (only then) the organizations it would cover.
	public static Map<String, Object> describeScope(BaseRecord user) {
		long orgId = requireAdministrator(user);
		boolean sysAdmin = isSystemOrganizationAdministrator(user, orgId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("organizationId", orgId);
		out.put("organizationPath", organizationPath(user, orgId));
		/// True only for an administrator of /System; the panel offers the all-organizations option on this.
		out.put("allOrganizationsAllowed", sysAdmin);
		List<Map<String, Object>> orgs = new ArrayList<>();
		if(sysAdmin) {
			for(BaseRecord org : listOrganizations()) {
				orgs.add(orgSummary(org));
			}
		}
		out.put("organizations", orgs);
		return out;
	}

	// ───────────────────────────────── thumbnails ─────────────────────────────────

	public static Map<String, Object> countThumbnails(BaseRecord user, boolean allOrganizations) {
		List<BaseRecord> orgs = resolveScope(user, allOrganizations);
		return perOrganization(orgs, allOrganizations, org -> countInOrganization(ModelNames.MODEL_THUMBNAIL, orgId(org), null));
	}

	/// Delete every data.thumbnail in scope. Returns {scope, total, organizations:[{id,path,count}]}.
	public static Map<String, Object> cleanupThumbnails(BaseRecord user, boolean allOrganizations) {
		List<BaseRecord> orgs = resolveScope(user, allOrganizations);
		Map<String, Object> out = perOrganization(orgs, allOrganizations, org -> deleteInOrganization(ModelNames.MODEL_THUMBNAIL, orgId(org), null));
		CacheUtil.clearCacheByModel(ModelNames.MODEL_THUMBNAIL);
		/// Thumbnails are created through AccessPoint, so each carried an ownership participation; the
		/// row delete above does not touch those. Sweep them now rather than leaving them for the next
		/// world cleanup.
		RecordFactory.cleanupOrphans(ModelNames.MODEL_THUMBNAIL);
		recordMaintenance(user, "Wiped " + out.get("total") + " thumbnail(s)", allOrganizations, orgs);
		return out;
	}

	// ───────────────────────────────── audit ─────────────────────────────────

	public static Map<String, Object> countAudit(BaseRecord user, boolean allOrganizations) {
		List<BaseRecord> orgs = resolveScope(user, allOrganizations);
		return perOrganization(orgs, allOrganizations, org -> countInOrganization(ModelNames.MODEL_AUDIT, orgId(org), null));
	}

	/// Newest first, paged, filtered. One query: the all-organizations scope simply drops the organization
	/// condition (the journal is one table), so unlike the delete paths this does not fan out per org.
	public static QueryResult listAudit(BaseRecord user, boolean allOrganizations, AuditFilter filter) {
		List<BaseRecord> orgs = resolveScope(user, allOrganizations);
		AuditFilter f = (filter != null ? filter : new AuditFilter());
		Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT);
		if(!allOrganizations) {
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId(orgs.get(0)));
		}
		applyAuditFilter(q, f);
		q.setRequest(AUDIT_LIST_FIELDS);
		long start = Math.max(0L, f.startRecord);
		int count = (f.recordCount <= 0 ? 25 : Math.min(f.recordCount, MAX_AUDIT_PAGE));
		q.setRequestRange(start, count);
		setSort(q, FieldNames.FIELD_CREATED_DATE, OrderEnumType.DESCENDING);
		q.setCache(false);
		try {
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			if(qr == null) {
				throw new AdminMaintenanceException(500, "Audit query returned no result");
			}
			BaseRecord[] recs = qr.getResults();
			if(recs != null) {
				for(BaseRecord r : recs) {
					if(r.getSchema() == null) {
						r.setSchema(ModelNames.MODEL_AUDIT);
					}
				}
			}
			return qr;
		}
		catch(ReaderException e) {
			logger.error(e);
			throw new AdminMaintenanceException(500, "Audit query failed: " + e.getMessage(), e);
		}
	}

	/// One audit record with its references resolved: {audit:{...}, organizationPath, contextUser,
	/// subject, resource}. subject and resource are $flex foreign fields, which the planner cannot follow
	/// (QueryPlan header), so they arrive as {id, schema} pairs and are read by that identity here. 404
	/// when the objectId is not in scope - an administrator of org A cannot view org B's journal by id.
	public static Map<String, Object> getAuditDetail(BaseRecord user, boolean allOrganizations, String objectId) {
		if(objectId == null || objectId.isBlank()) {
			throw new AdminMaintenanceException(400, "objectId is required");
		}
		List<BaseRecord> orgs = resolveScope(user, allOrganizations);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT, FieldNames.FIELD_OBJECT_ID, objectId);
		if(!allOrganizations) {
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId(orgs.get(0)));
		}
		q.setRequest(AUDIT_DETAIL_FIELDS);
		QueryPlan plan = q.plan(false);
		plan.plan(FieldNames.FIELD_CONTEXT_USER, REF_FIELDS);
		q.setCache(false);
		BaseRecord audit = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(audit == null) {
			throw new AdminMaintenanceException(404, "Audit record not found");
		}
		if(audit.getSchema() == null) {
			audit.setSchema(ModelNames.MODEL_AUDIT);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("audit", recordToMap(audit));
		Object oid = audit.get(FieldNames.FIELD_ORGANIZATION_ID);
		long aorg = (oid instanceof Number ? ((Number) oid).longValue() : 0L);
		out.put("organizationPath", pathOf(orgs, aorg));
		out.put("contextUser", describeReference(audit.hasField(FieldNames.FIELD_CONTEXT_USER) ? audit.get(FieldNames.FIELD_CONTEXT_USER) : null, ModelNames.MODEL_USER));
		out.put("subject", describeReference(audit.hasField(FieldNames.FIELD_SUBJECT) ? audit.get(FieldNames.FIELD_SUBJECT) : null, audit.get(FieldNames.FIELD_SUBJECT_TYPE)));
		out.put("resource", describeReference(audit.hasField(FieldNames.FIELD_RESOURCE) ? audit.get(FieldNames.FIELD_RESOURCE) : null, audit.get(FieldNames.FIELD_RESOURCE_TYPE)));
		return out;
	}

	/// Delete every audit record OLDER than `days` days (createdDate < now - days). days must be >= 1; use
	/// {@link #purgeAudit} for everything.
	public static Map<String, Object> trimAudit(BaseRecord user, boolean allOrganizations, int days) {
		if(days < 1) {
			throw new AdminMaintenanceException(400, "days must be at least 1");
		}
		List<BaseRecord> orgs = resolveScope(user, allOrganizations);
		final Date cutoff = new Date(System.currentTimeMillis() - (days * 86400000L));
		Map<String, Object> out = perOrganization(orgs, allOrganizations, org -> deleteInOrganization(ModelNames.MODEL_AUDIT, orgId(org), q -> q.field(FieldNames.FIELD_CREATED_DATE, ComparatorEnumType.LESS_THAN, cutoff)));
		out.put("days", days);
		out.put("cutoff", cutoff.getTime());
		CacheUtil.clearCacheByModel(ModelNames.MODEL_AUDIT);
		recordMaintenance(user, "Trimmed " + out.get("total") + " audit record(s) older than " + days + " day(s)", allOrganizations, orgs);
		return out;
	}

	/// Delete every audit record in scope.
	public static Map<String, Object> purgeAudit(BaseRecord user, boolean allOrganizations) {
		List<BaseRecord> orgs = resolveScope(user, allOrganizations);
		Map<String, Object> out = perOrganization(orgs, allOrganizations, org -> deleteInOrganization(ModelNames.MODEL_AUDIT, orgId(org), null));
		CacheUtil.clearCacheByModel(ModelNames.MODEL_AUDIT);
		recordMaintenance(user, "Purged " + out.get("total") + " audit record(s)", allOrganizations, orgs);
		return out;
	}

	// ───────────────────────────────── helpers ─────────────────────────────────

	private interface OrgOp {
		int apply(BaseRecord org);
	}

	private interface QueryDecorator {
		void apply(Query q);
	}

	/// Run `op` for each organization and shape the standard result: {scope, total, organizations:[{id,path,count}]}.
	private static Map<String, Object> perOrganization(List<BaseRecord> orgs, boolean allOrganizations, OrgOp op) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", (allOrganizations ? SCOPE_ALL : SCOPE_ORGANIZATION));
		List<Map<String, Object>> rows = new ArrayList<>();
		long total = 0L;
		for(BaseRecord org : orgs) {
			int n = op.apply(org);
			total += n;
			Map<String, Object> row = orgSummary(org);
			row.put("count", n);
			rows.add(row);
		}
		out.put("total", total);
		out.put("organizations", rows);
		return out;
	}

	private static int countInOrganization(String model, long orgId, QueryDecorator decorator) {
		Query q = QueryUtil.createQuery(model);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		if(decorator != null) {
			decorator.apply(q);
		}
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	/// IWriter.delete(Query) is the canonical bulk delete (WorldUtil.cleanupLocation, MemberUtil,
	/// VectorUtil); StatementUtil.getDeleteTemplate refuses a query without an organizationId condition,
	/// which is exactly the guarantee wanted here.
	private static int deleteInOrganization(String model, long orgId, QueryDecorator decorator) {
		Query q = QueryUtil.createQuery(model);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		if(decorator != null) {
			decorator.apply(q);
		}
		try {
			return IOSystem.getActiveContext().getWriter().delete(q);
		}
		catch(WriterException e) {
			logger.error(e);
			throw new AdminMaintenanceException(500, "Delete failed for " + model + " in organization #" + orgId + ": " + e.getMessage(), e);
		}
	}

	private static void applyAuditFilter(Query q, AuditFilter f) {
		if(notBlank(f.action)) {
			q.field(FieldNames.FIELD_ACTION, parseEnum(ActionEnumType.class, f.action, "action").name());
		}
		if(notBlank(f.response)) {
			q.field(FieldNames.FIELD_RESPONSE, parseEnum(ResponseEnumType.class, f.response, "response").name());
		}
		if(notBlank(f.resourceType)) {
			q.field(FieldNames.FIELD_RESOURCE_TYPE, ComparatorEnumType.LIKE, "%" + f.resourceType.trim() + "%");
		}
		if(notBlank(f.subjectType)) {
			q.field(FieldNames.FIELD_SUBJECT_TYPE, ComparatorEnumType.LIKE, "%" + f.subjectType.trim() + "%");
		}
		if(f.since != null) {
			q.field(FieldNames.FIELD_CREATED_DATE, ComparatorEnumType.GREATER_THAN_OR_EQUALS, f.since);
		}
		if(f.until != null) {
			q.field(FieldNames.FIELD_CREATED_DATE, ComparatorEnumType.LESS_THAN_OR_EQUALS, f.until);
		}
		if(notBlank(f.text)) {
			String like = "%" + f.text.trim() + "%";
			QueryField or = q.field(null, ComparatorEnumType.GROUP_OR, null);
			q.field(FieldNames.FIELD_DESCRIPTION, ComparatorEnumType.LIKE, like, or);
			q.field(FieldNames.FIELD_MESSAGE, ComparatorEnumType.LIKE, like, or);
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> cls, String value, String label) {
		try {
			return Enum.valueOf(cls, value.trim().toUpperCase());
		}
		catch(IllegalArgumentException e) {
			throw new AdminMaintenanceException(400, "Unknown " + label + ": " + value);
		}
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private static void setSort(Query q, String field, OrderEnumType order) {
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, field);
			q.set(FieldNames.FIELD_ORDER, order.toString());
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

	private static long orgId(BaseRecord org) {
		Object o = org.get(FieldNames.FIELD_ID);
		return (o instanceof Number ? ((Number) o).longValue() : 0L);
	}

	private static Map<String, Object> orgSummary(BaseRecord org) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", orgId(org));
		m.put("path", org.hasField(FieldNames.FIELD_PATH) ? org.get(FieldNames.FIELD_PATH) : null);
		return m;
	}

	private static String pathOf(List<BaseRecord> orgs, long orgId) {
		for(BaseRecord org : orgs) {
			if(orgId(org) == orgId && org.hasField(FieldNames.FIELD_PATH)) {
				return org.get(FieldNames.FIELD_PATH);
			}
		}
		return null;
	}

	private static String organizationPath(BaseRecord user, long orgId) {
		String path = (user.hasField(FieldNames.FIELD_ORGANIZATION_PATH) ? user.get(FieldNames.FIELD_ORGANIZATION_PATH) : null);
		if(path != null) {
			return path;
		}
		BaseRecord org = IOSystem.getActiveContext().getRecordUtil().getRecordById(null, ModelNames.MODEL_ORGANIZATION, orgId);
		return (org != null && org.hasField(FieldNames.FIELD_PATH) ? org.get(FieldNames.FIELD_PATH) : null);
	}

	/// {schema, id, objectId, name} for a reference the audit row carries as an identity pair. The name is
	/// read with a projection of its own (never the whole record - a data.data subject would drag its
	/// bytes along). Null when the row carries no reference; a reference whose target is gone keeps the
	/// schema and id and reports found:false.
	private static Map<String, Object> describeReference(BaseRecord ref, String schemaHint) {
		if(ref == null) {
			return null;
		}
		String schema = (ref.getSchema() != null ? ref.getSchema() : schemaHint);
		Object ido = (ref.hasField(FieldNames.FIELD_ID) ? ref.get(FieldNames.FIELD_ID) : null);
		long id = (ido instanceof Number ? ((Number) ido).longValue() : 0L);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", schema);
		m.put("id", id);
		if(schema == null || id <= 0L || ModelNames.MODEL_FLEX.equals(schema)) {
			m.put("found", false);
			return m;
		}
		ModelSchema ms = RecordFactory.getSchema(schema);
		if(ms == null) {
			m.put("found", false);
			return m;
		}
		List<String> req = new ArrayList<>();
		for(String fn : REF_FIELDS) {
			if(ms.hasField(fn)) {
				req.add(fn);
			}
		}
		Query q = QueryUtil.createQuery(schema, FieldNames.FIELD_ID, id);
		q.setRequest(req.toArray(new String[0]));
		q.setCache(false);
		BaseRecord target = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(target == null) {
			m.put("found", false);
			return m;
		}
		m.put("found", true);
		if(target.hasField(FieldNames.FIELD_OBJECT_ID)) {
			m.put("objectId", target.get(FieldNames.FIELD_OBJECT_ID));
		}
		if(target.hasField(FieldNames.FIELD_NAME)) {
			m.put("name", target.get(FieldNames.FIELD_NAME));
		}
		return m;
	}

	/// Scalar view of a record for embedding in a plain map: timestamps as epoch millis (the wire format
	/// RecordSerializer uses), enums as their names, foreign/model fields reduced to {schema,id}.
	private static Map<String, Object> recordToMap(BaseRecord rec) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", rec.getSchema());
		for(org.cote.accountmanager.model.field.FieldType f : rec.getFields()) {
			Object v = f.getValue();
			if(v == null) {
				continue;
			}
			if(v instanceof Date) {
				long t = ((Date) v).getTime();
				if(t != 0L) {
					m.put(f.getName(), t);
				}
			}
			else if(v instanceof BaseRecord) {
				BaseRecord br = (BaseRecord) v;
				Map<String, Object> sub = new LinkedHashMap<>();
				sub.put("schema", br.getSchema());
				if(br.hasField(FieldNames.FIELD_ID)) {
					sub.put("id", br.get(FieldNames.FIELD_ID));
				}
				if(br.hasField(FieldNames.FIELD_OBJECT_ID)) {
					sub.put("objectId", br.get(FieldNames.FIELD_OBJECT_ID));
				}
				if(br.hasField(FieldNames.FIELD_NAME)) {
					sub.put("name", br.get(FieldNames.FIELD_NAME));
				}
				m.put(f.getName(), sub);
			}
			else if(v instanceof byte[]) {
				continue;
			}
			else if(v instanceof Enum) {
				m.put(f.getName(), ((Enum<?>) v).name());
			}
			else if(v instanceof Number || v instanceof Boolean || v instanceof String) {
				m.put(f.getName(), v);
			}
			else {
				m.put(f.getName(), String.valueOf(v));
			}
		}
		return m;
	}

	/// Journal the maintenance action itself as a PERMIT DELETE by the caller, via the normal queue.
	private static void recordMaintenance(BaseRecord user, String what, boolean allOrganizations, List<BaseRecord> orgs) {
		try {
			BaseRecord audit = AuditUtil.startAudit(user, ActionEnumType.DELETE, user, null);
			if(audit == null) {
				logger.warn("Could not open an audit for: " + what);
				return;
			}
			StringBuilder scope = new StringBuilder();
			scope.append(allOrganizations ? "all organizations" : "organization");
			if(!orgs.isEmpty()) {
				scope.append(" (");
				for(int i = 0; i < orgs.size(); i++) {
					if(i > 0) {
						scope.append(", ");
					}
					String p = (orgs.get(i).hasField(FieldNames.FIELD_PATH) ? orgs.get(i).get(FieldNames.FIELD_PATH) : null);
					scope.append(p != null ? p : ("#" + orgId(orgs.get(i))));
				}
				scope.append(")");
			}
			AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, "Admin maintenance: " + what + " in " + scope);
		}
		catch(Exception e) {
			/// Never let the journal entry fail the command that already ran.
			logger.error("Failed to record maintenance audit: " + e.getMessage());
		}
	}
}
