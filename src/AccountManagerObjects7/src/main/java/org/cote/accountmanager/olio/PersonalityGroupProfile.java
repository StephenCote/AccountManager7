package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class PersonalityGroupProfile {
	
	private Map<PhysiologicalNeedsEnumType, Integer> physiologicalNeeds = new HashMap<>();
	private Map<SafetyNeedsEnumType, Integer> safetyNeeds = new HashMap<>();
	private Map<EsteemNeedsEnumType, Integer> esteemNeeds = new HashMap<>();
	private Map<LoveNeedsEnumType, Integer> loveNeeds = new HashMap<>();
	
	private Map<Long, Double> relativeWealth = new HashMap<>();
	
	public PersonalityGroupProfile() {
		
	}

	public List<PhysiologicalNeedsEnumType> getPhysiologicalNeedsPriority(){
		return physiologicalNeeds.entrySet().stream()
        .sorted(Map.Entry.<PhysiologicalNeedsEnumType, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}

	public List<SafetyNeedsEnumType> getSafetyNeedsPriority(){
		return safetyNeeds.entrySet().stream()
        .sorted(Map.Entry.<SafetyNeedsEnumType, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	public List<EsteemNeedsEnumType> getEsteemNeedsPriority(){
		return esteemNeeds.entrySet().stream()
        .sorted(Map.Entry.<EsteemNeedsEnumType, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	public List<LoveNeedsEnumType> getLoveNeedsPriority(){
		return loveNeeds.entrySet().stream()
        .sorted(Map.Entry.<LoveNeedsEnumType, Integer>comparingByValue().reversed()).map(e -> e.getKey()).collect(Collectors.toList());
	}
	
	
	public Map<PhysiologicalNeedsEnumType, Integer> getPhysiologicalNeeds() {
		return physiologicalNeeds;
	}

	public Map<SafetyNeedsEnumType, Integer> getSafetyNeeds() {
		return safetyNeeds;
	}

	public Map<EsteemNeedsEnumType, Integer> getEsteemNeeds() {
		return esteemNeeds;
	}

	public Map<LoveNeedsEnumType, Integer> getLoveNeeds() {
		return loveNeeds;
	}
	
	public Map<Long, Double> getRelativeWealth(){
		return relativeWealth;
	}
	
	
}
