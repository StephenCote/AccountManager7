package org.cote.accountmanager.olio.sd;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SDExtraGenerationParams {
	@JsonProperty("Lora hashes")
	private String loraHashes = null;
	
	@JsonProperty("Schedule type")
	private String scheduleType = null;

	public SDExtraGenerationParams() {
		
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
