package org.cote.accountmanager.olio;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.record.BaseRecord;

public class OlioContextConfiguration {

	private BaseRecord user = null;
	private String dataPath = null;
	private String basePath = "/Olio";
	private String universePath = basePath + "/Universes";
	private String worldPath = null;
	private String universeName = null;
	private String worldName = null;
	private String[] features = null;
	private ZonedDateTime baseInceptionDate = ZonedDateTime.now();
	private int baseLocationCount = 0;
	private int basePopulationCount = 0;
	private boolean resetUniverse = false;
	private boolean resetWorld = false;
	private List<IOlioContextRule> contextRules = new ArrayList<>();
	private List<IOlioEvolveRule> evolutionRules = new ArrayList<>();
	private boolean fastDataCheck = true;
	private boolean useSharedLibraries = true;
	

	public OlioContextConfiguration() {
		
	}
	
	public OlioContextConfiguration(
		BaseRecord user,
		String dataPath,
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
		this.universeName = universeName;
		this.worldName = worldName;
		this.worldPath = this.universePath + "/" + universeName + "/Worlds";
		this.features = features;
		this.resetUniverse = resetUniverse;
		this.resetWorld = resetWorld;
		this.baseLocationCount = locationCount;
		this.basePopulationCount = populationCount;
	}
	
	public String getBasePath() {
		return basePath;
	}

	public void setBasePath(String basePath) {
		this.basePath = basePath;
	}

	public String getUniversePath() {
		return universePath;
	}

	public void setUniversePath(String universePath) {
		this.universePath = universePath;
	}

	public boolean isUseSharedLibraries() {
		return useSharedLibraries;
	}

	public void setUseSharedLibraries(boolean useSharedLibraries) {
		this.useSharedLibraries = useSharedLibraries;
	}

	public boolean isFastDataCheck() {
		return fastDataCheck;
	}

	public void setFastDataCheck(boolean fastDataCheck) {
		this.fastDataCheck = fastDataCheck;
	}

	public List<IOlioEvolveRule> getEvolutionRules() {
		return evolutionRules;
	}

	public void setEvolutionRules(List<IOlioEvolveRule> evolutionRules) {
		this.evolutionRules = evolutionRules;
	}

	public List<IOlioContextRule> getContextRules() {
		return contextRules;
	}

	public void setContextRules(List<IOlioContextRule> contextRules) {
		this.contextRules = contextRules;
	}

	public ZonedDateTime getBaseInceptionDate() {
		return baseInceptionDate;
	}

	public void setBaseInceptionDate(ZonedDateTime baseInceptionDate) {
		this.baseInceptionDate = baseInceptionDate;
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
