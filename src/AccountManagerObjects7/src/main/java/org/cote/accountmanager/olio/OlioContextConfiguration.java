package org.cote.accountmanager.olio;

import org.cote.accountmanager.record.BaseRecord;

public class OlioContextConfiguration {
	private BaseRecord user = null;
	private String dataPath = null;
	private String worldPath = null;
	private String universeName = null;
	private String worldName = null;
	private String[] features = null;
	private int baseLocationCount = 0;
	private int basePopulationCount = 0;
	private boolean resetUniverse = false;
	private boolean resetWorld = false;
	
	public OlioContextConfiguration() {
		
	}
	
	public OlioContextConfiguration(
		BaseRecord user,
		String dataPath,
		String worldPath,
		String universeName,
		String worldName,
		String[] features,
		int locationCount,
		int populationCount,
		boolean resetWorld,
		boolean resetUniverse
	) {
		this.user = user;
		this.dataPath = dataPath;
		this.worldPath = worldPath;
		this.universeName = universeName;
		this.worldName = worldName;
		this.features = features;
		this.resetUniverse = resetUniverse;
		this.resetWorld = resetWorld;
		this.baseLocationCount = locationCount;
		this.basePopulationCount = populationCount;
	}

	public boolean isResetWorld() {
		return resetWorld;
	}

	public void setResetWorld(boolean resetWorld) {
		this.resetWorld = resetWorld;
	}

	public int getBaseLocationCount() {
		return baseLocationCount;
	}

	public void setBaseLocationCount(int baseLocationCount) {
		this.baseLocationCount = baseLocationCount;
	}

	public int getBasePopulationCount() {
		return basePopulationCount;
	}

	public void setBasePopulationCount(int basePopulationCount) {
		this.basePopulationCount = basePopulationCount;
	}

	public boolean isResetUniverse() {
		return resetUniverse;
	}

	public void setResetUniverse(boolean resetUniverse) {
		this.resetUniverse = resetUniverse;
	}

	public BaseRecord getUser() {
		return user;
	}

	public void setUser(BaseRecord user) {
		this.user = user;
	}

	public String getDataPath() {
		return dataPath;
	}

	public void setDataPath(String dataPath) {
		this.dataPath = dataPath;
	}

	public String getWorldPath() {
		return worldPath;
	}

	public void setWorldPath(String worldPath) {
		this.worldPath = worldPath;
	}

	public String getUniverseName() {
		return universeName;
	}

	public void setUniverseName(String universeName) {
		this.universeName = universeName;
	}

	public String getWorldName() {
		return worldName;
	}

	public void setWorldName(String worldName) {
		this.worldName = worldName;
	}

	public String[] getFeatures() {
		return features;
	}

	public void setFeatures(String[] features) {
		this.features = features;
	}
	
	
}
