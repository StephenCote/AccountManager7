package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryPlan;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ErrorUtil;

public class OlioUtil {
	public static final Logger logger = LogManager.getLogger(OlioUtil.class);
	protected static final long SECOND = 1000;
    protected static final long MINUTE = 60 * SECOND;
    protected static final long HOUR = 60 * MINUTE;
    protected static final long DAY = 24 * HOUR;
    protected static final long YEAR = 365 * DAY;
    
	protected static Map<String, List<String>> dirNameCache = new HashMap<>();
	private static SecureRandom rand = new SecureRandom();
	private static String[] demographicLabels = new String[]{"Alive","Child","Young Adult","Adult","Available","Senior","Mother","Coupled","Deceased"};
	protected static Map<String,List<BaseRecord>> getDemographicMap(OlioContext ctx, BaseRecord location){
		long id = location.get(FieldNames.FIELD_ID);
		if(!ctx.getDemographicMap().containsKey(id)) {
			Map<String,List<BaseRecord>> map = new ConcurrentHashMap<>();
			for(String label : demographicLabels){
				map.put(label, new CopyOnWriteArrayList<>());
			}
			ctx.getDemographicMap().put(id, map);
		}
		return ctx.getDemographicMap().get(id);
	}
	
	public static void clearCache() {
		ProfileUtil.clearCache();
	}
	
	public static void setDemographicMap(OlioContext ctx, Map<String,List<BaseRecord>> map, BaseRecord realm, BaseRecord person) {
		try {
			ZonedDateTime birthDate = person.get(FieldNames.FIELD_BIRTH_DATE);
			ZonedDateTime endDate = ctx.clock().getEnd();
			int age = (int)((endDate.toInstant().toEpochMilli() - birthDate.toInstant().toEpochMilli()) / OlioUtil.YEAR);
			map.values().stream().forEach(l -> l.removeIf(f -> ((long)person.get(FieldNames.FIELD_ID)) == ((long)f.get(FieldNames.FIELD_ID))));
			
			if(CharacterUtil.isDeceased(person)){
				map.get("Deceased").add(person);
				List<BaseRecord> partners = person.get(FieldNames.FIELD_PARTNERS);
				if(partners.size() > 0) {
					logger.error("***** Deceased " + person.get(FieldNames.FIELD_NAME) + " should have been decoupled and wasn't");
					// decouple(user, person);
				}
			}
			else{
				map.get("Alive").add(person);
	
				if(age <= Rules.MAXIMUM_CHILD_AGE){
					map.get("Child").add(person);
				}
				else if(age >= Rules.SENIOR_AGE){
					map.get("Senior").add(person);
				}
				else if(age < Rules.MINIMUM_ADULT_AGE) {
					map.get("Young Adult").add(person);
				}
				else{
					map.get("Adult").add(person);
				}

				List<BaseRecord> partners = person.get(FieldNames.FIELD_PARTNERS);
				if(!partners.isEmpty()) {
					map.get("Coupled").add(person);
				}
				else if(age >= Rules.MINIMUM_MARRY_AGE && age <= Rules.MAXIMUM_MARRY_AGE && partners.isEmpty()) {
					map.get("Available").add(person);
				}
				if("female".equals(person.get(FieldNames.FIELD_GENDER)) && age >= Rules.MINIMUM_ADULT_AGE && age <= Rules.MAXIMUM_FERTILITY_AGE_FEMALE) {
					map.get("Mother").add(person);
				}

			}
		}
		catch(ModelException e) {
			logger.error(e);
		}
	}
	
	protected static void populateDirNameCache(BaseRecord user, String model, long groupId) throws IndexException, ReaderException {
		String key = model + "-" + groupId;
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, groupId);
		q.setRequest(new String[] {FieldNames.FIELD_NAME});
		QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
		List<String> names = Arrays.asList(qr.getResults()).stream().map(r -> (String)r.get(FieldNames.FIELD_NAME)).collect(Collectors.toList());
		dirNameCache.put(key, names);
	}
	
	protected static boolean nameInDirExists(BaseRecord user, String model, long groupId, String name) throws IndexException, ReaderException {
		String key = model + "-" + groupId;
		if(!dirNameCache.containsKey(key) || dirNameCache.get(key).size() == 0) {
			populateDirNameCache(user, model, groupId);
		}
		List<String> names = dirNameCache.get(key);
		if(names.contains(name)) {
			return true;
		}
		names.add(name);
		return false;
		//OlioUtil.recordExists(user, model, name, groupId);
	}

	public static AlignmentEnumType getRandomAlignment() {
		AlignmentEnumType alignment = randomEnum(AlignmentEnumType.class);
		while(alignment == AlignmentEnumType.UNKNOWN || alignment == AlignmentEnumType.NEUTRAL) {
			alignment = randomEnum(AlignmentEnumType.class);
		}
		return alignment;
	}
	public static CharacterRoleEnumType getRandomCharacterRole(String gender) {
		CharacterRoleEnumType tets = randomEnum(CharacterRoleEnumType.class);
		while(tets == CharacterRoleEnumType.UNKNOWN || ((gender == null || gender.equals("male")) && tets == CharacterRoleEnumType.TEMPTRESS)) {
			tets = randomEnum(CharacterRoleEnumType.class);
		}
		return tets;
	}
	public static ThreatEnumType getRandomPersonThreat() {
		ThreatEnumType[] threats = ThreatEnumType.getThreats();
		ThreatEnumType tets = threats[rand.nextInt(threats.length)];
		while(tets == ThreatEnumType.NONE || tets == ThreatEnumType.ANIMAL_THREAT || tets == ThreatEnumType.ANIMAL_TARGET) {
			tets = threats[rand.nextInt(threats.length)];
		}
		return tets;
	}
	public static ReasonEnumType getRandomReason() {
		ReasonEnumType tets = randomEnum(ReasonEnumType.class);
		while(tets == ReasonEnumType.UNKNOWN) {
			tets = randomEnum(ReasonEnumType.class);
		}
		return tets;
	}
	public static InteractionEnumType getRandomInteraction() {
		InteractionEnumType tets = randomEnum(InteractionEnumType.class);
		while(tets == InteractionEnumType.UNKNOWN) {
			tets = randomEnum(InteractionEnumType.class);
		}
		return tets;
	}
	public static OutcomeEnumType getRandomOutcome() {
		return randomEnum(OutcomeEnumType.class);
	}
	
	public static String randomSelectionName(BaseRecord user, Query query) {
		String[] names = randomSelectionNames(user, query, 1);
		if(names.length > 0) {
			return names[0];
		}
		return null;
	}
	public static String[] randomSelectionNames(BaseRecord user, Query query, int count) {
		return randomSelection(user, query, FieldNames.FIELD_NAME, count);
	}
	public static String[] randomSelection(BaseRecord user, Query query, String fieldName, int count) {
		return Arrays.asList(randomSelections(user, query, count)).stream().map(f -> f.get(fieldName)).collect(Collectors.toList()).toArray(new String[0]);
	}
	public static BaseRecord randomSelection(BaseRecord user, Query query) {
		BaseRecord[] recs = randomSelections(user, query, 1);
		BaseRecord rec = null;
		if(recs.length > 0) {
			rec = recs[0];
		}
		return rec;
	}
	public static BaseRecord[] randomSelections(BaseRecord user, Query query, int count) {
		QueryResult qr = null;
		try {
			query.setRequestRange(0, count);
			query.set(FieldNames.FIELD_CACHE, false);
			query.set(FieldNames.FIELD_SORT_FIELD, "random()");
			query.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
			qr = IOSystem.getActiveContext().getSearch().find(query);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		if(qr != null && qr.getCount() > 0) {
			// logger.info("RLoc = " + qr.getResults()[0].get(FieldNames.FIELD_NAME));
			return qr.getResults();
		}
		else {
			// logger.warn("No results: ");
			logger.warn(query.toFullString());
		}
		return new BaseRecord[0];
	}
	public static boolean recordExists(BaseRecord user, String modelName, String name, BaseRecord group) throws IndexException, ReaderException {
		return recordExists(user, modelName, name, (long)group.get(FieldNames.FIELD_ID));
	}
	public static boolean recordExists(BaseRecord user, String modelName, String name, long groupId) throws IndexException, ReaderException {
		Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.setRequest(new String[] {FieldNames.FIELD_ID});
		return (IOSystem.getActiveContext().getSearch().find(q).getCount() > 0);
	}
    public static <T extends Enum<?>> T randomEnum(Class<T> cls){
        int x = (new Random()).nextInt(cls.getEnumConstants().length);
        return cls.getEnumConstants()[x];
    }
    

	/*
	protected static void applyRandomOlioValues(BaseRecord user, BaseRecord world, BaseRecord record) {
		record.getFields().forEach(f -> {
			try {
				if(f.getValueType() == FieldEnumType.STRING) {
					String val = f.getValue();
					if("$random".equals(val)) {
						f.setValue(getRandomOlioValue(user, world, f.getName()));
					}
				}
			}
			catch(ValueException e) {
				logger.error(e);
			}
		});
	}
	*/

	/*
	public static BaseRecord newQuality(BaseRecord user, String groupPath) {
		BaseRecord qual = newGroupRecord(user, OlioModelNames.MODEL_QUALITY, groupPath, null);
		return IOSystem.getActiveContext().getAccessPoint().create(user, qual);
	}
	*/
	
	public static BaseRecord getCreatePopulationGroup(OlioContext context, String name)  {
		BaseRecord popDir = context.getWorld().get(OlioFieldNames.FIELD_POPULATION);
		BaseRecord grp = null;
		try{
			BaseRecord[] grps = IOSystem.getActiveContext().getSearch().findByNameInParent(ModelNames.MODEL_GROUP, popDir.get(FieldNames.FIELD_ID), name);
			if(grps.length > 0) {
				return grps[0];
			}

			grp = newRegionGroup(context.getOlioUser(), popDir, name);
		
			IOSystem.getActiveContext().getRecordUtil().createRecord(grp);
		}
		catch(ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return grp;
	}
	protected static BaseRecord newRegionGroup(BaseRecord user, BaseRecord parent, String groupName) throws FieldException, ValueException, ModelNotFoundException {
		BaseRecord grp = RecordFactory.model(ModelNames.MODEL_GROUP).newInstance();
		grp.set(FieldNames.FIELD_NAME, groupName);
		grp.set(FieldNames.FIELD_TYPE, GroupEnumType.PERSON);
		grp.set(FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
		IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, grp, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return grp;
	}
	
	public static BaseRecord newGroupRecord(BaseRecord user, String model, String groupPath, BaseRecord template) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dir == null) {
			logger.error("Failed to find or create group " + groupPath);
			return null;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		BaseRecord irec = null;
		try {
			irec = IOSystem.getActiveContext().getFactory().newInstance(model, user, template, plist);
		}
		catch(ClassCastException | FactoryException e) {
			logger.error(e);
		}
		return irec;
	}
	public static <T> void addAttribute(BaseRecord obj, String attrName, T val) throws ModelException, FieldException, ModelNotFoundException, ValueException {
		BaseRecord attr = AttributeUtil.addAttribute(obj, attrName, val);
		Queue.queue(attr);
	}

	
	/*
	public static BaseRecord getPopulationGroup(OlioContext ctx, BaseRecord location, String name) {
		IOSystem.getActiveContext().getReader().populate(location, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID});
		String locName = location.get(FieldNames.FIELD_NAME) + " " + name;
		Optional<BaseRecord> grp = ctx.getPopulationGroups().stream().filter(f -> locName.equals((String)f.get(FieldNames.FIELD_NAME))).findFirst();
		BaseRecord ogrp = null;
		if(grp.isPresent()) {
			ogrp = grp.get();
		}
		else {
			logger.warn("No population group for " + locName);
		}
		return ogrp;
	}
	*/
	
	protected static int countPeople(BaseRecord group) {
		Query pq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
		pq.filterParticipation(group, null, OlioModelNames.MODEL_CHAR_PERSON, null);
		return IOSystem.getActiveContext().getSearch().count(pq);
	}

	protected static List<BaseRecord> getRealmPopulation(OlioContext ctx, BaseRecord realm){
		
		long id = realm.get(FieldNames.FIELD_ID);

		if(!ctx.getPopulationMap().containsKey(id)) {
			BaseRecord popGrp = realm.get(OlioFieldNames.FIELD_POPULATION);
			if(popGrp == null) {
				logger.error("Failed to find population group");
				return new ArrayList<>();
			}
			List<BaseRecord> pop = listGroupPopulation(ctx, popGrp);
			if(pop.size() == 0) {
				logger.warn("Realm " + realm.get(FieldNames.FIELD_NAME) + " population is empty for group " + popGrp.get(FieldNames.FIELD_ID));
				//logger.warn(q.toFullString());
				ErrorUtil.printStackTrace();
			}
			ctx.getPopulationMap().put(id, pop);
		}
		return ctx.getPopulationMap().get(id);
	}
	
	/// TODO: Deprecate this method
	/*
	protected static List<BaseRecord> getPopulation(OlioContext ctx, BaseRecord location){
		long id = location.get(FieldNames.FIELD_ID);
		if(!ctx.getPopulationMap().containsKey(id)) {
			long start = System.currentTimeMillis();
			BaseRecord popGrp = getPopulationGroup(ctx, location, "Population");
			if(popGrp == null) {
				logger.error("Failed to find population group");
				return new ArrayList<>();
			}
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
			q.filterParticipation(popGrp, null, OlioModelNames.MODEL_CHAR_PERSON, null);
			planMost(q);
			// q.setCache(false);
			
			List<BaseRecord> pop = new CopyOnWriteArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
			ctx.getPopulationMap().put(id, pop);
			long stop = System.currentTimeMillis();
		}
		return ctx.getPopulationMap().get(id);
	}
	*/

	public static List<BaseRecord> listGroupPopulation(OlioContext ctx, BaseRecord group){
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
		q.filterParticipation(group, null, OlioModelNames.MODEL_CHAR_PERSON, null);
		planMost(q);
		return new CopyOnWriteArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
	}
	

	
	protected static <T> BaseRecord getCreateDirectoryObject(BaseRecord user, String modelName, String name, T type, BaseRecord group, BaseRecord template) {
		Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, name);
		if(type != null) {
			q.field(FieldNames.FIELD_TYPE, type);
		}
		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, group.get(FieldNames.FIELD_PATH));
			plist.parameter(FieldNames.FIELD_NAME, name);
			try {
				rec = IOSystem.getActiveContext().getFactory().newInstance(modelName, user, template, plist);
				if(type != null) {
					rec.set(FieldNames.FIELD_TYPE, type);
				}
				IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
			}
			catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return rec;
	}
	
	public static BaseRecord getCreateTag(OlioContext ctx, String name, String type) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_TAG, FieldNames.FIELD_GROUP_ID, ctx.getUniverse().get("tagsGroup.id"));
		q.field(FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_TYPE, type);
		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getUniverse().get("tagsGroup.path"));
			plist.parameter(FieldNames.FIELD_NAME, name);
			try {
				rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_TAG, ctx.getOlioUser(), null, plist);
				rec.set(FieldNames.FIELD_TYPE, type);
				IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
			}
			catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return rec;
	}

	protected static BaseRecord getCreateTrait(OlioContext ctx, String name, TraitEnumType type) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_TRAIT, FieldNames.FIELD_GROUP_ID, ctx.getUniverse().get(OlioFieldNames.FIELD_TRAITS_ID));
		q.field(FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_TYPE, type);
		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getUniverse().get("traits.path"));
			plist.parameter(FieldNames.FIELD_NAME, name);
			try {
				rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_TRAIT, ctx.getOlioUser(), null, plist);
				rec.set(FieldNames.FIELD_TYPE, type);
				IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
			}
			catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return rec;
		
	}
	protected static BaseRecord getCreateSkill(OlioContext ctx, String name) {
		return getCreateTrait(ctx, name, TraitEnumType.SKILL);
	}
	protected static BaseRecord getCreatePerk(OlioContext ctx, String name) {
		return getCreateTrait(ctx, name, TraitEnumType.PERK);
	}
	protected static BaseRecord getCreateFeature(OlioContext ctx, String name) {
		return getCreateTrait(ctx, name, TraitEnumType.FEATURE);
	}
	
	public static BaseRecord[] list(OlioContext ctx, String model, String groupName) {
		return list(ctx, model, groupName, null, null);
	}
	public static <T> BaseRecord[] list(OlioContext ctx, String model, String groupName, String fieldName, T val) {
		Query q = getQuery(ctx.getOlioUser(), model, ctx.getWorld().get(groupName + ".path"));
		BaseRecord[] recs = new BaseRecord[0];

		try {
			planMost(q);
			if(fieldName != null) {
				q.field(fieldName, val);
			}
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			if(qr.getResults().length > 0) {
				recs = qr.getResults();
			}

		} catch (ReaderException e) {
			logger.error(e);
		}
		return recs;
	}
	
	public static BaseRecord getFullRecord(BaseRecord rec) {
		return getFullRecord(rec, true);
	}
	public static BaseRecord getFullRecord(BaseRecord rec, boolean applyFilter) {
		if(rec == null) {
			logger.warn("Null record passed to getFullRecord");
			return null;
		}
		Query q = null;
		long id = rec.get(FieldNames.FIELD_ID);
		String objectId = rec.get(FieldNames.FIELD_OBJECT_ID);
		if(id > 0L) {
			q = QueryUtil.createQuery(rec.getSchema(), FieldNames.FIELD_ID, id);
		}
		else if(objectId != null) {
			q = QueryUtil.createQuery(rec.getSchema(), FieldNames.FIELD_OBJECT_ID, objectId);
		}
		else if(rec.hasField(FieldNames.FIELD_URN)){
			q = QueryUtil.createQuery(rec.getSchema(), FieldNames.FIELD_URN, rec.get(FieldNames.FIELD_URN));
		}
		if(applyFilter) {
			planMost(q);
		}
		else {
			q.planMost(true);
		}
		q.setCache(false);  // Bypass cache to ensure full plan is applied
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	
	public static Query getQuery(BaseRecord user, String model, String groupPath) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return QueryUtil.getGroupQuery(model, null, (long)dir.get(FieldNames.FIELD_ID), (long)dir.get(FieldNames.FIELD_ORGANIZATION_ID));
	}

	public static BaseRecord cloneIntoGroup(BaseRecord src, BaseRecord dir) {
		IOSystem.getActiveContext().getReader().populate(src);
		BaseRecord targ = src.copyDeidentifiedRecord();
		try {
			targ.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			targ.set(FieldNames.FIELD_GROUP_PATH, dir.get(FieldNames.FIELD_PATH));
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
			targ = null;
		}
		return targ;
	}
	
	public static boolean isTagged(BaseRecord rec, String tagName) {
		List<BaseRecord> tags = rec.get(FieldNames.FIELD_TAGS);
		return tags.stream().filter(t -> tagName.equalsIgnoreCase(t.get(FieldNames.FIELD_NAME))).findFirst().isPresent();
	}
	
	public static void batchAddForeignList(OlioContext ctx, BaseRecord rec, String fieldName, List<BaseRecord> parts) {
		List<BaseRecord> flist = rec.get(fieldName);
		// Clear any pending queue items first to avoid batch compatibility issues
		Queue.processQueue();
		for(BaseRecord p : parts) {
			flist.add(p);
			Queue.queue(p);
		}
		Queue.processQueue();
		for(BaseRecord p: parts) {
			long partId = p.get(FieldNames.FIELD_ID);
			if(partId == 0L) {
				logger.warn("Part " + p.getSchema() + " has no ID - skipping participation");
				continue;
			}
			BaseRecord participation = ParticipationFactory.newParticipation(ctx.getOlioUser(), rec, fieldName, p);
			if(participation != null) {
				Queue.queue(participation);
			}
		}
		Queue.processQueue();
	}

	
	public static void planMost(Query q) {
		q.planMost(true, FULL_PLAN_FILTER);
		prunePlan(q.plan());
	}
	
	public static void limitSubplanFields(QueryPlan plan, String modelName, String fieldName) {
		List<BaseRecord> cplans = QueryPlan.findPlans(plan, modelName, fieldName);
		cplans.forEach(cp -> {
			limitPlanFields(cp);
		});
	}
	public static void limitPlanFields(BaseRecord plan) {
		limitPlanFields(plan, Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME}));
	}
	public static void limitPlanFields(BaseRecord plan, List<String> fields) {
		QueryPlan.limitPlan(plan, fields);
	}

	/// This is really more of a subtraction experiment with creating deeply nested queries from a given model definition, and then pruning certain branches
	public static void prunePlan(QueryPlan plan) {
		List<BaseRecord> cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_INVENTORY_ENTRY, OlioFieldNames.FIELD_ITEM);
		cplans.forEach(cp -> {
			QueryPlan.limitPlan(cp, Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME}));
		});

		cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_INVENTORY_ENTRY, OlioFieldNames.FIELD_APPAREL);
		cplans.forEach(cp -> {
			QueryPlan.limitPlan(cp, Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME}));
		});
		
		cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_ITEM, null);
		cplans.forEach(cp -> {
			List<String> fields = cp.get(FieldNames.FIELD_FIELDS);
			fields.add(FieldNames.FIELD_TAGS);
		});

		
		cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_BUILDER, null);
		cplans.forEach(cp -> {
			List<String> fields = cp.get(FieldNames.FIELD_FIELDS);
			fields.add(FieldNames.FIELD_TAGS);
		});

		
		cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_BUILDER, FieldNames.FIELD_STORE);
		cplans.forEach(cp -> {
			List<BaseRecord> ccplans = QueryPlan.findPlans(cp, OlioModelNames.MODEL_STORE, OlioFieldNames.FIELD_APPAREL);
			ccplans.forEach(ccp -> {
				QueryPlan.limitPlan(ccp, Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME}));
			});
		});
		
		cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_BUILDER, FieldNames.FIELD_LOCATIONS);
		cplans.forEach(cp -> {
			QueryPlan.limitPlan(cp, Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME}));
		});
		
		cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_BUILDER, OlioFieldNames.FIELD_MATERIALS);
		cplans.forEach(cp -> {
			QueryPlan.limitPlan(cp, Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME}));
		});

		cplans = QueryPlan.findPlans(plan, OlioModelNames.MODEL_STORE, FieldNames.FIELD_LOCATIONS);
		cplans.forEach(cp -> {
			QueryPlan.limitPlan(cp, Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME}));
		});
	
	}
	public static List<String> FULL_PLAN_FILTER = Arrays.asList(new String[] {
			FieldNames.FIELD_TAGS,
			FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_CONTROLS,
			// Note: FIELD_OBJECT_ID removed - needed by game client for character identification
			FieldNames.FIELD_URN,
			FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_ORGANIZATION_PATH,
			//FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_GROUP_PATH,
			FieldNames.FIELD_BYTE_STORE,
			FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_SCORE,
			FieldNames.FIELD_STREAM,
			FieldNames.FIELD_USERS,
			FieldNames.FIELD_ACCOUNTS,
			OlioFieldNames.FIELD_SCHEDULES,
			"boundaries",
			"borders",
			"childLocations",
			"positive",
			"negative",
			OlioFieldNames.FIELD_TRAITS,
			"entryTraits",
			"exitTraits",
			FieldNames.FIELD_BEHAVIOR,
			"album",
			"portrait",
			FieldNames.FIELD_PATTERN,
			"images",
			OlioFieldNames.FIELD_ITEMS,
			"socialRing",
			"dimensions"
		});
	

}
