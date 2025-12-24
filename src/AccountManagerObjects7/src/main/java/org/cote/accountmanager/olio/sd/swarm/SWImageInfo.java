package org.cote.accountmanager.olio.sd.swarm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SWImageInfo {
	@JsonProperty("sui_image_params")
	private SWTxt2Img imageParams = null;
	
	public SWImageInfo() {

	}

	public SWTxt2Img getImageParams() {
		return imageParams;
	}

	public void setImageParams(SWTxt2Img imageParams) {
		this.imageParams = imageParams;
	}
	
	

}
