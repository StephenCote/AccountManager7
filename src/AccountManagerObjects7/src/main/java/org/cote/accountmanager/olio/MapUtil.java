package org.cote.accountmanager.olio;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;
import org.cote.accountmanager.util.FileUtil;

/// MapUtil is currently only setup to work with generated GridSquare maps, which use the same model as the GeoLocation data
/// At the 'admin2' level, each Grid Square is 1 square kilometer
/// At the 'feature' level, each Grid Square is 1 square kilometer 

public class MapUtil {
	public static final Logger logger = LogManager.getLogger(MapUtil.class);
	private static String exportPath = IOFactory.DEFAULT_FILE_BASE + "/.olio/maps"; 
	public static void printMapFromAdmin2(OlioContext ctx) {
		/// Find the admin2 location of the first location and map that
		///
		BaseRecord[] locs = ctx.getLocations();
		BaseRecord loc = locs[0];
		IOSystem.getActiveContext().getReader().populate(loc);
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_ID, loc.get(FieldNames.FIELD_PARENT_ID));
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		printAdmin2Map(ctx, IOSystem.getActiveContext().getSearch().findRecord(pq));
		printLocationMaps(ctx);
	}
	
	public static void printAdmin2Map(OlioContext ctx, BaseRecord location) {
		logger.info("Printing admin2 location " + location.get(FieldNames.FIELD_NAME));
		logger.info("NOTE: This currently expects a GridSquare layout");
		GridSquareLocationInitializationRule rule = new GridSquareLocationInitializationRule();
		IOSystem.getActiveContext().getReader().populate(location);
		List<BaseRecord> locs = new ArrayList<>(Arrays.asList(ctx.getLocations()));
		/// This will look for the locations only in the universe, not the world
		/// These are the templates, and the context locations will be substituted for these
		///
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));
		pq.field(FieldNames.FIELD_GROUP_ID, location.get(FieldNames.FIELD_GROUP_ID));
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		/// Note: finding based only on parentId will span groups
		/// 
		BaseRecord[] plocs = IOSystem.getActiveContext().getSearch().findRecords(pq);

		int cellWidth = rule.getMapCellWidthM();
		int cellHeight = rule.getMapCellWidthM();

		BufferedImage image = new BufferedImage(rule.getMapWidth1km() * cellWidth, rule.getMapHeight1km() * cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		for(BaseRecord uloc : plocs) {
			BaseRecord loc = uloc;
			Optional<BaseRecord> oloc = locs.stream().filter(l -> l.get(FieldNames.FIELD_NAME).equals(uloc.get(FieldNames.FIELD_NAME))).findFirst();
			boolean blot = false;
			if(oloc.isPresent()) {
				loc = oloc.get();
				IOSystem.getActiveContext().getReader().populate(loc);
				blot = true;
			}
			int east = loc.get("eastings");
			int north = loc.get("northings");
			int x = east * cellWidth;
			int y = north * cellHeight;

			String type = loc.get("geoType");
			TerrainEnumType tet = TerrainEnumType.valueOf((String)loc.get("terrainType"));
			if(blot) {
				g2d.setColor(Color.RED);
			}
			else {
				Color c = TerrainEnumType.getColor(tet);
				g2d.setColor(c);
			}
			g2d.fillRect(x, y, cellWidth, cellHeight);
			if(type != null && type.equals("feature")) {
				g2d.setColor(Color.DARK_GRAY);
				g2d.drawRect(x, y, cellWidth, cellHeight);
			}

		}
		g2d.dispose();
		FileUtil.makePath(exportPath);
		File outputfile = new File(exportPath + "/map - admin2 - " + location.get(FieldNames.FIELD_NAME) + ".png");
		try {
			ImageIO.write(image, "png", outputfile);
		} catch (IOException e) {
			logger.error(e);
		}

	}
	
	public static void printLocationMaps(OlioContext ctx) {
		for(BaseRecord rec : ctx.getLocations()) {
			printLocationMap(ctx, rec);
		}
	}
	
	/// For printing a description of locations based on feature
	public static void printDescriptionByFeature(OlioContext ctx, BaseRecord location, String feature) {
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_PARENT_ID));
		/// DON'T search by group id because feature level grid squares may still be in the universe group
		// pq.field(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
		pq.field("feature", feature);
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		BaseRecord[] locations = IOSystem.getActiveContext().getSearch().findRecords(pq);
		Map<TerrainEnumType, Integer> map = TerrainUtil.getTerrainTypes(Arrays.asList(locations));
		List<TerrainEnumType> sortTypes = map.entrySet()
			.stream()
			.sorted(Comparator.comparing(Map.Entry::getValue))
			.map(e -> e.getKey()).collect(Collectors.toList())
		;
		for(TerrainEnumType tet : sortTypes) {
			logger.info(tet + " " + map.get(tet));
		}
		
	}
	
	public static void printLocationMap(OlioContext ctx, BaseRecord location) {
		logger.info("Printing location " + location.get(FieldNames.FIELD_NAME) + " " + location.get("terrainType"));
		logger.info("NOTE: This currently expects a GridSquare layout");
		printDescriptionByFeature(ctx, location, (String)location.get("feature"));
		GridSquareLocationInitializationRule rule = new GridSquareLocationInitializationRule();
		IOSystem.getActiveContext().getReader().populate(location);
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));

		/// Note: finding based only on parentId will span groups.  Each location map should be scoped to only the world
		/// 
		pq.field(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
		
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}


		BaseRecord[] cells = IOSystem.getActiveContext().getSearch().findRecords(pq);
		
		int cellWidth = rule.getMapCellWidthM() * 50;
		int cellHeight = rule.getMapCellWidthM() * 50;

		BufferedImage image = new BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();

		for(BaseRecord cell : cells) {
			String ctype = cell.get("geoType");
			int east = cell.get("eastings");
			int north = cell.get("northings");
			int x = east * 50;
			int y = north * 50;
			
			TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
			Color c = TerrainEnumType.getColor(tet);
			g2d.setColor(c);
			g2d.fillRect(x, y, 50, 50);
			g2d.setColor(Color.DARK_GRAY);
			g2d.drawRect(x, y, 50, 50);
		}
		g2d.dispose();
		FileUtil.makePath(exportPath);
		File outputfile = new File(exportPath + "/map - location - " + location.get(FieldNames.FIELD_NAME) + ".png");
		try {
			ImageIO.write(image, "png", outputfile);
		} catch (IOException e) {
			logger.error(e);
		}

	}
}
