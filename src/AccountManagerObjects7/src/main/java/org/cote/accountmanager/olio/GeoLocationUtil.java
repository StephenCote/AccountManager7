package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GeographyEnumType;

public class GeoLocationUtil {
	public static final Logger logger = LogManager.getLogger(GeoLocationUtil.class);
	private static Map<String, String[]> altNamesCache = new HashMap<>();

	private static Map<Long, List<BaseRecord>> cellMap = new ConcurrentHashMap<>();

	/// 30K is in the middle of the ocean
	public static final String GZD = "30K";
	public static final DecimalFormat df2 = new DecimalFormat("00");
	public static final DecimalFormat df3 = new DecimalFormat("000");

	private static SecureRandom rand = new SecureRandom();
    
	public static int getMaximumHeight(List<BaseRecord> locations) {
		return getMaximumInt(locations, "northings");
	}
	public static int getMaximumWidth(List<BaseRecord> locations) {
		return getMaximumInt(locations, "eastings");
	}
	public static int getMinimumHeight(List<BaseRecord> locations) {
		return getMinimumInt(locations, "northings");
	}
	public static int getMinimumWidth(List<BaseRecord> locations) {
		return getMinimumInt(locations, "eastings");
	}
	public static int getMaximumInt(List<BaseRecord> locations, String field) {
		int val = 0;
		Optional<Integer> oval = locations.stream().map(l -> (int)l.get(field)).max(Comparator.naturalOrder());
		if(oval.isPresent()) {
			val = oval.get();
		}
		return val;
	}

	public static int getMinimumInt(List<BaseRecord> locations, String field) {
		int val = 0;
		Optional<Integer> oval = locations.stream().map(l -> (int)l.get(field)).min(Comparator.naturalOrder());
		if(oval.isPresent()) {
			val = oval.get();
		}
		return val;
	}
	
	public static List<BaseRecord> limitToAdjacent(OlioContext ctx, List<BaseRecord> pop, BaseRecord location){
		long locId = location.get(FieldNames.FIELD_ID);
		List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(ctx, location, Rules.MAXIMUM_OBSERVATION_DISTANCE);
		List<Long> aids = acells.stream().map(c -> ((long)c.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		return pop.stream().filter(zp ->{
			BaseRecord zloc = zp.get("state.currentLocation");
			long zlid = (zloc != null ? zloc.get("id") : 0L);
			return (zlid > 0 && (zlid == locId || aids.contains(zlid)));
		}).collect(Collectors.toList());
	}
	
	public static void prepareGridCells(OlioContext ctx, BaseRecord location) {
		// ParameterList plist = ParameterList.newParameterList("path", ctx.getUniverse().get("locations.path"));
		IOSystem.getActiveContext().getReader().populate(location);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));
		
		cq.field("geoType", "cell");
		int count = IOSystem.getActiveContext().getSearch().count(cq);
		if(count > 0) {
			logger.info("Location " + location.get(FieldNames.FIELD_NAME) + " is already prepared with cells");
			return;
		}
		logger.info("Preparing " + location.get(FieldNames.FIELD_NAME) + " cells");
		// logger.info(location.toFullString());
		int iter = 1 + IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, location.get(FieldNames.FIELD_GROUP_ID)));
		List<BaseRecord> cells = new ArrayList<>();
		try {
			for(int y = 0; y < Rules.MAP_EXTERIOR_CELL_HEIGHT; y++) {
				for(int x = 0; x < Rules.MAP_EXTERIOR_CELL_WIDTH; x++) {
					int ie = x; //  * 10
					int in = y; //  * 10
	    			BaseRecord cell = GeoLocationUtil.newLocation(ctx, location, GZD + " " + location.get("kident") + " " + df2.format((int)location.get("eastings")) + "" + df2.format((int)location.get("northings")) + " " + df3.format(ie) + "" + df3.format(in), iter++);
	    			/// Location util defaults to putting new locations into the universe.  change to the world group
	    			///
	    			cell.set(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
	    			cell.set("area", (double)10);
	    			cell.set("gridZone", GZD);
	    			cell.set("kident", location.get("kident"));
	    			cell.set("eastings", x);
	    			cell.set("northings", y);
	    			cell.set("geoType", "cell");
	    			cells.add(cell);
				}
			}
			TerrainUtil.blastCells(ctx, location, cells, Rules.MAP_EXTERIOR_CELL_WIDTH, Rules.MAP_EXTERIOR_CELL_HEIGHT);
			IOSystem.getActiveContext().getRecordUtil().createRecords(cells.toArray(new BaseRecord[0]));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}

	
	public static List<BaseRecord> getAdjacentCells(OlioContext ctx, BaseRecord origin, int distance){
		BaseRecord parentLoc = getParentLocation(ctx, origin);
		return getAdjacentCells(getCells(ctx, parentLoc), origin, distance);
	}
	public static List<BaseRecord> getAdjacentCells(List<BaseRecord> cells, BaseRecord origin, int distance){
		IOSystem.getActiveContext().getReader().populate(origin, new String[] {"eastings", "northings"});
		int east = origin.get("eastings");
		int north = origin.get("northings");
		
		int peast = east + distance;
		int neast = east - distance;
		int pnorth = north + distance;
		int nnorth = north - distance;
		long id = origin.get("id");
		List<BaseRecord> outCells = cells.stream().filter(c -> {
			long cid = c.get("id");
			int ceast = c.get("eastings");
			int cnorth = c.get("northings");
			return (cid != id && (ceast == peast || ceast == neast || ceast == east) && (cnorth == pnorth || cnorth == nnorth || cnorth == north));
		}).collect(Collectors.toList());
		return outCells;
	}
	public static BaseRecord getParentLocation(OlioContext ctx, BaseRecord location) {
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_ID, location.get(FieldNames.FIELD_PARENT_ID));
		cq.setLimitFields(false);
		return IOSystem.getActiveContext().getSearch().findRecord(cq);
	}

	public static List<BaseRecord> getCells(OlioContext ctx, BaseRecord location){
		long id = location.get(FieldNames.FIELD_ID);
		if(cellMap.containsKey(id)) {
			return cellMap.get(id);
		}
		IOSystem.getActiveContext().getReader().populate(location);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, id);
		cq.field("geoType", "cell");
		cq.setCache(false);
		cq.setLimitFields(false);
		List<BaseRecord> cells = Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(cq));
		if(cells.size() > 0) {
			cellMap.put(id,  cells);
		}
		return cells;
	}
	public static BaseRecord[] getLocationsByFeature(BaseRecord location, String feature, long groupId){
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_PARENT_ID));
		/// DON'T search by group id because feature level grid squares may still be in the universe group
		if(groupId > 0L) {
			pq.field(FieldNames.FIELD_GROUP_ID, groupId);
		}
		pq.field("feature", feature);
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return IOSystem.getActiveContext().getSearch().findRecords(pq);
	}

	public static List<BaseRecord> findLocationsRegionByGrid(List<BaseRecord> locs, int x, int y, int r) {
		return locs.stream().filter(l -> {
			int e = (int)l.get("eastings");
			int n = (int)l.get("northings");
			return(
				e >= (x-r) && e <= (x+r)
				&&
				n >= (y-r) && n <= (y+r)
			);
		}).collect(Collectors.toList());
	}
	
	public static BaseRecord findAdjacentLocationByGrid(OlioContext ctx, long parentId, int x, int y) {
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, parentId);
		cq.field("eastings", x);
		cq.field("northings", y);
		// cq.setLimitFields(false);
		return IOSystem.getActiveContext().getSearch().findRecord(cq);
	}
	
	public static BaseRecord findLocationByGrid(List<BaseRecord> locs, int x, int y) {
		BaseRecord rec = null;
		Optional<BaseRecord> oloc = locs.stream().filter(r -> 
			x == ((int)r.get("eastings"))
			&&
			y == ((int)r.get("northings"))
		).findFirst();
		if(oloc.isPresent()) {
			rec = oloc.get();
		}
		return rec;
	}

	public static BaseRecord newLocation(OlioContext ctx, BaseRecord parent, String name, int id) {
		return newLocation(ctx, parent, name, id, false);
	}
	public static BaseRecord newLocation(OlioContext ctx, BaseRecord parent, String name, int id, boolean world) {
		BaseRecord rec = null;
		if(ctx.getWorld() == null) {
			return rec;
		}
		ParameterList plist = ParameterList.newParameterList("path", (world ? ctx.getWorld() : ctx.getUniverse()).get("locations.path"));
		plist.parameter("name", name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_GEO_LOCATION, ctx.getUser(), null, plist);
			rec.set("geographyType", GeographyEnumType.PHYSICAL);
			if(parent != null) {
				rec.set(FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
				rec.set("geoType", "feature");
				rec.set("geonameid", id);
			}
			else {
				rec.set("geoType", "country");
			}
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return rec;
		
	}
	
	public static BaseRecord createLocation(OlioContext ctx, BaseRecord parent, String name, int id) {
		BaseRecord rec = newLocation(ctx, parent, name, id);
		if(rec == null) {
			return rec;
		}
		IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
		return rec;
	}
	public static double calculateGridDistance(BaseRecord location1, BaseRecord location2) {
		double dist = 0.0;
		int x1 = location1.get("eastings");
		int y1 = location1.get("northings");
		int x2 = location2.get("eastings");
		int y2 = location2.get("northings");
		return distance(x1, y1, x2, y2);
	}
	public static double distance(int x1, int y1, int x2, int y2) {
		return Math.sqrt(Math.pow((double)x2 - x1,2) + Math.pow((double)y2 - y1, 2));
	}
	public static float calculateDistance(BaseRecord location1, BaseRecord location2) {
		StringBuilder buff = new StringBuilder();
		buff.append("SELECT SQRT(POW(69.1 * (G1.latitude -  G2.latitude), 2) + POW(69.1 * (G2.longitude - G1.longitude) * COS(G1.latitude / 57.3), 2))");
		String tableName = IOSystem.getActiveContext().getDbUtil().getTableName(ModelNames.MODEL_GEO_LOCATION);
		buff.append(" FROM " + tableName + " G1 CROSS JOIN " + tableName + " G2");
		buff.append(" WHERE G1.id = ? AND G2.id = ?");
		float distance = 0F;
	    try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); PreparedStatement st = con.prepareStatement(buff.toString())){
	    	st.setLong(1, location1.get(FieldNames.FIELD_ID));
	    	st.setLong(2, location2.get(FieldNames.FIELD_ID));
	    	ResultSet rset = st.executeQuery();
	    	if(rset.next()) {
	    		distance = rset.getFloat(1);
	    	}
	    	rset.close();
			st.close();
			
		} catch (SQLException e) {
			logger.error(e);
	    }
	    return distance;
	}
	public static BaseRecord getRootLocation(OlioContext ctx) {
		return getRootLocation(ctx.getUser(), ctx.getWorld());
	}
	public static BaseRecord getRootLocation(BaseRecord user, BaseRecord world) {
		BaseRecord loc = null;
		BaseRecord evt = EventUtil.getRootEvent(user, world);
		if(evt != null) {
			loc = evt.get("location");
		}
		return loc;
	}
	public static BaseRecord[] getRegionLocations(OlioContext ctx) {
		return getRegionLocations(ctx.getUser(), ctx.getWorld());
	}
	public static BaseRecord[] getRegionLocations(BaseRecord user, BaseRecord world) {
		List<BaseRecord> locs = new ArrayList<>();
		BaseRecord[] evts = EventUtil.getBaseRegionEvents(user, world);
		if(evts.length == 0) {
			logger.error("Zero region events were found");
		}
		for(BaseRecord evt : evts) {
			locs.add(evt.get("location"));
		}
		return locs.toArray(new BaseRecord[0]);
	}
	
	public static BaseRecord randomLocation(BaseRecord user, BaseRecord world) {
		BaseRecord dir = world.get("locations");
		logger.info("Random location in " + dir.get(FieldNames.FIELD_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field("geoType", "feature");
		return OlioUtil.randomSelection(user, q);
	}
	
	
	public static String[] getAlternateNames(BaseRecord user, BaseRecord location, String altType) {
		return getAlternateNames(user, null, location, altType);
	}
	public static String[] getAlternateNames(BaseRecord user, BaseRecord world, BaseRecord location, String altType) {
		String key = location.get(FieldNames.FIELD_GROUP_ID) + "-" + altType;
		if(altNamesCache.containsKey(key)) {
			return altNamesCache.get(key);
		}
		
		IOSystem.getActiveContext().getReader().conditionalPopulate(location, new String[] {"geonameid", FieldNames.FIELD_GROUP_ID});
		long groupId = location.get(FieldNames.FIELD_GROUP_ID);
		if(world != null) {
			groupId = world.get("locations.id");
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, "altgeonameid", location.get("geonameid"));
		q.field("geoType", "alternateName");
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		logger.info("Alt Type: " + altType);
		if(altType != null) {
			q.field("altType", altType);
		}

		q.setRequestRange(0, 10);
		QueryResult qr = null;
		List<String> names = new ArrayList<>();
		try {
			qr = IOSystem.getActiveContext().getSearch().find(q);
			names = Arrays.asList(qr.getResults()).stream().map(r -> (String)r.get(FieldNames.FIELD_NAME)).collect(Collectors.toList());
		} catch (ReaderException e) {
			logger.error(e);
		}
		// if(names.size() > 0) {
		altNamesCache.put(key, names.toArray(new String[0]));
		//}

		return names.toArray(new String[0]);
	}

}
