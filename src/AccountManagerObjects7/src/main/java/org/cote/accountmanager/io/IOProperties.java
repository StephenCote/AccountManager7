package org.cote.accountmanager.io;

public class IOProperties {
	private String dataSourceName = null;
	private String dataSourceUrl = null;
	private String dataSourceUserName = null;
	private String dataSourcePassword = null;
	private boolean reset = false;
	
	private boolean schemaCheck = false;
	
	public IOProperties() {
		
	}
	
	

	public boolean isReset() {
		return reset;
	}



	public void setReset(boolean reset) {
		this.reset = reset;
	}



	public boolean isSchemaCheck() {
		return schemaCheck;
	}

	public void setSchemaCheck(boolean schemaCheck) {
		this.schemaCheck = schemaCheck;
	}

	public String getDataSourceName() {
		return dataSourceName;
	}

	public void setDataSourceName(String dataSourceName) {
		this.dataSourceName = dataSourceName;
	}

	public String getDataSourceUrl() {
		return dataSourceUrl;
	}

	public void setDataSourceUrl(String dataSourceUrl) {
		this.dataSourceUrl = dataSourceUrl;
	}

	public String getDataSourceUserName() {
		return dataSourceUserName;
	}

	public void setDataSourceUserName(String dataSourceUserName) {
		this.dataSourceUserName = dataSourceUserName;
	}

	public String getDataSourcePassword() {
		return dataSourcePassword;
	}

	public void setDataSourcePassword(String dataSourcePassword) {
		this.dataSourcePassword = dataSourcePassword;
	}
	
	
	
}
