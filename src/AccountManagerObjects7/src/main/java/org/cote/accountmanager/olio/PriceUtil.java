package org.cote.accountmanager.olio;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class PriceUtil {
	public static final Logger logger = LogManager.getLogger(PriceUtil.class);

	/// retailValue = (laborCost + materialCost) * qualityModifier * artistryModifier * rarityModifier
	public static double calculateRetailValue(OlioContext ctx, BaseRecord builder, BaseRecord actor) {
		double labor = calculateLaborCost(builder);
		double materials = calculateMaterialCost(ctx, builder);
		double quality = calculateQualityModifier(builder, actor);
		double artistry = calculateArtistryModifier(ctx, actor);
		double rarity = calculateRarityModifier(ctx, builder);
		return (labor + materials) * quality * artistry * rarity;
	}

	/// Base labor cost = minimum wage per minute * builder time in minutes
	public static double calculateLaborCost(BaseRecord builder) {
		int time = builder.get("time");
		return Rules.MINIMUM_WAGE_PER_MINUTE * time;
	}

	/// Sum of material retail values; raw materials use RAW_MATERIAL_BASE_VALUE
	public static double calculateMaterialCost(OlioContext ctx, BaseRecord builder) {
		List<BaseRecord> materials = builder.get(OlioFieldNames.FIELD_MATERIALS);
		if (materials == null || materials.isEmpty()) {
			return 0.0;
		}
		double total = 0.0;
		for (BaseRecord mat : materials) {
			IOSystem.getActiveContext().getReader().populate(mat, new String[] {"retailValue", OlioFieldNames.FIELD_CATEGORY});
			double rv = mat.get("retailValue");
			if (rv > 0) {
				total += rv;
			} else {
				total += Rules.RAW_MATERIAL_BASE_VALUE;
			}
		}
		return total;
	}

	/// Quality modifier based on skill match: QUALITY_FLOOR + (1 - QUALITY_FLOOR) * skillMatch
	/// skillMatch = avg of actor's relevant build stats / MAXIMUM_STATISTIC
	public static double calculateQualityModifier(BaseRecord builder, BaseRecord actor) {
		BaseRecord stats = actor.get(OlioFieldNames.FIELD_STATISTICS);
		if (stats == null) {
			return Rules.QUALITY_FLOOR;
		}
		String[] buildStats = {"physicalStrength", "physicalEndurance", "manualDexterity", "intelligence"};
		double sum = 0.0;
		int count = 0;
		for (String s : buildStats) {
			if (stats.hasField(s)) {
				int val = stats.get(s);
				sum += val;
				count++;
			}
		}
		double skillMatch = count > 0 ? (sum / count) / Rules.MAXIMUM_STATISTIC : 0.5;
		return Rules.QUALITY_FLOOR + (1.0 - Rules.QUALITY_FLOOR) * skillMatch;
	}

	/// Artistry modifier based on favorable interaction ratio
	/// 1.0 + (favorableRatio - 0.5) * ARTISTRY_WEIGHT, clamped to [0.5, 2.0]
	public static double calculateArtistryModifier(OlioContext ctx, BaseRecord actor) {
		if (actor == null) {
			return 1.0;
		}
		try {
			String actorId = actor.get(FieldNames.FIELD_OBJECT_ID);
			if (actorId == null) {
				return 1.0;
			}
			long orgId = actor.get(FieldNames.FIELD_ORGANIZATION_ID);
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION, "actorType", actor.getSchema());
			q.field("actor.objectId", actorId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			int total = IOSystem.getActiveContext().getSearch().count(q);
			if (total == 0) {
				return 1.0;
			}

			Query qf = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION, "actorType", actor.getSchema());
			qf.field("actor.objectId", actorId);
			qf.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			qf.field("actorOutcome", OutcomeEnumType.FAVORABLE);
			int favorable = IOSystem.getActiveContext().getSearch().count(qf);

			Query qvf = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION, "actorType", actor.getSchema());
			qvf.field("actor.objectId", actorId);
			qvf.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			qvf.field("actorOutcome", OutcomeEnumType.VERY_FAVORABLE);
			favorable += IOSystem.getActiveContext().getSearch().count(qvf);

			double ratio = (double) favorable / total;
			double mod = 1.0 + (ratio - 0.5) * Rules.ARTISTRY_WEIGHT;
			return Math.max(0.5, Math.min(2.0, mod));
		} catch (Exception e) {
			logger.warn("Error calculating artistry modifier: " + e.getMessage());
			return 1.0;
		}
	}

	/// Rarity modifier based on how few characters have the required skills
	/// 1.0 + (1.0 - skillPrevalence) * RARITY_WEIGHT, clamped to [1.0, 3.0]
	public static double calculateRarityModifier(OlioContext ctx, BaseRecord builder) {
		List<BaseRecord> skills = builder.get(OlioFieldNames.FIELD_SKILLS);
		if (skills == null || skills.isEmpty()) {
			return 1.0;
		}

		try {
			List<BaseRecord> realms = ctx.getRealms();
			if (realms == null || realms.isEmpty()) {
				return 1.0;
			}
			List<BaseRecord> pop = new java.util.ArrayList<>();
			for (BaseRecord realm : realms) {
				List<BaseRecord> rPop = ctx.getRealmPopulation(realm);
				if (rPop != null) {
					pop.addAll(rPop);
				}
			}
			if (pop.isEmpty()) {
				return 1.0;
			}

			int totalPop = pop.size();
			int withSkills = 0;
			for (BaseRecord person : pop) {
				List<String> trades = person.get(OlioFieldNames.FIELD_TRADES);
				if (trades == null) continue;
				boolean hasAll = true;
				for (BaseRecord skill : skills) {
					String skillName = skill.get(FieldNames.FIELD_NAME);
					if (!trades.stream().anyMatch(t -> t.equalsIgnoreCase(skillName))) {
						hasAll = false;
						break;
					}
				}
				if (hasAll) withSkills++;
			}

			double prevalence = (double) withSkills / totalPop;
			double mod = 1.0 + (1.0 - prevalence) * Rules.RARITY_WEIGHT;
			return Math.max(1.0, Math.min(3.0, mod));
		} catch (Exception e) {
			logger.warn("Error calculating rarity modifier: " + e.getMessage());
			return 1.0;
		}
	}
}
