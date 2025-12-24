package org.cote.accountmanager.olio.sd.automatic1111;

public class SDResponse {
	private String[] images = new String[0];
	private SDTxt2Img parameters = null;
	private String info = null;
	
	public SDResponse() {
		
	}

	public String[] getImages() {
		return images;
	}

	public void setImages(String[] images) {
		this.images = images;
	}

	public SDTxt2Img getParameters() {
		return parameters;
	}

	public void setParameters(SDTxt2Img parameters) {
		this.parameters = parameters;
	}

	public String getInfo() {
		return info;
	}

	public void setInfo(String info) {
		this.info = info;
	}
	
	
}
