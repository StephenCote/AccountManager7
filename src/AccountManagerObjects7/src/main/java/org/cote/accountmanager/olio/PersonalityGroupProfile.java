package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.olio.PersonalityProfile.EsteemNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.LoveNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.PhysiologicalNeeds;
import org.cote.accountmanager.olio.PersonalityProfile.SafetyNeeds;

public class PersonalityGroupProfile {
	
	private Map<PhysiologicalNeeds, Integer> physiologicalNeeds = new HashMap<>();
	private Map<SafetyNeeds, Integer> safetyNeeds = new HashMap<>();
	private Map<EsteemNeeds, Integer> esteemNeeds = new HashMap<>();
	private Map<LoveNeeds, Integer> loveNeeds = new HashMap<>();
	
	private Map<Long, Double> relativeWealth = new HashMap<>();
	
	public PersonalityGroupProfile() {
		
	}

	public List<PhysiologicalNeeds> getPhysiologicalNeedsPriority(){
		return physiologicalNeeds.entrySet().stream()
        .sorted(Map.Entry.<PhysiologicalNeeds, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}

	public List<SafetyNeeds> getSafetyNeedsPriority(){
		return safetyNeeds.entrySet().stream()
        .sorted(Map.Entry.<SafetyNeeds, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	public List<EsteemNeeds> getEsteemNeedsPriority(){
		return esteemNeeds.entrySet().stream()
        .sorted(Map.Entry.<EsteemNeeds, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	public List<LoveNeeds> getLoveNeedsPriority(){
		return loveNeeds.entrySet().stream()
        .sorted(Map.Entry.<LoveNeeds, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	
	public Map<PhysiologicalNeeds, Integer> getPhysiologicalNeeds() {
		return physiologicalNeeds;
	}

	public Map<SafetyNeeds, Integer> getSafetyNeeds() {
		return safetyNeeds;
	}

	public Map<EsteemNeeds, Integer> getEsteemNeeds() {
		return esteemNeeds;
	}

	public Map<LoveNeeds, Integer> getLoveNeeds() {
		return loveNeeds;
	}
	
	public Map<Long, Double> getRelativeWealth(){
		return relativeWealth;
	}
	
	
}
