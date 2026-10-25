package org.cote.accountmanager.olio.sd.swarm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SWSessionResponse extends SWCommon {

	/// Swarm's version string as GetNewSession reports it (e.g. "0.9.8.4.GIT-d74f8ce4"); provenance for
	/// a raw-graph render (PictureBook V3 design §38.3).
	private String version = null;

	public SWSessionResponse() {

	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

}
