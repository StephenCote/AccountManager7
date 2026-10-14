package org.cote.accountmanager.olio.illustration;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;

/// The one model policy applied to a config (PictureBook V3 §16, P2-3; ⚠ §6.C 156 e/f/i). Every
/// image-producing caller - OlioService reimage, ChatService scene/landscape, PB1 portrait, the V3
/// executor and the ChapBook page path - builds the same ModelResolver.Request from the same four
/// tiers (override -> style task pin -> style config default -> deployment default) against the same
/// SwarmModelInventory, and writes the winning LISTED checkpoint name into the config copy it is about
/// to send. Nothing here persists, caches per-org state, or touches IOContext singletons; the only
/// mutation is the field on the copy the caller hands in.
///
/// Unresolved (not installed, unknown family, inventory unreadable, ...): the copy gets the
/// deployment default for the task's field and the resolver's reason is logged at WARN. The caller
/// still receives the Resolution so a pre-render surface can report "missing model" with the reason
/// (P2-5) instead of discovering it from the renderer.
///
/// Field names are plain strings on purpose: this package knows nothing of olio.picturebook or
/// OlioFieldNames (§31 layering).
public final class IllustrationModelPolicy {
	public static final Logger logger = LogManager.getLogger(IllustrationModelPolicy.class);

	/// olio.sd.config.model - the classic/SDXL checkpoint field.
	public static final String FIELD_MODEL = "model";

	/// olio.sd.config.flux2Model - the FLUX.2 checkpoint field, written for SCENE_DIRECT.
	public static final String FIELD_FLUX2_MODEL = "flux2Model";

	private IllustrationModelPolicy() {
		/// static utility
	}

	/// The config field a task's pick lands in: flux2Model for SCENE_DIRECT, model for everything else.
	public static String fieldForTask(PbModelTaskEnumType task) {
		return (task == PbModelTaskEnumType.SCENE_DIRECT ? FIELD_FLUX2_MODEL : FIELD_MODEL);
	}

	/// Build the request, resolve against the server's inventory, write the pick (or the deployment
	/// default when unresolved) into sdConfigCopy, and return the Resolution.
	///
	/// @param task              what is being rendered (§16 task names)
	/// @param sdConfigCopy      the per-call config copy to write into; null is accepted (resolve only)
	/// @param override          an explicit checkpoint request (client body, node configOverride); null when none
	/// @param taskModels        the effective style's task -> checkpoint map; null when no style
	/// @param styleModel        the style tier's config.model; null when none
	/// @param styleFlux2Model   the style tier's config.flux2Model; null when none
	/// @param sdApiType         the renderer API in use (SWARM resolves; anything else is "inventory unavailable")
	/// @param sdServer          the renderer base URL
	/// @param referencesRequested whether reference images will be attached (affects family support)
	public static ModelResolver.Resolution apply(PbModelTaskEnumType task, BaseRecord sdConfigCopy, String override,
			Map<PbModelTaskEnumType, String> taskModels, String styleModel, String styleFlux2Model,
			SDAPIEnumType sdApiType, String sdServer, boolean referencesRequested) {
		PbModelTaskEnumType t = (task != null ? task : PbModelTaskEnumType.UNKNOWN);
		DeploymentModelDefaults.Defaults defaults = DeploymentModelDefaults.current();
		ModelResolver.Request.Builder req = ModelResolver.Request.builder(t)
			.override(override)
			.styleModel(styleModel)
			.styleFlux2Model(styleFlux2Model)
			.deploymentModel(defaults.getModel())
			.deploymentFlux2Model(defaults.getFlux2Model())
			.referencesRequested(referencesRequested);
		if(taskModels != null && !taskModels.isEmpty()) {
			req.taskModels(taskModels);
		}
		ModelInventory inventory = SwarmModelInventory.forServer(sdApiType, sdServer);
		ModelResolver.Resolution res = ModelResolver.resolve(req.build(), inventory);
		String field = fieldForTask(t);
		String pick;
		if(res.isResolved()) {
			pick = res.getModel();
			if(res.isDegraded()) {
				logger.warn("Model policy (" + t + "): " + ModelResolver.degradationText(res));
			}
		}
		else {
			pick = (FIELD_FLUX2_MODEL.equals(field) ? defaults.getFlux2Model() : defaults.getModel());
			logger.warn("Model policy (" + t + "): unresolved - " + res.getReason()
				+ (res.getInventoryError() != null ? " (" + res.getInventoryError() + ")" : "")
				+ "; writing deployment default " + pick + " into " + field);
		}
		write(sdConfigCopy, field, pick);
		return res;
	}

	/// Lenient overload for transport callers that hold the API type as the raw init-param string. An
	/// unparseable or blank type resolves with no inventory (INVENTORY_UNAVAILABLE -> deployment default
	/// + WARN) rather than throwing.
	public static ModelResolver.Resolution apply(PbModelTaskEnumType task, BaseRecord sdConfigCopy, String override,
			Map<PbModelTaskEnumType, String> taskModels, String styleModel, String styleFlux2Model,
			String sdApiType, String sdServer, boolean referencesRequested) {
		return apply(task, sdConfigCopy, override, taskModels, styleModel, styleFlux2Model, parseApiType(sdApiType), sdServer, referencesRequested);
	}

	public static SDAPIEnumType parseApiType(String sdApiType) {
		if(sdApiType == null || sdApiType.isBlank()) {
			return null;
		}
		try {
			return SDAPIEnumType.valueOf(sdApiType.trim().toUpperCase());
		}
		catch(IllegalArgumentException e) {
			logger.warn("Unknown SD API type '" + sdApiType + "' - model inventory unavailable");
			return null;
		}
	}

	/// The value a config currently carries in the task's field, or null. Read-side helper so callers
	/// can pass "what the client explicitly asked for" as the override without importing field names.
	public static String currentModel(BaseRecord sdConfig, PbModelTaskEnumType task) {
		String field = fieldForTask(task);
		if(sdConfig == null || !sdConfig.hasField(field)) {
			return null;
		}
		Object v = sdConfig.get(field);
		return (v instanceof String && !((String) v).isBlank() ? (String) v : null);
	}

	/// A deserialized config carries only the fields its JSON named, so presence is not the test -
	/// whether the schema declares the field is; set() materialises a declared field on write.
	private static void write(BaseRecord copy, String field, String value) {
		if(copy == null || value == null || value.isBlank()) {
			return;
		}
		if(!copy.hasField(field) && !declares(copy, field)) {
			logger.warn("Model policy: " + copy.getSchema() + " declares no '" + field + "' field; the pick " + value + " is not applied");
			return;
		}
		copy.setValue(field, value);
	}

	private static boolean declares(BaseRecord rec, String field) {
		try {
			ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
			return (ms != null && ms.getFieldSchema(field) != null);
		}
		catch(RuntimeException e) {
			return false;
		}
	}
}
