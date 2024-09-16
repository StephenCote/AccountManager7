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
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
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
			TerrainEnumType tet = cell.getEnum("terrainType");
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
					poi.setValue("builder", bld);
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

			BaseRecord bld = poi.get("builder");
			if(bld == null) {
				logger.warn("Not a builder");
				continue;
			}

			BuilderEnumType bet = bld.getEnum(FieldNames.FIELD_TYPE);
			try {
				if(bet == BuilderEnumType.BUILDER) {
					List<BaseRecord> materials = bld.get("materials");
					for(BaseRecord mat : materials) {
						ItemUtil.addNewInventory(ctx, mat, poi.get("store"), -1);
					}
				}
				else if(bet == BuilderEnumType.ITEM || bet == BuilderEnumType.FIXTURE || (bet == BuilderEnumType.LOCATION && bld.get("item") != null)) {
					ItemUtil.addNewInventory(ctx, bld.get("item"), poi.get("store"), -1);
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
		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("pointsOfInterest.path"));
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_POI, ctx.getOlioUser(), null, plist);
			rec.set("store", IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("stores.path"))));
			rec.set("type", type);
			rec.set("location", cell);
			rec.set("north", y);
			rec.set("east", x);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return rec;
		
	}
	
	public static BaseRecord getPointOfInterest(OlioContext ctx, BaseRecord cell, int east, int north) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("pointsOfInterest.id"));
		q.field(FieldNames.FIELD_LOCATION, cell.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field("east", east);
		q.field("north", north);
		OlioUtil.planMost(q);

		//q.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, "store", "builder", "east", "north"}));
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
		q.setRequest(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, "store", "builder", "east", "north"});
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}
}
