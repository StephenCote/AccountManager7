package org.cote.accountmanager.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

public class FaceRaceScores{
	@JsonProperty("asian")
	private double asian = 0.0;
	@JsonProperty("East Asian")
	private double east_asian = 0.0;
	@JsonProperty("Southeast Asian")
	private double southeast_asian = 0.0;
	@JsonProperty("indian")
	private double indian = 0.0;
	@JsonProperty("black")
	private double black = 0.0;
	@JsonProperty("white")
	private double white = 0.0;
	
	@JsonProperty("middle eastern")
	private double middle_eastern = 0.0;
	@JsonProperty("latino hispanic")
	private double latino_hispanic = 0.0;
	public FaceRaceScores() {
		
	}

	public double getEast_asian() {
		return east_asian;
	}

	public void setEast_asian(double east_asian) {
		this.east_asian = east_asian;
	}

	public double getSoutheast_asian() {
		return southeast_asian;
	}

	public void setSoutheast_asian(double southeast_asian) {
		this.southeast_asian = southeast_asian;
	}

	public double getIndian() {
		return indian;
	}
	public void setIndian(double indian) {
		this.indian = indian;
	}
	public double getBlack() {
		return black;
	}
	public void setBlack(double black) {
		this.black = black;
	}
	public double getWhite() {
		return white;
	}
	public void setWhite(double white) {
		this.white = white;
	}
	public double getMiddle_eastern() {
		return middle_eastern;
	}
	public void setMiddle_eastern(double middle_eastern) {
		this.middle_eastern = middle_eastern;
	}
	public double getLatino_hispanic() {
		return latino_hispanic;
	}
	public void setLatino_hispanic(double latino_hispanic) {
		this.latino_hispanic = latino_hispanic;
	}
	
}

