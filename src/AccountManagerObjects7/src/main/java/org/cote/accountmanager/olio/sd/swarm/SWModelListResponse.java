package org.cote.accountmanager.olio.sd.swarm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SWModelListResponse {
	private List<Map<String, Object>> files = new ArrayList<>();
	private List<String> folders = new ArrayList<>();
	/// Swarm reports failures (an expired session_id among them) as an HTTP 200 whose body carries
	/// `error` instead of `files`; without this field such a body reads as "no models installed".
	private String error;

	public SWModelListResponse() {

	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

	public List<Map<String, Object>> getFiles() {
		return files;
	}

	public void setFiles(List<Map<String, Object>> files) {
		this.files = files;
	}

	public List<String> getFolders() {
		return folders;
	}

	public void setFolders(List<String> folders) {
		this.folders = folders;
	}
}
