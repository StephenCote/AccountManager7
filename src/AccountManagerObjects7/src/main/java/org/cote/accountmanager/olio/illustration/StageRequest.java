package org.cote.accountmanager.olio.illustration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// Everything an adapter needs to execute one stage of a {@link RenderPlan} (design §15). The prompt
/// pair is the plan's compiled prompt for the stage - the adapter sends it verbatim. Explicit numeric
/// parameters live here; the refiner block (UPSCALE only) is read from the config snapshot because it
/// is the config's, not the plan's, decision. No init image in Phase 2.
public final class StageRequest {

	public static final int SEED_RANDOM = -1;

	private final RenderPlan plan;
	private final String stage;
	private final CompiledPrompt prompt;
	private final String model;
	private final int width;
	private final int height;
	private final int steps;
	private final double cfg;
	private final String sampler;
	private final String scheduler;
	private final int seed;
	private final int images;
	private final boolean hires;
	private final Map<String, Object> config;

	private StageRequest(Builder b) {
		this.plan = b.plan;
		this.stage = b.stage;
		this.prompt = b.prompt;
		this.model = b.model;
		this.width = b.width;
		this.height = b.height;
		this.steps = b.steps;
		this.cfg = b.cfg;
		this.sampler = b.sampler;
		this.scheduler = b.scheduler;
		this.seed = b.seed;
		this.images = b.images;
		this.hires = b.hires;
		this.config = (b.config == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(b.config)));
	}

	public RenderPlan getPlan() { return plan; }
	public String getStage() { return stage; }
	public CompiledPrompt getPrompt() { return prompt; }
	public String getModel() { return model; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public int getSteps() { return steps; }
	public double getCfg() { return cfg; }
	public String getSampler() { return sampler; }
	public String getScheduler() { return scheduler; }
	/// {@link #SEED_RANDOM} lets the backend choose.
	public int getSeed() { return seed; }
	public int getImages() { return images; }
	/// True only for a stage that should run the config's refiner block (UPSCALE); BASE never does.
	public boolean isHires() { return hires; }
	/// The plan's config snapshot (read-only), consulted for the refiner block when {@link #isHires()}.
	public Map<String, Object> getConfig() { return config; }

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private RenderPlan plan;
		private String stage = RenderPlan.STAGE_BASE;
		private CompiledPrompt prompt;
		private String model;
		private int width = 1024;
		private int height = 1024;
		private int steps = 20;
		private double cfg = 7.0;
		private String sampler;
		private String scheduler;
		private int seed = SEED_RANDOM;
		private int images = 1;
		private boolean hires = false;
		private Map<String, Object> config;

		private Builder() { }

		public Builder plan(RenderPlan plan) { this.plan = plan; return this; }
		public Builder stage(String stage) { this.stage = stage; return this; }
		public Builder prompt(CompiledPrompt prompt) { this.prompt = prompt; return this; }
		public Builder model(String model) { this.model = model; return this; }
		public Builder width(int width) { this.width = width; return this; }
		public Builder height(int height) { this.height = height; return this; }
		public Builder steps(int steps) { this.steps = steps; return this; }
		public Builder cfg(double cfg) { this.cfg = cfg; return this; }
		public Builder sampler(String sampler) { this.sampler = sampler; return this; }
		public Builder scheduler(String scheduler) { this.scheduler = scheduler; return this; }
		public Builder seed(int seed) { this.seed = seed; return this; }
		public Builder images(int images) { this.images = images; return this; }
		public Builder hires(boolean hires) { this.hires = hires; return this; }
		public Builder config(Map<String, Object> config) { this.config = config; return this; }

		public StageRequest build() {
			if(prompt == null) {
				prompt = new CompiledPrompt("", "", 0);
			}
			if(images < 1) {
				images = 1;
			}
			return new StageRequest(this);
		}
	}
}
