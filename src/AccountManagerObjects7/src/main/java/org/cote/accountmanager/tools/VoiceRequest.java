package org.cote.accountmanager.tools;

public class VoiceRequest{
	private String text = null;
	private double speed = 1.2;
	
	public VoiceRequest() {
		
	}
	
	public VoiceRequest(String text) {
		this.text = text;
	}

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	public String getText() {
		return text;
	}
	public void setText(String text) {
		this.text = text;
	}

}