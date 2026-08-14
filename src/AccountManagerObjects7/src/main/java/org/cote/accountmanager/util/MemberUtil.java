package org.cote.accountmanager.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IMember;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.EffectEnumType;

public class MemberUtil implements IMember {
	public static final Logger logger = LogManager.getLogger(MemberUtil.class);
	
	private final IReader reader;
	private final IWriter writer;
	private final ISearch search;
	private final RecordUtil recordUtil;
	
	public MemberUtil(IReader reader, IWriter writer, ISearch search) {
		this.reader = reader;
		this.writer = writer;
		this.search = search;
		recordUtil = new RecordUtil(reader, writer, search); 
	}
	
	public MemberUtil(IOContext context) {
		this.reader = context.getReader();
		this.writer = context.getWriter();
		this.search = context.getSearch();
		recordUtil = context.getRecordUtil(); 
	}
	
	public List<BaseRecord> findMembers(BaseRecord rec, String fieldName, String model, long id) throws IndexException, ReaderException {
		return findMembers(rec, fieldName, model, id, 0L, "ParticipationList");
	}
	public List<BaseRecord> findMembers(BaseRecord rec, String fieldName, String model, long id, long permissionId) throws IndexException, ReaderException {
		return findMembers(rec, fieldName, model, id, permissionId, "ParticipationList");
	}
	public List<BaseRecord> findParticipants(BaseRecord rec, String fieldName, String model, long id) throws IndexException, ReaderException {
		return findMembers(rec, fieldName, model, id, 0L, "ParticipantList");
	}
	public List<BaseRecord> findParticipants(BaseRecord rec, String fieldName, String model, long id, long permissionId) throws IndexException, ReaderException {
		return findMembers(rec, fieldName, model, id, permissionId, "ParticipantList");
	}
	private List<BaseRecord> findMembers(BaseRecord rec, String fieldName, String model, long id, long permissionId, String nameSuffix) throws IndexException, ReaderException {
		
		List<BaseRecord> list = new ArrayList<>();
		final String partModel = ParticipationFactory.getParticipantModel(rec.getSchema(), fieldName, model);
		
		if(reader.getRecordIo() == RecordIO.FILE) {
			logger.error("Not supported");
			return list;
		}
		else if(reader.getRecordIo() == RecordIO.DATABASE) {
			Query q = QueryUtil.createParticipationQuery(null, rec, fieldName, null, null);
			q.planMost(false);
			if(permissionId > 0L) {
				q.field(FieldNames.FIELD_PERMISSION_ID, permissionId);
				q.field(FieldNames.FIELD_EFFECT_TYPE, EffectEnumType.GRANT_PERMISSION);
			}
			else {
				q.field(FieldNames.FIELD_EFFECT_TYPE, EffectEnumType.AGGREGATE);
			}
			if(partModel != null) {
				q.field(FieldNames.FIELD_PARTICIPANT_MODEL, partModel);
			}
			if(id > 0L) {
				q.field(FieldNames.FIELD_PARTICIPANT_ID, id);
			}
			
			QueryResult qr = search.find(q);
			list.addAll(Arrays.asList(qr.getResults()));
		}
		else {
			throw new ReaderException(reader.getRecordIo() + " not supported");
		}
		return list;
	}
	
	/**
	 * Bulk-revoke every participation of {@code rec} (optionally narrowed to one effect).
	 * <p>
	 * Participation query-cache invalidation is PARTIAL: see {@code clearParticipationQueryCache()}
	 * for what is NOT covered.
	 */
	public int deleteMembers(BaseRecord rec, BaseRecord effect) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PARTICIPATION, FieldNames.FIELD_PARTICIPATION_MODEL, rec.getSchema());
		q.field(FieldNames.FIELD_PARTICIPATION_ID, rec.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, rec.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(effect == null) {
			q.field(FieldNames.FIELD_EFFECT_TYPE, EffectEnumType.AGGREGATE);
		}
		else {
			q.field(FieldNames.FIELD_PERMISSION_ID, effect.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_EFFECT_TYPE, EffectEnumType.GRANT_PERMISSION);
		}
		int del = 0;
		try {
			del = IOSystem.getActiveContext().getWriter().delete(q);
		} catch (WriterException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		CacheUtil.clearCache(rec);
		if(del > 0) {
			clearParticipationQueryCache();
		}
		return del;
	}

	/**
	 * Invalidate every cached participation QUERY RESULT after a participation write.
	 * <p>
	 * {@code CacheUtil.clearCache(record)} is not sufficient and never was. The search cache
	 * ({@code CacheDBSearch}) is keyed by query hash inside a per-query-TYPE map, and every
	 * participation query - {@link #findMembers}, {@link #isMember},
	 * {@code AuthorizationUtil.checkEntitlement} - is typed {@code system.participation}. Clearing by
	 * record only drops cached results that already CONTAIN an identity-matching record, so:
	 * <ul>
	 * <li>clearing by the participation TARGET (a group/role) drops nothing, because the cached
	 * results hold participation rows, not the target record;</li>
	 * <li>clearing by the newly created participation drops nothing either, because a row that was
	 * just inserted cannot appear in a result cached before it existed.</li>
	 * </ul>
	 * The consequence was a genuine read-after-write violation: a grant written by this process was
	 * invisible to the same process until an unrelated {@code CacheUtil.clearCache()} happened to
	 * flush everything (PictureBook 2.0 phase 1, {@code TestBookWorld} case 12 - the group's member
	 * list read straight back after {@code setEntitlement} was one row short).
	 * <p>
	 * Model-scoped invalidation is the honest granularity here: after a participation row is written
	 * or deleted, every cached participation result is potentially stale, and nothing cheaper can
	 * distinguish them (an insert is invisible to identity matching by construction). This drops one
	 * bucket - other models' cached queries are untouched.
	 * <p>
	 * <b>What this does NOT cover.</b> Three gaps, all live, none fixed by this call:
	 * <ol>
	 * <li><b>The decision caches are not reached.</b> {@code CacheUtil.clearCacheByModel} fans out
	 * across the registered caches, but two of them implement it as an empty stub -
	 * {@code CacheAuthorizationUtil.clearCacheByModel} ({@code :97-100}) and
	 * {@code CachePolicyUtil.clearCacheByModel} ({@code :216-219}). So
	 * {@code clearCacheByModel(system.participation)} effectively reaches only {@code CacheDBSearch}.
	 * Cached authorization/policy DECISIONS still depend entirely on the
	 * {@code clearCache(object)}/{@code clearCache(actor)} calls in {@link #member} - and
	 * {@link #deleteMembers} clears only {@code rec}, never the individual participants, so
	 * per-participant cached decisions survive a bulk revoke.</li>
	 * <li><b>Participation rows are also written outside {@code MemberUtil} entirely.</b>
	 * {@code DBWriter.updateAutoCreateReference} ({@code DBWriter.java:277-282}) bulk-adds
	 * {@code StatementUtil.getForeignParticipations(model)} on every create/update of a record that
	 * carries a foreign LIST field, and {@code StatementUtil.java:119} emits the matching
	 * {@code DELETE FROM ...participation} when such a record is deleted. Neither path invalidates
	 * anything. A membership written by a full-record update therefore still exhibits the original
	 * read-after-write bug this method exists to fix; only memberships written THROUGH
	 * {@code MemberUtil} are covered.</li>
	 * <li><b>Other models' queries that JOIN participation are not covered.</b>
	 * {@code QueryUtil.filterParticipant}/{@code filterParticipation} results (e.g.
	 * {@code ScimUserAdapter.java:204,227}, {@code AccessPoint.listMembers}/{@code countMembers}) are
	 * cached under their OWN model's bucket, not {@code system.participation}, so dropping this bucket
	 * leaves them stale.</li>
	 * </ol>
	 */
	private void clearParticipationQueryCache() {
		CacheUtil.clearCacheByModel(ModelNames.MODEL_PARTICIPATION);
	}
	
	public List<BaseRecord> getMembers(BaseRecord rec, String fieldName, String memberModelType) throws IndexException, ReaderException {
		List<BaseRecord> recs = new ArrayList<>();

		if(reader.getRecordIo() == RecordIO.FILE) {
			BaseRecord prec = getFileMembers(rec);
			if(prec != null) {
				recs = prec.get(FieldNames.FIELD_PARTS);
			}
		}
		else if(reader.getRecordIo() == RecordIO.DATABASE) {
			recs = getDbMembers(rec, fieldName, false, memberModelType);
		}
		return recs;
	}
	public List<BaseRecord> getParticipations(BaseRecord rec, String participationModelType) throws IndexException, ReaderException {
		List<BaseRecord> recs = new ArrayList<>();
		if(reader.getRecordIo() == RecordIO.FILE) {
			BaseRecord prec = getFileParticipants(rec);
			if(prec != null) {
				recs = prec.get(FieldNames.FIELD_PARTS);
			}
		}
		else if(reader.getRecordIo() == RecordIO.DATABASE) {
			recs = getDbMembers(rec, null, true, participationModelType);
		}
		return recs;
	}
	private BaseRecord getFileMembers(BaseRecord rec) throws IndexException, ReaderException {
		return getFileMembers(rec, "ParticipationList");
	}
	private BaseRecord getFileParticipants(BaseRecord rec) throws IndexException, ReaderException {
		return getFileMembers(rec, "ParticipantList");
	}
	private List<BaseRecord> getDbMembers(BaseRecord rec, String fieldName, boolean byPart, String modelType) throws IndexException, ReaderException {
		Query q = QueryUtil.createParticipationQuery(null, (!byPart ? rec : null), fieldName, (byPart ? rec : null), null);
		
		String idField = FieldNames.FIELD_PARTICIPATION_ID;
		String modelField = FieldNames.FIELD_PARTICIPATION_MODEL;
		if(!byPart) {
			idField = FieldNames.FIELD_PARTICIPANT_ID;
			modelField = FieldNames.FIELD_PARTICIPANT_MODEL;
			q.field(FieldNames.FIELD_PARTICIPANT_MODEL, modelType);
		}
		else {
			q.field(FieldNames.FIELD_PARTICIPATION_MODEL, modelType);
		}
		
		q.setRequest(new String[] {FieldNames.FIELD_ID, modelField, idField});
		QueryResult qr = search.find(q);
		List<String> ids = new ArrayList<>();

		String partModel = null;
		for(BaseRecord prec : qr.getResults()) {
			String model = prec.get(modelField);
			if(partModel == null) {
				partModel = model;
			}
			if(!partModel.equals(model)) {
				throw new ReaderException("Mixed models in participation result");
			}
			ids.add(Long.toString(prec.get(idField)));
		}
		List<BaseRecord> recs = new ArrayList<>();
		if(partModel == null) {
			return recs;
		}
		try {
			Query sq = QueryUtil.createQuery(partModel);
			sq.field(FieldNames.FIELD_ID, ComparatorEnumType.IN, ids.stream().collect(Collectors.joining(",")));
			QueryResult sqr = search.find(sq);
			recs = Arrays.asList(sqr.getResults());
		}
		catch(Exception e) {
			logger.error(e);
		}
		return recs;
		
	}
	private BaseRecord getFileMembers(BaseRecord rec, String nameSuffix) throws IndexException, ReaderException {
		BaseRecord list = null;
		if(reader.getRecordIo() == RecordIO.FILE) {
			String partcName = rec.get(FieldNames.FIELD_ID) + "-" + nameSuffix;
			BaseRecord[] recc = search.findByName(ModelNames.MODEL_PARTICIPATION_LIST, partcName);
			if(recc.length > 0) {
				list = recc[0];
			}
		}
		else {
			throw new ReaderException(reader.getRecordIo() + " not supported");
		}
		
		return list;
	}
	
	public boolean isMember(BaseRecord actor, BaseRecord object, String fieldName) {
		return isMember(actor, object, fieldName, false);
	}
	public boolean isMember(BaseRecord actor, BaseRecord object, String fieldName, boolean browseHierarchy) {
		boolean outBool = false;
		
		try {
			List<BaseRecord> parts = findMembers(object, fieldName, actor.getSchema(), actor.get(FieldNames.FIELD_ID));
			if(parts.size() > 0) {
				outBool = true;
			}
			else if(browseHierarchy && object.inherits(ModelNames.MODEL_PARENT)){
				long parentId = object.get(FieldNames.FIELD_PARENT_ID);
				if(parentId > 0L) {
					BaseRecord oparent =reader.read(object.getSchema(), parentId);
					if(oparent != null) {
						outBool = isMember(actor, oparent, fieldName, browseHierarchy);
					}
				}
			}
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
		
		return outBool;
	}
	
	/**
	 * Add ({@code enable=true}) or remove ({@code enable=false}) a participation of {@code actor} in
	 * {@code object}. Idempotent in both directions.
	 * <p>
	 * Participation query-cache invalidation is PARTIAL: see {@code clearParticipationQueryCache()}
	 * for what is NOT covered.
	 */
	public boolean member(BaseRecord user, BaseRecord object, BaseRecord actor, BaseRecord effect, boolean enable) {
		return member(user, object, null, actor, effect, enable);
	}
	/**
	 * Field-scoped {@link #member(BaseRecord, BaseRecord, BaseRecord, BaseRecord, boolean)}.
	 * <p>
	 * Participation query-cache invalidation is PARTIAL: see {@code clearParticipationQueryCache()}
	 * for what is NOT covered.
	 */
	public boolean member(BaseRecord user, BaseRecord object, String fieldName, BaseRecord actor, BaseRecord effect, boolean enable) {
		boolean outBool = false;
		
		Query q = QueryUtil.createParticipationQuery(user, object, fieldName, actor, effect);
		/// q.setCache(false);
		/// Clear the cache for any checks for existing membership
		///
		CacheUtil.clearCache(q.hash());
		
		QueryResult res = null;
		try {
			res = search.find(q);
		}
		catch(Exception e) {
			logger.error(e);
		}
		
		/// Clear any cache for both object and actor
		///
		CacheUtil.clearCache(object);
		CacheUtil.clearCache(actor);
		
		if(res != null && res.getCount() > 0) {
			if(!enable) {
				try {
					outBool = writer.delete(res.getResults()[0]);
					if(outBool) {
						writer.flush();
						/// A removed membership must not remain visible to this process - see
						/// clearParticipationQueryCache()
						clearParticipationQueryCache();
					}
				} catch (WriterException e) {
					logger.error(e);
				}
			}
			else {
				logger.debug("Entry already exists");
			}
			return outBool;
		}
		else if(!enable) {
			return false;
		}

		BaseRecord part1 = null;
		if(effect != null) {
			part1 = ParticipationFactory.newParticipation(user, object, fieldName, actor, effect);
		}
		else {
			part1 = ParticipationFactory.newParticipation(user, object, fieldName, actor);
		}
		boolean created = recordUtil.createRecord(part1);
		if(created) {
			/// A membership this process just wrote must be visible to this process - see
			/// clearParticipationQueryCache()
			clearParticipationQueryCache();
		}
		return created;
	}

	/// Returns participation counts for a given model, sorted by count descending.
	/// containerId scopes by groupId (for directory-based models) or parentId (for hierarchical models like roles/groups).
	public List<MembershipStatistic> countMembers(String modelName, String participantModel, long containerId, long organizationId) {
		List<MembershipStatistic> results = new ArrayList<>();

		if(reader.getRecordIo() != RecordIO.DATABASE) {
			logger.warn("countMembers is only supported for database IO");
			return results;
		}

		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		ModelSchema schema = RecordFactory.getSchema(modelName);
		if(schema == null) {
			logger.error("Model schema not found: " + modelName);
			return results;
		}

		String modelTable = dbUtil.getTableName(null, modelName);
		String partTable = dbUtil.getTableName(schema, ModelNames.MODEL_PARTICIPATION);
		boolean hasName = schema.hasField(FieldNames.FIELD_NAME);
		boolean hasType = schema.hasField(FieldNames.FIELD_TYPE);
		boolean hasGroupId = schema.hasField(FieldNames.FIELD_GROUP_ID);
		boolean hasParentId = schema.hasField(FieldNames.FIELD_PARENT_ID);

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT t.id, t.objectId");
		if(hasName) sql.append(", t.name");
		if(hasType) sql.append(", t.type");
		sql.append(", COUNT(p.participantId) as memberCount");
		sql.append(" FROM ").append(modelTable).append(" t");
		sql.append(" INNER JOIN ").append(partTable).append(" p ON p.participationId = t.id");
		sql.append(" WHERE p.participationModel = ?");
		if(hasType) {
			sql.append(" AND p.participantModel = t.type");
		} else if(participantModel != null) {
			sql.append(" AND p.participantModel = ?");
		}
		if(containerId > 0L) {
			if(hasGroupId) {
				sql.append(" AND t.groupId = ?");
			} else if(hasParentId) {
				sql.append(" AND t.parentId = ?");
			}
		}
		if(organizationId > 0L) {
			sql.append(" AND t.organizationId = ?");
		}
		sql.append(" GROUP BY t.id, t.objectId");
		if(hasName) sql.append(", t.name");
		if(hasType) sql.append(", t.type");
		sql.append(" ORDER BY memberCount DESC");

		try (Connection con = dbUtil.getDataSource().getConnection();
			PreparedStatement stmt = con.prepareStatement(sql.toString())) {
			int idx = 1;
			stmt.setString(idx++, modelName);
			if(!hasType && participantModel != null) {
				stmt.setString(idx++, participantModel);
			}
			if(containerId > 0L && (hasGroupId || hasParentId)) {
				stmt.setLong(idx++, containerId);
			}
			if(organizationId > 0L) {
				stmt.setLong(idx++, organizationId);
			}
			ResultSet rs = stmt.executeQuery();
			while(rs.next()) {
				long id = rs.getLong("id");
				String objectId = rs.getString("objectId");
				String name = hasName ? rs.getString("name") : null;
				String type = hasType ? rs.getString("type") : null;
				long count = rs.getLong("memberCount");
				results.add(new MembershipStatistic(id, objectId, name, type, modelName, count));
			}
			rs.close();
		} catch (SQLException e) {
			logger.error(e);
			e.printStackTrace();
		}

		return results;
	}

}
