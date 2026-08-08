package org.cote.accountmanager.olio.sd.swarm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SWTxt2Img extends SWCommon {

	private String model = null;
	private String prompt = null;
	
	private String sampler = null;
	private String scheduler = null;
	
	@JsonProperty("refinersampler")
	private String refinerSampler = null;
	@JsonProperty("refinerscheduler")
	private String refinerScheduler = null;
	
	//s2i.setScheduler("Karras");
	//s2i.setSampler_name("DPM++ 2M");
	
	@JsonProperty("negativeprompt")
	private String negativePrompt = null;
	private int images = 1;
	private int steps = 20;
	/// double, not int: FLUX edit/multi-reference models want a fractional CFG (the guidance in
	/// aiDocs/imageComposite.md calls for 1.0-3.5, recommending 2.5), which an int cannot express —
	/// it forced a choice between 2 and 3. SDXL callers passing whole numbers are unaffected (int
	/// widens), and SwarmUI accepts a float cfgscale.
	@JsonProperty("cfgscale")
	private double cfgScale = 7;
	
	private int seed = -1;
	private int height = 1024;
	private int width = 1024;
	
	/// The refiner block is OMITTED unless a caller actually configures it.
	///
	/// These were primitives with non-null initializers (cfgScale 7, upscale 1, steps 20,
	/// "PostApply", "pixel-lanczos"), so Jackson serialized a full refiner block on EVERY request -
	/// including FLUX.2 composites, which have no refiner and never read `hires`. It rendered in
	/// SwarmUI as "Refiner CFG Scale: 7, Refiner Steps: 20, Refiner Method: Post-Apply..." and read as
	/// an enabled refiner. It was inert (no refiner model, control percentage 0) but indistinguishable
	/// from a real misconfiguration, and it cost real time to chase.
	///
	/// Nullable + NON_NULL, matching the initImage/initImageCreativity pattern already used below. The
	/// hires branches in SWUtil/SDUtil set every one of these explicitly, so configured behavior is
	/// unchanged; the non-hires branches set only refinerControlPercentage and now send nothing else.
	@JsonProperty("refinercfgscale")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer refinerCfgScale = null;

	@JsonProperty("refinerupscale")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer refinerUpscale = null;

	@JsonProperty("refinermodel")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String refinerModel = null;

	@JsonProperty("refinersteps")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer refinerSteps = null;

	@JsonProperty("refinermethod")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String refinerMethod = null;

	@JsonProperty("refinerupscalemethod")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String refinerUpscaleMethod = null;

	/// Stays a primitive: every path sets it explicitly (the hires branch from config, the else branch
	/// to 0.0), and two tests assert on it, so it is never ambiguously unset.
	@JsonProperty("refinercontrolpercentage")
	private double refinerControlPercentage = 0.2;

	@JsonProperty("initimage")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String initImage = null;

	@JsonProperty("initimagecreativity")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double initImageCreativity = null;

	/// Prompt reference images: SwarmUI expects "promptimages" as a JSON array of
	/// data URI strings, e.g. ["data:image/png;base64,...", "data:image/png;base64,..."].
	@JsonProperty("promptimages")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private List<String> promptImages = null;

	public SWTxt2Img() {

	}

	public String getSampler() {
		return sampler;
	}

	public void setSampler(String sampler) {
		this.sampler = sampler;
	}

	public String getScheduler() {
		return scheduler;
	}

	public void setScheduler(String scheduler) {
		this.scheduler = scheduler;
	}

	public String getRefinerSampler() {
		return refinerSampler;
	}

	public void setRefinerSampler(String refinerSampler) {
		this.refinerSampler = refinerSampler;
	}

	public String getRefinerScheduler() {
		return refinerScheduler;
	}

	public void setRefinerScheduler(String refinerScheduler) {
		this.refinerScheduler = refinerScheduler;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public String getNegativePrompt() {
		return negativePrompt;
	}

	public void setNegativePrompt(String negativeprompt) {
		this.negativePrompt = negativeprompt;
	}

	public int getImages() {
		return images;
	}

	public void setImages(int images) {
		this.images = images;
	}

	public int getSteps() {
		return steps;
	}

	public void setSteps(int steps) {
		this.steps = steps;
	}

	public double getCfgScale() {
		return cfgScale;
	}

	public void setCfgScale(double cfgScale) {
		this.cfgScale = cfgScale;
	}

	public int getSeed() {
		return seed;
	}

	public void setSeed(int seed) {
		this.seed = seed;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public Integer getRefinerCfgScale() {
		return refinerCfgScale;
	}

	public void setRefinerCfgScale(Integer refinerCfgScale) {
		this.refinerCfgScale = refinerCfgScale;
	}

	public String getRefinerModel() {
		return refinerModel;
	}

	public void setRefinerModel(String refinerModel) {
		this.refinerModel = refinerModel;
	}

	public Integer getRefinerSteps() {
		return refinerSteps;
	}

	public void setRefinerSteps(Integer refinerSteps) {
		this.refinerSteps = refinerSteps;
	}

	public String getRefinerMethod() {
		return refinerMethod;
	}

	public void setRefinerMethod(String refinerMethod) {
		this.refinerMethod = refinerMethod;
	}

	public String getRefinerUpscaleMethod() {
		return refinerUpscaleMethod;
	}

	public void setRefinerUpscaleMethod(String refinerUpscaleMethod) {
		this.refinerUpscaleMethod = refinerUpscaleMethod;
	}

	public double getRefinerControlPercentage() {
		return refinerControlPercentage;
	}

	public void setRefinerControlPercentage(double refinerControlPercentage) {
		this.refinerControlPercentage = refinerControlPercentage;
	}

	public Integer getRefinerUpscale() {
		return refinerUpscale;
	}

	public void setRefinerUpscale(Integer refinerUpscale) {
		this.refinerUpscale = refinerUpscale;
	}

	public String getInitImage() {
		return initImage;
	}

	public void setInitImage(String initImage) {
		this.initImage = initImage;
	}

	public Double getInitImageCreativity() {
		return initImageCreativity;
	}

	public void setInitImageCreativity(Double initImageCreativity) {
		this.initImageCreativity = initImageCreativity;
	}

	public List<String> getPromptImages() {
		return promptImages;
	}

	public void setPromptImages(List<String> promptImages) {
		this.promptImages = promptImages;
	}

}
