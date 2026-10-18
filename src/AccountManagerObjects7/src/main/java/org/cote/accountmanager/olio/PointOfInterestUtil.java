package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class PointOfInterestUtil {
	public static final Logger logger = LogManager.getLogger(PointOfInterestUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	public static void paintPointsOfInterest(OlioContext ctx, List<BaseRecord> cells) {
		List<BaseRecord> pois = new ArrayList<>();
		for(BaseRecord cell : cells) {
			pois.addAll(paintPointsOfInterest(ctx, cell));
		}
		if(pois.size() > 0) {
			IOSystem.getActiveContext().getRecordUtil().createRecords(pois.toArray(new BaseRecord[0]));
			logger.info("Painted " + pois.size() + " points of interest");
		}
	}
	private static List<BaseRecord> paintPointsOfInterest(OlioContext ctx, BaseRecord cell) {
		List<BaseRecord> pois = new ArrayList<>();
		int pcount = countPointsOfInterest(ctx, cell);
		if(pcount == 0) {
			TerrainEnumType tet = cell.getEnum(FieldNames.FIELD_TERRAIN_TYPE);
			List<BaseRecord> builders = BuilderUtil.listBuildersCommonToTerrain(ctx, tet);
			if(builders.size() == 0) {
				logger.warn("No builders for " + tet.toString());
			}
			if(builders.size() > 0 && rand.nextDouble() <= Rules.POINT_OF_INTEREST_ODDS) {
				int pc = rand.nextInt(1, Rules.MAXIMUM_POINTS_OF_INTEREST);
				for(int i = 0; i < pc; i++) {
					BaseRecord bld = builders.get(rand.nextInt(0, builders.size()));
					PointOfInterestEnumType poit = PointOfInterestEnumType.RESOURCE;
					String cat = bld.get("item.category");
					if(cat != null && cat.equals("food")) {
						poit = PointOfInterestEnumType.HARVESTABLE;
					}
					int x = rand.nextInt(0,10);
					int y = rand.nextInt(0,10);
					BaseRecord poi = newPointOfInterest(ctx, poit, cell, bld.get(FieldNames.FIELD_NAME), x, y);
					poi.setValue(OlioFieldNames.FIELD_BUILDER, bld);
					pois.add(poi);
				}
			}
			populatePointOfInterestBuilder(ctx, pois);
		}
		else {
			logger.info("There are already " + pcount + " POIs for cell " + cell.get(FieldNames.FIELD_NAME));
		}
		return pois;
	}
	
	/// for any builder of type builder, with materials, populate the poi store with an initial set of inventory items for that builder's materials
	///

	protected static void populatePointOfInterestBuilder(OlioContext ctx, List<BaseRecord> pois) {
		for(BaseRecord poi : pois) {

			BaseRecord bld = poi.get(OlioFieldNames.FIELD_BUILDER);
			if(bld == null) {
				logger.warn("Not a builder");
				continue;
			}

			BuilderEnumType bet = bld.getEnum(FieldNames.FIELD_TYPE);
			try {
				if(bet == BuilderEnumType.BUILDER) {
					List<BaseRecord> materials = bld.get(OlioFieldNames.FIELD_MATERIALS);
					for(BaseRecord mat : materials) {
						ItemUtil.addNewInventory(ctx, mat, poi.get(FieldNames.FIELD_STORE), -1);
					}
				}
				else if(bet == BuilderEnumType.ITEM || bet == BuilderEnumType.FIXTURE || (bet == BuilderEnumType.LOCATION && bld.get(OlioFieldNames.FIELD_ITEM) != null)) {
					ItemUtil.addNewInventory(ctx, bld.get(OlioFieldNames.FIELD_ITEM), poi.get(FieldNames.FIELD_STORE), -1);
				}
				else {
					logger.warn("Unhandled builder type: " + bet.toString());
				}
			}
			catch(FactoryException e) {
				logger.error(e);
			}
		}
	}
	
	public static BaseRecord newPointOfInterest(OlioContext ctx, PointOfInterestEnumType type, BaseRecord cell, String name, int x, int y) {
		BaseRecord rec = null;
		if(ctx.getWorld() == null) {
			return rec;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get(OlioFieldNames.FIELD_POINTS_OF_INTEREST_PATH));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_POI, ctx.getOlioUser(), null, plist);
			rec.set(FieldNames.FIELD_STORE, IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get(OlioFieldNames.FIELD_STORES_PATH))));
			rec.set(FieldNames.FIELD_TYPE, type);
			rec.set(FieldNames.FIELD_LOCATION, cell);
			rec.set(FieldNames.FIELD_NORTH, y);
			rec.set(FieldNames.FIELD_EAST, x);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return rec;
		
	}

	/**
	 * A map-free point of interest (PictureBook V3 §7.2 rule 1): the POI and a fresh {@code olio.store} are
	 * instantiated in the world's {@code Points of Interest} / {@code Stores} groups as {@code olioUser}, with
	 * no {@code location} cell and {@code east}/{@code north} left at the model default (-1). Nothing is
	 * persisted here; the caller writes the store and then the POI.
	 */
	public static BaseRecord newPointOfInterest(BaseRecord olioUser, BaseRecord world, PointOfInterestEnumType type, String name) {
		BaseRecord rec = null;
		if(olioUser == null || world == null) {
			return rec;
		}
		String poiPath = world.get(OlioFieldNames.FIELD_POINTS_OF_INTEREST_PATH);
		String storePath = world.get(OlioFieldNames.FIELD_STORES_PATH);
		if(poiPath == null || storePath == null) {
			logger.error("World " + world.get(FieldNames.FIELD_NAME) + " does not expose its Points of Interest / Stores group paths");
			return rec;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, poiPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_POI, olioUser, null, plist);
			/// The model declares name itself rather than inheriting common.name, so applyNameGroupOwnership leaves it unset
			rec.set(FieldNames.FIELD_NAME, name);
			rec.set(FieldNames.FIELD_STORE, IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, olioUser, null, ParameterList.newParameterList(FieldNames.FIELD_PATH, storePath)));
			rec.set(FieldNames.FIELD_TYPE, type);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			rec = null;
		}
		return rec;
	}

	/**
	 * Every point of interest named {@code name} (exact, trimmed) in {@code world}'s {@code Points of Interest}
	 * group - PictureBook V3 §7.2 rule 3, the name link for game worlds. An unauthorized, find-only read bounded
	 * by the world's POI group and the name; callers decide what to do with zero or several hits.
	 */
	public static List<BaseRecord> findByName(BaseRecord world, String name) {
		List<BaseRecord> out = new ArrayList<>();
		if(world == null || name == null || name.trim().length() == 0) {
			return out;
		}
		Object gid = world.get(OlioFieldNames.FIELD_POINTS_OF_INTEREST_ID);
		if(!(gid instanceof Number) || ((Number) gid).longValue() <= 0L) {
			return out;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_GROUP_ID, ((Number) gid).longValue());
		q.field(FieldNames.FIELD_NAME, name.trim());
		Object orgId = world.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(orgId instanceof Number) {
			q.field(FieldNames.FIELD_ORGANIZATION_ID, ((Number) orgId).longValue());
		}
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TYPE,
			FieldNames.FIELD_LOCATION, FieldNames.FIELD_EAST, FieldNames.FIELD_NORTH, FieldNames.FIELD_STORE,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID
		});
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
		if(recs != null) {
			for(BaseRecord r : recs) {
				if(r.getSchema() == null) {
					r.setSchema(OlioModelNames.MODEL_POI);
				}
				out.add(r);
			}
		}
		return out;
	}

	public static List<BaseRecord> findByName(OlioContext ctx, String name) {
		if(ctx == null || ctx.getWorld() == null) {
			return new ArrayList<>();
		}
		return findByName(ctx.getWorld(), name);
	}
	
	public static BaseRecord getPointOfInterest(OlioContext ctx, BaseRecord cell, int east, int north) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("pointsOfInterest.id"));
		q.field(FieldNames.FIELD_LOCATION, cell.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_EAST, east);
		q.field(FieldNames.FIELD_NORTH, north);
		OlioUtil.planMost(q);

		//q.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_STORE, OlioFieldNames.FIELD_BUILDER, FieldNames.FIELD_EAST, FieldNames.FIELD_NORTH}));
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	public static List<BaseRecord> listPointsOfInterest(OlioContext ctx, BaseRecord cell) {
		return listPointsOfInterest(ctx, Arrays.asList(new BaseRecord[] {cell}));
	}
	
	public static List<BaseRecord> listPointsOfInterest(OlioContext ctx, List<BaseRecord> cells) {
		return listPointsOfInterest(ctx, cells, new ArrayList<>());
	}
	
	public static List<BaseRecord> listPointsOfInterest(OlioContext ctx, List<BaseRecord> cells, List<PointOfInterestEnumType> pointTypes) {
		if(cells.size() == 0) {
			logger.warn("No cells were specified");
			return new ArrayList<>();
		}

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("pointsOfInterest.id"));
		List<String> ptypes = pointTypes.stream().map(t -> t.toString()).collect(Collectors.toList());
		if(ptypes.size() > 0) {
			q.field(FieldNames.FIELD_TYPE, ComparatorEnumType.IN, ptypes.stream().collect(Collectors.joining(",")));	
		}
		
		List<String> ids = cells.stream().map(c -> Long.toString(c.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		q.field(FieldNames.FIELD_LOCATION, ComparatorEnumType.IN, ids.stream().collect(Collectors.joining(",")));
		OlioUtil.planMost(q);

		return new ArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
	}
	
	public static int countPointsOfInterest(OlioContext ctx, BaseRecord cell) {
		return countPointsOfInterest(ctx, Arrays.asList(new BaseRecord[] {cell}));
	}
	
	public static int countPointsOfInterest(OlioContext ctx, List<BaseRecord> cells) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("pointsOfInterest.id"));
		//q.field(FieldNames.FIELD_LOCATION, cell.copyRecord(new String[] {FieldNames.FIELD_ID}));
		List<String> ids = cells.stream().map(c -> Long.toString(c.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
		q.field(FieldNames.FIELD_ID, ComparatorEnumType.IN, ids.stream().collect(Collectors.joining(",")));
		q.setRequest(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_STORE, OlioFieldNames.FIELD_BUILDER, FieldNames.FIELD_EAST, FieldNames.FIELD_NORTH});
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}
}
