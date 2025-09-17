package org.cote.accountmanager.parsers;

public class ParseMap {
	private String columnName = null;
	private int columnIndex = -1;
	private String fieldName = null;
	private ParseMap link = null;
	private IParseInterceptor interceptor = null;
	private String matchValue = null;
	private boolean excludeMatch = false;
	private String dateFormat = null;
	
	public ParseMap() {
		
	}
	
	public ParseMap(String fieldName, int columnIndex) {
		this.fieldName = fieldName;
		this.columnIndex = columnIndex;
	}

	public ParseMap(String fieldName, int columnIndex, String dateFormat) {
		this.fieldName = fieldName;
		this.columnIndex = columnIndex;
		this.dateFormat = dateFormat;
	}
	
	public ParseMap(String fieldName, int columnIndex, ParseMap link) {
		this.fieldName = fieldName;
		this.columnIndex = columnIndex;
		this.link = link;
	}

	public ParseMap(String fieldName, int columnIndex, IParseInterceptor interceptor) {
		this.fieldName = fieldName;
		this.columnIndex = columnIndex;
		this.interceptor = interceptor;
	}
	

	public String getDateFormat() {
		return dateFormat;
	}

	public void setDateFormat(String dateFormat) {
		this.dateFormat = dateFormat;
	}

	public boolean isExcludeMatch() {
		return excludeMatch;
	}

	public void setExcludeMatch(boolean excludeMatch) {
		this.excludeMatch = excludeMatch;
	}

	public String getMatchValue() {
		return matchValue;
	}

	public void setMatchValue(String matchValue) {
		this.matchValue = matchValue;
	}

	public IParseInterceptor getInterceptor() {
		return interceptor;
	}

	public void setInterceptor(IParseInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	public ParseMap getLink() {
		return link;
	}

	public void setLink(ParseMap link) {
		this.link = link;
	}

	public String getColumnName() {
		return columnName;
	}

	public void setColumnName(String columnName) {
		this.columnName = columnName;
	}

	public int getColumnIndex() {
		return columnIndex;
	}

	public void setColumnIndex(int columnIndex) {
		this.columnIndex = columnIndex;
	}

	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}
	
	
	
}
