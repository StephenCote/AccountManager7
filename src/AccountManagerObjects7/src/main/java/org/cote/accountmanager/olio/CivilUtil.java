package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class CivilUtil {

	public static final Logger logger = LogManager.getLogger(CivilUtil.class);

	/// https://futurism.com/the-kardashev-scale-type-i-ii-iii-iv-v-civilization
	public static final String[] KARDASHEV_SCALE = new String[] { "Type I", "Type II", "Type III", "Type IV", "Type V"};

	/// https://blog.adw.org/2016/10/eight-stages-rise-fall-civilizations/
	public static final String[] CYCLE = new String[] {
		"Bondage",
		"Spiritual Growth",
		"Great Courage",
		"Liberty",
		"Abundance",
		"Complacency",
		"Apathy",
		"Dependence"
	};

	private static String[] leaderPopulation = new String[]{"Political","Religious","Military","Business","Social","Trade"};

	// https://education.nationalgeographic.org/resource/key-components-civilization/
	public static final String[] COMPONENTS = new String[] {
		"Urban Areas",
		"Monuments",
		"Shared Communication",
		"Administration",
		"Infrastructure",
		"Division of Labor",
		"Class Structure",
		"Trade",
		"Conflict",
		"Exploration",
		"Innovation",
		"Internal Change",
		"External Pressure",
		"Environmental Collapse",
		"Lost"
	};

	/// Climate types derived from terrain
	public enum ClimateType {
		TROPICAL,
		ARID,
		TEMPERATE,
		COLD,
		ARCTIC
	}

	/// Tier labels for display/logging
	public static final String[] TIER_LABELS = new String[] {
		"Primitive", "Crafted", "Artisan", "Industrial", "Advanced"
	};

	/// Fabric name keywords mapped to tech tiers
	private static final Map<String, Integer> fabricTierMap = new HashMap<>();
	/// Clothing name keywords mapped to tech tiers
	private static final Map<String, Integer> clothingTierMap = new HashMap<>();

	private static String[] fabricTypes = new String[0];
	private static String[] clothingTypes = new String[0];

	static {
		fabricTypes = JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/fabrics.json"), String[].class);
		clothingTypes = JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/clothing.json"), String[].class);

		/// Tier 0: Primitive - raw natural materials
		for(String s : new String[]{"hide", "fur", "bark", "barkcloth"}) {
			fabricTierMap.put(s, 0);
		}
		/// Tier 1: Crafted - basic processed materials
		for(String s : new String[]{"leather", "linen", "hemp", "canvas", "felt", "burlap", "hessian",
			"wadmal", "haircloth", "camlet", "drugget", "hodden", "linsey-woolsey", "osnaburg",
			"fustian", "buckram", "crash", "dowlas"}) {
			fabricTierMap.put(s, 1);
		}
		/// Tier 2: Artisan - refined fabrics
		for(String s : new String[]{"cotton", "silk", "satin", "velvet", "brocade", "muslin",
			"damask", "taffeta", "chiffon", "organza", "crepe", "georgette", "charmeuse",
			"voile", "batiste", "cambric", "lawn", "poplin", "chintz", "calico",
			"gauze", "lace", "tulle", "net", "mesh", "stockinette", "wool",
			"tweed", "serge", "gabardine", "broadcloth", "velour", "velveteen",
			"pongee", "shantung", "dupioni", "habutai", "suede", "surah",
			"mousseline", "moire", "ottoman", "rep", "sateen", "dimity",
			"percale", "pique", "toile", "tapestry"}) {
			fabricTierMap.put(s, 2);
		}
		/// Tier 3: Industrial - machine-produced fabrics
		for(String s : new String[]{"denim", "jersey", "corduroy", "chino", "flannel",
			"fleece", "polar fleece", "tricot", "interlock", "rib knit",
			"polyester", "nylon", "acrylic", "rayon", "spandex",
			"faux fur", "faux leather", "leatherette", "ultrasuede",
			"ripstop", "cordura", "silnylon", "vinyl"}) {
			fabricTierMap.put(s, 3);
		}
		/// Tier 4: Advanced - high-tech fabrics
		for(String s : new String[]{"gore-tex", "kevlar", "neoprene", "coolmax",
			"ballistic nylon", "dyneema", "nomex", "sympatex", "darlexx",
			"beta cloth", "capilene"}) {
			fabricTierMap.put(s, 4);
		}

		/// Clothing tiers
		/// Tier 0: Primitive - draped/tied items (not in current data, included for completeness)
		for(String s : new String[]{"loincloth", "wrap", "sarong"}) {
			clothingTierMap.put(s, 0);
		}
		/// Tier 1: Crafted - simple sewn garments
		for(String s : new String[]{"leggings", "sandals", "shawl", "scarf", "cloak", "tunic", "moccasins"}) {
			clothingTierMap.put(s, 1);
		}
		/// Tier 2: Artisan - tailored garments
		for(String s : new String[]{"shirt", "pants", "dress", "boots", "gloves", "skirt",
			"blouse", "corset", "shorts", "belt", "vest", "hat", "cap", "coat",
			"slacks", "suit jacket", "suit pants", "evening gown", "wedding dress",
			"tuxedo jacket", "tuxedo pants", "overalls", "socks", "underwear",
			"undershirt", "bikini top", "bikini bottom", "panties", "bra",
			"g-string", "thong", "camisole", "babydoll", "boxer shorts",
			"demi bra", "pajama top", "pajama bottom", "shoes", "high heels",
			"thigh-high heeled boots", "tie", "mittens", "mini dress", "mini skirt",
			"pullover", "sweater", "cardigan", "swim trunks", "swimsuit"}) {
			clothingTierMap.put(s, 2);
		}
		/// Tier 3: Industrial - mass-produced garments
		for(String s : new String[]{"jeans", "t-shirt", "hoodie", "cargo pants",
			"sweatpants", "sweatshirt", "tank top", "polo shirt",
			"tracksuit", "jacket", "pantyhose", "sneakers"}) {
			clothingTierMap.put(s, 3);
		}
		/// Tier 4: Advanced - technical garments
		for(String s : new String[]{"trench coat", "raincoat", "windbreaker", "parka", "wetsuit"}) {
			clothingTierMap.put(s, 4);
		}
	}

	/// Map terrain type string to a ClimateType
	public static ClimateType getClimateForTerrain(String terrainType) {
		if(terrainType == null) return ClimateType.TEMPERATE;
		switch(terrainType.toUpperCase()) {
			case "DESERT": return ClimateType.ARID;
			case "JUNGLE": case "TROPICAL": case "SWAMP": return ClimateType.TROPICAL;
			case "TUNDRA": case "ARCTIC": case "GLACIER": return ClimateType.ARCTIC;
			case "MOUNTAIN": case "ALPINE": return ClimateType.COLD;
			default: return ClimateType.TEMPERATE;
		}
	}

	/// Get the tech tier for a fabric name (0-4). Returns default tier 2 if unknown.
	public static int getFabricTier(String fabricName) {
		if(fabricName == null) return 2;
		String lower = fabricName.toLowerCase();
		for(Map.Entry<String, Integer> entry : fabricTierMap.entrySet()) {
			if(lower.contains(entry.getKey())) {
				return entry.getValue();
			}
		}
		return 2;
	}

	/// Get the tech tier for a clothing name (0-4). Returns default tier 2 if unknown.
	public static int getClothingTier(String clothingName) {
		if(clothingName == null) return 2;
		String lower = clothingName.toLowerCase();
		for(Map.Entry<String, Integer> entry : clothingTierMap.entrySet()) {
			if(lower.equals(entry.getKey())) {
				return entry.getValue();
			}
		}
		return 2;
	}

	/// Filter fabric names to those at or below the given tech tier.
	/// Returns the fabric name (first field from the colon-delimited format).
	public static List<String> filterFabricsByTier(int maxTier) {
		List<String> result = new ArrayList<>();
		if(fabricTypes == null) return result;
		for(String entry : fabricTypes) {
			String[] parts = entry.split(":");
			if(parts.length < 2) continue;
			String name = parts[0].trim();
			if(getFabricTier(name) <= maxTier) {
				result.add(name);
			}
		}
		return result;
	}

	/// Filter clothing names to those at or below the given tech tier.
	/// Returns the clothing name (first field from the colon-delimited format).
	public static List<String> filterClothingByTier(int maxTier) {
		List<String> result = new ArrayList<>();
		if(clothingTypes == null) return result;
		for(String entry : clothingTypes) {
			if(entry.indexOf(':') < 0) continue;
			String[] parts = entry.split(":");
			if(parts.length < 4) continue;
			String name = parts[0].trim();
			if(getClothingTier(name) <= maxTier) {
				result.add(name);
			}
		}
		return result;
	}

	/// Filter fabrics suitable for a climate type and at or below the given tier.
	public static List<String> filterFabricsByTierAndClimate(int maxTier, ClimateType climate) {
		List<String> tierFiltered = filterFabricsByTier(maxTier);
		if(climate == null || climate == ClimateType.TEMPERATE) {
			return tierFiltered;
		}
		return tierFiltered.stream().filter(name -> isFabricSuitableForClimate(name, climate)).collect(Collectors.toList());
	}

	/// Determine if a fabric name is suitable for a given climate
	private static boolean isFabricSuitableForClimate(String fabricName, ClimateType climate) {
		String lower = fabricName.toLowerCase();
		switch(climate) {
			case TROPICAL:
				/// Exclude heavy/insulating fabrics
				if(lower.contains("wool") || lower.contains("fur") || lower.contains("fleece") ||
					lower.contains("felt") || lower.contains("tweed") || lower.contains("velvet") ||
					lower.contains("neoprene") || lower.contains("gore-tex")) {
					return false;
				}
				return true;
			case ARID:
				/// Exclude heavy wool and fur, allow light coverage fabrics
				if(lower.contains("fur") || lower.contains("fleece") || lower.contains("neoprene")) {
					return false;
				}
				return true;
			case COLD:
				/// Exclude sheer/minimal coverage fabrics
				if(lower.contains("lace") || lower.contains("mesh") || lower.contains("net") ||
					lower.contains("chiffon") || lower.contains("gauze") || lower.contains("tulle") ||
					lower.contains("fishnet") || lower.contains("stockinette")) {
					return false;
				}
				return true;
			case ARCTIC:
				/// Only allow insulating, windproof fabrics
				if(lower.contains("lace") || lower.contains("mesh") || lower.contains("net") ||
					lower.contains("chiffon") || lower.contains("gauze") || lower.contains("tulle") ||
					lower.contains("fishnet") || lower.contains("stockinette") ||
					lower.contains("muslin") || lower.contains("organza") || lower.contains("voile")) {
					return false;
				}
				return true;
			default:
				return true;
		}
	}

	/// Get the minimum wear level for a climate type
	public static WearLevelEnumType getMinWearLevel(ClimateType climate) {
		return WearLevelEnumType.BASE;
	}

	/// Get the maximum wear level for a climate type
	public static WearLevelEnumType getMaxWearLevel(ClimateType climate) {
		if(climate == null) return WearLevelEnumType.OVER;
		switch(climate) {
			case TROPICAL: return WearLevelEnumType.SUIT;
			case ARID: return WearLevelEnumType.SUIT;
			case TEMPERATE: return WearLevelEnumType.OVER;
			case COLD: return WearLevelEnumType.OUTER;
			case ARCTIC: return WearLevelEnumType.OUTER;
			default: return WearLevelEnumType.OVER;
		}
	}

	/// Get the probability of generating mid-level layers for a climate
	public static double getMidLayerProbability(ClimateType climate) {
		if(climate == null) return 0.35;
		switch(climate) {
			case TROPICAL: return 0.1;
			case ARID: return 0.2;
			case TEMPERATE: return 0.35;
			case COLD: return 0.7;
			case ARCTIC: return 0.9;
			default: return 0.35;
		}
	}
}
