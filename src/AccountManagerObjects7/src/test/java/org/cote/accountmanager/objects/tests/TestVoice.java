package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.tools.VoiceRequest;
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
		
		
		logger.info("Testing Piper ...");
		VoiceRequest vreq = new VoiceRequest("piper", "en_GB-alba-medium", content);
		VoiceResponse voice = eu.getVoice(vreq);
		assertNotNull("Voice response is null", voice);
		FileUtil.emitFile("./test_piper_voice.mp3", voice.getAudio());
		
		/*
		logger.info("Testing Xtts ...");
		VoiceRequest vreq2 = new VoiceRequest("xtts", FileUtil.getFile("./media/English Female American.mp3"), content);
		VoiceResponse voice2 = eu.getVoice(vreq2);
		assertNotNull("Voice response is null", voice2);
		FileUtil.emitFile("./test_xtts_voice.mp3", voice2.getAudio());
		*/
		
	}
}
