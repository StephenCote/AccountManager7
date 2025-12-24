package org.cote.accountmanager.olio.sd.automatic1111;

public class Auto1111Response {
	private String[] images = new String[0];
	private Auto1111Txt2Img parameters = null;
	private String info = null;
	
	public Auto1111Response() {
		
	}

	public String[] getImages() {
		return images;
	}

	public void setImages(String[] images) {
		this.images = images;
	}

	public Auto1111Txt2Img getParameters() {
		return parameters;
	}

	public void setParameters(Auto1111Txt2Img parameters) {
		this.parameters = parameters;
	}

	public String getInfo() {
		return info;
	}

	public void setInfo(String info) {
		this.info = info;
	}
	
	
}
