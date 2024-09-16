package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ItemUtil {
	public static final Logger logger = LogManager.getLogger(ItemUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	private static List<BaseRecord> itemTemplates = new ArrayList<>();
	public static List<BaseRecord> getItemTemplates(OlioContext ctx){
		if(itemTemplates.size() == 0) {
			itemTemplates = ItemUtil.getTemplateItems(ctx);
		}
		return itemTemplates;
	}
	
	private static String[] weaponFab = new String[] {"iron", "steel", "bronze", "stainless steel", "damascus steel"};
	public static List<BaseRecord> randomArms(OlioContext ctx){
		List<BaseRecord> aweaps = new ArrayList<>();
		List<BaseRecord> weapL = getItemTemplates(ctx).stream().filter(r -> "weapon".equals(r.get("category"))).collect(Collectors.toList());
		BaseRecord weapT = weapL.get(random.nextInt(weapL.size()));
		BaseRecord weap = ItemUtil.buildItem(ctx, weapT);
		//Queue.queue(weap);
		ApparelUtil.applyFabric(weap, weaponFab[random.nextInt(weaponFab.length)]);
		aweaps.add(weap);
		
		// logger.info(weap.toFullString());
		if(!OlioUtil.isTagged(weap, "two-handed") && random.nextDouble() <= 0.25) {
			List<BaseRecord> armorL = getItemTemplates(ctx).stream().filter(r -> "armor".equals(r.get("category"))).collect(Collectors.toList());
			BaseRecord armorT = armorL.get(random.nextInt(armorL.size()));
			BaseRecord armor = ItemUtil.buildItem(ctx, armorT);
			ApparelUtil.applyFabric(armor, weaponFab[random.nextInt(weaponFab.length)]);	
			aweaps.add(armor);
			// logger.info(weap.toFullString());
		}
		return aweaps;
	}
	private static SecureRandom random = new SecureRandom();
	private static String[] monies = new String[] {"script", "seashell", "shiny pebble"};
	public static void showerWithMoney(OlioContext ctx, List<BaseRecord> party) {
		String money = monies[random.nextInt(monies.length)];
		
		for(BaseRecord p: party) {
			int count = ItemUtil.countItemByCategoryInInventory(ctx, p, "money");
			if(count == 0) {
				boolean isBroke = random.nextDouble() <= 0.25;
				if(isBroke) {
					/// give them a piece of lint
					ItemUtil.depositItemIntoInventory(ctx, p, "lint", 1);
				}
				else {
					int moneyMax = 100;
					int moneyMin = 5;
					if(random.nextDouble() <= 0.25) {
						moneyMax = 150;
						moneyMin = 25;
						if(random.nextDouble() <= 0.10) {
							moneyMax = 200;
							moneyMin = 75;
						}
					}
					ItemUtil.depositItemIntoInventory(ctx, p, money, random.nextInt(moneyMin, moneyMax));
				}
			}
		}
	}
	
	protected static BaseRecord newItem(OlioContext ctx, String name) {
		BaseRecord rec = null;
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("items.path"));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			BaseRecord stat = rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ITEM_STATISTICS, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("statistics.path")));
			rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ITEM, ctx.getOlioUser(), null, plist);
			rec.set(OlioFieldNames.FIELD_STATISTICS, stat);
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
		Query q = OlioUtil.getQuery(ctx.getOlioUser(), OlioModelNames.MODEL_ITEM, ctx.getWorld().get("items.path"));
		q.field("type", "template");
		q.field(FieldNames.FIELD_NAME, name);
		OlioUtil.planMost(q);
		q.getRequest().add(FieldNames.FIELD_TAGS);

		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	public static void convertItemsToInventory(OlioContext ctx, BaseRecord rec) {
		BaseRecord store = rec.get(FieldNames.FIELD_STORE);
		if(store == null) {
			logger.warn("Store was null");
			return;
		}
		List<BaseRecord> items = store.get(OlioFieldNames.FIELD_ITEMS);
		List<BaseRecord> entries = store.get("inventory");
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("inventories.path"));
		for(BaseRecord i : items) {


			try {
				BaseRecord inv = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_INVENTORY_ENTRY, ctx.getOlioUser(), null, plist);
				inv.set("item", i);
				inv.set("quantity", 1);
				entries.add(inv);
			} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		items.clear();
	}
	
	public static double countMoney(BaseRecord rec) {
		double count = 0.0;
		BaseRecord store = rec.get(FieldNames.FIELD_STORE);
		List<BaseRecord> inv = store.get("inventory");

		for(BaseRecord i: inv) {
			String cat = i.get("item.category");
			if(cat == null || !cat.equals("money")) {
				continue;
			}
			List<BaseRecord> quals = i.get("item.qualities");
			double adj = 0.0;
			if(quals != null && quals.size() > 0) {
				adj = quals.get(0).get("valueAdjustment");
			}
			int quan = i.get("quantity");
			count += (quan * adj);
		}
		return count;
	}
	
	public static List<BaseRecord> getMoneyTemplates(OlioContext ctx){
		return getMoneyTemplates(ctx, null);
	}
	public static List<BaseRecord> getMoneyTemplates(OlioContext ctx, String name){
		return ItemUtil.getTemplateItems(ctx).stream().filter(i -> (name == null || name.equals(i.get(FieldNames.FIELD_NAME))) && ((String)i.get("category")).equals("money")).collect(Collectors.toList());
	}
	
	public static int countItemInInventory(OlioContext ctx, BaseRecord rec, String itemName) {
		Optional<BaseRecord> item = getTemplateItems(ctx).stream().filter(i -> ((String)i.get("type")).equals("template") && ((String)i.get(FieldNames.FIELD_NAME)).equals(itemName)).findFirst();
		if(item.isPresent()) {
			return countItemInInventory(ctx, rec, item.get());
		}
		return 0;
	}
	public static int countItemInInventory(OlioContext ctx, BaseRecord rec, BaseRecord item) {
		BaseRecord store = rec.get(FieldNames.FIELD_STORE);
		if(store == null) {
			logger.warn("Store was null");
			return 0;
		}
		int count = 0;
		long id = item.get(FieldNames.FIELD_ID);
		BaseRecord invItem = null;
		Optional<BaseRecord> oinvItem = ((List<BaseRecord>)store.get("inventory")).stream().filter(i -> ((long)i.get("item.id")) == id).findFirst();
		if(oinvItem.isPresent()) {
			count = oinvItem.get().get("quantity");
		}
		else {
			// logger.warn("Didn't find item #" + id + " in the inventory");
		}
		return count;
	}
	public static List<BaseRecord> getTemplateItemsByCategory(OlioContext ctx, String itemCat){
		return getTemplateItems(ctx).stream().filter(i -> {
			String type = i.get("type");
			String cat = i.get("category");
			return type != null && type.equals("template") && cat != null && cat.equals(itemCat);
		}).collect(Collectors.toList());	
	}
	public static int countItemByCategoryInInventory(OlioContext ctx, BaseRecord rec, String itemCat) {
		
		List<BaseRecord> items = getTemplateItemsByCategory(ctx, itemCat);
	
		int count = 0;
		for(BaseRecord i : items) {
			count += countItemInInventory(ctx, rec, i);
		}
		return count;
	}
	public static int countItemByCategoryInInventory(OlioContext ctx, BaseRecord rec, BaseRecord item) {
		BaseRecord store = rec.get(FieldNames.FIELD_STORE);
		if(store == null) {
			logger.warn("Store was null");
			return 0;
		}
		int count = 0;
		long id = item.get(FieldNames.FIELD_ID);
		BaseRecord invItem = null;
		Optional<BaseRecord> oinvItem = ((List<BaseRecord>)store.get("inventory")).stream().filter(i -> ((long)i.get("item.id")) == id).findFirst();
		if(oinvItem.isPresent()) {
			count = oinvItem.get().get("quantity");
		}
		return 0;
	}
	
	public static void addNewInventory(OlioContext ctx, BaseRecord item,  BaseRecord store, int quantity) throws FactoryException {
		List<BaseRecord> inv = store.get("inventory");
		String iname = item.get(FieldNames.FIELD_NAME);
		Optional<BaseRecord> oive = inv.stream().filter(i -> iname.equals(i.get("item.name"))).findFirst();
		if(!oive.isPresent()) {
			BaseRecord ive = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_INVENTORY_ENTRY, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("inventories.path")));
			ive.setValue("item", item);
			ive.setValue("quantity", (quantity < 0 ? rand.nextInt(1, 10) : quantity));
			inv.add(ive);
		}
	}
	
	public static boolean depositItemIntoInventory(OlioContext ctx, BaseRecord rec, String itemName, int count) {
		Optional<BaseRecord> item = getTemplateItems(ctx).stream().filter(i -> {
			String type = i.get("type");
			String name = i.get(FieldNames.FIELD_NAME);
			return type != null && type.equals("template") && name != null && name.equals(itemName);
		}).findFirst();

		if(item.isPresent()) {
			return depositItemIntoInventory(ctx, rec, item.get(), count);
		}
		return false;
	}
	public static boolean depositItemIntoInventory(OlioContext ctx, BaseRecord rec, BaseRecord item, int count) {
		BaseRecord store = rec.get(FieldNames.FIELD_STORE);
		if(store == null) {
			logger.warn("Store was null");
			return false;
		}
		if(count <= 0) {
			logger.warn("Invalid count: " + count);
			return false;
		}
		long id = item.get(FieldNames.FIELD_ID);
		BaseRecord invItem = null;
		Optional<BaseRecord> oinvItem = ((List<BaseRecord>)store.get("inventory")).stream().filter(i -> ((long)i.get("item.id")) == id).findFirst();
		if(oinvItem.isPresent()) {
			invItem = oinvItem.get();
		}
		else {
			invItem = addItemToInventory(ctx, rec, item);
			IOSystem.getActiveContext().getRecordUtil().createRecord(invItem);
		}
		
		int quan = invItem.get("quantity");
		invItem.setValue("quantity", quan + count);
		Queue.queueUpdate(invItem, new String[] {FieldNames.FIELD_ID, "quantity"});

		return true;
	}
	public static boolean withdrawItemFromInventory(OlioContext ctx, BaseRecord rec, String itemName, int count) {
		Optional<BaseRecord> item = getTemplateItems(ctx).stream().filter(i -> {
			String type = i.get("type");
			String name = i.get(FieldNames.FIELD_NAME);
			return type != null && type.equals("template") && name != null && name.equals(itemName);
		}).findFirst();
		
		if(item.isPresent()) {
			return withdrawItemFromInventory(ctx, rec, item.get(), count);
		}
		return false;
	}

	public static boolean withdrawItemFromInventory(OlioContext ctx, BaseRecord rec, BaseRecord item, int count) {
		BaseRecord store = rec.get(FieldNames.FIELD_STORE);
		if(store == null) {
			logger.warn("Store was null");
			return false;
		}
		if(count <= 0) {
			logger.warn("Invalid count: " + count);
			return false;
		}
		long id = item.get(FieldNames.FIELD_ID);
		BaseRecord invItem = null;
		Optional<BaseRecord> oinvItem = ((List<BaseRecord>)store.get("inventory")).stream().filter(i -> ((long)i.get("item.id")) == id).findFirst();
		if(oinvItem.isPresent()) {
			invItem = oinvItem.get();
		}
		else {
			/// Don't have any to withdraw
			return false;
		}
		
		int quan = ((int)invItem.get("quantity")) - count;
		if(quan < 0) {
			logger.warn("Withdraw amount is more than the quantity balance");
			return false;
		}
		invItem.setValue("quantity", quan);
		Queue.queueUpdate(invItem, new String[] {FieldNames.FIELD_ID, "quantity"});

		return true;
	}
	
	public static BaseRecord addItemToInventory(OlioContext ctx, BaseRecord rec, BaseRecord item) {
		BaseRecord store = rec.get(FieldNames.FIELD_STORE);
		if(store == null) {
			logger.warn("Store was null");
			return null;
		}
		BaseRecord inv = null;
		List<BaseRecord> entries = store.get("inventory");
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("inventories.path"));
		try {
			inv = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_INVENTORY_ENTRY, ctx.getOlioUser(), null, plist);
			inv.set("item", item);
			entries.add(inv);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return inv;
	}
	
	public static BaseRecord findStoredItemByName(BaseRecord person, String name) {
		BaseRecord item = null;
		BaseRecord store = person.get(FieldNames.FIELD_STORE);
		List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
		List<BaseRecord> iteml = store.get(OlioFieldNames.FIELD_ITEMS);
		for(BaseRecord a: appl) {
			List<BaseRecord> wearl = a.get(OlioFieldNames.FIELD_WEARABLES);
			Optional<BaseRecord> owear = wearl.stream().filter(r -> name.equals(r.get(FieldNames.FIELD_NAME))).findFirst();
			if(owear.isPresent()) {
				item = owear.get();
			}
		}
		if(item == null) {
			Optional<BaseRecord> oitem = iteml.stream().filter(r -> name.equals(r.get(FieldNames.FIELD_NAME))).findFirst();
			if(oitem.isPresent()) {
				item = oitem.get();
			}
		}
		return item;
	}
	
	public static BaseRecord buildItem(OlioContext ctx, String name) {
		BaseRecord itemT = getItemTemplate(ctx, name);
		if(itemT == null) {
			logger.error("Failed to retrieve template for " + name);
			return null;
		}
		return buildItem(ctx, itemT);
	}
	public static BaseRecord buildItem(OlioContext ctx, BaseRecord template) {
		BaseRecord item = OlioUtil.cloneIntoGroup(template, ctx.getWorld().get(OlioFieldNames.FIELD_ITEMS));
		try {
			item.set("type", null);
			item.set("tags", template.get("tags"));
			item.set("perks", template.get("perks"));
			item.set(OlioFieldNames.FIELD_FEATURES, template.get(OlioFieldNames.FIELD_FEATURES));
		}
		catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return item;
	}
	
	public static BaseRecord getCreateRawMaterial(OlioContext ctx, String name, String type, String cat) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("items.id"));
		q.field(FieldNames.FIELD_NAME, name);
		if(type != null) {
			q.field(FieldNames.FIELD_TYPE, type);
		}
		if(cat != null) {
			q.field("category", cat);
		}

		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			rec = newItem(ctx, name);
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
	
	public static List<BaseRecord> getTemplateBuilders(OlioContext ctx) {
		return Arrays.asList(OlioUtil.list(ctx, OlioModelNames.MODEL_BUILDER, OlioFieldNames.FIELD_BUILDERS, "type", "template"));
	}
	public static List<BaseRecord> getTemplateItems(OlioContext ctx) {
		return Arrays.asList(OlioUtil.list(ctx, OlioModelNames.MODEL_ITEM, OlioFieldNames.FIELD_ITEMS, "type", "template"));
	}
	public static void loadItems(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(ctx.getOlioUser(), OlioModelNames.MODEL_ITEM, ctx.getWorld().get("items.path")));
		if(count == 0) {
			BaseRecord[] items = importItems(ctx);
			Queue.processQueue();
			//IOSystem.getActiveContext().getRecordUtil().createRecords(items);
		}
	}
	
	/// "name:desc:type:category:store[location,person]:materials:perks:features:damage=1,range=0,protection=1,consumes=0:opacity=0.0,elasticity=0.0,glossiness=0.0,viscocity=0.0,sliminess=0.0,smoothness=0.0,hardness=0.0,toughness=0.0,defensive=0.0,offensive=0.0,waterresistance=0.0,heatresistance=0.0,insulation=0.0,skill=0.0"
	/// 
	protected static BaseRecord[] importItems(OlioContext ctx) {
		// logger.info("Import default item configuration");
		List<BaseRecord> items = JSONUtil.getList(ResourceUtil.getInstance().getResource("olio/items.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		List<BaseRecord> oitems = new ArrayList<>();

		Factory mf = IOSystem.getActiveContext().getFactory();

		try {
			for(BaseRecord item : items) {
				
				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("items.path"));
				plist.parameter(FieldNames.FIELD_NAME, item.get(FieldNames.FIELD_NAME));
				BaseRecord itm = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ITEM, ctx.getOlioUser(), item, plist);

				BaseRecord os = mf.newInstance(OlioModelNames.MODEL_ITEM_STATISTICS, ctx.getOlioUser(), item.get(OlioFieldNames.FIELD_STATISTICS), ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("statistics.path")));
				itm.set(OlioFieldNames.FIELD_STATISTICS, os);

				List<BaseRecord> qs = itm.get(OlioFieldNames.FIELD_QUALITIES);
				BaseRecord oq = mf.newInstance(OlioModelNames.MODEL_QUALITY, ctx.getOlioUser(), (qs.size() > 0 ? qs.get(0) : null), ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("qualities.path")));
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
				
				List<BaseRecord> feats = itm.get(OlioFieldNames.FIELD_FEATURES);
				List<BaseRecord> ifeats = new ArrayList<>();
				for(BaseRecord t: feats) {
					ifeats.add(OlioUtil.getCreateFeature(ctx, t.get(FieldNames.FIELD_NAME)));
				}
				itm.set(OlioFieldNames.FIELD_FEATURES, ifeats);

				Queue.queue(itm);
				oitems.add(itm);
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return oitems.toArray(new BaseRecord[0]);
	}
}
