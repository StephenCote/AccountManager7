package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.LevelEnumType;

public class Assessment {

	private AssessedEnumType assessedType = AssessedEnumType.UNKNOWN;
	private Map<AssessmentEnumType,LevelEnumType> assessment = new HashMap<>();
	private List<Assessment> assessments = new ArrayList<>();
	private BaseRecord location = null;
	private BaseRecord event = null;

	/// Possible actions to take
	private List<BaseRecord> suggestedActions = new ArrayList<>();
	/// Possible actionResults, both positive and negative
	
	private List<BaseRecord> possibleResults = new ArrayList<>();
	private List<BaseRecord> suggestedSkills = new ArrayList<>();
	private List<String> suggestedStatistics = new ArrayList<>();
	private List<BaseRecord> persons = new ArrayList<>();
	private List<BaseRecord> suggestedEvents = new ArrayList<>();
	
	public Assessment() {
		
	}
	
	public List<Assessment> getAssessments() {
		return assessments;
	}

	public Assessment(BaseRecord location) {
		this.location = location;
		this.assessedType = AssessedEnumType.LOCATION;
	}

	public List<BaseRecord> getSuggestedEvents() {
		return suggestedEvents;
	}

	public List<BaseRecord> getSuggestedActions() {
		return suggestedActions;
	}

	public List<BaseRecord> getSuggestedSkills() {
		return suggestedSkills;
	}

	public List<String> getSuggestedStatistics() {
		return suggestedStatistics;
	}


	public AssessedEnumType getAssessedType() {
		return assessedType;
	}

	public void setAssessedType(AssessedEnumType assessedType) {
		this.assessedType = assessedType;
	}

	public Map<AssessmentEnumType, LevelEnumType> getAssessment() {
		return assessment;
	}

	public BaseRecord getLocation() {
		return location;
	}

	public void setLocation(BaseRecord location) {
		this.location = location;
	}

	public BaseRecord getEvent() {
		return event;
	}

	public void setEvent(BaseRecord event) {
		this.event = event;
	}

	
	public List<BaseRecord> getPersons() {
		return persons;
	}
	
	

}
