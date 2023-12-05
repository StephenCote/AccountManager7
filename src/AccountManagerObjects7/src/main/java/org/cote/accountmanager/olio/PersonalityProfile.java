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
	public PersonalityProfile() {
		
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
