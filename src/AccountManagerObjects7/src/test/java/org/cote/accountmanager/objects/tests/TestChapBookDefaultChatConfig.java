package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * FIX 2 coverage for {@link ChapBookUtil#resolveDefaultChatConfig(BaseRecord)}.
 * <p>
 * The prior implementation filtered the "default" chatConfig by {@code ownerId == user.id}, so the
 * fallback the analyze/render/create endpoints use ({@code ChapBookService} lines 141/205/269/335)
 * always returned one of the acting user's OWN configs, never a shared/system one. The fix now prefers
 * the SYSTEM/shared-library config (via {@link ChatUtil#getLibraryConfig(BaseRecord, String)}, no owner
 * filter, PBAC-gated by {@code AccessPoint.find}) and only falls back to a user-owned config when no
 * library config exists.
 * <p>
 * Two scenarios, each in its OWN organization so the two library states cannot cross-contaminate:
 * <ol>
 *   <li><b>Library preferred:</b> the org has a shared-library chatConfig AND the acting user also owns
 *       a personal chatConfig → {@code resolveDefaultChatConfig} returns the LIBRARY one (admin-owned,
 *       in the shared ChatConfigs library dir), not the personal one.</li>
 *   <li><b>Personal fallback:</b> the org has NO shared-library chatConfig, only a user-owned one →
 *       {@code resolveDefaultChatConfig} returns the personal one.</li>
 * </ol>
 * No LLM or SD is contacted: {@code populateDefaults} builds the library records from bundled templates
 * and a stored (never-called) connection URL.
 */
public class TestChapBookDefaultChatConfig extends BaseTest {

	private BaseRecord createPersonalChatConfig(BaseRecord user, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory()
			.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		assertNotNull("Personal chatConfig must be created", created);
		return created;
	}

	/**
	 * Library preferred: with BOTH a shared-library config and a personal config present, the default
	 * resolves to the LIBRARY config.
	 */
	@Test
	public void resolveDefaultChatConfig_prefersSharedLibraryOverPersonal() throws Exception {
		OrganizationContext o = getTestOrganization("/Development/CbChatCfgLib");
		BaseRecord user = getCreateUser("cbCfgLibUser", o);
		assertNotNull("Test user must resolve", user);
		long userId = ((Number) user.get(FieldNames.FIELD_ID)).longValue();

		// A personal, user-owned chatConfig (what the OLD behavior would have returned).
		BaseRecord personal = createPersonalChatConfig(user, "PersonalCfg-" + System.currentTimeMillis());
		long personalOwnerId = ((Number) personal.get(FieldNames.FIELD_OWNER_ID)).longValue();
		assertEquals("Personal config must be owned by the acting user", userId, personalOwnerId);

		// Populate the shared ChatConfigs library (idempotent; builds records from templates, no LLM).
		// populateDefaults seeds MANY named configs from templates (contentAnalysis, generalChat, coding,
		// ...) plus the "Open Chat" alias — so the library is deliberately crowded here, which is exactly
		// the condition under which determinism must hold.
		String server = testProperties.getProperty("test.llm.ollama.server");
		String model = testProperties.getProperty("test.llm.ollama.model");
		ChatLibraryUtil.populateDefaults(user, server, model, "ollama");

		// Precondition: a shared-library config now exists (no owner filter) and its dir resolves.
		BaseRecord libDir = ChatLibraryUtil.findLibraryDir(user, ChatLibraryUtil.LIBRARY_CHAT_CONFIGS);
		assertNotNull("ChatConfigs library dir must exist after populateDefaults", libDir);
		BaseRecord libraryConfig = ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG);
		assertNotNull("Precondition: a shared-library chatConfig must exist", libraryConfig);
		long libGroupId = ((Number) libDir.get(FieldNames.FIELD_ID)).longValue();

		// Precondition (determinism proof): the library is crowded — the named contentAnalysis config
		// AND at least one OTHER distinctly-named library config (generalChat) both exist, and the two
		// are different records. If resolveDefaultChatConfig returned merely "the first library config"
		// it could return either; asserting it returns contentAnalysis specifically is the fix's contract.
		BaseRecord analysisCfg = ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG,
			ChatUtil.DEFAULT_ANALYSIS_CHAT_CONFIG_NAME);
		assertNotNull("Precondition: the named '" + ChatUtil.DEFAULT_ANALYSIS_CHAT_CONFIG_NAME
			+ "' library config must exist", analysisCfg);
		BaseRecord generalCfg = ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG,
			ChatUtil.DEFAULT_GENERAL_CHAT_CONFIG_NAME);
		assertNotNull("Precondition: the named '" + ChatUtil.DEFAULT_GENERAL_CHAT_CONFIG_NAME
			+ "' library config must ALSO exist (so the library is genuinely crowded)", generalCfg);
		assertFalse("The two named library configs must be distinct records (crowded library)",
			((String) analysisCfg.get(FieldNames.FIELD_OBJECT_ID))
				.equals((String) generalCfg.get(FieldNames.FIELD_OBJECT_ID)));

		// The fix: the default must be the LIBRARY config, not the personal one.
		BaseRecord resolved = ChapBookUtil.resolveDefaultChatConfig(user);
		assertNotNull("resolveDefaultChatConfig must return a config", resolved);
		long resolvedGroupId = ((Number) resolved.get(FieldNames.FIELD_GROUP_ID)).longValue();
		long resolvedOwnerId = ((Number) resolved.get(FieldNames.FIELD_OWNER_ID)).longValue();
		assertEquals("Resolved default must be the SYSTEM/shared-library config (its groupId is the "
			+ "ChatConfigs library dir)", libGroupId, resolvedGroupId);
		assertFalse("Resolved default must NOT be one of the acting user's own configs", resolvedOwnerId == userId);

		// DETERMINISM: even with other named library configs present (generalChat, coding, Open Chat, ...),
		// the resolved default must be the one NAMED contentAnalysis — not merely "a" library config.
		assertEquals("Resolved default must be the config NAMED '" + ChatUtil.DEFAULT_ANALYSIS_CHAT_CONFIG_NAME
			+ "' (deterministic contentAnalysis-first order)", ChatUtil.DEFAULT_ANALYSIS_CHAT_CONFIG_NAME,
			(String) resolved.get(FieldNames.FIELD_NAME));
		assertEquals("Resolved default must be exactly the contentAnalysis library record",
			(String) analysisCfg.get(FieldNames.FIELD_OBJECT_ID), (String) resolved.get(FieldNames.FIELD_OBJECT_ID));

		logger.info("resolveDefaultChatConfig_prefersSharedLibraryOverPersonal PASSED: name={} libGroupId={} resolvedGroupId={} resolvedOwnerId={} userId={}",
			(String) resolved.get(FieldNames.FIELD_NAME), libGroupId, resolvedGroupId, resolvedOwnerId, userId);
	}

	/**
	 * Personal fallback: in an org with NO shared-library config, the default resolves to the acting
	 * user's own config.
	 */
	@Test
	public void resolveDefaultChatConfig_fallsBackToPersonalWhenNoLibrary() throws Exception {
		OrganizationContext o = getTestOrganization("/Development/CbChatCfgNoLib");
		// A UNIQUE user per run: getCreateUser reuses a same-named user across runs, and each run of this
		// test creates one personal chatConfig for it — so a reused user accumulates several, and step-4's
		// find (no sort) returns the OLDEST, not the one this run created, making the exact-objectId check
		// below flap. A fresh user has exactly one personal config, so "the fallback returns a user-owned
		// config" and "it is the one just created" coincide — keeping the strong, deterministic assertion.
		BaseRecord user = getCreateUser("cbCfgNoLibUser-" + System.currentTimeMillis(), o);
		assertNotNull("Test user must resolve", user);
		long userId = ((Number) user.get(FieldNames.FIELD_ID)).longValue();

		// Precondition: this org has NO shared-library chatConfig (it is never populated here).
		BaseRecord libraryConfig = ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG);
		assertNull("Precondition: this org must have NO shared-library chatConfig for the fallback path "
			+ "to be exercised", libraryConfig);

		BaseRecord personal = createPersonalChatConfig(user, "OnlyPersonalCfg-" + System.currentTimeMillis());
		String personalOid = personal.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord resolved = ChapBookUtil.resolveDefaultChatConfig(user);
		assertNotNull("resolveDefaultChatConfig must fall back to the personal config", resolved);
		long resolvedOwnerId = ((Number) resolved.get(FieldNames.FIELD_OWNER_ID)).longValue();
		assertEquals("With no library config, the default must be the acting user's OWN config", userId, resolvedOwnerId);
		assertEquals("The fallback must return the personal config that was created", personalOid,
			(String) resolved.get(FieldNames.FIELD_OBJECT_ID));

		logger.info("resolveDefaultChatConfig_fallsBackToPersonalWhenNoLibrary PASSED: resolvedOwnerId={} userId={} oid={}",
			resolvedOwnerId, userId, personalOid);
	}
}
