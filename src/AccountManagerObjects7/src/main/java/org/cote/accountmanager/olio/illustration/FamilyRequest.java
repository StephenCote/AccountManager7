package org.cote.accountmanager.olio.illustration;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.sd.Flux2Defaults;

/// Maps the plan's effective sd.config onto a {@link StageRequest} for the family of the checkpoint the
/// request is about to be sent to (PictureBook V3 §6.C 239 c family, P10-A 5). The mapping lives here,
/// once, and every send path applies it where it builds its request - PictureBook BASE, ACTOR_REGION and
/// the chat scene - so no branch is left on the wrong family's knobs (§18.9). Capability-driven, never a
/// per-model option override: the family comes from capabilities.json, the figures from the config and
/// the family's resource defaults.
///
/// <ul>
/// <li><b>FLUX.2</b> ({@link RendererCapabilities#PATTERN_FLUX2}): the SDXL knobs on the config
/// ({@code cfg}/{@code sampler}/{@code scheduler}) are the wrong family's, so cfg = the config's
/// {@code flux2Cfg} when positive else {@code flux2Defaults.json}'s cfgScale; sampler/scheduler = the
/// config's {@code flux2Sampler}/{@code flux2Scheduler} when meaningful else the resource's. Steps are
/// never touched: they are the preset's (§6.C 245 b).</li>
/// <li><b>Every family</b>: the cfg is clamped into the family's {@code cfgRange} when that range is
/// non-degenerate ({@code cfgMin < cfgMax}; a family that declares no range parses to 1.0/1.0 and is left
/// alone), and the clamp is reported once as {@link ConfigNotApplied#CFG_CLAMPED} naming the stage, the
/// value, the range and what was sent. The range is a figure in capabilities.json: if it is wrong for a
/// deployment the fix is the resource, not this class.</li>
/// </ul>
///
/// Idempotent: applying it twice yields the same request and reports nothing new, and a CFG_CLAMPED
/// entry is deduplicated by code so per-variant / per-pass application never breaks the each-code-once
/// contract of the configNotApplied list. Null capabilities (no family matched) leave the request as it is.
public final class FamilyRequest {

	static final String KEY_FLUX2_CFG = "flux2Cfg";
	static final String KEY_FLUX2_SAMPLER = "flux2Sampler";
	static final String KEY_FLUX2_SCHEDULER = "flux2Scheduler";

	private FamilyRequest() { }

	/// The capabilities for a pick: the family the resolver recorded, matched by pattern, else the
	/// checkpoint name re-matched (the same order as {@link PromptCompilers#forPick}); null for a null pick
	/// or when no family matches.
	public static RendererCapabilities capabilitiesFor(RenderPlan.ModelPick pick) {
		if(pick == null) {
			return null;
		}
		String family = pick.getFamily();
		if(family != null && !family.isBlank()) {
			for(RendererCapabilities rc : RendererCapabilities.families()) {
				if(family.equalsIgnoreCase(rc.getPattern())) {
					return rc;
				}
			}
		}
		return RendererCapabilities.forModel(pick.getModel());
	}

	/// True when the pick is resolved and its family is FLUX.2.
	public static boolean isFlux2(RenderPlan.ModelPick pick) {
		if(pick == null || !pick.isResolved()) {
			return false;
		}
		RendererCapabilities caps = capabilitiesFor(pick);
		return caps != null && RendererCapabilities.PATTERN_FLUX2.equals(caps.getPattern());
	}

	/// {@link #apply(StageRequest, RendererCapabilities, Map, List)} for a pick.
	public static StageRequest apply(StageRequest req, RenderPlan.ModelPick pick, Map<String, Object> cfg, List<String> configNotApplied) {
		return apply(req, capabilitiesFor(pick), cfg, configNotApplied);
	}

	/// The request with the family's knobs applied and its cfg clamped into the family's range; the
	/// request itself when {@code caps} is null. {@code configNotApplied} (may be null) receives at most one
	/// CFG_CLAMPED entry, and none when it already carries one.
	public static StageRequest apply(StageRequest req, RendererCapabilities caps, Map<String, Object> cfg, List<String> configNotApplied) {
		if(req == null || caps == null) {
			return req;
		}
		double cfgVal = req.getCfg();
		String sampler = req.getSampler();
		String scheduler = req.getScheduler();
		if(RendererCapabilities.PATTERN_FLUX2.equals(caps.getPattern())) {
			cfgVal = flux2Cfg(cfg);
			sampler = configString(cfg, KEY_FLUX2_SAMPLER, Flux2Defaults.sampler());
			scheduler = configString(cfg, KEY_FLUX2_SCHEDULER, Flux2Defaults.scheduler());
		}
		double lo = caps.getCfgMin();
		double hi = caps.getCfgMax();
		double sent = cfgVal;
		if(lo < hi) {
			sent = Math.max(lo, Math.min(hi, cfgVal));
		}
		if(sent != cfgVal) {
			report(configNotApplied, ConfigNotApplied.CFG_CLAMPED + ": " + req.getStage() + " cfg " + fmt(cfgVal)
				+ " is outside the " + caps.getPattern() + " family's range [" + fmt(lo) + ", " + fmt(hi) + "]; sent " + fmt(sent));
		}
		if(sent == req.getCfg() && same(sampler, req.getSampler()) && same(scheduler, req.getScheduler())) {
			return req;
		}
		return req.toBuilder().cfg(sent).sampler(sampler).scheduler(scheduler).build();
	}

	/// The config's flux2Cfg when positive, else the resource's cfgScale.
	static double flux2Cfg(Map<String, Object> cfg) {
		if(cfg != null) {
			Object c = cfg.get(KEY_FLUX2_CFG);
			if(c instanceof Number && ((Number) c).doubleValue() > 0d) {
				return ((Number) c).doubleValue();
			}
		}
		return Flux2Defaults.cfgScale();
	}

	private static String configString(Map<String, Object> cfg, String key, String fallback) {
		if(cfg == null) {
			return fallback;
		}
		Object o = cfg.get(key);
		String s = (o == null ? null : String.valueOf(o).trim());
		return (NarrativeUtil.isMeaningful(s) ? s : fallback);
	}

	/// Adds the entry unless the list is null or already carries the same code.
	private static void report(List<String> configNotApplied, String entry) {
		if(configNotApplied == null) {
			return;
		}
		String code = entry.substring(0, entry.indexOf(':') + 1);
		for(String e : configNotApplied) {
			if(e != null && e.startsWith(code)) {
				return;
			}
		}
		configNotApplied.add(entry);
	}

	private static boolean same(String a, String b) {
		return (a == null ? b == null : a.equals(b));
	}

	private static String fmt(double d) {
		if(d == Math.rint(d) && !Double.isInfinite(d)) {
			return String.valueOf((long) d);
		}
		return String.format(Locale.ROOT, "%.2f", d);
	}
}
