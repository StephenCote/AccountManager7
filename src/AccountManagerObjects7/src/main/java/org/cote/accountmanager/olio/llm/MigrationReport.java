package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

public class MigrationReport {

	private int fieldsScanned;
	private int fieldsWithContent;
	private int sectionsToCreate;
	private final List<String> sectionNames = new ArrayList<>();

	public int getFieldsScanned() {
		return fieldsScanned;
	}

	public void setFieldsScanned(int fieldsScanned) {
		this.fieldsScanned = fieldsScanned;
	}

	public int getFieldsWithContent() {
		return fieldsWithContent;
	}

	public void setFieldsWithContent(int fieldsWithContent) {
		this.fieldsWithContent = fieldsWithContent;
	}

	public int getSectionsToCreate() {
		return sectionsToCreate;
	}

	public void setSectionsToCreate(int sectionsToCreate) {
		this.sectionsToCreate = sectionsToCreate;
	}

	public List<String> getSectionNames() {
		return sectionNames;
	}
}
