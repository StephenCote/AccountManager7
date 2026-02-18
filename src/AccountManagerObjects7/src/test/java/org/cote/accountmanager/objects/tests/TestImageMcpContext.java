package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.tools.ImageTagUtil;
import org.junit.Test;

/// Phase 2 (chatRefactor2): Tests for buildImageMcpContext() — image drag/drop MCP context generation.
public class TestImageMcpContext extends BaseTest {

	@Test
	public void testBuildImageMcpContextWithImage() {
		logger.info("testBuildImageMcpContextWithImage");
		BaseRecord testUser = getCreateUser("testImageMcp");
		assertNotNull("Test user is null", testUser);

		/// Upload a test image to ~/Gallery/Uploads
		byte[] imageBytes = null;
		try {
			Path imgPath = Path.of("./media/sunflower.jpg");
			if (!Files.exists(imgPath)) {
				/// Try relative to project root
				imgPath = Path.of("../AccountManagerObjects7/media/sunflower.jpg");
			}
			if (!Files.exists(imgPath)) {
				logger.warn("Test image not found, skipping testBuildImageMcpContextWithImage");
				return;
			}
			imageBytes = Files.readAllBytes(imgPath);
		} catch (IOException e) {
			logger.error("Failed to read test image", e);
			return;
		}
		assertNotNull("Image bytes are null", imageBytes);
		assertTrue("Image bytes are empty", imageBytes.length > 0);

		BaseRecord data = getCreateData(testUser, "test-sunflower.jpg", "image/jpeg", imageBytes, "~/Gallery/Uploads", testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Data record is null", data);
		String objectId = data.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("ObjectId is null", objectId);

		/// Create a Chat instance with minimal config for testing
		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, org.cote.accountmanager.olio.llm.LLMServiceEnumType.OLLAMA, "TestImageMcp Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "TestImageMcp Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Chat/prompt config not available, skipping LLM-dependent assertions");
			return;
		}

		Chat chat = new Chat(testUser, chatConfig, promptConfig);

		/// Test with a message containing an image token
		String message = "Look at this image ${image." + objectId + ".sunflower,nature}";
		String mcpContext = chat.buildImageMcpContext(testUser, message);

		/// The result depends on ImageTagUtil availability (localhost:8000)
		ImageTagUtil itu = new ImageTagUtil();
		boolean tagServiceAvailable = false;
		try {
			tagServiceAvailable = itu.isHealthy();
		} catch (Exception e) {
			// service not running
		}

		if (tagServiceAvailable) {
			assertNotNull("MCP context is null", mcpContext);
			assertFalse("MCP context is empty", mcpContext.isEmpty());
			assertTrue("MCP context missing mcp:context tag", mcpContext.contains("<mcp:context"));
			assertTrue("MCP context missing image-description schema", mcpContext.contains("urn:am7:media:image-description"));
			assertTrue("MCP context missing objectId", mcpContext.contains(objectId));
			logger.info("Image MCP context generated successfully:\n" + mcpContext.substring(0, Math.min(500, mcpContext.length())));
		} else {
			logger.warn("Image tag service not available — verifying graceful degradation");
			assertTrue("MCP context should be empty when tag service unavailable", mcpContext == null || mcpContext.isEmpty());
		}
	}

	@Test
	public void testBuildImageMcpContextNoTokens() {
		logger.info("testBuildImageMcpContextNoTokens");
		BaseRecord testUser = getCreateUser("testImageMcpNoToken");
		assertNotNull("Test user is null", testUser);

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, org.cote.accountmanager.olio.llm.LLMServiceEnumType.OLLAMA, "TestImageMcpNoTok Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "TestImageMcpNoTok Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Config not available, skipping");
			return;
		}
		Chat chat = new Chat(testUser, chatConfig, promptConfig);

		/// Message with no image tokens should return empty
		String result = chat.buildImageMcpContext(testUser, "Hello, how are you?");
		assertTrue("Should return empty for message without image tokens", result.isEmpty());

		/// Null message should return empty
		result = chat.buildImageMcpContext(testUser, null);
		assertTrue("Should return empty for null message", result.isEmpty());
	}

	@Test
	public void testBuildImageMcpContextBadObjectId() {
		logger.info("testBuildImageMcpContextBadObjectId");
		BaseRecord testUser = getCreateUser("testImageMcpBadOid");
		assertNotNull("Test user is null", testUser);

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, org.cote.accountmanager.olio.llm.LLMServiceEnumType.OLLAMA, "TestImageMcpBadOid Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "TestImageMcpBadOid Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Config not available, skipping");
			return;
		}
		Chat chat = new Chat(testUser, chatConfig, promptConfig);

		/// Message with a non-existent objectId should gracefully return empty
		String result = chat.buildImageMcpContext(testUser, "${image.00000000-0000-0000-0000-000000000000.test}");
		assertTrue("Should return empty for non-existent objectId", result.isEmpty());
	}
}
