package org.cote.accountmanager.tools;

public class VoiceRequest{
	private String text = null;
	private double speed = 1.2;
	/// xtts, piper
	private String engine = "piper";
	private String speaker = "en_GB-alba-medium";
	private byte[] voice_sample = new byte[0];
	private String voiceSampleId = null;
	private String voiceProfileId = null;
	private int speaker_id = -1;
	private byte[] audio_sample = null;
	private String model_name = "base";
	private String uid = null;
	public VoiceRequest() {
		
	}

	public VoiceRequest(byte[] audio_base64) {
		this.audio_sample = audio_base64;
	}
	
	public VoiceRequest(String engine, String speaker, String text) {
		this.engine = engine;
		this.text = text;
		this.speaker = speaker;
	}	

	public VoiceRequest(String engine, byte[] sample, String text) {
		this.text = text;
		this.engine = engine;
		this.voice_sample = sample;
	}
	


	public String getUid() {
		return uid;
	}

	public void setUid(String uid) {
		this.uid = uid;
	}

	public byte[] getAudio_sample() {
		return audio_sample;
	}

	public void setAudio_sample(byte[] audio_sample) {
		this.audio_sample = audio_sample;
	}

	public String getModel_name() {
		return model_name;
	}

	public void setModel_name(String model_name) {
		this.model_name = model_name;
	}

	public int getSpeaker_id() {
		return speaker_id;
	}

	public void setSpeaker_id(int speaker_id) {
		this.speaker_id = speaker_id;
	}

	public String getVoiceProfileId() {
		return voiceProfileId;
	}

	public void setVoiceProfileId(String voiceProfileId) {
		this.voiceProfileId = voiceProfileId;
	}

	public String getVoiceSampleId() {
		return voiceSampleId;
	}

	public void setVoiceSampleId(String voiceSampleId) {
		this.voiceSampleId = voiceSampleId;
	}
	
	public byte[] getVoice_sample() {
		return voice_sample;
	}

	public void setVoice_sample(byte[] voice_sample) {
		this.voice_sample = voice_sample;
	}

	public String getEngine() {
		return engine;
	}

	public void setEngine(String engine) {
		this.engine = engine;
	}

	public String getSpeaker() {
		return speaker;
	}

	public void setSpeaker(String speaker) {
		this.speaker = speaker;
	}

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	public String getText() {
		return text;
	}
	public void setText(String text) {
		this.text = text;
	}

}