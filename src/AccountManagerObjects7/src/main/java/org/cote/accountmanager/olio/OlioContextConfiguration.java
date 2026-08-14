package org.cote.accountmanager.olio;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioStateRule;
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
	private List<IOlioStateRule> stateRules = new ArrayList<>();
	private boolean fastDataCheck = true;
	private boolean useSharedLibraries = true;

	/// PictureBook 2.0 phase 1 additions. All four are additive: the 9-arg constructor is unchanged,
	/// and the defaults reproduce today's grid/arena/game behaviour EXCEPT for enrolActingUser, which
	/// is safe-by-default false and therefore must be opted into by every caller that relies on
	/// context construction enrolling the acting user (see OlioContextUtil.getGridContext /
	/// getArenaContext, and the Olio test harnesses).
	///
	private boolean requireRealms = true;
	private boolean enrolActingUser = false;
	private BaseRecord authorizationUserRole = null;
	private BaseRecord authorizationAdminRole = null;



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
	
	/**
	 * When false, "no realms detected" / "failed to start realms" / "zero region events were found"
	 * are downgraded from ERROR to INFO and no longer count as initialization errors. A book world is
	 * deliberately location-free and realm-free, so the three lines are noise there rather than a
	 * fault. Default true, so every existing context is unaffected.
	 */
	public boolean isRequireRealms() {
		return requireRealms;
	}

	public void setRequireRealms(boolean requireRealms) {
		this.requireRealms = requireRealms;
	}

	/**
	 * When false, {@code OlioContext.configureEnvironment} does NOT enrol {@link #getUser()} in the
	 * Olio user role - neither on the every-run branch nor on the first-run branch. Enrolment then
	 * has to be an explicit, authorized call ({@code OlioContext.registerUser}).
	 * <p>
	 * Default is <b>false</b> (safe by default). Callers that depend on context construction granting
	 * the acting user access - the game/arena paths and the Olio test harnesses - set this to true
	 * explicitly.
	 */
	public boolean isEnrolActingUser() {
		return enrolActingUser;
	}

	public void setEnrolActingUser(boolean enrolActingUser) {
		this.enrolActingUser = enrolActingUser;
	}

	/**
	 * Optional per-context user role. When BOTH this and {@link #getAuthorizationAdminRole()} are
	 * non-null, {@code OlioContext.initialize()} grants against this pair instead of the org-wide
	 * {@code ~/Roles/Olio User} / {@code ~/Roles/Olio Admin} pair, and the role-less entry points
	 * ({@code enroleReader}, {@code enroleAdmin}, {@code scanNestedGroups}) resolve this pair first.
	 * Null (the default) reproduces today's org-wide behaviour exactly.
	 */
	public BaseRecord getAuthorizationUserRole() {
		return authorizationUserRole;
	}

	public void setAuthorizationUserRole(BaseRecord authorizationUserRole) {
		this.authorizationUserRole = authorizationUserRole;
	}

	/**
	 * Optional per-context admin role. See {@link #getAuthorizationUserRole()}.
	 */
	public BaseRecord getAuthorizationAdminRole() {
		return authorizationAdminRole;
	}

	public void setAuthorizationAdminRole(BaseRecord authorizationAdminRole) {
		this.authorizationAdminRole = authorizationAdminRole;
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

	public List<IOlioStateRule> getStateRules() {
		return stateRules;
	}

	public void setStateRules(List<IOlioStateRule> stateRules) {
		this.stateRules = stateRules;
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
