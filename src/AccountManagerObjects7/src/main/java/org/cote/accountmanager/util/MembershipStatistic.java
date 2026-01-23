package org.cote.accountmanager.util;

public class MembershipStatistic {
	private long id;
	private String objectId;
	private String name;
	private String type;
	private String modelName;
	private long count;

	public MembershipStatistic() {}

	public MembershipStatistic(long id, String objectId, String name, String type, String modelName, long count) {
		this.id = id;
		this.objectId = objectId;
		this.name = name;
		this.type = type;
		this.modelName = modelName;
		this.count = count;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public String getObjectId() {
		return objectId;
	}

	public void setObjectId(String objectId) {
		this.objectId = objectId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getModelName() {
		return modelName;
	}

	public void setModelName(String modelName) {
		this.modelName = modelName;
	}

	public long getCount() {
		return count;
	}

	public void setCount(long count) {
		this.count = count;
	}
}
