package org.cote.accountmanager.tools;

import org.cote.accountmanager.util.BinaryUtil;

public class VoiceResponse {
	private String audio_base64 = null;
	private byte[] audio = new byte[0];
	private String text = null;
	private String status_code = null;
	private String detail = null;
	private String uid = null;
	public VoiceResponse() {
	
	}
	public String getAudio_base64() {
		return audio_base64;
	}
	
	
	
	public String getUid() {
		return uid;
	}
	public void setUid(String uid) {
		this.uid = uid;
	}
	public String getText() {
		return text;
	}
	public void setText(String text) {
		this.text = text;
	}
	public String getStatus_code() {
		return status_code;
	}
	public void setStatus_code(String status_code) {
		this.status_code = status_code;
	}
	public String getDetail() {
		return detail;
	}
	public void setDetail(String detail) {
		this.detail = detail;
	}
	public void setAudio_base64(String audio_base64) {
		this.audio_base64 = audio_base64;
		if(audio_base64 != null) {
			this.audio = BinaryUtil.fromBase64(audio_base64.getBytes());
		}
		else {
			this.audio = new byte[0];
		}
	}
	
	public byte[] getAudio() {
		return audio;
	}
	public void setAudio(byte[] audio) {
		this.audio = audio;
	}
	
}
