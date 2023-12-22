package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.parsers.data.WordParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class BuilderUtil {
	public static final Logger logger = LogManager.getLogger(BuilderUtil.class);
	

	
	protected static BaseRecord getCreateRawMaterial(OlioContext ctx, String name) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_ITEM, FieldNames.FIELD_GROUP_ID, ctx.getUniverse().get("items.id"));
		q.field(FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_TYPE, "template");
		q.field("category", "raw material");
		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			rec = ItemUtil.newItem(ctx, name);
			try {
				rec.set(FieldNames.FIELD_TYPE, "template");
				rec.set("category", "raw material");
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
			IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
		}
		return rec;
		
	}

	public static BaseRecord[] getBuilders(OlioContext ctx) {
		return OlioUtil.list(ctx, ModelNames.MODEL_BUILDER, "builders");
	}
	
	public static void loadBuilders(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getAccessPoint().count(ctx.getUser(), WordParser.getQuery(ctx.getUser(), ModelNames.MODEL_BUILDER, ctx.getWorld().get("builders.path")));
		if(count == 0) {
			BaseRecord[] builders = importBuilders(ctx);
			IOSystem.getActiveContext().getRecordUtil().createRecords(builders);
		}
	}
	protected static BaseRecord[] importBuilders(OlioContext ctx) {
		logger.info("Import default builder configuration");
		String[] builders = JSONUtil.importObject(ResourceUtil.getResource("./olio/builders.json"), String[].class);
		List<BaseRecord> blds = new ArrayList<>();
		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("builders.path"));
		ParameterList plist2 = ParameterList.newParameterList("path", ctx.getWorld().get("qualities.path"));
		try {
			for(String build : builders) {
				BaseRecord ob = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_BUILDER, ctx.getUser(), null, plist);
				BaseRecord oq = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_QUALITY, ctx.getUser(), null, plist2);
				String[] pairs = build.split(":");
				if(pairs.length != 12) {
					logger.error("Unexpected format - expected 12 pairs");
					logger.error(build);
					continue;
				}
				ob.set(FieldNames.FIELD_NAME, pairs[0]);
				List<BaseRecord> qs = ob.get("qualities");
				qs.add(oq);
				if(pairs[1].length() > 0) {
					ob.set(FieldNames.FIELD_DESCRIPTION, pairs[1]);
				}
				ob.set(FieldNames.FIELD_TYPE,  pairs[2]);
				ob.set("renewable", (pairs[3].equalsIgnoreCase("true")));
				if(pairs[4].length() > 0) {
					logger.warn("Handle store reference");
					ob.set("store", null);
				}
				if(pairs[5].length() > 0) {
					String[] mats = pairs[5].split(",");
					List<BaseRecord> materials = ob.get("materials");
					for(String m: mats) {
						BaseRecord rmat = getCreateRawMaterial(ctx, m);
						materials.add(rmat);
					}
				}
				
				/// 6 = item template name
				if(pairs[6].length() > 0) {
					BaseRecord it = ItemUtil.getItemTemplate(ctx, pairs[6]);
					if(it != null) {
						ob.set("item", it);
					}
				}
				/// 7 = apparel template name
				if(pairs[8].length() > 0) {
					String[] skilz = pairs[8].split(",");
					List<BaseRecord> skills = ob.get("skills");
					for(String s: skilz) {
						BaseRecord rmat = OlioUtil.getCreateSkill(ctx, s);
						skills.add(rmat);
					}
				}
				if(pairs[9].length() > 0) {
					ob.set("time", Integer.parseInt(pairs[9]));
				}
				if(pairs[10].length() > 0) {
					logger.warn("Handle schedule");
				}
				if(pairs[11].length() > 0) {
					String[] mats = pairs[11].split(",");
					for(String m: mats) {
						String[] mpair = m.split("=");
						if(mpair.length != 2) {
							logger.error("Invalid quality pair: " + m);
							continue;
						}
						oq.set(mpair[0], Double.parseDouble(mpair[1]));
					}
				}
				blds.add(ob);
				/// "name:desc:type:renew:store[location,person]:materials:skills:buildTime:buildSchedule[hourly,daily,weekly,monthly]:qual
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return blds.toArray(new BaseRecord[0]);
	}
	
	


}
