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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ColorUtil {
	public static final Logger logger = LogManager.getLogger(ColorUtil.class);
	private static SecureRandom rand = new SecureRandom();
	private static List<BaseRecord> defaultColors = new ArrayList<>();
	static {
		defaultColors = JSONUtil.getList(ResourceUtil.getInstance().getResource("olio/colors.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}
	
	/**
	 * Complementary-color memoization, keyed {@code colorsGroupId + "|" + lowercased hex}.
	 * <p>
	 * It used to be a plain {@link HashMap} keyed by hex ALONE, while the lookup it serves is scoped
	 * by {@code world.get(colors.id)} - so in a process with more than one world, world B could be
	 * served world A's complement record for the same hex. Including the colors group id in the key
	 * removes that. For a single-world process this is not a behaviour change; for a multi-world
	 * process it corrects cross-world colour leakage.
	 * <p>
	 * {@code defaultColorMap}, which sat next to this one, was removed: it was read in
	 * {@code getDefaultColor} and never written anywhere in the codebase - dead code.
	 */
	private static final Map<String, BaseRecord> colorComplements = new ConcurrentHashMap<>();

	/** Maximum number of distinct colors-group scopes retained in {@link #colorComplements}. */
	private static final int MAX_COLOR_SCOPES = 16;

	/** colorsGroupId -&gt; last access tick, used for approximate-LRU scope eviction. */
	private static final Map<Long, Long> colorScopeAccess = new ConcurrentHashMap<>();

	private static final AtomicLong COLOR_SCOPE_TICK = new AtomicLong(0L);

	private static String complementKey(long colorsGroupId, String hex) {
		return colorsGroupId + "|" + (hex == null ? "" : hex.toLowerCase());
	}

	/**
	 * Approximate LRU over colors-group SCOPES (not individual hexes): the bound is on how many
	 * worlds' complement tables are retained, mirroring the per-world bound in {@code Decks}.
	 * Bounding individual hex entries instead would defeat the memoization, since a world generation
	 * pass walks hundreds of distinct hexes.
	 */
	private static void touchColorScope(long colorsGroupId) {
		colorScopeAccess.put(colorsGroupId, COLOR_SCOPE_TICK.incrementAndGet());
		if(colorScopeAccess.size() <= MAX_COLOR_SCOPES) {
			return;
		}
		synchronized(colorScopeAccess) {
			while(colorScopeAccess.size() > MAX_COLOR_SCOPES) {
				Long oldestKey = null;
				long oldest = Long.MAX_VALUE;
				for(Map.Entry<Long, Long> e : colorScopeAccess.entrySet()) {
					if(e.getValue() < oldest) {
						oldest = e.getValue();
						oldestKey = e.getKey();
					}
				}
				if(oldestKey == null) {
					break;
				}
				colorScopeAccess.remove(oldestKey);
				String prefix = oldestKey + "|";
				int removed = 0;
				for(String k : new ArrayList<>(colorComplements.keySet())) {
					if(k.startsWith(prefix)) {
						colorComplements.remove(k);
						removed++;
					}
				}
				logger.info("Evicted " + removed + " cached complementary colors for colors group #" + oldestKey + " (scope bound " + MAX_COLOR_SCOPES + " reached)");
			}
		}
	}

	/** Drop all memoized complementary colors. These are self-refilling lookups, not state. */
	public static void clearCache() {
		int n = colorComplements.size();
		colorComplements.clear();
		colorScopeAccess.clear();
		if(n > 0) {
			logger.info("Cleared " + n + " cached complementary color(s)");
		}
	}

	public static List<BaseRecord> getDefaultColors(){
		return defaultColors;
	}

	/**
	 * C3: resolve a free-text color NAME (e.g. an LLM-guessed apparel color like "Navy Blue") to the
	 * matching persisted {@code data.color} record in the world's SHARED color library
	 * ({@code ctx.getUniverse().colors} — the same group {@link #getDefaultColor} get-or-creates into),
	 * suitable for use as a FOREIGN reference on an {@code olio.item}/{@code olio.wearable} color field.
	 * Case-insensitive, whitespace-trimmed match on the library entry's {@code name}. Returns null when
	 * the name matches no library entry (callers keep whatever random color was already assigned) —
	 * never a raw string, and never a per-owner fallback group.
	 */
	public static BaseRecord getColorByName(OlioContext ctx, String name) {
		if (ctx == null || name == null) return null;
		String t = name.trim();
		if (t.isEmpty()) return null;
		BaseRecord universe = ctx.getUniverse();
		if (universe == null) return null;
		BaseRecord colorsGroup = universe.get(OlioFieldNames.FIELD_COLORS);
		if (colorsGroup == null) return null;
		Query q = QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_GROUP_ID, colorsGroup.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.ILIKE, t);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	protected static String getRandomDefaultColor() {
		return defaultColors.get(rand.nextInt(defaultColors.size())).get("hex");
	}
	
	public static double[] getHSL(int red, int green, int blue) {
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

	/**
	 * Resolve (get-or-create) a persisted {@code data.color} for one of the built-in default color
	 * hexes.
	 * <p>
	 * The {@code defaultColorMap} memoization that used to short-circuit the top of this method was
	 * removed: nothing in the codebase ever wrote to it, so the lookup could never hit.
	 *
	 * @param ctx optional Olio context; when present the world's shared colors group is used
	 * @param ownerId used only on the ctx-less fallback path below
	 * @param hex the default color hex
	 */
	protected static BaseRecord getDefaultColor(OlioContext ctx, long ownerId, String hex) {

		if(hex == null || defaultColors == null) {
			return null;
		}

		BaseRecord group = null;
		BaseRecord owner = null;
		if(ctx != null) {
			owner = ctx.getOlioUser();
			group = ctx.getUniverse().get(OlioFieldNames.FIELD_COLORS);
		}
		else {
			try {
				owner = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_USER, ownerId);
				if(owner != null) {
					IOSystem.getActiveContext().getReader().populate(owner, 2);
					/// DEFERRED (not fixed here): this makePath is a HOME-GROUP CREATE ON A READ PATH.
					/// It runs OWNER-SCOPED, not admin-scoped - the owner is read by ownerId just above,
					/// and on the REST path (OlioService:353,355 -> CharacterUtil:307,309, and
					/// ApparelUtil:442) that owner is the acting principal. So merely rolling a
					/// character can create "~/Colors" under the caller's home and persist color
					/// records into it.
					/// It is NOT removed in this change because those live callers have no `world` in
					/// scope; dropping the fallback would silently null out colors on
					/// GET /olio/roll/{gender} - a regression an Objects7 JUnit gate would not catch.
					/// Removal is its own change, with the world threaded down to these call sites.
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
	
	/**
	 * Find the complementary color to {@code colorHex} within the given world's colors group.
	 * <p>
	 * <b>Bypasses PBAC.</b> The lookup below is a RAW SQL read executed straight against the
	 * datasource - it builds a statement over {@code DBUtil.getTableName(data.color)} and runs it on
	 * a borrowed {@link java.sql.Connection}, so it goes nowhere near {@code AccessPoint} and no
	 * authorization check is applied to the returned record. Recorded here as a known issue; fixing
	 * it (pushing this through the query layer) is a separate change.
	 * <p>
	 * Results are memoized in {@link #colorComplements}, keyed by colors-group id AND hex - keying
	 * by hex alone leaked one world's complement records into another.
	 */
	public static BaseRecord findComplementaryColor(BaseRecord world, String colorHex) {
		long groupId = world.get(OlioFieldNames.FIELD_COLORS_ID);
		String cacheKey = complementKey(groupId, colorHex);
		if(colorComplements.containsKey(cacheKey)) {
			touchColorScope(groupId);
			return colorComplements.get(cacheKey);
		}
		BaseRecord outColor = null;
		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		String tableName = dbUtil.getTableName(ModelNames.MODEL_COLOR);

		/// A random offset between 0 and 10 is given to add a little variety
		/// An adjustment of 10% is added to give a little more variety
		/// OFFSET ?

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
				id = rset.getLong(FieldNames.FIELD_ID);
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
			colorComplements.put(cacheKey, outColor);
			touchColorScope(groupId);
		}
		return outColor;
	}
	
	
}
