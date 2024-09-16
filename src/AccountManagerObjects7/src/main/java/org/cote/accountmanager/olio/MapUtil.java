package org.cote.accountmanager.olio;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.GraphicsUtil;

/// MapUtil is currently only setup to work with generated GridSquare maps, which use the same model as the GeoLocation data
/// At the 'admin2' level, each Grid Square is 1 square kilometer
/// At the 'feature' level, each Grid Square is 1 square kilometer 

public class MapUtil {
	public static final Logger logger = LogManager.getLogger(MapUtil.class);
	private static String exportPath = IOFactory.DEFAULT_FILE_BASE + "/.olio/maps"; 
	private static boolean useTileIcons = true;
	private static String tilePath = "./media/tiles";
	
	public static void printMapFromAdmin2(OlioContext ctx) {
		/// Find the admin2 location of the first location and map that
		///
		List<BaseRecord> locs = ctx.getLocations();
		if(locs.size() == 0) {
			logger.error("Context does not contain any locations!");
			return;
		}
		BaseRecord loc = locs.get(0);
		IOSystem.getActiveContext().getReader().populate(loc);
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_ID, loc.get(FieldNames.FIELD_PARENT_ID));
		OlioUtil.planMost(pq);

		printAdmin2Map(ctx, IOSystem.getActiveContext().getSearch().findRecord(pq));
		printLocationMaps(ctx);
	}
	
	public static void printAdmin2Map(OlioContext ctx, BaseRecord location) {
		if(location == null) {
			logger.error("Null location");
			return;
		}
		logger.info("Printing admin2 location " + location.get(FieldNames.FIELD_ID) + " " + location.get(FieldNames.FIELD_NAME));

		IOSystem.getActiveContext().getReader().populate(location);
		List<BaseRecord> locs = ctx.getLocations();
		
		/// This will look for the locations only in the universe, not the world
		/// These are the templates, and the context locations will be substituted for these
		///
		
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));
		pq.field(FieldNames.FIELD_GROUP_ID, location.get(FieldNames.FIELD_GROUP_ID));
		OlioUtil.planMost(pq);

		/// Note: finding based only on parentId will span groups
		/// 
		BaseRecord[] plocs = IOSystem.getActiveContext().getSearch().findRecords(pq);

		int cellWidth = GeoLocationUtil.getMapCellWidthM();
		int cellHeight = GeoLocationUtil.getMapCellWidthM();

		BufferedImage image = new BufferedImage(GeoLocationUtil.getMapWidth1km() * cellWidth, GeoLocationUtil.getMapHeight1km() * cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		for(BaseRecord uloc : plocs) {
			BaseRecord loc = uloc;
			Optional<BaseRecord> oloc = locs.stream().filter(l -> l.get(FieldNames.FIELD_NAME) != null && l.get(FieldNames.FIELD_NAME).equals(uloc.get(FieldNames.FIELD_NAME))).findFirst();
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
			boolean ico = false;
			if(!blot && useTileIcons) {
				Image img = getTile(tet, cellWidth, cellHeight);
				if(img != null) {
					ico = g2d.drawImage(img, x, y, null);
				}
			}
			if(!ico) {
				g2d.fillRect(x, y, cellWidth, cellHeight);
			}
			if(type != null && type.equals(FieldNames.FIELD_FEATURE)) {
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

	private static void paintPopulation(Graphics2D g2d, BaseRecord cell, List<BaseRecord> pop, int x, int y, int tileSize, int spriteSize, Color color) {
		long cid = cell.get(FieldNames.FIELD_ID);

		List<BaseRecord> lpop = pop.stream().filter(p -> (p.get("state.currentLocation") != null && ((long)p.get("state.currentLocation.id")) == cid)).collect(Collectors.toList());
		int div = (Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER) / tileSize;
		if(lpop.size() > 0) {
			for(BaseRecord p : lpop) {
				int peast = p.get("state.currentEast");
				/// To show the position on a grid cell w/ multiplier, take the grid cell w/ multiplier (eg: 10 * 10), divided by the raster cell (eg: 50), and divide the current state position
				///
				if(peast > 0) {
					peast = peast / div;
				}
				int pnorth = p.get("state.currentNorth");
				if(pnorth > 0) {
					pnorth = pnorth / div;
				}
				//logger.info(p.get("state.currentEast") + ", " + p.get("state.currentNorth") + " -> " + cell.get("eastings") + ", " + cell.get("northings") + " -> " + peast + ", " + pnorth + "; " +  " -> " + (x + peast) + ", " + (y + pnorth));
				// logger.info((x + peast) + ", " + (y + pnorth));
				g2d.setColor(color);
				g2d.fillOval(x + peast, y + pnorth, spriteSize, spriteSize);
			}
		}
	}
	
	public static void printRealmMap(OlioContext ctx, BaseRecord realm) {
		printRealmMap(ctx, realm, new ArrayList<>());
	}
	public static void printRealmMap(OlioContext ctx, BaseRecord realm, List<BaseRecord> pop) {
		// logger.info("Printing realm: " + realm.get(FieldNames.FIELD_NAME));
		// logger.info("NOTE: This currently expects a GridSquare layout");
		IOSystem.getActiveContext().getReader().populate(realm);
		IOSystem.getActiveContext().getReader().populate(realm, new String[] {FieldNames.FIELD_LOCATIONS});
		List<BaseRecord> locs = realm.get(FieldNames.FIELD_LOCATIONS);
		if(locs.size() == 0) {
			logger.error("Zero locations for realm");
			return;
		}

		// int featureWidth = Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		// int featureHeight = Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;

		int featureWidth = Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
		int featureHeight = Rules.MAP_EXTERIOR_CELL_HEIGHT * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;

		
		int minHeight = GeoLocationUtil.getMinimumHeight(locs);
		int height = (GeoLocationUtil.getMaximumHeight(locs) - minHeight + 1) * featureHeight;
		int minWidth = GeoLocationUtil.getMinimumWidth(locs);
		int width = (GeoLocationUtil.getMaximumWidth(locs) - minWidth + 1) * featureWidth;

		// logger.info(width + ", " + height + "; " + featureWidth + ", " + featureHeight);
		
		int cellWidth = Rules.MAP_EXTERIOR_CELL_WIDTH;
		int cellHeight = Rules.MAP_EXTERIOR_CELL_HEIGHT;

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		for(BaseRecord uloc : locs) {
			BaseRecord loc = uloc;
			IOSystem.getActiveContext().getReader().populate(loc);
			int east = loc.get("eastings");
			int north = loc.get("northings");
			int x = (east - minWidth) * featureWidth;
			int y = (north - minHeight) * featureHeight;
			String type = loc.get("geoType");
			TerrainEnumType tet = TerrainEnumType.valueOf((String)loc.get("terrainType"));
			boolean ico = false;
			if(useTileIcons) {
				Image img = getTile(tet, featureWidth, featureHeight);
				if(img != null) {
					ico = g2d.drawImage(img, x, y, null);
				}
			}
			if(!ico) {
				Color c = TerrainEnumType.getColor(tet);
				// logger.info(x + ", " + y + " " + cellWidth + ", " + cellHeight + " " + tet.toString() + " " + c.toString());
				g2d.setColor(c);
				g2d.fillRect(x, y, featureWidth, featureHeight);
			}
			
			List<BaseRecord> cells = GeoLocationUtil.getCells(ctx, loc);
			for(BaseRecord cell: cells) {
				int ceast = cell.get("eastings");
				int cnorth = cell.get("northings");
				
				TerrainEnumType ctet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
				int cx = x + (ceast * cellWidth);
				int cy = y + (cnorth * cellHeight);
				ico = false;
				if(useTileIcons) {
					Image img = getTile(tet, cellWidth, cellHeight);
					if(img != null) {
						ico = g2d.drawImage(img, cx, cy, null);
					}
				}
				if(!ico) {
					Color cc = TerrainEnumType.getColor(ctet);
					g2d.setColor(cc);
	
					// logger.info(x + ", " + y + " " + ceast + ", " + cnorth + " " + cx + ", " + cy + " " + cellWidth + ", " + cellHeight + " " + ctet.toString() + " " + cc.toString());
					g2d.fillRect(cx, cy, cellWidth, cellHeight);
					g2d.setColor(Color.DARK_GRAY);
					g2d.drawRect(cx, cy, cellWidth, cellHeight);
				}
				paintPopulation(g2d, cell, pop, cx, cy, cellWidth, 4, Color.WHITE);
			}
			
			g2d.setColor(Color.DARK_GRAY);
			g2d.drawRect(x, y, featureWidth, featureHeight);
			
		}
		g2d.dispose();
		FileUtil.makePath(exportPath);
		File outputfile = new File(exportPath + "/map - realm - " + realm.get(FieldNames.FIELD_NAME) + ".png");
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
		// pq.field(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get(OlioFieldNames.FIELD_LOCATIONS_ID));
		pq.field(FieldNames.FIELD_FEATURE, feature);
		OlioUtil.planMost(pq);

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
		printLocationMap(ctx, location, null, new ArrayList<>());
	}
	
	public static void printLocationMap(OlioContext ctx, BaseRecord location, BaseRecord realm, List<BaseRecord> pop) {
		logger.info("Printing location " + location.get(FieldNames.FIELD_NAME) + " " + location.get("terrainType"));

		IOSystem.getActiveContext().getReader().populate(location);
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));

		/// Note: finding based only on parentId will span groups.  Each location map should be scoped to only the world
		/// 
		pq.field(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get(OlioFieldNames.FIELD_LOCATIONS_ID));
		OlioUtil.planMost(pq);

		BaseRecord[] cells = IOSystem.getActiveContext().getSearch().findRecords(pq);
		
		int cellWidth = GeoLocationUtil.getMapCellWidthM() * 50;
		int cellHeight = GeoLocationUtil.getMapCellWidthM() * 50;

		BufferedImage image = new BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		List<BaseRecord> zoo = new ArrayList<>();
		if(realm != null) {
			zoo = realm.get("zoo");
		}
		for(BaseRecord cell : cells) {
			String ctype = cell.get("geoType");
			int east = cell.get("eastings");
			int north = cell.get("northings");
			int x = east * 50;
			int y = north * 50;
			
			TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
			boolean ico = false;
			if(useTileIcons) {
				Image img = getTile(tet, 50, 50);
				if(img != null) {
					ico = g2d.drawImage(img, x, y, null);
				}
			}
			if(!ico) {
				Color c = TerrainEnumType.getColor(tet);
				g2d.setColor(c);
				g2d.fillRect(x, y, 50, 50);
				g2d.setColor(Color.DARK_GRAY);
				g2d.drawRect(x, y, 50, 50);
			}
			long cid = cell.get(FieldNames.FIELD_ID);

			List<BaseRecord> lpop = pop.stream().filter(p -> (p.get("state.currentLocation") != null && ((long)p.get("state.currentLocation.id")) == cid)).collect(Collectors.toList());
			if(lpop.size() > 0) {
				for(BaseRecord p : lpop) {
					int peast = p.get("state.currentEast");
					/// To show the position on a grid cell w/ multiplier, take the grid cell w/ multiplier (eg: 10 * 10), divided by the raster cell (eg: 50), and divide the current state position
					///
					if(peast > 0) {
						peast = peast / 2;
					}
					int pnorth = p.get("state.currentNorth");
					if(pnorth > 0) {
						pnorth = pnorth / 2;
					}
					g2d.setColor(Color.WHITE);
					g2d.fillOval(x + peast, y + pnorth, 10, 10);
				}
			}
			
			if(realm != null) {
				List<BaseRecord> zpop = zoo.stream().filter(p -> (p.get("state.currentLocation") != null && ((long)p.get("state.currentLocation.id")) == cid)).collect(Collectors.toList());
				
				if(zpop.size() > 0) {
					for(BaseRecord z : zpop) {
						int peast = z.get("state.currentEast");
						int pnorth = z.get("state.currentNorth");
						/// To show the position on a grid cell w/ multiplier, take the grid cell w/ multiplier (eg: 10 * 10), divided by the raster cell (eg: 50), and divide the current state position
						///
						if(peast > 0) {
							peast = peast / 2;
						}
						if(pnorth > 0) {
							pnorth = pnorth / 2;
						}

						g2d.setColor(Color.RED);

						g2d.fillOval(x + peast, y + pnorth, 10, 10);

						//g2d.fillOval(x + 5, y + 5, 10, 10);
					}
				}
			}

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
	
	public static void printPovLocationMap(OlioContext ctx, BaseRecord realm, BaseRecord pov, int radius) {

		BaseRecord location = pov.get("state.currentLocation");
		if(location == null) {
			logger.error("Location is null");
			return;
		}
		logger.info("Printing POV location for " + pov.get(FieldNames.FIELD_NAME) + " in " + location.get(FieldNames.FIELD_NAME) + " " + location.get("terrainType"));
		//List<BaseRecord> cells = GeoLocationUtil.getAdjacentCells(ctx, location, radius);
		BaseRecord[][] cells = GeoLocationUtil.findAdjacentCells(ctx, location, radius);
		
		int diam = ((radius * 2) + 1);
		int cellWidth = diam * 50;
		int cellHeight = diam * 50;

		BufferedImage image = new BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		
		List<BaseRecord> zoo = new ArrayList<>();
		if(realm != null) {
			zoo = realm.get("zoo");
		}
		
		long plid = pov.get("state.currentLocation.id");
		//for(BaseRecord cell : cells) {
		for(int xi = 0; xi < diam; xi++) {
			for(int yi = 0; yi < diam; yi++) {
				BaseRecord cell = cells[xi][yi];
				if(cell == null) {
					continue;
				}
				String ctype = cell.get("geoType");
				int x = 50 * xi;
				int y = 50 * yi;
				
				TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
				boolean ico = false;
				if(useTileIcons) {
					Image img = getTile(tet, 50, 50);
					if(img != null) {
						ico = g2d.drawImage(img, x, y, null);
					}
				}
				if(!ico) {
					Color c = TerrainEnumType.getColor(tet);
					g2d.setColor(c);
					g2d.fillRect(x, y, 50, 50);
					g2d.setColor(Color.DARK_GRAY);
					g2d.drawRect(x, y, 50, 50);
				}
				long cid = cell.get(FieldNames.FIELD_ID);
	
				if(plid == cid) {
	
					int peast = pov.get("state.currentEast");
					/// To show the position on a grid cell w/ multiplier, take the grid cell w/ multiplier (eg: 10 * 10), divided by the raster cell (eg: 50), and divide the current state position
					///
					if(peast > 0) {
						peast = peast / 2;
					}
					int pnorth = pov.get("state.currentNorth");
					if(pnorth > 0) {
						pnorth = pnorth / 2;
					}
					g2d.setColor(Color.WHITE);
					g2d.fillOval(x + peast, y + pnorth, 10, 10);
					
				}
				
				if(realm != null) {
					List<BaseRecord> zpop = zoo.stream().filter(p -> (p.get("state.currentLocation") != null && ((long)p.get("state.currentLocation.id")) == cid)).collect(Collectors.toList());
					
					if(zpop.size() > 0) {
						for(BaseRecord z : zpop) {
							int peast = z.get("state.currentEast");
							int pnorth = z.get("state.currentNorth");
							/// To show the position on a grid cell w/ multiplier, take the grid cell w/ multiplier (eg: 10 * 10), divided by the raster cell (eg: 50), and divide the current state position
							///
							if(peast > 0) {
								peast = peast / 2;
							}
							if(pnorth > 0) {
								pnorth = pnorth / 2;
							}

							g2d.setColor(Color.RED);

							g2d.fillOval(x + peast, y + pnorth, 10, 10);

							//g2d.fillOval(x + 5, y + 5, 10, 10);
						}
					}
				}
				
			}

		}
		g2d.dispose();
		FileUtil.makePath(exportPath);
		File outputfile = new File(exportPath + "/map - pov - " + pov.get(FieldNames.FIELD_NAME) + " " + location.get(FieldNames.FIELD_NAME) + ".png");
		try {
			ImageIO.write(image, "png", outputfile);
		} catch (IOException e) {
			logger.error(e);
		}

	}
	
	private static Map<String, Image> tileMap = new HashMap<>();
	private static Image getTile(TerrainEnumType tet, int width, int height) {
		String key = tet.toString() + "-" + width + "-" + height;
		if(tileMap.containsKey(key)) {
			return tileMap.get(key);
		}
		byte[] imgData = FileUtil.getFile(tilePath + "/" + tet.toString().toLowerCase() + ".png");
		if(imgData.length == 0) {
			logger.error("Failed to find tile for " + tet.toString());
			tileMap.put(key, null);
			return null;
		}
		byte[] thumbBytes = new byte[0];
		Image image = null;
		try {
			thumbBytes = GraphicsUtil.createThumbnail(imgData, width, height);
			if(thumbBytes.length > 0) {
				image = ImageIO.read(new ByteArrayInputStream(thumbBytes));
			}
		} catch (IOException e) {
			logger.error(e);
		}
		if(image == null) {
			logger.error("Failed to create thumbnail for " + tet.toString());
		}
		tileMap.put(key, image);
		return image;

	}
}

