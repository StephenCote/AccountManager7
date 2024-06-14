package org.cote.accountmanager.olio.sd;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SDExtraGenerationParams {
	@JsonProperty("Lora hashes")
	private String loraHashes = null;
	
	@JsonProperty("Schedule type")
	private String scheduleType = null;
	
	@JsonProperty("Refiner")
	private String refiner = null;
	
	@JsonProperty("Refiner switch at")
	private double refinerSwitchAt = 0.75;
	
	@JsonProperty("Hires upscale")
	private int hiresUpscale = 2;
	
	@JsonProperty("Hires upscaler")
	private String hiresUpscaler = "Latent";
	
	@JsonProperty("Denoising strength")
	private double denoisingStrength = 0.75;

	public SDExtraGenerationParams() {
		
	}

	public String getRefiner() {
		return refiner;
	}

	public void setRefiner(String refiner) {
		this.refiner = refiner;
	}

	public double getRefinerSwitchAt() {
		return refinerSwitchAt;
	}

	public void setRefinerSwitchAt(double refinerSwitchAt) {
		this.refinerSwitchAt = refinerSwitchAt;
	}

	public int getHiresUpscale() {
		return hiresUpscale;
	}

	public void setHiresUpscale(int hiresUpscale) {
		this.hiresUpscale = hiresUpscale;
	}

	public String getHiresUpscaler() {
		return hiresUpscaler;
	}

	public void setHiresUpscaler(String hiresUpscaler) {
		this.hiresUpscaler = hiresUpscaler;
	}

	public double getDenoisingStrength() {
		return denoisingStrength;
	}

	public void setDenoisingStrength(double denoisingStrength) {
		this.denoisingStrength = denoisingStrength;
	}

	public String getLoraHashes() {
		return loraHashes;
	}

	public void setLoraHashes(String loraHashes) {
		this.loraHashes = loraHashes;
	}

	public String getScheduleType() {
		return scheduleType;
	}

	public void setScheduleType(String scheduleType) {
		this.scheduleType = scheduleType;
	}
	
	
}
