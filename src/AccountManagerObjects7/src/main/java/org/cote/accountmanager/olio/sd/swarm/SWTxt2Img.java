package org.cote.accountmanager.olio.sd.swarm;

import java.util.Map;

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
	@JsonProperty("cfgscale")
	private int cfgScale = 7;
	
	private int seed = -1;
	private int height = 1024;
	private int width = 1024;
	
	@JsonProperty("refinercfgscale")
	private int refinerCfgScale = 7;
	
	@JsonProperty("refinerupscale")
	private int refinerUpscale = 1;
	
	@JsonProperty("refinermodel")
	private String refinerModel = null;
	
	@JsonProperty("refinersteps")
	private int refinerSteps = 20;
	
	@JsonProperty("refinermethod")
	private String refinerMethod = "PostApply";

	@JsonProperty("refinerupscalemethod")
	private String refinerUpscaleMethod = "pixel-lanczos";
	
	@JsonProperty("refinercontrolpercentage")
	private double refinerControlPercentage = 0.2;

	@JsonProperty("initimage")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String initImage = null;

	@JsonProperty("initimagecreativity")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double initImageCreativity = null;

	/// IP-Adapter reference images: SwarmUI accepts "promptimages" as a JSON object
	/// mapping reference names to base64 image data. In the prompt text, use <image:name>
	/// to embed the reference at that position for face/style consistency.
	@JsonProperty("promptimages")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private Map<String, String> promptImages = null;

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

	public int getCfgScale() {
		return cfgScale;
	}

	public void setCfgScale(int cfgScale) {
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

	public int getRefinerCfgScale() {
		return refinerCfgScale;
	}

	public void setRefinerCfgScale(int refinerCfgScale) {
		this.refinerCfgScale = refinerCfgScale;
	}

	public String getRefinerModel() {
		return refinerModel;
	}

	public void setRefinerModel(String refinerModel) {
		this.refinerModel = refinerModel;
	}

	public int getRefinerSteps() {
		return refinerSteps;
	}

	public void setRefinerSteps(int refinerSteps) {
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

	public int getRefinerUpscale() {
		return refinerUpscale;
	}

	public void setRefinerUpscale(int refinerUpscale) {
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

	public Map<String, String> getPromptImages() {
		return promptImages;
	}

	public void setPromptImages(Map<String, String> promptImages) {
		this.promptImages = promptImages;
	}

}
