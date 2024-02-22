package org.cote.accountmanager.olio;

import java.util.ArrayList;
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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ItemUtil {
	public static final Logger logger = LogManager.getLogger(ItemUtil.class);
	
	
	protected static BaseRecord newItem(OlioContext ctx, String name) {
		BaseRecord rec = null;
		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("items.path"));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			BaseRecord stat = rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ITEM_STATISTICS, ctx.getUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("statistics.path")));
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ITEM, ctx.getUser(), null, plist);
			rec.set("statistics", stat);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return rec;
	}

	public static BaseRecord getCreateItemTemplate(OlioContext ctx, String name) {
		BaseRecord tmp = getItemTemplate(ctx, name);
		if(tmp == null) {
			tmp = newItem(ctx, name);
			try {
				tmp.set("type", "template");
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e.toString());
			}
			IOSystem.getActiveContext().getRecordUtil().createRecord(tmp);
		}
		return tmp;
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
		int count = IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(ctx.getUser(), ModelNames.MODEL_ITEM, ctx.getWorld().get("items.path")));
		if(count == 0) {
			BaseRecord[] items = importItems(ctx);
			ctx.processQueue();
			//IOSystem.getActiveContext().getRecordUtil().createRecords(items);
		}
	}
	
	/// "name:desc:type:category:store[location,person]:materials:perks:features:damage=1,range=0,protection=1,consumes=0:opacity=0.0,elasticity=0.0,glossiness=0.0,viscocity=0.0,sliminess=0.0,smoothness=0.0,hardness=0.0,toughness=0.0,defensive=0.0,offensive=0.0,waterresistance=0.0,heatresistance=0.0,insulation=0.0,skill=0.0"
	/// 
	protected static BaseRecord[] importItems(OlioContext ctx) {
		// logger.info("Import default item configuration");
		List<BaseRecord> items = JSONUtil.getList(ResourceUtil.getResource("./olio/items.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		List<BaseRecord> oitems = new ArrayList<>();

		Factory mf = IOSystem.getActiveContext().getFactory();

		try {
			for(BaseRecord item : items) {
				
				ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("items.path"));
				plist.parameter(FieldNames.FIELD_NAME, item.get(FieldNames.FIELD_NAME));
				BaseRecord itm = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ITEM, ctx.getUser(), item, plist);

				BaseRecord os = mf.newInstance(ModelNames.MODEL_ITEM_STATISTICS, ctx.getUser(), item.get("statistics"), ParameterList.newParameterList("path", ctx.getWorld().get("statistics.path")));
				itm.set("statistics", os);

				List<BaseRecord> qs = itm.get("qualities");
				BaseRecord oq = mf.newInstance(ModelNames.MODEL_QUALITY, ctx.getUser(), (qs.size() > 0 ? qs.get(0) : null), ParameterList.newParameterList("path", ctx.getWorld().get("qualities.path")));
				qs.clear();
				qs.add(oq);
				
				List<BaseRecord> tags = itm.get("tags");
				List<BaseRecord> itags = new ArrayList<>();
				for(BaseRecord t: tags) {
					itags.add(OlioUtil.getCreateTag(ctx, t.get(FieldNames.FIELD_NAME), item.getModel()));
				}
				itm.set("tags", itags);
				
				List<BaseRecord> perks = itm.get("perks");
				List<BaseRecord> iperks = new ArrayList<>();
				for(BaseRecord t: perks) {
					iperks.add(OlioUtil.getCreatePerk(ctx, t.get(FieldNames.FIELD_NAME)));
				}
				itm.set("perks", iperks);
				
				List<BaseRecord> feats = itm.get("features");
				List<BaseRecord> ifeats = new ArrayList<>();
				for(BaseRecord t: feats) {
					ifeats.add(OlioUtil.getCreateFeature(ctx, t.get(FieldNames.FIELD_NAME)));
				}
				itm.set("features", ifeats);

				ctx.queue(itm);
				oitems.add(itm);
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return oitems.toArray(new BaseRecord[0]);
	}
}
