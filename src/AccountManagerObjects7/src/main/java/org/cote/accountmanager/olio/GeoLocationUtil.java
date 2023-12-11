package org.cote.accountmanager.olio;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class GeoLocationUtil {
	public static final Logger logger = LogManager.getLogger(GeoLocationUtil.class);
	private static Map<String, String[]> altNamesCache = new HashMap<>();
	
	public static BaseRecord createLocation(OlioContext ctx, BaseRecord parent, String name, int id) {
		BaseRecord rec = null;
		if(ctx.getWorld() == null) {
			return rec;
		}
		ParameterList plist = ParameterList.newParameterList("path", ctx.getUniverse().get("locations.path"));
		plist.parameter("name", name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_GEO_LOCATION, ctx.getUser(), null, plist);
			if(parent != null) {
				rec.set(FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
				rec.set("geoType", "feature");
				rec.set("geonameid", id);
			}
			else {
				rec.set("geoType", "country");
			}
			IOSystem.getActiveContext().getRecordUtil().createRecord(rec);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return rec;
		
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
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
		// if(names.size() > 0) {
		altNamesCache.put(key, names.toArray(new String[0]));
		//}

		return names.toArray(new String[0]);
	}

}
