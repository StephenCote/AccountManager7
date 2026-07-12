package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Test;

public class ScratchTocProbe extends BaseTest {

    private static final String TOC_SYSTEM_PROMPT =
        "You are a document structure analysis assistant. You extract a hierarchical table of contents from "
        + "prose text and return it as strict JSON. Return only the JSON, with no other text.";
    private static final String TOC_USER_PROMPT =
        "You are given the text of a document that has no explicit headings. Identify the natural hierarchical "
        + "section structure of the document and return it as a table of contents." + System.lineSeparator()
        + System.lineSeparator()
        + "Return a STRICT JSON array. Each entry must be an object with exactly these keys:" + System.lineSeparator()
        + "  \"title\": a short descriptive section title you compose (the document has no headings, so you "
        + "invent a concise title)." + System.lineSeparator()
        + "  \"level\": an integer nesting depth, where 1 is a top-level section, 2 is a subsection of the "
        + "preceding level-1 section, and so on." + System.lineSeparator()
        + "  \"startMarker\": a VERBATIM snippet of 5 to 12 words copied EXACTLY from the document text marking "
        + "where this section begins. Copy the words exactly as they appear, including punctuation and casing. "
        + "Do not paraphrase. The snippet MUST be findable by an exact substring search of the document." + System.lineSeparator()
        + System.lineSeparator()
        + "Order the entries in the order the sections appear in the document. Produce a genuine hierarchy with "
        + "subsections where the content supports it, not a flat list." + System.lineSeparator()
        + System.lineSeparator() + "Document text:" + System.lineSeparator();
    private static final String TOC_USER_SUFFIX =
        System.lineSeparator() + System.lineSeparator() + "Return only the JSON array, do not include any other text.";

    @Test
    public void probe() throws Exception {
        Factory mf = ioContext.getFactory();
        OrganizationContext octx = getTestOrganization("/Development/PageIndex");
        BaseRecord adminUser = octx.getAdminUser();
        BaseRecord testUser = mf.getCreateUser(adminUser, "testUser1", octx.getOrganizationId());
        assertNotNull(testUser);

        String server = testProperties.getProperty("test.llm.ollama.server");
        String model = testProperties.getProperty("test.llm.ollama.model");

        ChatLibraryUtil.getCreateChatConfigLibrary(adminUser);
        ChatLibraryUtil.getCreateConnectionLibrary(adminUser);
        BaseRecord ollamaConn = OlioTestUtil.getCreateConnection(testUser, "Ollama PageIndex Conn", server, null, 180);
        assertNotNull(ollamaConn);

        BaseRecord cfg = ChatUtil.getLibraryConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, "contentAnalysis");
        if(cfg == null) {
            BaseRecord chatLibDir = ChatLibraryUtil.findLibraryDir(adminUser, ChatLibraryUtil.LIBRARY_CHAT_CONFIGS);
            ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, chatLibDir.get("path"));
            plist.parameter(FieldNames.FIELD_NAME, "contentAnalysis");
            cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, adminUser, null, plist);
            ChatUtil.applyChatConfigTemplate(cfg, "contentAnalysis");
            cfg.set("connection", ollamaConn);
            cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
            cfg.set("model", model);
            cfg = IOSystem.getActiveContext().getAccessPoint().create(adminUser, cfg);
        }
        else {
            cfg.set("connection", ollamaConn);
            cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
            cfg.set("model", model);
            cfg = IOSystem.getActiveContext().getAccessPoint().update(adminUser, cfg);
        }
        BaseRecord chatConfig = ChatUtil.getLibraryConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, "contentAnalysis");
        assertNotNull(chatConfig);

        String path = "./media/catatone.docx";
        String content = DocumentUtil.readDocument(path);
        logger.info("[PROBE] content length=" + content.length());

        // splitIntoGroups is private; replicate via reflection
        Class<?> piu = Class.forName("org.cote.accountmanager.util.PageIndexUtil");
        Method split = piu.getDeclaredMethod("splitIntoGroups", String.class);
        split.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> groups = (List<String>) split.invoke(null, content);
        logger.info("[PROBE] group count=" + groups.size());
        for(int i=0;i<groups.size();i++) {
            logger.info("[PROBE] group " + i + " length=" + groups.get(i).length());
        }

        for(int i=0;i<groups.size();i++) {
            String group = groups.get(i);
            String raw = callChatRaw(testUser, chatConfig, TOC_SYSTEM_PROMPT, TOC_USER_PROMPT + group + TOC_USER_SUFFIX);
            logger.info("[PROBE] === GROUP " + i + " RAW RESPONSE (len=" + (raw==null?-1:raw.length()) + ") ===");
            logger.info("[PROBE] GROUP " + i + " RAW: " + raw);
        }
    }

    private String callChatRaw(BaseRecord user, BaseRecord chatConfig, String systemPrompt, String userMessage) {
        try {
            Chat chat = new Chat(user, chatConfig, null);
            chat.setLlmSystemPrompt(systemPrompt);
            OpenAIRequest req = chat.newRequest(chat.getModel());
            req.setStream(false);
            if(chatConfig.getEnum("serviceType") == LLMServiceEnumType.OLLAMA) {
                req.set("think", false);
            }
            chat.newMessage(req, userMessage, Chat.userRole);
            OpenAIResponse resp = chat.chat(req);
            if(resp != null && resp.getMessage() != null) {
                return resp.getMessage().get(FieldNames.FIELD_CONTENT);
            }
            return "(null response/message)";
        }
        catch(Exception e) {
            logger.error("[PROBE] callChatRaw failed", e);
            return "(exception: " + e.getMessage() + ")";
        }
    }
}
