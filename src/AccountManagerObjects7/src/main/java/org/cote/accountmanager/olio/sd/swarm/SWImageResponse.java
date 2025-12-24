package org.cote.accountmanager.olio.sd.swarm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SWImageResponse {
	private List<String> images = new ArrayList<>();
	
	public SWImageResponse() {

	}

	public List<String> getImages() {
		return images;
	}

	public void setImages(List<String> images) {
		this.images = images;
	}
	
	
}
