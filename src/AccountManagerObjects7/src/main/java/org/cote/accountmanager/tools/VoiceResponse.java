package org.cote.accountmanager.tools;

import org.cote.accountmanager.util.BinaryUtil;

public class VoiceResponse {
	private String audio_base64 = null;
	private byte[] audio = new byte[0];
	public VoiceResponse() {
	
	}
	public String getAudio_base64() {
		return audio_base64;
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
