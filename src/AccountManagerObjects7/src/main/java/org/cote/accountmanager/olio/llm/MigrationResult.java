package org.cote.accountmanager.olio.llm;

public class MigrationResult {

	private boolean dryRun;
	private boolean alreadyExists;
	private int fieldsUpdated;
	private int sectionsCreated;
	private String templateName;

	public boolean isDryRun() {
		return dryRun;
	}

	public void setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
	}

	public boolean isAlreadyExists() {
		return alreadyExists;
	}

	public void setAlreadyExists(boolean alreadyExists) {
		this.alreadyExists = alreadyExists;
	}

	public int getFieldsUpdated() {
		return fieldsUpdated;
	}

	public void setFieldsUpdated(int fieldsUpdated) {
		this.fieldsUpdated = fieldsUpdated;
	}

	public int getSectionsCreated() {
		return sectionsCreated;
	}

	public void setSectionsCreated(int sectionsCreated) {
		this.sectionsCreated = sectionsCreated;
	}

	public String getTemplateName() {
		return templateName;
	}

	public void setTemplateName(String templateName) {
		this.templateName = templateName;
	}
}
