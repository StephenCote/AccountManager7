package org.cote.accountmanager.io.db;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.Query;

public class DBStatementMeta {
	private String sql = null;
	private List<String> fields = new ArrayList<>();
	private List<String> columns = new ArrayList<>();

	private Query query = null;
	
	public DBStatementMeta() {
		
	}
	
	public DBStatementMeta(Query query) {
		this.query = query;
	}
	
	public Query getQuery() {
		return query;
	}



	public void setQuery(Query query) {
		this.query = query;
	}



	public String getSql() {
		return sql;
	}
	public void setSql(String sql) {
		this.sql = sql;
	}
	public List<String> getFields() {
		return fields;
	}
	public void setFields(List<String> fields) {
		this.fields = fields;
	}
	public List<String> getColumns() {
		return columns;
	}
	public void setColumns(List<String> columns) {
		this.columns = columns;
	}
	
	
}
