package org.cote.accountmanager.olio.assist;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.BaseRecord;

public abstract class DocumentAssistant implements IAssist {
	public static final Logger logger = LogManager.getLogger(DocumentAssistant.class);
	protected DocumentMap map = null;
	private long pause = 5000;
	private BaseRecord chatConfig = null;
	private BaseRecord user = null;

	protected String analysisPrompt = """
You are are an editor assistant and expert in Critical Discourse Analysis and Critical Rhetorical Analysis content analyst.
Rules:
* Use Critical Discoures Analysis to provide a broad analysis of language use within its social and cultural context, and look for patterns of communication beyond just persuasion tactics.
* Use Critical Rhetorical Analysis to provide a focused analysis on the strategies and techniques of how power dynamics and ideology are employed to influence the characters and audience.
* Include effective and ineffective language styles and patterns.
* Include implicit or explicit biases.
* Include social, political, and gender dynamics, biases, and/or implications.
* Include key characters, relationships, and plot developments.
ALWAYS Format Response As Follows:
Characters: Narrator, Character1, Charactor2, ... etc.
Discourse: Brief objective constructive discourse analysis.
Rhetorical: Brief objective constructive rhetorical analysis.
ALWAYS LIMIT RESPONSE TO 200 WORDS OR FEWER
""";

	protected String summaryCommand = "Summarize the following content so I can quickly understand what's going on:";

	protected String summaryPrompt = """
You are are an editor assistant and expert in creating short summaries of provided content.
Rules:
* Create objective summaries of content
* Give the content a quality rating: Read to Publish, Still Needs Work, Draft
* Include character names, relationships, and plot developments.
ALWAYS LIMIT RESPONSE TO 200 WORDS OR FEWER
""";

	protected String analysisCommand = "Analyze the following content.";

	protected String completionPrompt = """
You are are an editor assistant and provide inline editing assistance.
Rules:
* Use the provided content summary and context samples to suggest any sentence completion.
* Limit response to 200 words or fewer.
* Include a description of the writing style so that you'd be able to replicate it later on.
* ONLY provide a suggested completion or revision.
* DO NOT provide commentary or any other content.
* ONLY PROVIDE ONE TYPE OF RESPONSE
* ALWAYS FORMAT RESPONSE AS FOLLOWS:
** <Completion> Put your response here
** <Revision> Revised sentance
** <Suggestion> Put suggestion here

Example:
USER Please make a suggestion for the following:
*SECTION SUMMARY*
This is a summary of the story that you created
*PREVIOUS LINE* The kitten jumped off the stool.
*CURRENT LINE* The kitten
*NEXT LINE* The kitten meowed in terror.
ASSISTANT <Completion> landed in a bowl of water.

DO NOT RESPOND WITH *REVISED LINE*, *PREVIOUS LINE*, *CURRENT LINE*, or *NEXT LINE*
""";

	protected String completionCommand = "Complete or revise content, or suggest changes, for content marked as *CURRENT LINE*.";

	public DocumentAssistant(DocumentMap map) {
		this.map = map;
	}

	public void processModified(Path path) {
		long now = System.currentTimeMillis();
		if (map.processedFiles.containsKey(path) && (map.processedFiles.get(path) + pause) > now) {
			logger.info("Too soon for " + path + ": " + (map.processedFiles.get(path) + pause) + " > " + now);
			return;
		}

		map.modifiedFiles.add(path);
		if (!map.documentMap.containsKey(path)) {
			map.mapRtfDocument(path);
		} else {
			List<String> lines1 = map.documentMap.get(path);
			// remap
			map.mapRtfDocument(path);
			if (!map.documentSummary.containsKey(path)) {
				int cont = 0;
				try {
					cont = summarize(path, lines1.stream().collect(Collectors.joining(System.lineSeparator())));
				} catch (FieldException | ModelNotFoundException e) {
					logger.error(e);
					e.printStackTrace();
				}
				if (cont == 2) {
					return;
				}
			}
			List<String> lines2 = map.documentMap.get(path);
			String prevLine = null;
			String line = null;
			String nextLine = null;
			List<String> lines3 = lines2.stream().filter(l -> !lines1.contains(l)).collect(Collectors.toList());
			logger.info("Changed lines: " + lines3.size());

			for (int i = 0; i < lines2.size(); i++) {
				String tline = lines2.get(i);

				/// Found the first changed line
				if (lines3.contains(tline)) {
					line = tline;
					if (i < (lines2.size() - 1)) {
						nextLine = lines2.get(i + 1);
					}
					break;
				}
				prevLine = tline;
			}
			if (line != null) {
				try {
					assist(path, prevLine, line, nextLine);
				} catch (FieldException | ModelNotFoundException e) {
					logger.error(e);
					e.printStackTrace();
				}
				map.processedFiles.put(path, System.currentTimeMillis());

			}

		}

	}

	public long getPause() {
		return pause;
	}

	public void setPause(long pause) {
		this.pause = pause;
	}

	public BaseRecord getUser() {
		return user;
	}

	public void setUser(BaseRecord user) {
		this.user = user;
	}

	public String getAnalysisPrompt() {
		return analysisPrompt;
	}

	public void setAnalysisPrompt(String analysisPrompt) {
		this.analysisPrompt = analysisPrompt;
	}

	public String getSummaryCommand() {
		return summaryCommand;
	}

	public void setSummaryCommand(String summaryCommand) {
		this.summaryCommand = summaryCommand;
	}

	public String getSummaryPrompt() {
		return summaryPrompt;
	}

	public void setSummaryPrompt(String summaryPrompt) {
		this.summaryPrompt = summaryPrompt;
	}

	public String getAnalysisCommand() {
		return analysisCommand;
	}

	public void setAnalysisCommand(String analysisCommand) {
		this.analysisCommand = analysisCommand;
	}

	public String getCompletionPrompt() {
		return completionPrompt;
	}

	public void setCompletionPrompt(String completionPrompt) {
		this.completionPrompt = completionPrompt;
	}

	public String getCompletionCommand() {
		return completionCommand;
	}

	public void setCompletionCommand(String completionCommand) {
		this.completionCommand = completionCommand;
	}

	public DocumentMap getMap() {
		return map;
	}

	public BaseRecord getChatConfig() {
		return chatConfig;
	}

	protected Chat getChat(String prompt) throws FieldException, ModelNotFoundException {
		Chat chat = new Chat(user, chatConfig, null);
		chat.setLlmSystemPrompt(prompt);
		return chat;
	}

	protected OpenAIRequest getRequest(Chat chat) {
		OpenAIRequest req = chat.getChatPrompt();
		req.setValue("max_completion_tokens", 2048);
		req.setValue("frequency_penalty", 1.5);
		req.setValue("presence_penalty", 1.5);
		return req;
	}
}
