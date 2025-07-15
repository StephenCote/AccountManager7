package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.tools.VoiceResponse;
import org.cote.accountmanager.tools.VoiceUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Test;

public class TestVoice extends BaseTest {

	@Test
	public void TestVoice() {
		logger.info("Test voice synthesis...");
		String content = "Hello, this is a test of the voice synthesis system.";
		VoiceUtil eu = new VoiceUtil(LLMServiceEnumType.valueOf(testProperties.getProperty("test.voice.type").toUpperCase()), testProperties.getProperty("test.voice.server"), testProperties.getProperty("test.voice.authorizationToken"));
		VoiceResponse voice = eu.getVoice(content);
		assertNotNull("Voice response is null", voice);
		FileUtil.emitFile("./test_voice.mp3", voice.getAudio());
		
		
	}
}
