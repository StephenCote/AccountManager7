package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.BuilderEnumType;
import org.cote.accountmanager.olio.BuilderUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.PriceUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;

public class Build extends CommonAction {
	public static final Logger logger = LogManager.getLogger(Build.class);

	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		BaseRecord params = actionResult.get(FieldNames.FIELD_PARAMETERS);
		if (params == null) {
			throw new OlioException("Missing required parameters");
		}

		/// Resolve the builder: NeedsUtil may have pre-set it, otherwise look up by itemName
		BaseRecord builder = actionResult.get(OlioFieldNames.FIELD_BUILDER);
		if (builder == null) {
			String builderName = params.get("itemName");
			if (builderName == null || builderName.isEmpty()) {
				throw new OlioException("Builder name (itemName) is required");
			}
			builder = BuilderUtil.getBuilderByName(context, builderName);
			if (builder == null) {
				throw new OlioException("Unknown builder: " + builderName);
			}
			actionResult.setValue(OlioFieldNames.FIELD_BUILDER, builder);
		}

		IOSystem.getActiveContext().getReader().populate(builder);

		/// Validate terrain
		List<String> terrains = builder.get(FieldNames.FIELD_TERRAIN);
		if (terrains != null && !terrains.isEmpty()) {
			BaseRecord cell = actor.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
			if (cell == null) {
				throw new OlioException("Actor has no current location");
			}
			String cellTerrain = ((String) cell.get(FieldNames.FIELD_TERRAIN_TYPE)).toLowerCase();
			if (!terrains.contains(cellTerrain)) {
				logger.warn("Terrain " + cellTerrain + " not suitable for " + builder.get(FieldNames.FIELD_NAME));
				actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
				return actionResult;
			}
		}

		/// Validate skills
		List<BaseRecord> requiredSkills = builder.get(OlioFieldNames.FIELD_SKILLS);
		if (requiredSkills != null && !requiredSkills.isEmpty()) {
			List<String> trades = actor.get(OlioFieldNames.FIELD_TRADES);
			for (BaseRecord skill : requiredSkills) {
				String skillName = skill.get(FieldNames.FIELD_NAME);
				if (trades == null || trades.stream().noneMatch(t -> t.equalsIgnoreCase(skillName))) {
					logger.warn(actor.get(FieldNames.FIELD_NAME) + " lacks required skill: " + skillName);
					actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
					return actionResult;
				}
			}
		}

		/// Validate materials
		List<BaseRecord> requiredMaterials = builder.get(OlioFieldNames.FIELD_MATERIALS);
		if (requiredMaterials != null && !requiredMaterials.isEmpty()) {
			for (BaseRecord mat : requiredMaterials) {
				String matName = mat.get(FieldNames.FIELD_NAME);
				int available = ItemUtil.countItemInInventory(context, actor, matName);
				if (available < 1) {
					logger.warn(actor.get(FieldNames.FIELD_NAME) + " lacks material: " + matName);
					actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
					return actionResult;
				}
			}
		}

		/// Calculate build time adjusted by skill
		/// builder.time is in minutes at mean skill; convert to iterations for edgeEnd
		int builderTime = builder.get("time");
		double skillMod = PriceUtil.calculateQualityModifier(builder, actor);
		/// Higher skill = faster build (skillMod ranges from QUALITY_FLOOR to ~1.0)
		/// Invert so that higher quality modifier means fewer iterations
		int iterations = Math.max(1, (int) Math.ceil(builderTime / skillMod));
		edgeEnd(context, actionResult, iterations);

		return actionResult;
	}

	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

		/// Wait for dependent actions to complete
		List<BaseRecord> dacts = actionResult.get("dependentActions");
		if (dacts.stream().filter(a ->
			a.getEnum(FieldNames.FIELD_TYPE) == ActionResultEnumType.PENDING ||
			a.getEnum(FieldNames.FIELD_TYPE) == ActionResultEnumType.IN_PROGRESS
		).findFirst().isPresent()) {
			return false;
		}

		BaseRecord builder = actionResult.get(OlioFieldNames.FIELD_BUILDER);
		if (builder == null) {
			logger.error("Builder not set on action result");
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
			return false;
		}

		IOSystem.getActiveContext().getReader().populate(builder);

		/// Consume materials
		List<BaseRecord> requiredMaterials = builder.get(OlioFieldNames.FIELD_MATERIALS);
		if (requiredMaterials != null) {
			for (BaseRecord mat : requiredMaterials) {
				String matName = mat.get(FieldNames.FIELD_NAME);
				boolean withdrawn = ItemUtil.withdrawItemFromInventory(context, actor, matName, 1);
				if (!withdrawn) {
					logger.warn("Failed to withdraw material: " + matName);
					actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
					return true;
				}
			}
		}

		/// Produce output based on builder type
		BuilderEnumType builderType = builder.getEnum(FieldNames.FIELD_TYPE);
		double retailValue = PriceUtil.calculateRetailValue(context, builder, actor);
		boolean produced = false;

		switch (builderType) {
			case ITEM:
				produced = produceItem(context, builder, actor, retailValue);
				break;
			case APPAREL:
				produced = produceApparel(context, builder, actor, retailValue);
				break;
			case WEARABLE:
				produced = produceItem(context, builder, actor, retailValue);
				break;
			case LOCATION:
			case FIXTURE:
				/// Location/fixture types: for now, deposit as an item
				/// Full cell modification is a future enhancement
				produced = produceItem(context, builder, actor, retailValue);
				break;
			case BUILDER:
				/// Meta-crafting: building tools that build other things
				/// For now, treat as item production
				produced = produceItem(context, builder, actor, retailValue);
				break;
			default:
				logger.warn("Unhandled builder type: " + builderType);
				break;
		}

		if (produced) {
			int minSeconds = actionResult.get(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME);
			ActionUtil.addProgressSeconds(actionResult, minSeconds);
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);
			logger.info(actor.get(FieldNames.FIELD_NAME) + " built " + builder.get(FieldNames.FIELD_NAME) + " (value: " + String.format("%.2f", retailValue) + ")");
		} else {
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
		}

		return produced;
	}

	private boolean produceItem(OlioContext context, BaseRecord builder, BaseRecord actor, double retailValue) {
		BaseRecord itemTemplate = builder.get(OlioFieldNames.FIELD_ITEM);
		if (itemTemplate == null) {
			/// Some builders (like rope variants) use the builder name as the item name
			String builderName = builder.get(FieldNames.FIELD_NAME);
			BaseRecord built = ItemUtil.buildItem(context, builderName);
			if (built == null) {
				logger.warn("No item template for builder: " + builderName);
				return false;
			}
			applyQualitiesAndPrice(builder, built, actor, retailValue);
			return ItemUtil.depositItemIntoInventory(context, actor, built, 1);
		}

		IOSystem.getActiveContext().getReader().populate(itemTemplate, new String[] {FieldNames.FIELD_NAME});
		BaseRecord built = ItemUtil.buildItem(context, itemTemplate);
		if (built == null) {
			logger.warn("Failed to build item from template: " + itemTemplate.get(FieldNames.FIELD_NAME));
			return false;
		}
		applyQualitiesAndPrice(builder, built, actor, retailValue);
		return ItemUtil.depositItemIntoInventory(context, actor, built, 1);
	}

	private boolean produceApparel(OlioContext context, BaseRecord builder, BaseRecord actor, double retailValue) {
		/// Apparel production - deposit into actor's store apparel list
		/// For now, create as an item; full apparel cloning is a future enhancement
		return produceItem(context, builder, actor, retailValue);
	}

	private void applyQualitiesAndPrice(BaseRecord builder, BaseRecord item, BaseRecord actor, double retailValue) {
		try {
			item.setValue("retailValue", retailValue);
			item.setValue("resaleAdjustment", Rules.RESALE_DEFAULT);
			item.setValue("value", retailValue * Rules.RESALE_DEFAULT);

			/// Apply quality scaling based on skill match
			List<BaseRecord> builderQualities = builder.get(OlioFieldNames.FIELD_QUALITIES);
			if (builderQualities != null && !builderQualities.isEmpty()) {
				BaseRecord bq = builderQualities.get(0);
				double skillReq = bq.get("skill");
				double skillMatch = PriceUtil.calculateQualityModifier(builder, actor);
				double qualityScale = (1.0 - skillReq) + skillReq * skillMatch;

				List<BaseRecord> itemQualities = item.get(OlioFieldNames.FIELD_QUALITIES);
				if (itemQualities != null && !itemQualities.isEmpty()) {
					BaseRecord iq = itemQualities.get(0);
					applyScaledQuality(bq, iq, "offensive", qualityScale);
					applyScaledQuality(bq, iq, "defensive", qualityScale);
					applyScaledQuality(bq, iq, "waterresistance", qualityScale);
					applyScaledQuality(bq, iq, "heatresistance", qualityScale);
					applyScaledQuality(bq, iq, "insulation", qualityScale);
					applyScaledQuality(bq, iq, "hardness", qualityScale);
					applyScaledQuality(bq, iq, "toughness", qualityScale);
				}
			}
		} catch (Exception e) {
			logger.warn("Error applying qualities/price: " + e.getMessage());
		}
	}

	private void applyScaledQuality(BaseRecord source, BaseRecord target, String field, double scale) {
		if (source.hasField(field)) {
			double val = source.get(field);
			if (val > 0) {
				target.setValue(field, val * scale);
			}
		}
	}
}
