package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.record.BaseRecord;

public class PersonalityProfile extends AnimalProfile {
	
	private List<BaseRecord> events = new ArrayList<>();
	private boolean married = false;
	private boolean children = false;
	private boolean divorced = false;
	private boolean narcissist = false;
	private boolean psychopath = false;
	private boolean machiavellian = false;
	private String sloanKey = null;
	private String sloanDescription = null;
	private String mbtiKey = null;
	private String mbtiTitle = null;
	private String mbtiDescription = null;
	private VeryEnumType open = VeryEnumType.DISREGARDED;
	private VeryEnumType conscientious = VeryEnumType.DISREGARDED;
	private VeryEnumType extraverted = VeryEnumType.DISREGARDED;
	private VeryEnumType agreeable = VeryEnumType.DISREGARDED;
	private VeryEnumType neurotic = VeryEnumType.DISREGARDED;
	
	public enum PhysiologicalNeeds {
		AIR,
		WATER,
		FOOD,
		SHELTER,
		SLEEP,
		CLOTHING,
		REPRODUCTION
	};
	private List<PhysiologicalNeeds> physiologicalNeeds = new ArrayList<>();
	public enum SafetyNeeds {
		SECURITY,
		EMPLOYMENT,
		RESOURCES,
		HEALTH,
		PROPERTY
	};
	private List<SafetyNeeds> safetyNeeds = new ArrayList<>();
	public enum LoveNeeds {
		FRIENDSHIP,
		INTIMACY,
		FAMILY,
		CONNECTION
	};
	private List<LoveNeeds> loveNeeds = new ArrayList<>();
	public enum EsteemNeeds{
		RESPECT,
		SELF_ESTEEM,
		STATUS,
		RECOGNITION,
		STRENGTH,
		FREEDOM
	};
	private List<EsteemNeeds> esteemNeeds = new ArrayList<>();
	
	public PersonalityProfile() {
		
	}
	
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

	public String getMbtiTitle() {
		return mbtiTitle;
	}

	public void setMbtiTitle(String mbtiTitle) {
		this.mbtiTitle = mbtiTitle;
	}

	public String getMbtiDescription() {
		return mbtiDescription;
	}

	public void setMbtiDescription(String mbtiDescription) {
		this.mbtiDescription = mbtiDescription;
	}

	public String getSloanDescription() {
		return sloanDescription;
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
	
	public List<PhysiologicalNeeds> getPhysiologicalNeeds() {
		return physiologicalNeeds;
	}

	public List<SafetyNeeds> getSafetyNeeds() {
		return safetyNeeds;
	}

	public List<LoveNeeds> getLoveNeeds() {
		return loveNeeds;
	}

	public List<EsteemNeeds> getEsteemNeeds() {
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
}
