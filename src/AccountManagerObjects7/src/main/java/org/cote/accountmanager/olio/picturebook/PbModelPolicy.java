package org.cote.accountmanager.olio.picturebook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.illustration.IllustrationModelPolicy;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;

/**
 * PictureBook-side adapter over {@link IllustrationModelPolicy} (P2-3; ⚠ §6.C 156 e/f): turns a book
 * (or an already-resolved {@link PbStyleResolver.EffectiveStyle}) into the policy's style inputs and
 * applies the pick to the config copy the caller is about to send. Three entry points, one chain:
 * <ul>
 * <li>{@link #applyForBook} - resolve the style as the caller, then apply;</li>
 * <li>{@link #applyForStyle} - the graph executors, which resolve once and hand the chain through;</li>
 * <li>{@link #applyWithoutBook} - reimage/chat callers with no book: override and deployment default only.</li>
 * </ul>
 * The style tier's config is {@link PbConfigUtil#styleTierConfig} - the same record whose values fill
 * the chain - so "the style's model" and "the style's values" always come from one place.
 * <p>
 * <b>The override must be the caller's explicit request, never the merged effective config's own
 * {@code model}</b>: the effective record already carries the style tier's checkpoint, and feeding it
 * back as an OVERRIDE would let it win over a {@code taskModels} pin that is meant to beat it. Executors
 * pass the node's sparse {@code configOverride} value; transport callers pass the client's explicit
 * field; both pass null when nothing was asked for.
 */
public final class PbModelPolicy {
	public static final Logger logger = LogManager.getLogger(PbModelPolicy.class);

	private PbModelPolicy() {
		/// static utility
	}

	/// Resolve the book's style as user, then apply the policy for task to cfgCopy.
	public static ModelResolver.Resolution applyForBook(BaseRecord user, BaseRecord book, boolean composite, PbModelTaskEnumType task,
			BaseRecord cfgCopy, String override, SDAPIEnumType sdApiType, String sdServer, boolean referencesRequested) {
		PbStyleResolver.EffectiveStyle style = PbStyleResolver.resolve(user, book);
		return applyForStyle(style, PbConfigUtil.styleTierConfig(book, composite, style), task, cfgCopy, override, sdApiType, sdServer, referencesRequested);
	}

	/// String-typed API overload for transport callers holding the raw init-param value.
	public static ModelResolver.Resolution applyForBook(BaseRecord user, BaseRecord book, boolean composite, PbModelTaskEnumType task,
			BaseRecord cfgCopy, String override, String sdApiType, String sdServer, boolean referencesRequested) {
		return applyForBook(user, book, composite, task, cfgCopy, override, IllustrationModelPolicy.parseApiType(sdApiType), sdServer, referencesRequested);
	}

	/// Apply with a chain the caller already resolved and the style tier's config already chosen.
	public static ModelResolver.Resolution applyForStyle(PbStyleResolver.EffectiveStyle style, BaseRecord styleTierConfig, PbModelTaskEnumType task,
			BaseRecord cfgCopy, String override, SDAPIEnumType sdApiType, String sdServer, boolean referencesRequested) {
		PbStyleResolver.EffectiveStyle es = (style != null ? style : PbStyleResolver.empty());
		return IllustrationModelPolicy.apply(task, cfgCopy, override, es.taskModels(),
			configModel(styleTierConfig, OlioFieldNames.FIELD_SD_MODEL),
			configModel(styleTierConfig, OlioFieldNames.FIELD_SD_FLUX2_MODEL),
			sdApiType, sdServer, referencesRequested);
	}

	/// No book, no style: override -> deployment default (OlioService reimage, ChatService).
	public static ModelResolver.Resolution applyWithoutBook(PbModelTaskEnumType task, BaseRecord cfgCopy, String override,
			SDAPIEnumType sdApiType, String sdServer, boolean referencesRequested) {
		return IllustrationModelPolicy.apply(task, cfgCopy, override, null, null, null, sdApiType, sdServer, referencesRequested);
	}

	/// String-typed API overload of {@link #applyWithoutBook}.
	public static ModelResolver.Resolution applyWithoutBook(PbModelTaskEnumType task, BaseRecord cfgCopy, String override,
			String sdApiType, String sdServer, boolean referencesRequested) {
		return applyWithoutBook(task, cfgCopy, override, IllustrationModelPolicy.parseApiType(sdApiType), sdServer, referencesRequested);
	}

	/// The checkpoint a node's sparse configOverride names for task, or null - the executor's override.
	public static String nodeOverride(BaseRecord node, PbModelTaskEnumType task) {
		if(node == null || !node.hasField(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE)) {
			return null;
		}
		BaseRecord ov = PbConfigUtil.parseOverride(node.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		return IllustrationModelPolicy.currentModel(ov, task);
	}

	private static String configModel(BaseRecord cfg, String field) {
		if(cfg == null || !cfg.hasField(field)) {
			return null;
		}
		Object v = cfg.get(field);
		return (v instanceof String && !((String) v).isBlank() ? (String) v : null);
	}
}
