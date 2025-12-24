package org.cote.accountmanager.olio.sd.automatic1111;

public class SDOverrideSettings {
	private String sd_model_checkpoint = null;
	private String sd_vae = "";
	public SDOverrideSettings() {
		
	}
	public String getSd_model_checkpoint() {
		return sd_model_checkpoint;
	}
	public void setSd_model_checkpoint(String sd_model_checkpoint) {
		this.sd_model_checkpoint = sd_model_checkpoint;
	}
	public String getSd_vae() {
		return sd_vae;
	}
	public void setSd_vae(String sd_vae) {
		this.sd_vae = sd_vae;
	}
	
}
