package org.cote.accountmanager.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

public class FaceGenderScores{
	@JsonProperty("Woman")
	private double woman = 0.0;
	@JsonProperty("Man")
	private double man = 0.0;
	public FaceGenderScores() {
		
	}
	public double getWoman() {
		return woman;
	}
	public void setWoman(double woman) {
		this.woman = woman;
	}
	public double getMan() {
		return man;
	}
	public void setMan(double man) {
		this.man = man;
	}
	
}