package org.cote.accountmanager.olio;

/// Assessments based on hierarchy of needs, plus a few internal and housekeeping items 
public enum AssessmentEnumType{
	UNKNOWN,
	/// air, water, food, shelter, sleep, clothing, reproduction
	PHYSIOLOGICAL,
	/// personal security, employment, resources, health, property
	SAFETY,
	/// friendship, intimacy, family, sense of connection
	LOVE, /// Alt: BELONGING
	/// respect, self-esteem, status, recognition, strength, freedom
	ESTEEM,
	/// morality, creativity, spontaneity, problem solving, lack of prejudice, acceptance of facts
	SELF,
	/// Replenish renewable items
	REPLENISH,
	/// Assess a prediction
	PREDICTION,
	/// Assess a predilection
	PREDILECTION,
	/// Assess a curiosity
	CURIOSITY
 };