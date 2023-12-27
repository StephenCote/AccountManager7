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
import org.cote.accountmanager.parsers.data.WordParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ItemUtil {
	public static final Logger logger = LogManager.getLogger(ItemUtil.class);
	
	protected static BaseRecord newItem(OlioContext ctx, String name) {
		BaseRecord rec = null;
		ParameterList plist = ParameterList.newParameterList("path", ctx.getUniverse().get("items.path"));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			BaseRecord stat = rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ITEM_STATISTICS, ctx.getUser(), null, ParameterList.newParameterList("path", ctx.getUniverse().get("statistics.path")));
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ITEM, ctx.getUser(), null, plist);
			rec.set("statistics", stat);
			IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return rec;
	}
	
	public static BaseRecord getItemTemplate(OlioContext ctx, String name) {
		Query q = OlioUtil.getQuery(ctx.getUser(), ModelNames.MODEL_ITEM, ctx.getWorld().get("items.path"));
		q.field("type", "template");
		q.field(FieldNames.FIELD_NAME, name);
		try {
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	public static BaseRecord[] getItems(OlioContext ctx) {
		return OlioUtil.list(ctx, ModelNames.MODEL_BUILDER, "builders", "type", "template");
	}
	
	public static void loadItems(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getAccessPoint().count(ctx.getUser(), OlioUtil.getQuery(ctx.getUser(), ModelNames.MODEL_ITEM, ctx.getWorld().get("items.path")));
		if(count == 0) {
			BaseRecord[] items = importItems(ctx);
			IOSystem.getActiveContext().getRecordUtil().createRecords(items);
		}
	}
	
	/// "name:desc:type:category:store[location,person]:materials:perks:features:damage=1,range=0,protection=1,consumes=0:opacity=0.0,elasticity=0.0,glossiness=0.0,viscocity=0.0,sliminess=0.0,smoothness=0.0,hardness=0.0,toughness=0.0,defensive=0.0,offensive=0.0,waterresistance=0.0,heatresistance=0.0,insulation=0.0,skill=0.0"
	/// 
	protected static BaseRecord[] importItems(OlioContext ctx) {
		logger.info("Import default item configuration");
		String[] items = JSONUtil.importObject(ResourceUtil.getResource("./olio/items.json"), String[].class);
		List<BaseRecord> blds = new ArrayList<>();

		Factory mf = IOSystem.getActiveContext().getFactory();

		try {
			for(String item : items) {
				BaseRecord ob = mf.newInstance(ModelNames.MODEL_ITEM, ctx.getUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("items.path")));
				BaseRecord oq = mf.newInstance(ModelNames.MODEL_QUALITY, ctx.getUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("qualities.path")));
				BaseRecord os = mf.newInstance(ModelNames.MODEL_ITEM_STATISTICS, ctx.getUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("statistics.path")));
				String[] pairs = item.split(":");
				if(pairs.length != 10) {
					logger.error("Unexpected format - expected 10 pairs, found " + pairs.length);
					logger.error(item);
					continue;
				}
				ob.set(FieldNames.FIELD_NAME, pairs[0]);
				ob.set("statistics", os);
				List<BaseRecord> qs = ob.get("qualities");
				qs.add(oq);
				if(pairs[1].length() > 0) {
					ob.set(FieldNames.FIELD_DESCRIPTION, pairs[1]);
				}
				ob.set(FieldNames.FIELD_TYPE,  pairs[2]);
				ob.set("category", pairs[3]);
				if(pairs[4].length() > 0) {
					logger.warn("Handle store reference");
					ob.set("store", null);
				}
				if(pairs[5].length() > 0) {
					List<String> materials = ob.get("materials");
					materials.addAll(Arrays.asList(pairs[5].split(",")));
				}
				
				/// 6 = perks
				if(pairs[6].length() > 0) {
					String[] skilz = pairs[6].split(",");
					List<BaseRecord> skills = ob.get("perks");
					for(String s: skilz) {
						BaseRecord rmat = OlioUtil.getCreatePerk(ctx, s);
						skills.add(rmat);
					}
				}
				/// 7 = features
				if(pairs[7].length() > 0) {
					String[] skilz = pairs[7].split(",");
					List<BaseRecord> skills = ob.get("features");
					for(String s: skilz) {
						BaseRecord rmat = OlioUtil.getCreateFeature(ctx, s);
						skills.add(rmat);
					}
				}
				/// 8 = stats
				if(pairs[8].length() > 0) {
					String[] mats = pairs[8].split(",");
					for(String m: mats) {
						String[] mpair = m.split("=");
						if(mpair.length != 2) {
							logger.error("Invalid quality pair: " + m);
							continue;
						}
						os.set(mpair[0], Integer.parseInt(mpair[1]));
					}
				}
				/// 9 = qualities
				if(pairs[9].length() > 0) {
					String[] mats = pairs[9].split(",");
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
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return blds.toArray(new BaseRecord[0]);
	}
}
