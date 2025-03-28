package org.cote.accountmanager.olio.assist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.xml.sax.SAXException;

public class ScrivenerAssistant extends DocumentAssistant implements IAssist {
	public static final Logger logger = LogManager.getLogger(ScrivenerAssistant.class);
	private boolean resetSummary = false;
	private ScrivenerDocumentMap sdm = null;
	private BaseRecord user = null;

	public ScrivenerAssistant(BaseRecord user) {
		super(new ScrivenerDocumentMap());
		this.user = user;
		this.sdm = (ScrivenerDocumentMap)map;
	}

	public int summarize(Path path, String contents) throws FieldException, ModelNotFoundException {
		int outVal = 0;
		ScrivenerBinding sb = sdm.documentMeta.get(path);

		logger.info("Summarizing " + sb.getTitle());

		String cnt = null;
		BaseRecord sdata = DocumentUtil.getData(user, sb.getUid(), "~/Scrivener");
		if (sdata != null && resetSummary) {
			try {
				IOSystem.getActiveContext().getWriter().delete(sdata);
			} catch (WriterException e) {
				logger.error(e);
				e.printStackTrace();
			}
			sdata = null;
		}
		if (sdata == null) {
			Chat chat = getChat(summaryPrompt);
			OpenAIRequest req = getRequest(chat);
			chat.newMessage(req, summaryCommand + System.lineSeparator() + contents, "user");
			OpenAIResponse resp = chat.chat(req);

			cnt = resp.getMessage().getContent();
			if (cnt != null) {
				sdata = DocumentUtil.getCreateData(user, sb.getUid(), "~/Scrivener", cnt);
			} else {
				logger.error("Failed to retrieve LLM response");
			}
			outVal = 2;
		} else {
			try {
				cnt = ByteModelUtil.getValueString(sdata);
				outVal = 1;
			} catch (ValueException | FieldException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		if (cnt != null && cnt.length() > 0) {
			sdm.documentSummary.put(path, cnt);
		}
		logger.info(cnt);
		return outVal;
	}

	public void assist(Path path, String prevLine, String line, String nextLine)
			throws FieldException, ModelNotFoundException {

		Chat chat = getChat(completionPrompt);

		StringBuilder lineBuff = new StringBuilder();
		String sum = sdm.documentSummary.get(path);
		lineBuff.append("*SECTION SUMMARY* " + System.lineSeparator() + lineBuff + System.lineSeparator());
		if (prevLine != null) {
			lineBuff.append("*PREVIOUS LINE* " + prevLine + System.lineSeparator());
		}
		lineBuff.append("*CURRENT LINE* " + line + System.lineSeparator());
		if (nextLine != null) {
			lineBuff.append("*NEXT LINE* " + nextLine + System.lineSeparator());
		}
		OpenAIRequest req = getRequest(chat);
		chat.newMessage(req, completionCommand + System.lineSeparator() + lineBuff.toString(), "user");
		OpenAIResponse resp = chat.chat(req);

		logger.info("SUGGESTION - " + resp.getMessage().getContent());

	}

	public List<ScrivenerBinding> parseProject(String path)
			throws ParserConfigurationException, SAXException, IOException, XPathExpressionException {
		return sdm.parseProject(path);
	}

}
