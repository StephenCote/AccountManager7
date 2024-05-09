package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ComputeUtil;
import org.cote.accountmanager.util.ErrorUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ApparelUtil {
	public static final Logger logger = LogManager.getLogger(ApparelUtil.class);
	
	/*
	private static Pattern randomCountPattern = Pattern.compile("[\"]*\\$count\\s*=\\s*\\[([\\d]+)\\-([\\d]+)\\][\"]*");
	private static Pattern randomRangePattern = Pattern.compile("[\"]*\\$randomRange\\[([\\d\\.]+)\\-([\\d\\.]+)\\][\"]*");
	private static Pattern parameterTokenPattern = Pattern.compile("\"\\$\\{([A-Za-z]+)\\.([A-Za-z]+)([A-Za-z\\[\\]=,\\s\\d\\.\\$\\-]*)\\}\"");
	*/
	private static String[] clothingTypes = new String[0];
	private static String[] jewelryTypes = new String[0];
	private static String[] fabricTypes = new String[0];
	static {
		clothingTypes = JSONUtil.importObject(ResourceUtil.getResource("olio/clothing.json"), String[].class);
		jewelryTypes = JSONUtil.importObject(ResourceUtil.getResource("olio/jewelry.json"), String[].class);
		fabricTypes = JSONUtil.importObject(ResourceUtil.getResource("olio/fabrics.json"), String[].class);
	}
	private static String[] jewelryColors = new String[] {
		/// Gold
		"#d4af37",
		/// Rose Gold,
		"#b76e79",
		/// Silver,
		"#c0c0c0",
		/// Platinum
		"#e5e4e2",
		/// Titanium
		"#eee600"
	};
	
	//{"Black", "Gold (Metallic)", "Old Gold", "Pale Gold", "Pale Silver", "Silver", "Platinum", "Rose Gold", "Titanium"};
	private static String[] jewels = {"diamond", "ruby", "emerald", "sapphire", "pearl"};
	private static String jewrlLoc = "brow|hair|chest|breast|toe|ankle|wrist|neck|groin|tricep|nose|ear|eye|waist|belly";
	private static String garnLoc = "head|brow|hair|chest|breast|toe|ankle|wrist|hand|finger|neck|groin|bicep|tricep|face|nose|ear|eye|waist|belly";
	private static String cpref = "clothing:";
	private static String jpref = "jewelry:";
	
	private static SecureRandom rand = new SecureRandom();
	
	public static String[] getFabricTypes() {
		return fabricTypes;
	}
	protected static BaseRecord getApparelTemplate(OlioContext ctx, String name) {
		return getCreateApparel(ctx, name, "template", null);
	}
	
	public static void outfitAndStage(OlioContext ctx, BaseRecord cell, List<BaseRecord> party) {
		for(BaseRecord p: party) {
			BaseRecord sto = p.get("store");
			List<BaseRecord> appl = sto.get("apparel");
			List<BaseRecord> iteml = sto.get("items");
			List<String> upf = new ArrayList<>();
			if(appl.size() == 0) {
				BaseRecord app = ApparelUtil.randomApparel(ctx, p);
				List<BaseRecord> wears = app.get("wearables");
				wears.addAll(ApparelUtil.randomArmor(ctx));
				IOSystem.getActiveContext().getRecordUtil().createRecord(app);
				appl.add(app);
				ctx.queue(ParticipationFactory.newParticipation(ctx.getUser(), sto, "apparel", app));
			}
			if(iteml.size() == 0) {
				List<BaseRecord> arms = ItemUtil.randomArms(ctx);
				for(BaseRecord a: arms) {
					IOSystem.getActiveContext().getRecordUtil().createRecord(a);
					ctx.queue(ParticipationFactory.newParticipation(ctx.getUser(), sto, "items", a));
				}
				iteml.addAll(arms);
			}

			BaseRecord sta = p.get("state");
			if(cell != null && sta.get("currentLocation") == null) {
				sta.setValue("currentLocation", cell);
				StateUtil.agitateLocation(ctx, sta);
				ctx.queueUpdate(sta, new String[] {FieldNames.FIELD_ID, "currentLocation", "currentEast", "currentNorth"});
			}
		}
	}
	
	public static List<BaseRecord> randomArmor(OlioContext ctx) {
		List<BaseRecord> wears = new ArrayList<>();
		String[] protect = new String[] {"head", "chest", "neck", "upper arm", "forearm", "hand", "foot", "shin", "thigh", "elbow", "knee"};
		double[] protectOdds = new double[] {0.15, 0.25, 0.15, 0.15, 0.15, 0.25, 0.25, 0.15, 0.15, 0.10, 0.10};
		for(int i = 0; i < protect.length; i++) {
			String p = protect[i];
		
			if(rand.nextDouble() <= protectOdds[i]) {
				BaseRecord wearRec = OlioUtil.newGroupRecord(ctx.getUser(), ModelNames.MODEL_WEARABLE, ctx.getWorld().get("wearables.path"), null);
				List<BaseRecord> quals = wearRec.get("qualities");
				quals.add(OlioUtil.newGroupRecord(ctx.getUser(), ModelNames.MODEL_QUALITY, ctx.getWorld().get("qualities.path"), null));
				ApparelUtil.embedWearable(ctx, 0L, wearRec, ApparelUtil.randomWearable(WearLevelEnumType.OUTER, p, null));
				ApparelUtil.applyEmbeddedFabric(wearRec, ApparelUtil.randomFabric(WearLevelEnumType.OUTER, null));
				ApparelUtil.designWearable(ctx, 0L, wearRec);
				wears.add(wearRec);
			}
		}
		return wears;
	}
	
	protected static BaseRecord newApparel(OlioContext ctx, String name, String type, String cat) {
		BaseRecord rec = null;
		ParameterList plist = ParameterList.newParameterList("path", ctx.getUniverse().get("apparel.path"));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_APPAREL, ctx.getUser(), null, plist);
			rec.set(FieldNames.FIELD_TYPE, type);
			rec.set("category", cat);
			IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return rec;
	}
	protected static BaseRecord getCreateApparel(OlioContext ctx, String name, String type, String cat) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_APPAREL, FieldNames.FIELD_GROUP_ID, ctx.getUniverse().get("apparel.id"));
		q.field(FieldNames.FIELD_NAME, name);
		if(type != null) {
			q.field(FieldNames.FIELD_TYPE, type);
		}
		if(cat != null) {
			q.field("category", cat);
		}		
		BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(rec == null) {
			rec = newApparel(ctx, name, type, cat);
			IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
		}
		return rec;
		
	}

	public static String randomWearable(WearLevelEnumType level, String location, String gender) {
		return randomWearable(WearLevelEnumType.valueOf(level), location, gender);
	}	
	public static String randomWearable(int level, String location, String gender) {
		return randomWearable(clothingTypes, level, location, gender);
	}
	public static String randomWearable(String[] list, int level, String location, String gender) {
		String wear = null;
		List<String> wearl = filterWearables(list, level, location, gender, null);
		if(wearl.size() > 0) {
			wear = wearl.get(rand.nextInt(wearl.size()));
		}
		return wear;
	}
	
	private static List<String> filterWearables(String[] list, int level, String location, String gender, String name) {
		final String gcode;
		if(gender != null) {
			gcode = gender.substring(0, 1);
		}
		else {
			 gcode = "u";
		}
		return Arrays.asList(list).stream().filter(f -> {
			String[] tmat = f.split(":");
			if(tmat.length > 3) {
				int lvl = Integer.parseInt(tmat[1]);
				String gc = tmat[2];
				//logger.info(level + "-" + lvl + " / " + gcode + "-" + gc + " / " + name + "-" + tmat[0]);
				if((level < 0 || lvl == level) && (gc.equals("u") || gc.equals(gcode))) {
					if(name != null) {
						return name.equals(tmat[0]);
					}
					if(location == null) {
						return true;
					}
					String[] lcs = location.split("\\|");
					for(String lc : lcs) {
						if(tmat[3].contains(lc)) {
							return true;
						}
					}
				}
			}
			return false;
		}).collect(Collectors.toList());
	}
	
	public static String[] getEmbeddedOutfit(String[] names, String gender) {
		List<String> oft = new ArrayList<>();
		for(String n: names) {
			int mat = 0;
			if(n.indexOf(":") > -1) {
				if(!n.startsWith(cpref) && !n.startsWith(jpref)) {
					oft.add(cpref + n);
				}
				else {
					oft.add(n);
				}
				continue;
			}
			List<String> art = filterWearables(clothingTypes, -1, null, gender, n);
			if(art.size() > 0) {
				oft.add(cpref + art.get(0));
				mat++;
			}
			art = filterWearables(jewelryTypes, -1, null, gender, n);
			if(art.size() > 0) {
				oft.add(jpref + art.get(0));
				mat++;
			}
			if(mat == 0) {
				logger.warn("Didn't find '" + n + "'");
			}
		}
		return oft.toArray(new String[0]);
		
	}
	public static String[] randomOutfit(WearLevelEnumType minLevel, WearLevelEnumType maxLevel, String gender, double probableMid) {
		List<String> rol = new ArrayList<>();
		int low = WearLevelEnumType.valueOf(minLevel);
		int high = WearLevelEnumType.valueOf(maxLevel);
		int base = WearLevelEnumType.valueOf(WearLevelEnumType.BASE);
		int garn = WearLevelEnumType.valueOf(WearLevelEnumType.GARNITURE);
		int suit = WearLevelEnumType.valueOf(WearLevelEnumType.SUIT);

		for(int i = low; i <= high; i++) {
			boolean req = (i == base || i == suit);
			if(i == garn) {
				int cnt = rand.nextInt(4);
				for(int c = 0; c < cnt; c++) {
					if(probableMid >= rand.nextDouble()) {
						String wear = randomWearable(clothingTypes, i, garnLoc, gender);
						if(wear != null && !rol.contains(cpref + wear)) {
							rol.add(cpref + wear);
						}
					}
					if(probableMid >= rand.nextDouble()) {
						String wear = randomWearable(jewelryTypes, i, null, gender);
						if(wear != null && !rol.contains(jpref + wear)) {
							rol.add(jpref + wear);
						}
					}
				}
			}
			else {
				boolean topAndBottom = false;
				if(req || probableMid >= rand.nextDouble()) {
					String wear = randomWearable(i, "torso|breast|chest|shoulder|hand|head|neck", gender);
					if(wear != null && !rol.contains(cpref + wear)) {
						rol.add(cpref + wear);
						if(filterWearables(new String[] {wear}, i, "waist|hip|leg|thigh|groin", gender, null).size() == 1) {
							// logger.warn("Wearable covers both top and bottom, so skip choosing bottom at this level: " + wear);
							topAndBottom = true;
						}
					}
					if(!req) {
						wear = randomWearable(i, "shoulder|hand|head|neck", gender);
						if(wear != null && !rol.contains(cpref + wear)) {
							rol.add(cpref + wear);
						}
					}
				}
				if(!topAndBottom && (req || probableMid >= rand.nextDouble())) {
					String wear = randomWearable(i, "belly|waist|hip|leg|thigh|groin", gender);
					if(wear != null && !rol.contains(cpref + wear)) {
						rol.add(cpref + wear);
					}
					wear = randomWearable(i, "foot|ankle", gender);
					if(wear != null && !rol.contains(cpref + wear)) {
						rol.add(cpref + wear);
					}
				}
			}
		}
		return rol.toArray(new String[0]);
	}
	
	/*
	private static String replaceTokens(final String text) {
		
		Matcher mat = randomRangePattern.matcher(text);
		
		DecimalFormat df = new DecimalFormat("#.##");
		df.setRoundingMode(RoundingMode.HALF_EVEN);
		int idx = 0;
		StringBuilder rep = new StringBuilder();

		while(mat.find()) {
			double min = Double.parseDouble(mat.group(1));
			double max = Double.parseDouble(mat.group(2));
			double res = Double.parseDouble(df.format(rand.nextDouble(max-min) + min));
			VeryEnumType ver = VeryEnumType.valueOf(res);
			logger.info(min + " to " + max + " = " + res + " - " + ver.toString());
		    rep.append(text, idx, mat.start()).append(Double.toString(res));
		    idx = mat.end();
		}
		if (idx < text.length()) {
		    rep.append(text, idx, text.length());
		}
		return rep.toString();
	}
	private static String getRandomType(BaseRecord user, BaseRecord world, String type, String gender, String parms) {
		String ranType = "null";
		int count = 1;
		if(parms != null) {
			Matcher m = randomCountPattern.matcher(parms);
			if(m.find()) {
				int min = Integer.parseInt(m.group(1));
				int max = Integer.parseInt(m.group(2));
				count = rand.nextInt(max-min) + min;
			}
		}
		StringBuilder buff = new StringBuilder();
		for(int i = 0; i < count; i++) {
			BaseRecord rec = null;
			if(i > 0){
				buff.append(",\n");
			}
			if(type.equals("wearable")) {
				rec = OlioUtil.newGroupRecord(user, ModelNames.MODEL_WEARABLE, world.get("wearables.path"), null);
				if(gender != null) {
					try {
						rec.set("gender", gender);
					} catch (FieldException | ValueException | ModelNotFoundException e) {
						logger.error(e);
					}
				}
				applyParameters(user, world, rec, parms);
				applyRandomWearable(user, world, rec);
			}
			buff.append(rec.toFullString());
		}
		ranType = buff.toString();
		if(ranType.length() == 0) {
			ranType = "null";
		}

		return ranType;
	}
	*/
	
	private static void alignPatternAndColors(List<BaseRecord> wears, double complementRatio, double patternRatio) {
		BaseRecord primPatt = null;
		BaseRecord primCol = null;
		BaseRecord secCol = null;
		String fabric = null;
		try {
			for(BaseRecord rec : wears) {
				List<String> locs = rec.get("location");
				if(!locs.contains("feet") && !locs.contains("ankle")) {
					/// First article sets the color, complement, pattern, and fabric
					if(primCol == null) {
						primCol = rec.get("color");
						secCol = rec.get("complementColor");
						primPatt = rec.get("pattern");
						fabric = rec.get("fabric");
					}
					else {
						/// Change next article to the complementary color
						if(rand.nextDouble() > complementRatio) {
							rec.set("color", secCol);
						}
						/// Or, match the color
						else {
							rec.set("color", primCol);
						}
						/// Change the next article to the same pattern and fabric
						///
						if(rand.nextDouble() > patternRatio) {
							rec.set("pattern", primPatt);
							rec.set("fabric", fabric);
						}
					}
				}
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	
	protected static void designWearable(OlioContext ctx, long ownerId, BaseRecord wear) {
		BaseRecord randomColor = null;
		List<String> nfeats = new ArrayList<>();
		BaseRecord pattern = null;
		String cat = wear.get("category");

		if(cat != null && cat.equals("jewelry")) {
			randomColor = ColorUtil.getDefaultColor(ctx, ownerId, jewelryColors[rand.nextInt(jewelryColors.length)]);
			int randJ = rand.nextInt(100);
			if(randJ > 65) {
				nfeats.add(jewels[rand.nextInt(jewels.length)]);
				if(randJ > 90) {
					nfeats.add(jewels[rand.nextInt(jewels.length)]);	
				}
			}
			List<String> mats = wear.get("materials");
			mats.addAll(nfeats);
		}
		else {
			if(ctx != null) {
				randomColor = Decks.getRandomColor(ctx.getUser(), ctx.getUniverse());
				pattern = Decks.getRandomPattern(ctx.getUser(), ctx.getUniverse());
			}
			else {
				randomColor = ColorUtil.getDefaultColor(ctx, ownerId, ColorUtil.getRandomDefaultColor());
			}
		}
		BaseRecord compColor = randomColor;
		if(ctx != null && randomColor != null) {
			compColor = ColorUtil.findComplementaryColor(ctx.getUniverse(), randomColor.get("hex"));
		}
		try {
			wear.set("color", randomColor);
			wear.set("complementColor", compColor);
			wear.set("pattern", pattern);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}
	public static void designApparel(BaseRecord apparel) {
		List<BaseRecord> wears = apparel.get("wearables");
		List<BaseRecord> base = wears.stream().filter(w -> "clothing".equals(w.get("category")) && WearLevelEnumType.BASE.toString().equals((String)w.get("level"))).collect(Collectors.toList());
		List<BaseRecord> suit = wears.stream().filter(w -> "clothing".equals(w.get("category")) && WearLevelEnumType.SUIT.toString().equals((String)w.get("level"))).collect(Collectors.toList());
		alignPatternAndColors(base, 0.8, 0.5);
		alignPatternAndColors(suit, 0.6, 0.7);
	}

	public static BaseRecord constructApparel(OlioContext ctx, long ownerId, BaseRecord person, String[] names) {
		return constructApparel(ctx, ownerId, (String)person.get("gender"), getEmbeddedOutfit(names, (String)person.get("gender")));
	}

	
	public static BaseRecord randomApparel(OlioContext ctx, BaseRecord person) {
		return randomApparel(ctx, person.get(FieldNames.FIELD_OWNER_ID), (String)person.get("gender"));
	}
	
	public static BaseRecord randomApparel(OlioContext ctx, long ownerId, String gender) {
		String [] wears = randomOutfit(WearLevelEnumType.BASE, WearLevelEnumType.ACCESSORY, gender, .35);
		return constructApparel(ctx, ownerId, gender, wears);
	}

	private static BaseRecord constructApparel(OlioContext ctx, long ownerId, String gender, String[] wears) {
		BaseRecord app = null;
		try {
			if(ctx != null) {
				app = OlioUtil.newGroupRecord(ctx.getUser(), ModelNames.MODEL_APPAREL, ctx.getWorld().get("apparel.path"), null);
			}
			else {
	
				app = RecordFactory.newInstance(ModelNames.MODEL_APPAREL);
			}
			app.setValue("gender", gender);
			
			List<BaseRecord> wearList = app.get("wearables");
			
			for(String emb : wears) {
				BaseRecord wearRec = null;
				if(ctx != null) {
					wearRec = OlioUtil.newGroupRecord(ctx.getUser(), ModelNames.MODEL_WEARABLE, ctx.getWorld().get("wearables.path"), null);
				}
				else {
					wearRec = RecordFactory.newInstance(ModelNames.MODEL_WEARABLE);
				}
				List<BaseRecord> quals = wearRec.get("qualities");
				if(ctx != null) {
					quals.add(OlioUtil.newGroupRecord(ctx.getUser(), ModelNames.MODEL_QUALITY, ctx.getWorld().get("qualities.path"), null));
				}
				else {
					quals.add(RecordFactory.newInstance(ModelNames.MODEL_QUALITY));
				}
				wearList.add(wearRec);
				applyEmbeddedWearable(ctx, ownerId, wearRec, emb);
			}
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}

		designApparel(app);
		return app;

	}
	
	/*
	protected static String randomClothingType(String gender, String type) {
		String outType = null;
		String pref = cpref;
		String[] base = clothingTypes;
		if(type != null && type.equals("jewelry")) {
			pref = jpref;
			base = jewelryTypes;
		}
		while(outType == null) {
			String chk = base[rand.nextInt(base.length)];
			if(chk.equals("jewelry")) {
				base = jewelryTypes;
				continue;
			}
			if(gender != null && !chk.contains(":" + gender + ":") && !chk.contains(":u:")) {
				continue;
			}
			outType = chk;
		}
		return (pref + outType);
	}
	*/
	
	
	/*
	protected static void applyRandomWearable(OlioContext ctx, BaseRecord rec) {
		String type = rec.get(FieldNames.FIELD_TYPE);
		String gender = rec.get("gender");
		if(gender != null) gender = gender.substring(0,1).toLowerCase();
		else gender = "u";
		String randType = randomClothingType(gender, type);
		applyEmbeddedWearable(ctx, rec, randType);
	}
	*/
	
	/// name - level - gender - opacity - elastic - glossy - smooth - def - water - heat - insul
	public static String randomFabric(WearLevelEnumType level, String gender) {
		return randomFabric(WearLevelEnumType.valueOf(level), gender);
	}
	protected static String randomFabric(int level, String gender) {
		final String gcode;
		if(gender != null) gcode = gender.substring(0, 1);
		else gcode = "u";
		String lstr = Integer.toString(level);

		List<String> fabs = Arrays.asList(fabricTypes).stream().filter(f -> {
			String[] tmat = f.split(":");
			if(tmat.length > 3) {
				String gc = tmat[2];
				return (tmat[1].contains(lstr) && (gc.equals("u") || gc.equals(gcode)));
			}
			return false;
		}
		).collect(Collectors.toList());
		
		String outFab = null;
		if(fabs.size() > 0) {
			outFab = fabs.get(rand.nextInt(fabs.size()));
		}
		return outFab;
	}
	
	



	private static void embedWearable(OlioContext ctx, long ownerId, BaseRecord rec, String embType) {
		applyEmbeddedWearable(ctx, ownerId, rec, cpref + embType);
	}
	private static void applyEmbeddedWearable(OlioContext ctx, long ownerId, BaseRecord rec, String embType) {
		String gender = rec.get("gender");
		if(gender != null) gender = gender.substring(0,1).toLowerCase();
		else gender = "u";
		String[] tmeta = embType.split(":");
		try {
			String ttype = tmeta[0];
			
			rec.set("category", ttype);
			designWearable(ctx, ownerId, rec);

			rec.set(FieldNames.FIELD_TYPE, tmeta[1]);
			rec.set(FieldNames.FIELD_NAME, tmeta[1]);
			int lvl = Integer.parseInt(tmeta[2]);
			WearLevelEnumType wlvl = WearLevelEnumType.valueOf(lvl);
			if(ttype.equals("clothing")) {
				applyEmbeddedFabric(rec, randomFabric(lvl, gender));
			}
			rec.set("level", wlvl);
			if(tmeta[3].equals("u")){
				gender = "unisex";
			}
			else if(tmeta[3].equals("f")){
				gender = "female";
			}
			else{
				gender = "male";
			}
			rec.set("gender", gender);
			List<String> locs = rec.get("location");
			String[] plocs = tmeta[4].split(",");
			String ploc = plocs[rand.nextInt(plocs.length)];
			String[] iplocs = ploc.split("\\+");
			locs.addAll(Arrays.asList(iplocs));
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	// name - level - gender - opacity - elastic - glossy - smooth - def - water - heat - insul
	public static void applyFabric(BaseRecord rec, String fabricName) {
		List<String> fabs = Arrays.asList(fabricTypes).stream().filter(f -> {
			String[] tmat = f.split(":");
			if(tmat.length > 3) {
				String name = tmat[0];
				return name.toLowerCase().equals(fabricName.toLowerCase());
			}
			return false;
		}
		).collect(Collectors.toList());
		if(fabs.size() > 0) {
			applyEmbeddedFabric(rec, fabs.get(0));
		}

	}
	public static void applyEmbeddedFabric(BaseRecord rec, String emb) {
		if(emb == null) {
			logger.error("Fabric embed was null");
			return;
		}
		List<BaseRecord> quals = rec.get("qualities");
		if(quals.size() == 0) {
			logger.error("Qualities not defined");

			return;
		}
		BaseRecord qual = quals.get(0);
		String[] tmat = emb.split(":");
		try {
			if(rec.getModel().equals(ModelNames.MODEL_WEARABLE)) {
				rec.set("fabric", tmat[0]);
			}
			else if(rec.getModel().equals(ModelNames.MODEL_ITEM)) {
				List<String> mats = rec.get("materials");
				mats.add(tmat[0]);
			}
			else {
				logger.warn("Unhandled model for fab type: " + rec.getModel());
			}
			ComputeUtil.addDouble(qual, "opacity", Double.parseDouble(tmat[3]));
			ComputeUtil.addDouble(qual, "elasticity", Double.parseDouble(tmat[4]));
			ComputeUtil.addDouble(qual, "glossiness", Double.parseDouble(tmat[5]));
			ComputeUtil.addDouble(qual, "smoothness", Double.parseDouble(tmat[6]));
			ComputeUtil.addDouble(qual, "defensive", Double.parseDouble(tmat[7]));
			ComputeUtil.addDouble(qual, "waterresistance", Double.parseDouble(tmat[8]));
			ComputeUtil.addDouble(qual, "heatresistance", Double.parseDouble(tmat[9]));
			ComputeUtil.addDouble(qual, "insulation", Double.parseDouble(tmat[10]));
		} catch (ArrayIndexOutOfBoundsException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			logger.error(emb);
		}
	}
	/*
	private static void applyParameters(BaseRecord user, BaseRecord world, BaseRecord rec, String parms) {
		if(parms != null) {
			String[] pairs = parms.substring(1, parms.length() - 1).split(",");
			for(String pair : pairs) {
				String[] kv = pair.split("=");
				if(kv.length == 2) {
					logger.info(rec.getModel() + " - " + kv[0].trim() + " = " + kv[1].trim());
					String fname = kv[0].trim();
					if(fname.startsWith("$")) {
						continue;
					}
					FieldType f = null;
					if(fname.contains(".")) {
						String[] fp = fname.split("\\.");
						BaseRecord r1 = null;
						if(rec.getField(fp[0]).getValueType() == FieldEnumType.MODEL) {
							r1 = rec.get(fp[0]);
						}
						else if (rec.getField(fp[0]).getValueType() == FieldEnumType.LIST) {
							List<BaseRecord> rrecs = rec.get(fp[0]);
							if(rrecs.size() > 0) {
								r1 = rrecs.get(0);
							}
						}
						if(r1 != null) {
							f = r1.getField(fp[1]);
						}
					}
					else {
						f = rec.getField(kv[0].trim());
					}
					String val = kv[1].trim();
					try {
						switch(f.getValueType()) {
							case STRING:
								f.setValue(val);
								break;
							case LIST:
								List<String> lst = f.getValue();
								lst.add(val);
								break;
							case DOUBLE:
								f.setValue(Double.parseDouble(val));
								break;
							default:
								logger.error("Unhandled type: " + f.getValueType().toString() + " " + val);
								break;
						}
					}
					catch(ValueException e) {
						logger.error(e);
					}
				}
			}
		}
	}
	*/
	/*
	public static String getOlioResource(BaseRecord user, BaseRecord world, String apparelBase, String gender) {

		String appStr = replaceTokens(ResourceUtil.getResource(apparelBase));
		Matcher m = parameterTokenPattern.matcher(appStr);
		int idx = 0;
		StringBuilder rep = new StringBuilder();
		
		while(m.find()) {
			String type = m.group(1);
			String wear = m.group(2);
			String parms = null;
			if(m.groupCount() > 2) {
				parms = m.group(3);
			}
			String resStr = null;
			if(wear.equals("random")) {
				resStr = getRandomType(user, world, type, gender, parms);
				parms = null;
			}
			else {
				resStr = ResourceUtil.getResource("./olio/" + type + "/" + wear + ".json");
			}
			String wearx = replaceTokens(resStr);
			String repStr = "null";
			if(resStr != null && resStr.length() > 0 && !resStr.equals("null")) {
				BaseRecord temp1 = IOSystem.getActiveContext().getFactory().template("olio." + type, wearx);
				applyParameters(user, world, temp1, parms);
				OlioUtil.applyRandomOlioValues(user, world, temp1);
				repStr = temp1.toFullString();
			}
		    rep.append(appStr, idx, m.start()).append(repStr);
		    idx = m.end();
		}
		if (idx < appStr.length()) {
		    rep.append(appStr, idx, appStr.length());
		}

		return rep.toString();
	}
	*/
	public BaseRecord newApparel(BaseRecord user, BaseRecord world, String name, BaseRecord[] wearables) {
		String wpath = world.get("apparel.path");
		BaseRecord temp1 = IOSystem.getActiveContext().getFactory().template(ModelNames.MODEL_APPAREL, "{\"name\": \"" + name + "\"}");
		BaseRecord app = OlioUtil.newGroupRecord(user, ModelNames.MODEL_APPAREL, wpath, temp1);
		if(wearables != null && wearables.length > 0) {
			List<BaseRecord> wears = app.get("wearables");
			wears.addAll(Arrays.asList(wearables));
		}
		return app;
	}
	
	public BaseRecord newWearable(BaseRecord user, BaseRecord world, String gender, String name, String location) {
		String wpath = world.get("wearables.path");
		String qpath = world.get("qualities.path");
		BaseRecord temp1 = IOSystem.getActiveContext().getFactory().template(ModelNames.MODEL_WEARABLE, "{\"name\": \"" + name + "\",\"gender\":\"" + gender + "\", \"location\":[\"" + location + "\"]}");
		BaseRecord wear = OlioUtil.newGroupRecord(user, ModelNames.MODEL_WEARABLE, wpath, temp1);
		List<BaseRecord> quals = wear.get("qualities");
		quals.add(OlioUtil.newGroupRecord(user, ModelNames.MODEL_QUALITY, qpath, null));
		return wear;
	}
}
