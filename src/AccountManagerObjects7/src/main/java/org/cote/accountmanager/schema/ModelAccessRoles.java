package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.List;

public class ModelAccessRoles {
	private List<String> create = new ArrayList<>();
	private List<String> update = new ArrayList<>();
	private List<String> delete = new ArrayList<>();
	private List<String> read = new ArrayList<>();
	private List<String> execute = new ArrayList<>();
	private List<String> admin = new ArrayList<>();
	
	public ModelAccessRoles() {
		
	}

	public List<String> getCreate() {
		return create;
	}

	public void setCreate(List<String> create) {
		this.create = create;
	}

	public List<String> getUpdate() {
		return update;
	}

	public void setUpdate(List<String> update) {
		this.update = update;
	}

	public List<String> getDelete() {
		return delete;
	}

	public void setDelete(List<String> delete) {
		this.delete = delete;
	}

	public List<String> getRead() {
		return read;
	}

	public void setRead(List<String> read) {
		this.read = read;
	}

	public List<String> getExecute() {
		return execute;
	}

	public void setExecute(List<String> execute) {
		this.execute = execute;
	}

	public List<String> getAdmin() {
		return admin;
	}

	public void setAdmin(List<String> admin) {
		this.admin = admin;
	}
	
	
	
}
