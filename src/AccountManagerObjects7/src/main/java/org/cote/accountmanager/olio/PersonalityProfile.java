package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.record.BaseRecord;

public class PersonalityProfile {
	private BaseRecord record = null;
	private List<BaseRecord> events = new ArrayList<>();
	private String name = null;
	private long id = 0L;
	private int age = 0;
	private String gender = null;
	private boolean married = false;
	private boolean alive = false;
	private boolean children = false;
	private boolean divorced = false;
	private VeryEnumType open = VeryEnumType.DISREGARDED;
	private VeryEnumType conscientious = VeryEnumType.DISREGARDED;
	private VeryEnumType extraverted = VeryEnumType.DISREGARDED;
	private VeryEnumType agreeable = VeryEnumType.DISREGARDED;
	private VeryEnumType neurotic = VeryEnumType.DISREGARDED;
	
	private HighEnumType physicalStrength = HighEnumType.DISREGARDED;
	private HighEnumType physicalEndurance = HighEnumType.DISREGARDED;
	private HighEnumType manualDexterity = HighEnumType.DISREGARDED;
	private HighEnumType agility = HighEnumType.DISREGARDED;
	private HighEnumType speed = HighEnumType.DISREGARDED;
	private HighEnumType mentalStrength = HighEnumType.DISREGARDED;
	private HighEnumType mentalEndurance = HighEnumType.DISREGARDED;
	private HighEnumType intelligence = HighEnumType.DISREGARDED;
	private HighEnumType wisdom = HighEnumType.DISREGARDED;
	private HighEnumType charisma = HighEnumType.DISREGARDED;
	private HighEnumType creativity = HighEnumType.DISREGARDED;
	private HighEnumType spirituality = HighEnumType.DISREGARDED;
	private HighEnumType science = HighEnumType.DISREGARDED;
	private HighEnumType reaction = HighEnumType.DISREGARDED;
	private HighEnumType save = HighEnumType.DISREGARDED;
	private HighEnumType magic = HighEnumType.DISREGARDED;
	private HighEnumType health = HighEnumType.DISREGARDED;
	private HighEnumType maximumHealth = HighEnumType.DISREGARDED;
	
	public PersonalityProfile() {
		
	}
	
	public HighEnumType getScience() {
		return science;
	}

	public void setScience(HighEnumType science) {
		this.science = science;
	}

	public HighEnumType getReaction() {
		return reaction;
	}

	public void setReaction(HighEnumType reaction) {
		this.reaction = reaction;
	}

	public HighEnumType getSave() {
		return save;
	}

	public void setSave(HighEnumType save) {
		this.save = save;
	}

	public HighEnumType getMagic() {
		return magic;
	}

	public void setMagic(HighEnumType magic) {
		this.magic = magic;
	}

	public HighEnumType getHealth() {
		return health;
	}

	public void setHealth(HighEnumType health) {
		this.health = health;
	}

	public HighEnumType getMaximumHealth() {
		return maximumHealth;
	}

	public void setMaximumHealth(HighEnumType maximumHealth) {
		this.maximumHealth = maximumHealth;
	}

	public HighEnumType getPhysicalStrength() {
		return physicalStrength;
	}

	public void setPhysicalStrength(HighEnumType physicalStrength) {
		this.physicalStrength = physicalStrength;
	}

	public HighEnumType getPhysicalEndurance() {
		return physicalEndurance;
	}

	public void setPhysicalEndurance(HighEnumType physicalEndurance) {
		this.physicalEndurance = physicalEndurance;
	}

	public HighEnumType getManualDexterity() {
		return manualDexterity;
	}

	public void setManualDexterity(HighEnumType manualDexterity) {
		this.manualDexterity = manualDexterity;
	}

	public HighEnumType getAgility() {
		return agility;
	}

	public void setAgility(HighEnumType agility) {
		this.agility = agility;
	}

	public HighEnumType getSpeed() {
		return speed;
	}

	public void setSpeed(HighEnumType speed) {
		this.speed = speed;
	}

	public HighEnumType getMentalStrength() {
		return mentalStrength;
	}

	public void setMentalStrength(HighEnumType mentalStrength) {
		this.mentalStrength = mentalStrength;
	}

	public HighEnumType getMentalEndurance() {
		return mentalEndurance;
	}

	public void setMentalEndurance(HighEnumType mentalEndurance) {
		this.mentalEndurance = mentalEndurance;
	}

	public HighEnumType getIntelligence() {
		return intelligence;
	}

	public void setIntelligence(HighEnumType intelligence) {
		this.intelligence = intelligence;
	}

	public HighEnumType getWisdom() {
		return wisdom;
	}

	public void setWisdom(HighEnumType wisdom) {
		this.wisdom = wisdom;
	}

	public HighEnumType getCharisma() {
		return charisma;
	}

	public void setCharisma(HighEnumType charisma) {
		this.charisma = charisma;
	}

	public HighEnumType getCreativity() {
		return creativity;
	}

	public void setCreativity(HighEnumType creativity) {
		this.creativity = creativity;
	}

	public HighEnumType getSpirituality() {
		return spirituality;
	}

	public void setSpirituality(HighEnumType spirituality) {
		this.spirituality = spirituality;
	}

	public List<BaseRecord> getEvents() {
		return events;
	}

	public void setEvents(List<BaseRecord> events) {
		this.events = events;
	}

	public BaseRecord getRecord() {
		return record;
	}

	public void setRecord(BaseRecord record) {
		this.record = record;
	}

	public String getGender() {
		return gender;
	}

	public void setGender(String gender) {
		this.gender = gender;
	}

	public int getAge() {
		return age;
	}

	public void setAge(int age) {
		this.age = age;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public boolean isMarried() {
		return married;
	}
	public void setMarried(boolean married) {
		this.married = married;
	}
	public boolean isAlive() {
		return alive;
	}
	public void setAlive(boolean alive) {
		this.alive = alive;
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
