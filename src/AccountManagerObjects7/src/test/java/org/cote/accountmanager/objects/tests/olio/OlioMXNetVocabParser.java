package org.cote.accountmanager.objects.tests.olio;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class OlioMXNetVocabParser {
	    private static List<String> idx2token = null;
	    public static List<String> parseToken(URL url) {
	    	if(idx2token == null) {
	    		idx2token = JSONUtil.importObject(FileUtil.getFileAsString(url.toString()), IDX2Token.class).getIdx_to_token();
	    	}
	    	return idx2token;
	    }
	    
	    class IDX2Token{
	    	private List<String> idx_to_token = new ArrayList<>();
	    	public IDX2Token() {
	    		
	    	}
			public List<String> getIdx_to_token() {
				return idx_to_token;
			}
			public void setIdx_to_token(List<String> idx_to_token) {
				this.idx_to_token = idx_to_token;
			}
	    	
	    }

}
