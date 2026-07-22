package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/// Reproduces the LIVE REST failure ("No scenes returned by LLM" within ~2-7s, vs. a real ~30-45s
/// completion) using a chatConfig built to match EXACTLY what the live curl repro created via REST
/// -- no chatOptions sub-record, requestTimeout=120 (not TestPictureBookFull's 300), plain
/// mf.getCreateUser() user -- to determine whether the gap is a missing config field (reproducible
/// here, in-process, no Tomcat) or genuinely specific to the REST/Tomcat layer (does NOT reproduce
/// here despite matching config).
public class TestPictureBookMinimalConfigRepro extends BaseTest {

	private static final String ORG_PATH = "/Development/PictureBook Minimal Config Repro";
	private static final String AIME_TEXT =
		"AIME\n\n" +
		"Valentines Day singles events were blissfully epicaricacious. Schadenfreudian. Delightfully delectable. Lonely adults trodding out a lifetime's worth of emotional baggage casually dressed to such fantasy as trophies enshrined behind the thin plate glass of a secondhand curio; Of course one or more cats sprayed the back, and occasionally a few exotic bugs crept from the darkened hollows of the rear right leg.\n\n" +
		"The AI And Me singles event promised to be unlike any other; perhaps had they simply spoke plain, for the price of a geriatric rock star's off-Broadway casino theatre ticket, one could spend a Valentines Day evening taking the very same date they'd been on every day for as long as they could remember; This special evening your phone and you will immerse in a peaceful atmosphere and draw reverie from the curated bar.\n\n" +
		"Introverts slouching in their date's romantic glow formed a wilted bouquet, long ago plucked buds desperately clutching leathery petals against stems thickened with unnatural fertilizers. An eery somber echo rang for every clink against rectangular glass. And, as if scripted to specific times or events, sometimes in a rippling sequence, lips momentarily touched glass and a tear gleamed silver as it melted through heavy foundation.\n\n" +
		"With Rejects popping off in a slow though consistent rolling thunder, Break-Ups were streaks and bolts and chains of lightning that when striking close set the spine to shiver. An unsettling crash and crunch, a sudden hush making audible room for the imminent gasp of a dejected soliloquy. Quick-change service staff carry the remains upon a silver platter hoisted betwixt outstretched hands, pallbearers leading a somber procession towards the always available cry room.\n\n" +
		"Momentarily the shock unchokes the perception of reality. Screens going dark are infrequent, and screens going plaid hint at an appreciation for Mel Brooks. Ciao. That's all that's left behind when Abandoned: The heartless sayonara of an emotionally binary ex.\n\n" +
		"A vacuum forms between the two tables, two singles abandoned at a Valentines Day singles event, forced into eye contact; the following sequence of events from table, to check, to door proceeded with all the predictability of a B-list romantic comedy.\n\n" +
		"Outside, the rain began to fall, light and misty, fog churning like a smoldering fire through the streets. Bathed in the bright neon lights advertising the very explicit fantasies so secretly craved, the walk across the slick street through choking fog appeared programmatic, hypnotic, and the way the doors whisper open and greet with a pleasant warm puff of air is resplendent, only to be greeted by a solemn faced caretaker who prepares an arrangement of new vessels into which you must pour your soul.";

	@Test
	public void TestExtractScenesOnlyWithMinimalRestStyleConfig() throws Exception {
		logger.warn("[LLM-LIVE] TestExtractScenesOnlyWithMinimalRestStyleConfig: requires reachable Ollama server; real completion expected");

		OrganizationContext testOrgCtx = getTestOrganization(ORG_PATH);
		Factory mf = IOSystem.getActiveContext().getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "pbMinimalReproUser", testOrgCtx.getOrganizationId());
		assertNotNull("Test user should be created", testUser);

		String ollamaServer = testProperties.getProperty("test.llm.ollama.server");
		assertNotNull("test.llm.ollama.server must be set", ollamaServer);

		/// Connection: requestTimeout=120 (curl repro's value), no apiKey -- matches the live repro.
		BaseRecord conn = OlioTestUtil.getCreateConnection(testUser, "MinimalReproConn", ollamaServer, null, 120);
		assertNotNull("Connection should be created", conn);

		/// ChatConfig: deliberately built with plist + direct field sets only, mirroring exactly
		/// what the curl repro's POST /rest/model {schema, name, groupId, groupPath, model,
		/// analyzeModel, serviceType, stream, connection} produced -- NO chatOptions sub-record set.
		String cfgName = "MinimalReproChatConfig";
		BaseRecord existing = org.cote.accountmanager.util.DocumentUtil.getRecord(
			testUser, OlioModelNames.MODEL_CHAT_CONFIG, cfgName, "~/Chat");
		BaseRecord chatConfig = existing;
		if (chatConfig == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, cfgName);
			chatConfig = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, testUser, null, plist);
			chatConfig.set("model", "qwen3-vl:8b-instruct");
			chatConfig.set("analyzeModel", "qwen3-vl:8b-instruct");
			chatConfig.set("serviceType", LLMServiceEnumType.OLLAMA);
			chatConfig.set("stream", false);
			chatConfig.set("connection", conn);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().create(testUser, chatConfig);
			assertNotNull("ChatConfig should be created", chatConfig);
		}

		/// Note: same AIME text as the live repro, same ~/Notes group path.
		BaseRecord notesDir = IOSystem.getActiveContext().getPathUtil().makePath(
			testUser, ModelNames.MODEL_GROUP, "~/Notes", org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(),
			((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
		assertNotNull("Notes dir should exist", notesDir);

		BaseRecord note = org.cote.accountmanager.util.DocumentUtil.getRecord(testUser, ModelNames.MODEL_NOTE, "AIME-minimal-repro", "~/Notes");
		if (note == null) {
			ParameterList nplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Notes");
			nplist.parameter(FieldNames.FIELD_NAME, "AIME-minimal-repro");
			note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, nplist);
			note.set("text", AIME_TEXT);
			note = IOSystem.getActiveContext().getAccessPoint().create(testUser, note);
			assertNotNull("Note should be created", note);
		}

		long start = System.currentTimeMillis();
		PictureBookUtil.ScenesOnlyResult result = PictureBookUtil.extractScenesOnly(
			testUser, note.get(FieldNames.FIELD_OBJECT_ID), 5, cfgName, null);
		long elapsedMs = System.currentTimeMillis() - start;
		logger.info("TestExtractScenesOnlyWithMinimalRestStyleConfig: elapsed=" + elapsedMs + "ms scenes=" + (result != null ? result.scenes.size() : -1));

		assertNotNull("Result should not be null", result);
		assertTrue("Expected real scenes back (elapsed=" + elapsedMs + "ms) -- got " + (result.scenes == null ? -1 : result.scenes.size()),
			result.scenes != null && result.scenes.size() > 0);
	}
}
