package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.personality.MBTI;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class PersonalityProfile extends AnimalProfile {
	
	private List<BaseRecord> events = new ArrayList<>();
	private boolean married = false;
	private boolean children = false;
	private boolean divorced = false;
	/*
	private boolean narcissist = false;
	private boolean psychopath = false;
	private boolean machiavellian = false;
	*/
	private String sloanKey = null;
	private String sloanDescription = null;
	private String darkTriadKey = null;
	
	private String mbtiKey = null;
	private MBTI mbti = null;

	private VeryEnumType open = VeryEnumType.DISREGARDED;
	private VeryEnumType conscientious = VeryEnumType.DISREGARDED;
	private VeryEnumType extraverted = VeryEnumType.DISREGARDED;
	private VeryEnumType agreeable = VeryEnumType.DISREGARDED;
	private VeryEnumType neurotic = VeryEnumType.DISREGARDED;
	private VeryEnumType narcissist = VeryEnumType.DISREGARDED;
	private VeryEnumType machiavellian = VeryEnumType.DISREGARDED;
	private VeryEnumType psychopath = VeryEnumType.DISREGARDED;
	
	private VeryEnumType aggressive = VeryEnumType.DISREGARDED;
	
	private List<PhysiologicalNeedsEnumType> physiologicalNeeds = new ArrayList<>();

	private List<SafetyNeedsEnumType> safetyNeeds = new ArrayList<>();

	private List<LoveNeedsEnumType> loveNeeds = new ArrayList<>();

	private List<EsteemNeedsEnumType> esteemNeeds = new ArrayList<>();
	
	public PersonalityProfile() {
		
	}
	
	public List<String> getRace(){
		return getRecord().get(OlioFieldNames.FIELD_RACE);
	}
	
	public List<String> getEthnicity(){
		return getRecord().get("ethnicity");
	}
	
	public String getOtherEthnicity(){
		return getRecord().get("otherEthnicity");
	}
	
	public boolean isNarcissist() {
		return VeryEnumType.compare(narcissist, VeryEnumType.SOMEWHAT, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public VeryEnumType getNarcissist() {
		return narcissist;
	}

	public void setNarcissist(VeryEnumType narcissist) {
		this.narcissist = narcissist;
	}
	public boolean isAggressive() {
		return VeryEnumType.compare(aggressive, VeryEnumType.LESS_FREQUENTLY, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public boolean isMachiavellian() {
		return VeryEnumType.compare(machiavellian, VeryEnumType.SOMEWHAT, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public VeryEnumType getMachiavellian() {
		return machiavellian;
	}

	public void setMachiavellian(VeryEnumType machiavellian) {
		this.machiavellian = machiavellian;
	}
	public boolean isPsychopath() {
		return VeryEnumType.compare(psychopath, VeryEnumType.SOMEWHAT, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	
	public VeryEnumType getPsychopath() {
		return psychopath;
	}

	public void setPsychopath(VeryEnumType psychopath) {
		this.psychopath = psychopath;
	}

	public String getDarkTriadKey() {
		return darkTriadKey;
	}
	public void setDarkTriadKey(String darkTriadKey) {
		this.darkTriadKey = darkTriadKey;
	}
	/*
	public boolean isNarcissist() {
		return narcissist;
	}

	public void setNarcissist(boolean narcissist) {
		this.narcissist = narcissist;
	}

	public boolean isPsychopath() {
		return psychopath;
	}

	public void setPsychopath(boolean psychopath) {
		this.psychopath = psychopath;
	}

	public boolean isMachiavellian() {
		return machiavellian;
	}

	public void setMachiavellian(boolean machiavellian) {
		this.machiavellian = machiavellian;
	}
	*/
	public String getMbtiKey() {
		return mbtiKey;
	}

	public void setMbtiKey(String mbtiKey) {
		this.mbtiKey = mbtiKey;
	}

	public String getSloanKey() {
		return sloanKey;
	}

	public void setSloanKey(String sloanKey) {
		this.sloanKey = sloanKey;
	}

	public String getSloanDescription() {
		return sloanDescription;
	}

	public MBTI getMbti() {
		return mbti;
	}

	public void setMbti(MBTI mbti) {
		this.mbti = mbti;
	}

	public void setSloanDescription(String sloanDescription) {
		this.sloanDescription = sloanDescription;
	}

	public List<BaseRecord> getEvents() {
		return events;
	}

	public void setEvents(List<BaseRecord> events) {
		this.events = events;
	}
	
	public List<PhysiologicalNeedsEnumType> getPhysiologicalNeeds() {
		return physiologicalNeeds;
	}

	public List<SafetyNeedsEnumType> getSafetyNeeds() {
		return safetyNeeds;
	}

	public List<LoveNeedsEnumType> getLoveNeeds() {
		return loveNeeds;
	}

	public List<EsteemNeedsEnumType> getEsteemNeeds() {
		return esteemNeeds;
	}

	
	public boolean isMarried() {
		return married;
	}
	public void setMarried(boolean married) {
		this.married = married;
	}

	public boolean isChildren() {
		return children;
	}
	public void setChildren(boolean children) {
		this.children = children;
	}
	public boolean isDivorced() {
		return divorced;
	}
	public void setDivorced(boolean divorced) {
		this.divorced = divorced;
	}
	public VeryEnumType getOpen() {
		return open;
	}
	public void setOpen(VeryEnumType open) {
		this.open = open;
	}
	public VeryEnumType getConscientious() {
		return conscientious;
	}
	public void setConscientious(VeryEnumType conscientious) {
		this.conscientious = conscientious;
	}
	public VeryEnumType getExtraverted() {
		return extraverted;
	}
	public void setExtraverted(VeryEnumType extraverted) {
		this.extraverted = extraverted;
	}
	public VeryEnumType getAgreeable() {
		return agreeable;
	}
	public void setAgreeable(VeryEnumType agreeable) {
		this.agreeable = agreeable;
	}
	public VeryEnumType getNeurotic() {
		return neurotic;
	}
	public void setNeurotic(VeryEnumType neurotic) {
		this.neurotic = neurotic;
	}

	public VeryEnumType getAggressive() {
		return aggressive;
	}

	public void setAggressive(VeryEnumType aggressive) {
		this.aggressive = aggressive;
	}
	
}
