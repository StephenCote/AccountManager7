package org.cote.accountmanager.analysis;

import java.util.ArrayList;
import java.util.List;

public class FaceResponse{
	private List<FaceResult> results = new ArrayList<>();
	private String message = null;
	public FaceResponse() {
		
	}
	
	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public List<FaceResult> getResults() {
		return results;
	}
	public void setResults(List<FaceResult> results) {
		this.results = results;
	}
	
}
