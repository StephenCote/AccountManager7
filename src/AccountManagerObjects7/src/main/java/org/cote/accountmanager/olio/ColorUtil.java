package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ColorUtil {
	public static final Logger logger = LogManager.getLogger(ColorUtil.class);
	private static SecureRandom rand = new SecureRandom();
	private static List<BaseRecord> defaultColors = new ArrayList<>();
	static {
		defaultColors = JSONUtil.getList(ResourceUtil.getResource("olio/colors.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}
	
	/// TODO - these hashes need to be replaced
	private static Map<String, BaseRecord> colorComplements = new HashMap<>();
	private static Map<String, BaseRecord> defaultColorMap = new HashMap<>();
	
	public static List<BaseRecord> getDefaultColors(){
		return defaultColors;
	}
	
	protected static String getRandomDefaultColor() {
		return defaultColors.get(rand.nextInt(defaultColors.size())).get("hex");
	}
	
	public static double[] getHSL(int red, int green, int blue) {
		/*
		/// Calculate HSL
		int max = Math.max(red,  Math.max(green, blue));
		int min = Math.min(red,  Math.max(green, blue));
		
		double l = (max + min) / 2;
		double h = 0;
		double s = 0;
		double d = max - min;

		if (max == min) {
			h = 0;
			s = 0;
		}
		
		else {
			s = l > 0.5d ? d / (2 - d) : d / (max + min);
			if(max == red) {
				h = ((60 * (green - blue)) / (d + 360)) % 360;
			}
			else if (max == green) {
				h = ((60 * (blue - red)) / d) + 120;
			}
			else { /// max == blue
				h = ((60 * (red - green)) / d) + 240;
			}
		}
		return new double[] {h, s * 100, l * 100};
		*/
		// float[] hsl = new float[3];
		// Color.RGBtoHSB(red, green, blue, hsl);
		// return new double[] { floatToDouble(hsl[0]), floatToDouble(hsl[1]), floatToDouble(hsl[2])};
		
		float[] hsl = fromRGB(red, green, blue);
		return new double[] { floatToDouble(hsl[0]), floatToDouble(hsl[1]), floatToDouble(hsl[2])};
	}
	
	/// from https://gist.github.com/Yona-Appletree/0c4b58763f070ae8cdff7db583c82563
	public static float[] fromRGB(float r, float g, float b)
	{
		//  Get RGB values in the range 0 - 1

		//	Minimum and Maximum RGB values are used in the HSL calculations

		float min = Math.min(r, Math.min(g, b));
		float max = Math.max(r, Math.max(g, b));

		//  Calculate the Hue

		float h = 0;

		if (max == min)
			h = 0;
		else if (max == r)
			h = ((60 * (g - b) / (max - min)) + 360) % 360;
		else if (max == g)
			h = (60 * (b - r) / (max - min)) + 120;
		else if (max == b)
			h = (60 * (r - g) / (max - min)) + 240;

		//  Calculate the Luminance

		float l = (max + min) / 2;

		//  Calculate the Saturation

		float s = 0;

		if (max == min)
			s = 0;
		else if (l <= .5f)
			s = (max - min) / (max + min);
		else
			s = (max - min) / (2 - max - min);

		return new float[] {h, s * 100, l * 100};
	}

	private static Double floatToDouble(float f) {
		return Double.valueOf(Float.valueOf(f).toString()).doubleValue();
	}
	
	protected static BaseRecord getDefaultColor(OlioContext ctx, long ownerId, String hex) {
		
		if(hex == null || defaultColors == null) {
			return null;
		}
		
		String lhex = hex.toLowerCase();
		if(defaultColorMap.containsKey(lhex)) {
			return defaultColorMap.get(lhex);
		}
		
		BaseRecord group = null;
		BaseRecord owner = null;
		if(ctx != null) {
			owner = ctx.getUser();
			group = ctx.getUniverse().get("colors");
		}
		else {
			try {
				owner = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_USER, ownerId);
				if(owner != null) {
					group = IOSystem.getActiveContext().getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, "~/Colors", GroupEnumType.DATA.toString(), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
				}
			} catch (ReaderException e) {
				logger.error(e);
			}
		}
		if(owner == null) {
			logger.warn("Invalid owner: " + ownerId);
			return null;
		}
		Optional<BaseRecord> omdef = defaultColors.stream().filter(r -> hex.equals(r.get("hex"))).findFirst();
		if(!omdef.isPresent()) {
			logger.warn("Did not find default entry for " + hex);
			return null;
		}
		BaseRecord mdef = omdef.get();
		
		return OlioUtil.getCreateDirectoryObject(owner, ModelNames.MODEL_COLOR, mdef.get(FieldNames.FIELD_NAME), null, group, mdef);
		
	}
	
	public static BaseRecord findComplementaryColor(BaseRecord world, String colorHex) {
		if(colorComplements.containsKey(colorHex)) {
			return colorComplements.get(colorHex);
		}
		long groupId = world.get("colors.id");
		BaseRecord outColor = null;
		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		String tableName = dbUtil.getTableName(ModelNames.MODEL_COLOR);

		/// A random offset between 0 and 10 is given to add a little variety
		/// An adjustment of 10% is added to give a little more variety
		///
		/*
		String sql = "SELECT C1.id as colorId, sqrt((power(C1.red - C2.red,2) *1.1) + (power(C1.green - C2.green,2) * 1.1) "
			+ "+ (power(C1.blue - C2.blue,2) * 1.1)) as dist, C2.hex as complement FROM "
			+ tableName + " C1 "
			+ "CROSS JOIN " + tableName + " C2 "
			+ "WHERE C1.name = ? "
			+ "AND NOT C2.name = 'Black' "
			+ " AND NOT C2.name = 'White' "
			+ " AND C1.groupId = ? AND C2.groupId = ? LIMIT 1 OFFSET ?"
		;
		*/
		StringBuilder buff = new StringBuilder();
		buff.append("SELECT id, name FROM (");
		buff.append("(SELECT id, name, hue FROM " + tableName + " C2 WHERE C2.hue >= (SELECT (CASE WHEN hue <= 0.5 THEN (hue + 0.5) ELSE (hue - 0.5) END) as chue FROM " + tableName + " WHERE hex = ? AND groupId = ? LIMIT 1) ORDER BY C2.hue LIMIT 1)");
		buff.append(" UNION ALL ");
		buff.append("(SELECT id, name, hue FROM " + tableName + " C3 WHERE C3.hue < (SELECT (CASE WHEN hue <= 0.5 THEN (hue + 0.5) ELSE (hue - 0.5) END) as chue FROM " + tableName + " WHERE hex = ? AND groupId = ? LIMIT 1) ORDER BY C3.hue DESC LIMIT 1)");
		buff.append(") as hs ORDER BY abs(hue) LIMIT 1;");
		long id = 0L;
		try (Connection con = dbUtil.getDataSource().getConnection(); PreparedStatement statement = con.prepareStatement(buff.toString())){

			statement.setString(1, colorHex);
			statement.setLong(2, groupId);
			statement.setString(3, colorHex);
			statement.setLong(4, groupId);
			//statement.setInt(4, rand.nextInt(10));
			
			ResultSet rset = statement.executeQuery();
			if(rset.next()) {
				id = rset.getLong("id");
			}
			rset.close();
			
		} catch (NullPointerException | SQLException e) {
			logger.error(e);
		}
		if(id > 0L) {
			try {
				outColor = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_COLOR, id);
			} catch (ReaderException e) {
				logger.error(e);
			}
		}
		if(outColor != null) {
			colorComplements.put(colorHex, outColor);
		}
		return outColor;
	}
	
	
}
