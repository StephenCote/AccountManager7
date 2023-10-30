package org.cote.accountmanager.parsers;

public class ParseMap {
	private String columnName = null;
	private int columnIndex = -1;
	private String fieldName = null;
	
	public ParseMap() {
		
	}
	
	public ParseMap(String fieldName, int columnIndex) {
		this.fieldName = fieldName;
		this.columnIndex = columnIndex;
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
