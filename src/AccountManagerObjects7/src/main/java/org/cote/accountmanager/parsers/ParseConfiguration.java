package org.cote.accountmanager.parsers;

import org.apache.commons.csv.CSVFormat;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.record.BaseRecord;

public class ParseConfiguration {
	private BaseRecord owner = null;
	private String model = null;
	private ParseMap[] fields = null;
	private ParseMap[] filters = null;
	private String groupPath = null;
	private String filePath = null;
	private int maxCount = 0;
	private BaseRecord template = null;
	private CSVFormat csvFormat = null;
	private IParseInterceptor interceptor = null;
	private Query parentQuery = null;
	private QueryResult parentQueryResult = null;
	private String mapField = null;
	private String parentMapField = null;
	
	public ParseConfiguration() {
		
	}
	
	
	public ParseMap[] getFilters() {
		return filters;
	}


	public void setFilters(ParseMap[] filters) {
		this.filters = filters;
	}


	public QueryResult getParentQueryResult() {
		return parentQueryResult;
	}


	public void setParentQueryResult(QueryResult parentQueryResult) {
		this.parentQueryResult = parentQueryResult;
	}


	public String getMapField() {
		return mapField;
	}


	public void setMapField(String mapField) {
		this.mapField = mapField;
	}


	public String getParentMapField() {
		return parentMapField;
	}


	public void setParentMapField(String parentMapField) {
		this.parentMapField = parentMapField;
	}


	public Query getParentQuery() {
		return parentQuery;
	}

	public void setParentQuery(Query parentQuery) {
		this.parentQuery = parentQuery;
	}

	public IParseInterceptor getInterceptor() {
		return interceptor;
	}

	public void setInterceptor(IParseInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	public BaseRecord getOwner() {
		return owner;
	}

	public void setOwner(BaseRecord owner) {
		this.owner = owner;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public ParseMap[] getFields() {
		return fields;
	}

	public void setFields(ParseMap[] fields) {
		this.fields = fields;
	}

	public String getGroupPath() {
		return groupPath;
	}

	public void setGroupPath(String groupPath) {
		this.groupPath = groupPath;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public int getMaxCount() {
		return maxCount;
	}

	public void setMaxCount(int maxCount) {
		this.maxCount = maxCount;
	}

	public BaseRecord getTemplate() {
		return template;
	}

	public void setTemplate(BaseRecord template) {
		this.template = template;
	}

	public CSVFormat getCsvFormat() {
		return csvFormat;
	}

	public void setCsvFormat(CSVFormat csvFormat) {
		this.csvFormat = csvFormat;
	}
	
	
	
}
