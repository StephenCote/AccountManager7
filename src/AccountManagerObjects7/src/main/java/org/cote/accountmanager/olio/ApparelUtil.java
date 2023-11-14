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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
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
		private static SecureRandom rand = new SecureRandom();
		
		private static String replaceTokens(final String text) {
			
			Matcher mat = randomRangePattern.matcher(text);
			
			DecimalFormat df = new DecimalFormat("#.##");
			df.setRoundingMode(RoundingMode.HALF_EVEN);
			//String workText = text;
			int idx = 0;
			StringBuilder rep = new StringBuilder();

			while(mat.find()) {
				double min = Double.parseDouble(mat.group(1));
				double max = Double.parseDouble(mat.group(2));
				double res = Double.parseDouble(df.format(rand.nextDouble(max-min) + min));
				VeryEnumType ver = VeryEnumType.valueOf(res);
				logger.info(min + " to " + max + " = " + res + " - " + ver.toString());
				//workText = mat.replaceAll(Double.toString(res));
			    rep.append(text, idx, mat.start()).append(Double.toString(res));
			    idx = mat.end();

			}
			if (idx < text.length()) {
			    rep.append(text, idx, text.length());
			}
			return rep.toString();
		}
		public static String getRandomType(BaseRecord user, BaseRecord world, String type, String gender, String parms) {
			String ranType = "null";
			logger.info("Randomize type: " + type);
			List<BaseRecord> objs = new ArrayList<>();

				
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
			logger.info("Generating " + count + " random " + type);
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
			///
			String sql = "SELECT C1.name as color, sqrt((power(C1.red - C2.red,2) *1.1) + (power(C1.green - C2.green,2) *1.1) "
				+ "+ (power(C1.blue - C2.blue,2) *1.1)) as dist, C2.name as compliment FROM "
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
					outColor = rset.getString("compliment");
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
		
		private static String[] clothingTypes = {"jewelry", "belt:u:waist", "bikini top:f:breast", "bikini bottom:f:hip", "blouse:f:torso", "boots:u:foot", "boxer shorts:m:waist", "bra:f:breast", "cap:u:head", "cardigan:u:torso", "cargo pants:u:hip+leg", "coat:u:torso", "dress:f:torso+thigh", "evening gown:f:torso+leg", "g-string:f:hip", "thong:f:hip", "gloves:u:hand", "hat:u:head", "hoodie:u:head+torso", "jacket:u:torso", "jeans:u:hip+leg", "leggings:f:hip+leg", "mittens:u:hand", "overalls:u:torso+hip+leg", "pajama top:u:torso", "pajama bottom:u:hip+leg", "panties:f:hip", "pants:u:hip+leg", "pantyhose:f:hip+leg", "polo shirt:u:torso", "pullover:u:torso", "raincoat:u:torso+hip", "scarf:u:neck", "shawl:f:neck+shoulder", "shirt:u:torso+arm", "shoes:u:foot", "shorts:u:hip", "skirt:f:hip+thigh", "slacks:u:hip+leg", "socks:u:foot+ankle", "suit jacket:u:torso+arm", "suit pants:u:hip+leg", "sweater:u:torso+arm", "sweatpants:u:hip+leg", "sweatshirt:u:hip+leg", "swim trunks:m:hip", "swimsuit:f:torso+hip", "t-shirt:u:torso", "tank top:u:shoulder+chest", "tie:m:neck", "tracksuit:u:torso+hip+leg", "trench coat:u:torso+hip+thigh", "tuxedo jacket:m:torso+arm", "tuxedo pants:m:hip+leg", "underwear:u:hip", "undershirt:u:torso", "vest:u:torso", "wedding dress:f:torso+hip+leg", "windbreaker:u:torso"};
		private static String[] jewelryTypes = {"armlet:u:tricep", "bangle:u:wrist", "bracelet:u:wrist", "cuff links:m:wrist,chest", "ring:u:finger,toe", "slave bracelet:f:wrist", "belly chain:f:belly", "body piercing:u:brow,nose,lip,navel,groin,breast", "earring:u:ear", "chatelain:f:waist", "brooch:f:chest", "anklet:f:ankle", "amulet:u:neck", "pledge pin:u:chest", "dog tags:u:neck", "prayer beads:u:neck", "prayer rope:u:wrist", "signet ring:u:finger", "watch:u:wrist"};
		private static String[] jewelryColors = {"Black", "Gold (Metallic)", "Old Gold", "Pale Gold", "Pale Silver", "Silver", "Platinum", "Rose Gold", "Titanium"};
		private static String[] jewels = {"diamond", "ruby", "emerald", "sapphire", "pearl"};
		
		public static BaseRecord randomApparel(BaseRecord user, BaseRecord world, String gender) {
			IOSystem.getActiveContext().getReader().populate(world, 2);
			BaseRecord parWorld = world.get("basis");
			IOSystem.getActiveContext().getReader().populate(parWorld, 2);
			BaseRecord app = OlioUtil.newGroupRecord(user, ModelNames.MODEL_APPAREL, world.get("apparel.path"), null);
			
			String[] wrand = {"head","eye","wrist","hand"};
			String[] male = {"+torso","+hip,leg","+foot","underwear"};
			String[] female = {"+torso","+hip,leg","+foot","bra","+underwear,g-string,thong"};
			String[] use = (gender != null && gender.equals("female") ? female: male);
			int irand = rand.nextInt(100);
			List<String> wears = new ArrayList<>();
			if(irand > 50) {

			}
			return app;
			
		}
		
		protected static String randomClothingType(String gender, String type) {
			String outType = null;
			String pref = "clothing:";
			String[] base = clothingTypes;
			if(type != null && type.equals("jewelry")) {
				pref = "jewelry:";
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
		
		protected static void applyEmbeddedWearable(BaseRecord user, BaseRecord world, BaseRecord rec, String embType) {
			BaseRecord parWorld = world.get("basis");
			IOSystem.getActiveContext().getReader().populate(parWorld, 2);
			String gender = rec.get("gender");
			if(gender != null) gender = gender.substring(0,1).toLowerCase();
			else gender = "u";

			String[] tmeta = embType.split(":");
			try {
				String ttype = tmeta[0];
				rec.set("category", ttype);
				String randomColor = null;
				List<String> nfeats = new ArrayList<>();
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
				}
				String compColor = findComplementaryColor(parWorld, randomColor);
				// logger.info("color - " + randomColor + " <- " + compColor);
				rec.set("color", randomColor);
				rec.set("complementColor", compColor);
				rec.set(FieldNames.FIELD_TYPE, tmeta[1]);
				if(tmeta[2].equals("u")){
					gender = "unisex";
				}
				else if(tmeta[2].equals("f")){
					gender = "female";
				}
				else{
					gender = "male";
				}
				rec.set("gender", gender);
				List<String> locs = rec.get("location");
				String[] plocs = tmeta[3].split(",");
				String ploc = plocs[rand.nextInt(plocs.length)];
				String[] iplocs = ploc.split("\\+");
				locs.addAll(Arrays.asList(iplocs));
			}
			catch(ValueException | FieldException | ModelNotFoundException e) {
				logger.error(e);
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
				// logger.info(wearx);
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
		
		public BaseRecord outfit(BaseRecord user, String path, String apparelStr) {
			BaseRecord temp1 = IOSystem.getActiveContext().getFactory().template(ModelNames.MODEL_APPAREL, apparelStr);
			
			return temp1;
		}
		
		/*
		public BaseRecord newApparel(BaseRecord user, String name, String type, String groupPath, BaseRecord[] wearables) {
			BaseRecord temp1 = IOSystem.getActiveContext().getFactory().template(ModelNames.MODEL_APPAREL, "{\"name\": \"" + name + "\",\"type\":\"" + type + "\"}");
			BaseRecord app = OlioUtil.newGroupRecord(user, ModelNames.MODEL_APPAREL, groupPath, temp1);
			List<BaseRecord> wables = app.get("wearables");
			wables.addAll(Arrays.asList(wearables));
			return IOSystem.getActiveContext().getAccessPoint().create(user, app);
		}
		
		public BaseRecord newWearable(BaseRecord user, String name, String location, String groupPath) {
			BaseRecord temp1 = IOSystem.getActiveContext().getFactory().template(ModelNames.MODEL_WEARABLE, "{\"name\": \"" + name + "\",\"location\":[\"" + location + "\"]}");
			BaseRecord wear = OlioUtil.newGroupRecord(user, ModelNames.MODEL_WEARABLE, groupPath, temp1);
			List<BaseRecord> quals = wear.get("qualities");
			quals.add(OlioUtil.newGroupRecord(user, ModelNames.MODEL_QUALITY, groupPath, null));
			return IOSystem.getActiveContext().getAccessPoint().create(user, wear);
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
