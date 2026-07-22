package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

/// Isolates the exact failure seen live in the PictureBook wizard: a real UX click-through
/// against qwen3-vl:8b-instruct returned an empty scene list within ~7 seconds, whereas a raw
/// direct call to the same Ollama server/model with the same prompt took ~44 seconds and returned
/// a full, valid response (eval_count=1487, load_duration=6.2s). TestChatStream's existing
/// TestStreamBufferMode proves buffer-mode chat works for a short prompt/response on qwen3:8b
/// (~2.4s, 4 NDJSON lines). This test isolates the one variable that differs from the PictureBook
/// failure: the actual model (qwen3-vl:8b-instruct) and a long-generation prompt, through
/// Chat.chat() directly -- no Tomcat, no PictureBookUtil, no REST layer.
public class TestChatStreamLongGeneration extends BaseTest {

	private static final String ORG_PATH = "/Development/Olio LLM Long Stream Tests";

	private BaseRecord getTestUser() {
		OrganizationContext testOrgContext = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		return mf.getCreateUser(testOrgContext.getAdminUser(), "longStreamTestUser1", testOrgContext.getOrganizationId());
	}

	@Test
	public void TestLongGenerationBufferModeCompletes() {
		logger.warn("[LLM-LIVE] TestLongGenerationBufferModeCompletes: requires reachable qwen3-vl:8b-instruct on the live Ollama server; a long completion (~30-45s) is expected and NOT a failure by itself");
		BaseRecord testUser = getTestUser();
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "OLLAMA Long Stream Test.chat", testProperties);
		assertNotNull("Chat config is null", cfg);

		/// The one deliberate difference from TestStreamBufferMode: the actual vision-language
		/// model PictureBookUtil uses, which is NOT already warm on the server (cold model load
		/// ~6s per a direct /api/chat probe) and produces a much longer completion.
		cfg.setValue("model", "qwen3-vl:8b-instruct");
		cfg.setValue("stream", false);
		OlioTestUtil.setConnectionRequestTimeout(testUser, cfg, 120);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Long Stream Buffer Test");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("You are a visual storytelling analyst. Return only valid JSON — no prose, no commentary, no markdown code fences. /no_think");
		IOSystem.getActiveContext().getAccessPoint().update(testUser, pcfg);

		String chatName = "Long Buffer Mode Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);

		Chat chat = new Chat(testUser, cfg, pcfg);
		chat.setRequestTimeout(120);
		OpenAIRequest req = chat.getChatPrompt();
		assertNotNull("OpenAI request is null", req);
		req.setStream(false);

		/// Same shape of ask as pictureBook.extract-scenes: a JSON array of 5 scenes with
		/// several string fields each -- enough output tokens to take real time to generate.
		String prompt = "Given this story, identify the 5 most visually notable scenes distributed across the narrative arc. Return a JSON array only, no prose:\n"
			+ "[{\"index\":0,\"title\":\"...\",\"blurb\":\"2-3 sentence description of who is doing what where\",\"setting\":\"environment/landscape description for illustration\",\"action\":\"what characters are doing\",\"mood\":\"atmosphere, lighting, time of day\",\"characters\":[{\"name\":\"...\",\"role\":\"brief role in scene\"}],\"diffusionPrompt\":\"detailed stable diffusion prompt for illustration\"}]\n\n"
			+ "STORY:\n"
			+ "AIME\n\n"
			+ "Valentines Day singles events were blissfully epicaricacious. Schadenfreudian. Delightfully delectable. Lonely adults trodding out a lifetime's worth of emotional baggage casually dressed to such fantasy as trophies enshrined behind the thin plate glass of a secondhand curio; Of course one or more cats sprayed the back, and occasionally a few exotic bugs crept from the darkened hollows of the rear right leg.\n\n"
			+ "The AI And Me singles event promised to be unlike any other; perhaps had they simply spoke plain, for the price of a geriatric rock star's off-Broadway casino theatre ticket, one could spend a Valentines Day evening taking the very same date they'd been on every day for as long as they could remember; This special evening your phone and you will immerse in a peaceful atmosphere and draw reverie from the curated bar.\n\n"
			+ "Introverts slouching in their date's romantic glow formed a wilted bouquet, long ago plucked buds desperately clutching leathery petals against stems thickened with unnatural fertilizers. An eery somber echo rang for every clink against rectangular glass. And, as if scripted to specific times or events, sometimes in a rippling sequence, lips momentarily touched glass and a tear gleamed silver as it melted through heavy foundation.\n\n"
			+ "With Rejects popping off in a slow though consistent rolling thunder, Break-Ups were streaks and bolts and chains of lightning that when striking close set the spine to shiver. An unsettling crash and crunch, a sudden hush making audible room for the imminent gasp of a dejected soliloquy. Quick-change service staff carry the remains upon a silver platter hoisted betwixt outstretched hands, pallbearers leading a somber procession towards the always available cry room.\n\n"
			+ "Momentarily the shock unchokes the perception of reality. Screens going dark are infrequent, and screens going plaid hint at an appreciation for Mel Brooks. Ciao. That's all that's left behind when Abandoned: The heartless sayonara of an emotionally binary ex.\n\n"
			+ "A vacuum forms between the two tables, two singles abandoned at a Valentines Day singles event, forced into eye contact; the following sequence of events from table, to check, to door proceeded with all the predictability of a B-list romantic comedy.\n\n"
			+ "Outside, the rain began to fall, light and misty, fog churning like a smoldering fire through the streets. Bathed in the bright neon lights advertising the very explicit fantasies so secretly craved, the walk across the slick street through choking fog appeared programmatic, hypnotic, and the way the doors whisper open and greet with a pleasant warm puff of air is resplendent, only to be greeted by a solemn faced caretaker who prepares an arrangement of new vessels into which you must pour your soul.";

		chat.newMessage(req, prompt);

		long start = System.currentTimeMillis();
		OpenAIResponse resp = chat.chat(req);
		long elapsedMs = System.currentTimeMillis() - start;
		logger.info("TestLongGenerationBufferModeCompletes: elapsed=" + elapsedMs + "ms");

		assertNotNull("Response should not be null in long-generation buffer mode (elapsed=" + elapsedMs + "ms)", resp);
		assertNotNull("Response message should not be null (elapsed=" + elapsedMs + "ms)", resp.getMessage());
		String content = resp.getMessage().getContent();
		assertNotNull("Response content should not be null (elapsed=" + elapsedMs + "ms)", content);
		logger.info("Long generation response length=" + content.length() + " content=" + content.substring(0, Math.min(500, content.length())));
		assertTrue("Response content should contain multiple scenes worth of JSON (got length=" + content.length() + " after " + elapsedMs + "ms): " + content,
			content.length() > 500);
	}
}
