package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.RaceEnumType;

public class PromptRaceConfiguration {
	private RaceEnumType raceType = RaceEnumType.U;
	private List<String> race = new ArrayList<>();
	public PromptRaceConfiguration() {
		
	}
	public PromptRaceConfiguration(RaceEnumType raceType, List<String> race) {
		this.raceType = raceType;
		this.race = race;
	}
	public RaceEnumType getRaceType() {
		return raceType;
	}
	public void setRaceType(RaceEnumType raceType) {
		this.raceType = raceType;
	}
	public List<String> getRace() {
		return race;
	}
	public void setRace(List<String> race) {
		this.race = race;
	}
	
}