package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;

/// The one model policy (PictureBook V3 design §16): which installed checkpoint renders a task.
/// Pure and final - plain values in, a Resolution out, never throws, no DB, no SD, nothing from
/// olio.picturebook. Callers (reimage, chat, PB, ChapBook) build a Request from their style chain and
/// deployment defaults and pass the server's inventory in.
///
/// Walk, in order, until one candidate passes: OVERRIDE (an explicit request) -> STYLE_TASK (the
/// style's taskModels entry for the task) -> STYLE_DEFAULT (the style's sdConfig.model, or
/// sdConfig.flux2Model for SCENE_DIRECT) -> DEPLOYMENT_DEFAULT (the boot-pinned sd.default.model /
/// sd.model, or the flux2 schema default for SCENE_DIRECT). A candidate passes when it is installed
/// (CheckpointNames), its family is known (RendererCapabilities.forModel), enabled, and supports the
/// task. The first pass wins and Resolution.model is the LISTED name - what the server accepts.
///
/// A rejected candidate followed by a later pass is a degradation (degradationCode
/// MODEL_SUBSTITUTED, §19) and is reported, never silent. All steps exhausted is an unresolved
/// Resolution with reason NO_CANDIDATE; an inventory that could not be read is INVENTORY_UNAVAILABLE
/// ("renderer unavailable", §20) - the two are different failures and are never conflated.
public final class ModelResolver {

	public static final String DEGRADATION_MODEL_SUBSTITUTED = "MODEL_SUBSTITUTED";

	/// Composite-mode storage vocabulary, mirrored from olio.sd.SceneCompositeUtil.MODE_* so this
	/// class stays free of SD imports; TestModelResolver asserts the two agree.
	public static final String MODE_FLUX2 = "flux2";
	public static final String MODE_KONTEXT = "kontext";
	public static final String MODE_CLASSIC = "classic";

	private ModelResolver() { }

	public enum Step {
		OVERRIDE,
		STYLE_TASK,
		STYLE_DEFAULT,
		DEPLOYMENT_DEFAULT
	}

	public enum Reason {
		OK,
		NOT_INSTALLED,
		FAMILY_UNKNOWN,
		FAMILY_DISABLED,
		FAMILY_UNSUPPORTED_FOR_TASK,
		NO_CANDIDATE,
		INVENTORY_UNAVAILABLE
	}

	// ─────────────────────────────── value types ───────────────────────────────

	/// One step's attempt: what was asked for, what the server lists it as (null when not installed),
	/// the family it matched (null when unknown) and why it passed or was rejected.
	public static final class Candidate {
		private final Step step;
		private final String requested;
		private final String listedName;
		private final RendererCapabilities capabilities;
		private final Reason reason;

		Candidate(Step step, String requested, String listedName, RendererCapabilities capabilities, Reason reason) {
			this.step = step;
			this.requested = requested;
			this.listedName = listedName;
			this.capabilities = capabilities;
			this.reason = reason;
		}

		public Step getStep() { return step; }
		public String getRequested() { return requested; }
		public String getListedName() { return listedName; }
		/// The matched family, or null when the name matched no family (FAMILY_UNKNOWN) or was not installed.
		public RendererCapabilities getCapabilities() { return capabilities; }
		public String getFamily() { return (capabilities != null ? capabilities.getPattern() : null); }
		public Reason getReason() { return reason; }

		@Override
		public String toString() {
			return step + "(" + requested + " -> " + listedName + ", " + getFamily() + "): " + reason;
		}
	}

	/// What a caller knows before resolving. Build with builder(); every value is optional except task.
	public static final class Request {
		private final PbModelTaskEnumType task;
		private final String override;
		private final Map<PbModelTaskEnumType, String> taskModels;
		private final String styleModel;
		private final String styleFlux2Model;
		private final String deploymentModel;
		private final String deploymentFlux2Model;
		private final boolean referencesRequested;

		private Request(Builder b) {
			this.task = (b.task != null ? b.task : PbModelTaskEnumType.UNKNOWN);
			this.override = b.override;
			this.taskModels = (b.taskModels != null && !b.taskModels.isEmpty()
				? Collections.unmodifiableMap(new LinkedHashMap<>(b.taskModels))
				: Collections.emptyMap());
			this.styleModel = b.styleModel;
			this.styleFlux2Model = b.styleFlux2Model;
			this.deploymentModel = b.deploymentModel;
			this.deploymentFlux2Model = b.deploymentFlux2Model;
			this.referencesRequested = b.referencesRequested;
		}

		public PbModelTaskEnumType getTask() { return task; }
		public String getOverride() { return override; }
		public Map<PbModelTaskEnumType, String> getTaskModels() { return taskModels; }
		public String getStyleModel() { return styleModel; }
		public String getStyleFlux2Model() { return styleFlux2Model; }
		public String getDeploymentModel() { return deploymentModel; }
		public String getDeploymentFlux2Model() { return deploymentFlux2Model; }
		public boolean isReferencesRequested() { return referencesRequested; }

		public static Builder builder(PbModelTaskEnumType task) {
			return new Builder(task);
		}

		public static final class Builder {
			private final PbModelTaskEnumType task;
			private String override;
			private Map<PbModelTaskEnumType, String> taskModels;
			private String styleModel;
			private String styleFlux2Model;
			private String deploymentModel;
			private String deploymentFlux2Model;
			private boolean referencesRequested;

			private Builder(PbModelTaskEnumType task) {
				this.task = task;
			}

			public Builder override(String v) { this.override = v; return this; }
			public Builder taskModels(Map<PbModelTaskEnumType, String> v) { this.taskModels = v; return this; }
			public Builder styleModel(String v) { this.styleModel = v; return this; }
			public Builder styleFlux2Model(String v) { this.styleFlux2Model = v; return this; }
			public Builder deploymentModel(String v) { this.deploymentModel = v; return this; }
			public Builder deploymentFlux2Model(String v) { this.deploymentFlux2Model = v; return this; }
			public Builder referencesRequested(boolean v) { this.referencesRequested = v; return this; }
			public Request build() { return new Request(this); }
		}
	}

	/// The outcome. isResolved() == true means model names an installed, enabled, task-capable
	/// checkpoint exactly as the server lists it. Otherwise reason says why: NO_CANDIDATE (every step
	/// was empty or rejected - candidates holds the audit trail) or INVENTORY_UNAVAILABLE (the list
	/// could not be read; inventoryError says why).
	public static final class Resolution {
		private final PbModelTaskEnumType task;
		private final String model;
		private final RendererCapabilities capabilities;
		private final Step step;
		private final Reason reason;
		private final boolean degraded;
		private final String degradationCode;
		private final String degradationText;
		private final long inventoryFetchedAt;
		private final String inventoryError;
		private final List<Candidate> candidates;

		Resolution(PbModelTaskEnumType task, String model, RendererCapabilities capabilities, Step step, Reason reason,
				boolean degraded, String degradationCode, String degradationText, long inventoryFetchedAt,
				String inventoryError, List<Candidate> candidates) {
			this.task = task;
			this.model = model;
			this.capabilities = capabilities;
			this.step = step;
			this.reason = reason;
			this.degraded = degraded;
			this.degradationCode = degradationCode;
			this.degradationText = degradationText;
			this.inventoryFetchedAt = inventoryFetchedAt;
			this.inventoryError = inventoryError;
			this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
		}

		public PbModelTaskEnumType getTask() { return task; }
		/// The listed name, or null when unresolved.
		public String getModel() { return model; }
		public RendererCapabilities getCapabilities() { return capabilities; }
		/// The family glob the model matched (RendererCapabilities.getPattern()), or null when unresolved.
		public String getFamily() { return (capabilities != null ? capabilities.getPattern() : null); }
		public Step getStep() { return step; }
		/// OK when resolved; NO_CANDIDATE or INVENTORY_UNAVAILABLE when not.
		public Reason getReason() { return reason; }
		public boolean isDegraded() { return degraded; }
		public String getDegradationCode() { return degradationCode; }
		public String getDegradationText() { return degradationText; }
		public long getInventoryFetchedAt() { return inventoryFetchedAt; }
		public String getInventoryError() { return inventoryError; }
		public List<Candidate> getCandidates() { return candidates; }
		public boolean isResolved() { return model != null && reason == Reason.OK; }

		@Override
		public String toString() {
			return "Resolution{" + task + " -> " + model + " via " + step + ", " + reason
				+ (degraded ? ", degraded: " + degradationText : "")
				+ (inventoryError != null ? ", inventoryError=" + inventoryError : "") + "}";
		}
	}

	// ─────────────────────────────── resolution ───────────────────────────────

	/// Resolve against a live or in-memory inventory. Primary entry point.
	public static Resolution resolve(Request req, ModelInventory inventory) {
		ModelInventory inv = (inventory != null ? inventory : ModelInventory.of(null));
		List<String> installed = (inv.installedModels() != null ? inv.installedModels() : Collections.emptyList());
		PbModelTaskEnumType task = (req != null ? req.getTask() : PbModelTaskEnumType.UNKNOWN);
		List<Candidate> trail = new ArrayList<>();

		if(inv.error() != null) {
			return new Resolution(task, null, null, null, Reason.INVENTORY_UNAVAILABLE, false, null,
				"installed-model list unavailable: " + inv.error(), inv.fetchedAt(), inv.error(), trail);
		}
		if(req == null || task == PbModelTaskEnumType.UNKNOWN) {
			return new Resolution(task, null, null, null, Reason.NO_CANDIDATE, false, null,
				"no model task was named", inv.fetchedAt(), null, trail);
		}

		boolean flux = (task == PbModelTaskEnumType.SCENE_DIRECT);
		String[] requested = new String[] {
			req.getOverride(),
			req.getTaskModels().get(task),
			(flux ? req.getStyleFlux2Model() : req.getStyleModel()),
			(flux ? req.getDeploymentFlux2Model() : req.getDeploymentModel())
		};
		Step[] steps = Step.values();
		for(int i = 0; i < steps.length; i++) {
			String want = requested[i];
			if(want == null || want.isBlank()) {
				continue;
			}
			Candidate c = evaluate(steps[i], want.trim(), installed, task, req.isReferencesRequested());
			trail.add(c);
			if(c.getReason() == Reason.OK) {
				boolean degraded = trail.size() > 1;
				return new Resolution(task, c.getListedName(), c.getCapabilities(), c.getStep(), Reason.OK, degraded,
					(degraded ? DEGRADATION_MODEL_SUBSTITUTED : null), (degraded ? degradationText(trail) : null),
					inv.fetchedAt(), null, trail);
			}
		}
		String text = (trail.isEmpty()
			? "no checkpoint was requested at any step for " + task
			: "no installed checkpoint can render " + task + ": " + rejectedText(trail));
		return new Resolution(task, null, null, null, Reason.NO_CANDIDATE, false, null, text, inv.fetchedAt(), null, trail);
	}

	/// Convenience for the pure tests and callers holding a plain list: wraps it as an in-memory
	/// inventory (a successful read, never INVENTORY_UNAVAILABLE).
	public static Resolution resolve(Request req, List<String> installed) {
		return resolve(req, ModelInventory.of(installed));
	}

	private static Candidate evaluate(Step step, String want, List<String> installed, PbModelTaskEnumType task, boolean refs) {
		String listed = CheckpointNames.matchInstalled(installed, want);
		if(listed == null) {
			return new Candidate(step, want, null, null, Reason.NOT_INSTALLED);
		}
		RendererCapabilities rc = RendererCapabilities.forModel(listed);
		return new Candidate(step, want, listed, rc, supportsTask(rc, task, refs));
	}

	/// The task->family rule from capabilities.json flags only. PORTRAIT, LOCATION and UPSCALE accept
	/// any enabled family (there is no upscale flag; the refiner upscale works on any checkpoint,
	/// §15); SCENE_DIRECT needs supportsReferences when references are requested; INPAINT needs
	/// supportsMasks; HARMONIZE needs supportsInitImage. A null family is FAMILY_UNKNOWN; a disabled
	/// one (Kontext, §37.3) is FAMILY_DISABLED at every step, an explicit override included.
	public static Reason supportsTask(RendererCapabilities rc, PbModelTaskEnumType task, boolean referencesRequested) {
		if(rc == null) {
			return Reason.FAMILY_UNKNOWN;
		}
		if(!rc.isEnabled()) {
			return Reason.FAMILY_DISABLED;
		}
		if(task == null) {
			return Reason.FAMILY_UNSUPPORTED_FOR_TASK;
		}
		switch(task) {
			case PORTRAIT:
			case LOCATION:
			case UPSCALE:
				return Reason.OK;
			case SCENE_DIRECT:
				return (!referencesRequested || rc.isSupportsReferences()) ? Reason.OK : Reason.FAMILY_UNSUPPORTED_FOR_TASK;
			case INPAINT:
				return rc.isSupportsMasks() ? Reason.OK : Reason.FAMILY_UNSUPPORTED_FOR_TASK;
			case HARMONIZE:
				return rc.isSupportsInitImage() ? Reason.OK : Reason.FAMILY_UNSUPPORTED_FOR_TASK;
			default:
				return Reason.FAMILY_UNSUPPORTED_FOR_TASK;
		}
	}

	/// The one place a stored composite mode becomes a §13 strategy: flux2 -> DIRECT_REFERENCE,
	/// classic -> CLASSIC_COMPOSITE; kontext (retired, §37.3), null and anything else -> UNKNOWN.
	/// Strategy SELECTION (fallbacks such as SEQUENTIAL_INPAINT) is the planner's, not this table's.
	public static PbStrategyEnumType strategyForMode(String compositeMode) {
		if(compositeMode == null) {
			return PbStrategyEnumType.UNKNOWN;
		}
		String m = compositeMode.trim();
		if(MODE_FLUX2.equalsIgnoreCase(m)) {
			return PbStrategyEnumType.DIRECT_REFERENCE;
		}
		if(MODE_CLASSIC.equalsIgnoreCase(m)) {
			return PbStrategyEnumType.CLASSIC_COMPOSITE;
		}
		return PbStrategyEnumType.UNKNOWN;
	}

	/// A Resolution as the plan's ModelPick (§12): the walk's candidates as plain string maps so the
	/// pick round-trips through RenderPlan.toJson/fromJson unchanged.
	public static RenderPlan.ModelPick pickOf(Resolution res) {
		List<Map<String, String>> cands = new ArrayList<>();
		if(res.getCandidates() != null) {
			for(Candidate c : res.getCandidates()) {
				Map<String, String> m = new LinkedHashMap<>();
				m.put("step", c.getStep() != null ? c.getStep().name() : null);
				m.put("requested", c.getRequested());
				m.put("listedName", c.getListedName());
				m.put("family", c.getFamily());
				m.put("reason", c.getReason() != null ? c.getReason().name() : null);
				cands.add(m);
			}
		}
		return new RenderPlan.ModelPick(res.getTask(), res.getModel(), res.getFamily(),
			res.getStep() != null ? res.getStep().name() : null,
			res.getReason() != null ? res.getReason().name() : null,
			res.isDegraded(), cands);
	}

	/// Adds the MODEL_SUBSTITUTED degradation a resolution earns: the §16 sentence when it substituted,
	/// the reason (and inventory error) when it is unresolved, nothing when it resolved cleanly.
	public static void addDegradation(Resolution res, List<RenderPlan.Degradation> out) {
		String message = null;
		if(res.isResolved() && res.isDegraded()) {
			message = degradationText(res);
		}
		else if(!res.isResolved()) {
			message = "Model for " + res.getTask() + " is unresolved: " + res.getReason()
				+ (res.getInventoryError() != null ? " (" + res.getInventoryError() + ")" : "");
		}
		if(message != null) {
			out.add(new RenderPlan.Degradation(RenderPlan.Degradation.MODEL_SUBSTITUTED, message, null, RenderPlan.STAGE_BASE));
		}
	}

	/// 503 when the refusal is the renderer's (its inventory could not be read), 409 when it is the plan's
	/// (nothing installed matches the request); both are "reported before rendering" (§12).
	public static int unresolvedStatus(RenderPlan.ModelPick pick) {
		return (pick != null && Reason.INVENTORY_UNAVAILABLE.name().equals(pick.getReason())) ? 503 : 409;
	}

	/// The resolver's reason, never the server URL or API type (those stay in the log, as the inventory
	/// does). planOid names the persisted plan artifact when there is one; null for a transient plan.
	public static String unresolvedMessage(String label, RenderPlan.ModelPick pick, RenderPlan plan, String planOid) {
		StringBuilder sb = new StringBuilder();
		if(pick == null) {
			sb.append("No model pick for the BASE stage of ").append(label);
		}
		else if(Reason.INVENTORY_UNAVAILABLE.name().equals(pick.getReason())) {
			sb.append("Renderer inventory unavailable; cannot verify a model for ").append(label).append(" before rendering");
		}
		else {
			sb.append("No installed model for ").append(label).append(": ");
			List<String> parts = new ArrayList<>();
			for(Map<String, String> c : pick.getCandidates()) {
				parts.add(c.get("step") + " '" + c.get("requested") + "' " + c.get("reason"));
			}
			sb.append(parts.isEmpty() ? String.valueOf(pick.getReason()) : String.join("; ", parts));
		}
		if(plan != null) {
			for(RenderPlan.Degradation d : plan.getDegradations()) {
				if(RenderPlan.Degradation.MODEL_SUBSTITUTED.equals(d.getCode())) {
					sb.append(" - ").append(d.getMessage());
					break;
				}
			}
		}
		if(planOid != null) {
			sb.append(" (plan artifact ").append(planOid).append(")");
		}
		return sb.toString();
	}

	/// §16's degradation sentence for a resolution that substituted: every rejected candidate with
	/// its reason, then the step and listed name that was used. Null when nothing was substituted.
	public static String degradationText(Resolution r) {
		if(r == null || !r.isResolved() || !r.isDegraded()) {
			return null;
		}
		return degradationText(r.getCandidates());
	}

	private static String degradationText(List<Candidate> trail) {
		Candidate chosen = trail.get(trail.size() - 1);
		return rejectedText(trail) + "; using " + chosen.getStep() + " → " + chosen.getListedName();
	}

	private static String rejectedText(List<Candidate> trail) {
		StringBuilder sb = new StringBuilder();
		for(Candidate c : trail) {
			if(c.getReason() == Reason.OK) {
				continue;
			}
			if(sb.length() > 0) {
				sb.append("; ");
			}
			sb.append(c.getRequested()).append(' ').append(phrase(c));
		}
		return sb.toString();
	}

	private static String phrase(Candidate c) {
		switch(c.getReason()) {
			case NOT_INSTALLED: return "not installed";
			case FAMILY_UNKNOWN: return "is of no known family";
			case FAMILY_DISABLED: return "is of a disabled family (" + c.getFamily() + ")";
			case FAMILY_UNSUPPORTED_FOR_TASK: return "family " + c.getFamily() + " does not support this task";
			default: return String.valueOf(c.getReason());
		}
	}
}
