package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ApparelUtil {
	public static final Logger logger = LogManager.getLogger(ApparelUtil.class);
	
	private static Pattern randomCountPattern = Pattern.compile("[\"]*\\$count\\s*=\\s*\\[([\\d]+)\\-([\\d]+)\\][\"]*");
	private static Pattern randomRangePattern = Pattern.compile("[\"]*\\$randomRange\\[([\\d\\.]+)\\-([\\d\\.]+)\\][\"]*");
	private static Pattern parameterTokenPattern = Pattern.compile("\"\\$\\{([A-Za-z]+)\\.([A-Za-z]+)([A-Za-z\\[\\]=,\\s\\d\\.\\$\\-]*)\\}\"");
	
	private static String[] clothingTypes = new String[0];
	private static String[] jewelryTypes = new String[0];
	private static String[] fabricTypes = new String[0];
	static {
		clothingTypes = JSONUtil.importObject(ResourceUtil.getResource("./olio/clothing.json"), String[].class);
		jewelryTypes = JSONUtil.importObject(ResourceUtil.getResource("./olio/jewelry.json"), String[].class);
		fabricTypes = JSONUtil.importObject(ResourceUtil.getResource("./olio/fabrics.json"), String[].class);
	}
	private static String[] jewelryColors = {"Black", "Gold (Metallic)", "Old Gold", "Pale Gold", "Pale Silver", "Silver", "Platinum", "Rose Gold", "Titanium"};
	private static String[] jewels = {"diamond", "ruby", "emerald", "sapphire", "pearl"};
	
	private static SecureRandom rand = new SecureRandom();
	
	public static String randomWearable(int level, String location, String gender) {
		return randomWearable(clothingTypes, level, location, gender);
	}
	public static String randomWearable(String[] list, int level, String location, String gender) {
		String wear = null;
		List<String> wearl = filterWearables(list, level, location, gender);
		if(wearl.size() > 0) {
			wear = wearl.get(rand.nextInt(wearl.size()));
		}
		return wear;
	}
	
	private static List<String> filterWearables(String[] list, int level, String location, String gender) {
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
				if(lvl == level && (gc.equals("u") || gc.equals(gcode))) {
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
	
	private static String jewrlLoc = "brow|hair|chest|breast|toe|ankle|wrist|neck|groin|tricep|nose|ear|eye|waist|belly";
	private static String garnLoc = "head|brow|hair|chest|breast|toe|ankle|wrist|hand|finger|neck|groin|bicep|tricep|face|nose|ear|eye|waist|belly";
	private static String cpref = "clothing:";
	private static String jpref = "jewelry:";
	
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
						String wear = randomWearable(jewelryTypes, i, jewrlLoc, gender);
						if(wear != null && !rol.contains(jpref + wear)) {
							rol.add(jpref + wear);
						}
					}
				}
			}
			else {
				if(req || probableMid >= rand.nextDouble()) {
					String wear = randomWearable(i, "torso|breast|chest|shoulder|hand|head|neck", gender);
					if(wear != null && !rol.contains(cpref + wear)) {
						rol.add(cpref + wear);
					}
					if(!req) {
						wear = randomWearable(i, "shoulder|hand|head|neck", gender);
						if(wear != null && !rol.contains(cpref + wear)) {
							rol.add(cpref + wear);
						}
					}
				}
				if(req || probableMid >= rand.nextDouble()) {
					String wear = randomWearable(i, "belly|waist|hip|leg|waist|groin", gender);
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
	private static Map<String, String> colorComplements = new HashMap<>();
	public static String findComplementaryColor(BaseRecord world, String colorName) {
		if(colorComplements.containsKey(colorName)) {
			return colorComplements.get(colorName);
		}
		long groupId = world.get("colors.id");
		String outColor = null;
		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		String tableName = dbUtil.getTableName(ModelNames.MODEL_COLOR);
		/// A random offset between 0 and 10 is given to add a little variety
		/// An adjustment of 10% is added to give a little more variety
		///
		String sql = "SELECT C1.name as color, sqrt((power(C1.red - C2.red,2) *1.1) + (power(C1.green - C2.green,2) *1.1) "
			+ "+ (power(C1.blue - C2.blue,2) *1.1)) as dist, C2.name as complement FROM "
			+ tableName + " C1 "
			+ "CROSS JOIN " + tableName + " C2 "
			+ "WHERE C1.name = ? "
			+ "AND NOT C2.name = 'Black' "
			+ " AND NOT C2.name = 'White' "
			+ " AND C1.groupId = ? AND C2.groupId = ? OFFSET ? LIMIT 1"
		;
		try (Connection con = dbUtil.getDataSource().getConnection(); PreparedStatement statement = con.prepareStatement(sql)){

			statement.setString(1, colorName);
			statement.setLong(2, groupId);
			statement.setLong(3, groupId);
			statement.setInt(4, rand.nextInt(10));
			
			ResultSet rset = statement.executeQuery();
			if(rset.next()) {
				outColor = rset.getString("complement");
			}
			rset.close();
			
		} catch (NullPointerException | SQLException e) {
			logger.error(e);
			if(sql != null) {
				logger.error(JSONUtil.exportObject(sql));
			}
		}
		if(outColor != null) {
			colorComplements.put(colorName, outColor);
		}
		return outColor;
	}
	private static void alignPatternAndColors(List<BaseRecord> wears, double complementRatio, double patternRatio) {
		BaseRecord primPatt = null;
		String primCol = null;
		String secCol = null;
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
	
	public static void designApparel(BaseRecord apparel) {
		List<BaseRecord> wears = apparel.get("wearables");
		List<BaseRecord> base = wears.stream().filter(w -> "clothing".equals(w.get("category")) && WearLevelEnumType.BASE.toString().equals((String)w.get("level"))).collect(Collectors.toList());
		List<BaseRecord> suit = wears.stream().filter(w -> "clothing".equals(w.get("category")) && WearLevelEnumType.SUIT.toString().equals((String)w.get("level"))).collect(Collectors.toList());
		alignPatternAndColors(base, 0.8, 0.5);
		alignPatternAndColors(suit, 0.6, 0.7);
	}
	
	public static BaseRecord randomApparel(BaseRecord user, BaseRecord world, BaseRecord person) {
		return randomApparel(user, world, (String)person.get("gender"));
	}
	
	public static BaseRecord randomApparel(BaseRecord user, BaseRecord world, String gender) {
		IOSystem.getActiveContext().getReader().populate(world, 2);
		BaseRecord parWorld = world.get("basis");
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		BaseRecord app = OlioUtil.newGroupRecord(user, ModelNames.MODEL_APPAREL, world.get("apparel.path"), null);
		try {
			app.set("gender", gender);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		String [] wears = randomOutfit(WearLevelEnumType.BASE, WearLevelEnumType.ACCESSORY, gender, .35);
		List<BaseRecord> wearList = app.get("wearables");
		
		for(String emb : wears) {
			BaseRecord wearRec = OlioUtil.newGroupRecord(user, ModelNames.MODEL_WEARABLE, world.get("wearables.path"), null);
			List<BaseRecord> quals = wearRec.get("qualities");
			quals.add(OlioUtil.newGroupRecord(user, ModelNames.MODEL_QUALITY, world.get("qualities.path"), null));
			wearList.add(wearRec);
			applyEmbeddedWearable(user, world, wearRec, emb);
		}
		designApparel(app);
		return app;
		
	}
	
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
	
	
	
	protected static void applyRandomWearable(BaseRecord user, BaseRecord world, BaseRecord rec) {
		String type = rec.get(FieldNames.FIELD_TYPE);
		String gender = rec.get("gender");
		BaseRecord parWorld = world.get("basis");
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		if(gender != null) gender = gender.substring(0,1).toLowerCase();
		else gender = "u";
		String randType = randomClothingType(gender, type);
		applyEmbeddedWearable(user, world, rec, randType);
	}
	
	/// name - level - gender - opacity - elastic - glossy - smooth - def - water - heat - insul
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
	protected static void applyEmbeddedWearable(BaseRecord user, BaseRecord world, BaseRecord rec, String embType) {
		BaseRecord parWorld = world.get("basis");
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		long patternDir = parWorld.get("patterns.id");
		String gender = rec.get("gender");
		if(gender != null) gender = gender.substring(0,1).toLowerCase();
		else gender = "u";

		String[] tmeta = embType.split(":");
		try {
			String ttype = tmeta[0];
			
			rec.set("category", ttype);
			String randomColor = null;
			List<String> nfeats = new ArrayList<>();
			BaseRecord pattern = null;
			if(ttype.equals("jewelry")) {
				randomColor = jewelryColors[rand.nextInt(jewelryColors.length)];
				int randJ = rand.nextInt(100);
				if(randJ > 65) {
					nfeats.add(jewels[rand.nextInt(jewels.length)]);
					if(randJ > 90) {
						nfeats.add(jewels[rand.nextInt(jewels.length)]);	
					}
				}
				List<String> mats = rec.get("materials");
				mats.addAll(nfeats);
			}
			else {
				randomColor = OlioUtil.getRandomOlioValue(user, parWorld, "color");
				Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, patternDir);
				q.setRequest(new String[]{FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_DESCRIPTION});
				pattern = OlioUtil.randomSelection(user, q);
			}
			String compColor = findComplementaryColor(parWorld, randomColor);
			rec.set("color", randomColor);
			rec.set("complementColor", compColor);
			rec.set("pattern", pattern);
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
	private static void applyEmbeddedFabric(BaseRecord wearable, String emb) {
		if(emb == null) {
			return;
		}
		List<BaseRecord> quals = wearable.get("qualities");
		if(quals.size() == 0) {
			return;
		}
		BaseRecord qual = quals.get(0);
		String[] tmat = emb.split(":");
		try {
			wearable.set("fabric", tmat[0]);
			qual.set("opacity", Double.parseDouble(tmat[3]));
			qual.set("elasticity", Double.parseDouble(tmat[4]));
			qual.set("glossiness", Double.parseDouble(tmat[5]));
			qual.set("smoothness", Double.parseDouble(tmat[6]));
			qual.set("defensive", Double.parseDouble(tmat[7]));
			qual.set("waterresistance", Double.parseDouble(tmat[8]));
			qual.set("heatresistance", Double.parseDouble(tmat[9]));
			qual.set("insulation", Double.parseDouble(tmat[10]));
		} catch (ArrayIndexOutOfBoundsException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			logger.error(emb);
		}
	}
	
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
