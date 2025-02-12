package org.cote.accountmanager.util;

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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
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
		final String partModel = ParticipationFactory.getParticipantModel(rec.getModel(), fieldName, model);
		
		if(reader.getRecordIo() == RecordIO.FILE) {
			BaseRecord plist = getFileMembers(rec, nameSuffix);
			if(plist != null) {

				List<BaseRecord> parts = plist.get(FieldNames.FIELD_PARTS);
				list = parts.stream().filter(o ->{
					long mid = o.get(FieldNames.FIELD_PART_ID);
					long pid = o.get(FieldNames.FIELD_PERMISSION_ID);
					String type = o.get(FieldNames.FIELD_TYPE);
					return (
						(partModel == null || partModel.equals(type))
						&&
						(id == 0L || id == mid)
						&&
						(permissionId == 0L || permissionId == pid)
					);
					
				}).collect(Collectors.toList());
			}
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
	
	public int deleteMembers(BaseRecord rec, BaseRecord effect) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PARTICIPATION, FieldNames.FIELD_PARTICIPATION_MODEL, rec.getModel());
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
		return del;
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
			List<BaseRecord> parts = findMembers(object, fieldName, actor.getModel(), actor.get(FieldNames.FIELD_ID));
			if(parts.size() > 0) {
				outBool = true;
			}
			else if(browseHierarchy && object.inherits(ModelNames.MODEL_PARENT)){
				long parentId = object.get(FieldNames.FIELD_PARENT_ID);
				if(parentId > 0L) {
					BaseRecord oparent =reader.read(object.getModel(), parentId);
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
	
	public boolean member(BaseRecord user, BaseRecord object, BaseRecord actor, BaseRecord effect, boolean enable) {
		return member(user, object, null, actor, effect, enable);
	}
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
		return recordUtil.createRecord(part1);
	}
	
}
