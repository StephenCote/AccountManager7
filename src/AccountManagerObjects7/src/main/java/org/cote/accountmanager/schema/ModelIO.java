package org.cote.accountmanager.schema;

public class ModelIO {
	private String reader = null;
	private String writer = null;
	private String search = null;
	
	public ModelIO() {
		
	}

	
	
	public String getSearch() {
		return search;
	}

	public void setSearch(String search) {
		this.search = search;
	}

	public String getReader() {
		return reader;
	}

	public void setReader(String reader) {
		this.reader = reader;
	}

	public String getWriter() {
		return writer;
	}

	public void setWriter(String writer) {
		this.writer = writer;
	}
	
	
}
