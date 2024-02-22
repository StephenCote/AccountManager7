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
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class BuilderUtil {
	public static final Logger logger = LogManager.getLogger(BuilderUtil.class);
	

	protected static BaseRecord getCreateRawMaterial(OlioContext ctx, String name, String type, String cat) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_ITEM, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("items.id"));
		q.field(FieldNames.FIELD_NAME, name);
		if(type != null) {
			q.field(FieldNames.FIELD_TYPE, type);
		}
		if(cat != null) {
			q.field("category", cat);
		}

		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			rec = ItemUtil.newItem(ctx, name);
			try {
				rec.set(FieldNames.FIELD_TYPE, type);
				rec.set("category", cat);
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
		int count = IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(ctx.getUser(), ModelNames.MODEL_BUILDER, ctx.getWorld().get("builders.path")));
		if(count == 0) {
			BaseRecord[] builders = importBuilders(ctx);
			ctx.processQueue();
		}
	}
	protected static BaseRecord[] importBuilders(OlioContext ctx) {
		// logger.info("Import default builder configuration");

		List<BaseRecord> blds = JSONUtil.getList(ResourceUtil.getResource("./olio/builders.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		List<BaseRecord> oblds = new ArrayList<>();
		Factory mf = IOSystem.getActiveContext().getFactory();
		
		try {
			for(BaseRecord vbld : blds) {
				
				ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("builders.path"));
				plist.parameter(FieldNames.FIELD_NAME, vbld.get(FieldNames.FIELD_NAME));
				BaseRecord bld = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_BUILDER, ctx.getUser(), vbld, plist);

				BaseRecord itm = bld.get("item");
				if(itm != null) {
					bld.set("item", ItemUtil.getItemTemplate(ctx, itm.get(FieldNames.FIELD_NAME)));
				}
				
				BaseRecord app = bld.get("apparel");
				if(app != null) {
					bld.set("apparel", ApparelUtil.getApparelTemplate(ctx, app.get(FieldNames.FIELD_NAME)));
				}

				List<BaseRecord> qs = bld.get("qualities");
				BaseRecord oq = mf.newInstance(ModelNames.MODEL_QUALITY, ctx.getUser(), (qs.size() > 0 ? qs.get(0) : null), ParameterList.newParameterList("path", ctx.getWorld().get("qualities.path")));
				qs.clear();
				qs.add(oq);

				List<BaseRecord> tags = bld.get("tags");
				List<BaseRecord> itags = new ArrayList<>();
				for(BaseRecord t: tags) {
					itags.add(OlioUtil.getCreateTag(ctx, t.get(FieldNames.FIELD_NAME), bld.getModel()));
				}
				bld.set("tags", itags);
				
				List<BaseRecord> skillz = bld.get("skills");
				List<BaseRecord> iskillz = new ArrayList<>();
				for(BaseRecord t: skillz) {
					iskillz.add(OlioUtil.getCreateSkill(ctx, t.get(FieldNames.FIELD_NAME)));
				}
				bld.set("skills", iskillz);
				
				List<BaseRecord> mats = bld.get("materials");
				List<BaseRecord> imats = new ArrayList<>();
				for(BaseRecord t: mats) {
					imats.add(getCreateRawMaterial(ctx, t.get(FieldNames.FIELD_NAME), "template", "raw material"));
				}
				bld.set("materials", imats);
				
				ctx.queue(bld);
				oblds.add(bld);
				
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return blds.toArray(new BaseRecord[0]);
	}
	
	


}
