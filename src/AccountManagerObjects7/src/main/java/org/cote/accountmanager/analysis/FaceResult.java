package org.cote.accountmanager.analysis;

public class FaceResult{
	private FaceLocation face_location = null;
	private int age = 0;
	private String dominant_gender = null;
	private String dominant_emotion = null;
	private String dominant_race = null;
	private FaceEmotionScores emotion_scores = null;
	private FaceRaceScores race_scores = null;
	private FaceGenderScores gender_scores = null;
	private FaceRegion region = null;
	private double face_confidence = 0.0;
	private String error = null;
	public FaceResult() {
		
	}
	
	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public String getDominant_race() {
		return dominant_race;
	}

	public void setDominant_race(String dominant_race) {
		this.dominant_race = dominant_race;
	}

	public FaceEmotionScores getEmotion_scores() {
		return emotion_scores;
	}

	public void setEmotion_scores(FaceEmotionScores emotion_scores) {
		this.emotion_scores = emotion_scores;
	}

	public FaceRegion getRegion() {
		return region;
	}

	public void setRegion(FaceRegion region) {
		this.region = region;
	}

	public double getFace_confidence() {
		return face_confidence;
	}

	public void setFace_confidence(double face_confidence) {
		this.face_confidence = face_confidence;
	}

	public FaceLocation getFace_location() {
		return face_location;
	}

	public void setFace_location(FaceLocation face_location) {
		this.face_location = face_location;
	}
	public int getAge() {
		return age;
	}
	public void setAge(int age) {
		this.age = age;
	}

	public String getDominant_gender() {
		return dominant_gender;
	}
	public void setDominant_gender(String dominant_gender) {
		this.dominant_gender = dominant_gender;
	}

	public FaceRaceScores getRace_scores() {
		return race_scores;
	}
	public void setRace_scores(FaceRaceScores race_scores) {
		this.race_scores = race_scores;
	}
	public FaceGenderScores getGender_scores() {
		return gender_scores;
	}
	public void setGender_scores(FaceGenderScores gender_scores) {
		this.gender_scores = gender_scores;
	}
	public String getDominant_emotion() {
		return dominant_emotion;
	}
	public void setDominant_emotion(String dominant_emotion) {
		this.dominant_emotion = dominant_emotion;
	}
	
}

