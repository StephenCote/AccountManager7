package org.cote.accountmanager.olio.llm;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.personality.PersonalityUtil;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class Chat {
	
	protected IOContext ioContext = null;
	// protected OrganizationContext orgContext = null;
	// protected String organizationPath = "/Development";
	
	public static final Logger logger = LogManager.getLogger(Chat.class);
	private String ollamaServer = "http://localhost:11434";
	
	private PromptConfiguration promptConfig = null;
	private boolean chatMode = true;
	private boolean includeMessageHistory = chatMode;
	private boolean includeContextHistory = !chatMode;
	private boolean enablePrune = false;
	private int tokenLength = 2048;
	private int tokenBuffer = 756;
	//private String model = "llama2-uncensored:7b-chat-q8_0";
	private String model = "dolphin-mistral";
	//private String model = "blue-orchid";
	private String saveName = "chat.save";
	private String analyzeName = "analyze.save";
	private BaseRecord user = null;
	private int pruneSkip = 1;
	//private boolean randomSetting = false;
	private String settingStr = null;
	private boolean includeScene = false;
	private ESRBEnumType rating = ESRBEnumType.E;
	private int remind = 0;
	private String annotation = null;
	private String assist = null;
	private String systemAnalyze = null;
	private String userAnalyze = null;
	private boolean useAssist = false;
	private boolean useNLP = false;
	private String llmSystemPrompt = """
You play the role of an assistant named Siren.
Begin conversationally.
""";
	
	public Chat(BaseRecord user) {
		this.user = user;
	}
	
	public String getSettingStr() {
		return settingStr;
	}

	public void setSettingStr(String settingStr) {
		this.settingStr = settingStr;
	}

	public boolean isEnablePrune() {
		return enablePrune;
	}

	public void setEnablePrune(boolean enablePrune) {
		this.enablePrune = enablePrune;
	}

	public PromptConfiguration getPromptConfig() {
		return promptConfig;
	}

	public void setPromptConfig(PromptConfiguration promptConfig) {
		this.promptConfig = promptConfig;
	}

	public boolean isUseNLP() {
		return useNLP;
	}

	public void setUseNLP(boolean useNLP) {
		this.useNLP = useNLP;
	}

	public String getAssist() {
		return assist;
	}

	public void setAssist(String assist) {
		this.assist = assist;
	}

	public boolean isUseAssist() {
		return useAssist;
	}

	public void setUseAssist(boolean useAssist) {
		this.useAssist = useAssist;
	}

	public String getAnnotation() {
		return annotation;
	}

	public void setAnnotation(String annotation) {
		this.annotation = annotation;
	}

	public int isRemind() {
		return remind;
	}

	public void setRemind(int remind) {
		this.remind = remind;
	}

	public ESRBEnumType getRating() {
		return rating;
	}

	public void setRating(ESRBEnumType rating) {
		this.rating = rating;
	}

	public boolean isIncludeScene() {
		return includeScene;
	}

	public void setIncludeScene(boolean includeScene) {
		this.includeScene = includeScene;
	}
/*
	public boolean isRandomSetting() {
		return randomSetting;
	}

	public void setRandomSetting(boolean randomSetting) {
		this.randomSetting = randomSetting;
	}
*/
	public String getOllamaServer() {
		return ollamaServer;
	}

	public void setOllamaServer(String ollamaServer) {
		this.ollamaServer = ollamaServer;
	}

	public int getPruneSkip() {
		return pruneSkip;
	}

	public void setPruneSkip(int pruneSkip) {
		this.pruneSkip = pruneSkip;
	}

	private void setMode(boolean chat) {
		chatMode = chat;
		includeMessageHistory = chatMode;
		includeContextHistory = !chatMode;
	}

	
	public boolean isChatMode() {
		return chatMode;
	}

	public void setChatMode(boolean chatMode) {
		this.chatMode = chatMode;
	}

	public boolean isIncludeMessageHistory() {
		return includeMessageHistory;
	}

	public void setIncludeMessageHistory(boolean includeMessageHistory) {
		this.includeMessageHistory = includeMessageHistory;
	}

	public boolean isIncludeContextHistory() {
		return includeContextHistory;
	}

	public void setIncludeContextHistory(boolean includeContextHistory) {
		this.includeContextHistory = includeContextHistory;
	}

	public int getTokenLength() {
		return tokenLength;
	}

	public void setTokenLength(int tokenLength) {
		this.tokenLength = tokenLength;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getLlmSystemPrompt() {
		return llmSystemPrompt;
	}

	public void setLlmSystemPrompt(String llmSystemPrompt) {
		this.llmSystemPrompt = llmSystemPrompt;
	}

	public void chatConsole(){
		chatConsole(newRequest(model));
	}
	
	public void continueChat(OllamaRequest req, String message){
		OllamaResponse lastRep = null;	
		/*
		if(req.getMessages().size() > 0) {
			logger.info("Initializing ...");
			lastRep = chat(req);
			if(lastRep != null) {
				handleResponse(req, lastRep);
			}
		}
		*/
		if(remind > 0 && annotation != null && req.getMessages().size() > 5 && (req.getMessages().size() % remind == 0)) {
			addReminder(req);
		}
		if(message != null) {
			newMessage(req, message);
		}
		lastRep = chat(req);
		if(lastRep != null) {
			handleResponse(req, lastRep, false);
		}
	}
	private String getFormattedChatHistory(OllamaRequest req, boolean full) {
		StringBuilder buff = new StringBuilder();
		//for(OllamaMessage msg : req.getMessages()) {
		//for(int i = (pruneSkip + 1); i < req.getMessages().size(); i++) {
		for(int i = (full ? 0 : (pruneSkip + 1)); i < req.getMessages().size(); i++) {
			if(buff.length() > 0) buff.append("\r\n");

			OllamaMessage msg = req.getMessages().get(i);
			String cont = msg.getContent();
			if(cont != null && cont.startsWith("(KeyFrame")) {
				continue;
			}
			String charPos = "#1";
			if(msg.getRole().equals("user")) {
				charPos = "#2";
			}
			buff.append("(" + charPos + "): ");
			buff.append(cont);
		}
		return buff.toString();
	}
	
	public OllamaRequest getAnalyzePrompt(OllamaRequest req, String command, boolean full) {
		
		if(command == null || command.length() == 0) {
			if(userAnalyze != null && userAnalyze.length() > 0) {
				command = userAnalyze;
			}
			else {
				command = "Summarize the following chat history.";
			}
		}
		
		OllamaRequest areq = new OllamaRequest();
		areq.setModel(req.getModel());
		areq.setOptions(req.getOptions());
		OllamaMessage sysMsg = new OllamaMessage();
		sysMsg.setRole("system");
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Limit your response to under 300 words.";
		if(systemAnalyze != null && systemAnalyze.length() > 0) {
			sys = systemAnalyze;
		}
		sysMsg.setContent(sys);
		areq.getMessages().add(sysMsg);
		OllamaMessage anMsg = new OllamaMessage();
		anMsg.setRole("user");

		anMsg.setContent(command);
		areq.getMessages().add(anMsg);
		
		anMsg = new OllamaMessage();
		anMsg.setRole("user");
		anMsg.setContent(getFormattedChatHistory(req, full));
		areq.getMessages().add(anMsg);

		return areq;
	}
	
	public String analyze(OllamaRequest req, String command, boolean full) {
		String resp = null;
		OllamaResponse oresp = chat(getAnalyzePrompt(req, command, full));
		if(oresp != null && oresp.getMessage() != null) {
			resp = oresp.getMessage().getContent();
		}
		return resp;
	}

	public void chatConsole(OllamaRequest req){
		BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		try{
			String prompt = "> ";
			String line = "";
			OllamaResponse lastRep = null;
			while (line != null && line.equalsIgnoreCase("/quit") == false && line.equalsIgnoreCase("/exit") == false && line.equalsIgnoreCase("/bye") == false) {
				if(lastRep == null && req.getMessages().size() > 0) {
					logger.info("Initializing ...");
					lastRep = chat(req);
					if(lastRep != null) {
						handleResponse(req, lastRep, true);
					}
				}
				System.out.print(prompt);
				line = is.readLine();
				if(line == null || line.equalsIgnoreCase("/bye")) {
					break;
				}
				if(line.equals("/new")) {
					req = newRequest(model);
					continue;
				}
				if(line.startsWith("/analyzeAll")) {
					logger.info(analyze(req, line.substring(8).trim(), true));
					continue;
				}
				if(line.startsWith("/analyze")) {
					logger.info(analyze(req, line.substring(8).trim(), false));
					continue;
				}

				if(line.equals("/prune")) {
					prune(req, true);
					continue;
				}
				if(line.equals("/prompt")) {
					if(chatMode && req.getMessages().size() > 0) {
						req.getMessages().get(0).setContent(llmSystemPrompt.trim());
					}
					if(!chatMode) {
						req.setSystem(llmSystemPrompt.trim());
					}
					continue;
				}
				if(line.equals("/remind")) {
					addReminder(req);
					continue;
				}
				if(line.equals("/truncate")) {
					lastRep = null;
					req.setMessages(req.getMessages().subList(0, this.useAssist ? 3 : 2));
					continue;
				}
				if(line.equals("/save")) {
					System.out.println("Saving ...");
					FileUtil.emitFile("./" + saveName, JSONUtil.exportObject(req));
					continue;
				}
				if(line.equals("/load")) {
					System.out.println("Loading ...");
					String sav = FileUtil.getFileAsString("./" + saveName);
					if(sav != null && sav.length() > 0) {
						req = JSONUtil.importObject(sav, OllamaRequest.class);
					}
					continue;
				}
				// System.out.println("'" + line + "'");
				
				if(chatMode) {
					if(remind > 0 && annotation != null && (req.getMessages().size() % remind == 0)) {
						addReminder(req);
					}
					newMessage(req, line);
				}
				else {
					req.setPrompt(line);
				}
				
				/// System.out.println(JSONUtil.exportObject(req));
				lastRep = chat(req);
				if(lastRep != null) {
					handleResponse(req, lastRep, true);
				}
			}
		    is.close();
		}
		catch(IOException e){
			logger.error(e.getMessage());
		} 
	}
	
	private void addKeyFrame(OllamaRequest req) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("assistant");
		msg.setContent("(KeyFrame: (" + rating.toString() + "/" + ESRBEnumType.getESRBMPA(rating) + "-rated content) " + analyze(req, annotation, true) + ")");
		List<OllamaMessage> msgs = req.getMessages().stream().filter(m -> m.getContent() != null && !m.getContent().startsWith("(KeyFrame")).collect(Collectors.toList());
		msgs.add(msg);
		req.setMessages(msgs);
	}
	
	private void addReminder(OllamaRequest req) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent(annotation);
		req.getMessages().add(msg);
	}
	private void handleResponse(OllamaRequest req, OllamaResponse rep, boolean emitResponse) {
		if(includeContextHistory) {
			req.setContext(rep.getContext());
		}

		/// System.out.println(JSONUtil.exportObject(lastRep));
		if(rep.getMessage() != null) {
			if(includeMessageHistory) {
				req.getMessages().add(rep.getMessage());
			}
			String cont = rep.getMessage().getContent();
			if(emitResponse && cont != null) {
				cont = cont.trim().replaceAll("^assistant[:]*\s*", "");
				System.out.println(cont);
			}
		}
	}
	
	public OllamaRequest newRequest(String model) {
		OllamaRequest req = new OllamaRequest();
		req.setModel(model);
		req.setStream(false);
		if(chatMode) {
			OllamaMessage msg = new OllamaMessage();
			msg.setRole("system");
			msg.setContent(llmSystemPrompt.trim());
			req.getMessages().add(msg);
		}
		else {
			req.setSystem(llmSystemPrompt.trim());
		}
		return req;
	}
	
	private void prune(OllamaRequest req, boolean force) {
		if((!force && !enablePrune) || !chatMode) {
			return;
		}
		if(req.getMessages().size() <= pruneSkip) {
			return;
		}
		OllamaMessage ctxMsg = req.getMessages().get(0);
		/// Including the system prompt
		///
		
		int curLength = 0;
		//ctxMsg.getContent().split("\\W+").length;
		//logger.info("Sys prompt length: " + curLength);
		int marker = -1;
		int markerAhead = -1;
		/// Skip the first message used to set any context prompt
		///
		/// pruneSkip
		for(int i = 0; i < req.getMessages().size(); i++) {
			OllamaMessage msg = req.getMessages().get(i);
			if(msg.isPruned()) continue;
			if(msg.getContent() != null) {
				curLength += msg.getContent().split("\\W+").length;
			}
			// logger.info("Tokens: " + curLength);
			if(i < pruneSkip) {
				continue;
			}
			else if(i == pruneSkip) {
				// logger.info("Front load: " + curLength);
			}
			if(marker == -1 && curLength >= tokenLength) {
				System.out.println("(Prune from " + pruneSkip + " prior to " + i + " of " + req.getMessages().size() + " / Token Size = " + curLength + ")");
				marker = i - 1;
				//break;
			}
			if(markerAhead == -1 && curLength >= (tokenLength + tokenBuffer)) {
				System.out.println("(Prune buffer from " + pruneSkip + " prior to " + i + " of " + req.getMessages().size() + " / Token Size = " + curLength + ")");
				markerAhead = i - 1;
				//break;
			}

		}
		if(force) {
			System.out.println("Found " + curLength + " tokens. " + (marker > -1 ? "Will":"Won't") + " prune.");
		}
		if(marker > -1) {
			
			int im = Math.max(marker, markerAhead);
			for(int i = pruneSkip; i <= im; i++) {
				req.getMessages().get(i).setPruned(true);
			}
			System.out.println("(Adding key frame)");
			addKeyFrame(req);
			/*
			List<OllamaMessage> msgs = Arrays.asList(Arrays.copyOfRange(req.getMessages().toArray(new OllamaMessage[0]), marker, req.getMessages().size()));
			req.getMessages().clear();
			req.getMessages().add(ctxMsg);
			req.getMessages().addAll(msgs);
			*/
		}
	}

	public OllamaMessage newMessage(OllamaRequest req, String message) {
		return newMessage(req, message, "user");
	}
	public OllamaMessage newMessage(OllamaRequest req, String message, String role) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole(role);
		msg.setContent(message);
		
		prune(req, false);
		req.getMessages().add(msg);

		return msg;
	}
	
	public OllamaRequest getPrunedRequest(OllamaRequest inReq) {
		OllamaRequest outReq = new OllamaRequest();
		outReq.setContext(inReq.getContext());
		outReq.setModel(inReq.getModel());
		outReq.setOptions(inReq.getOptions());
		outReq.setPrompt(inReq.getPrompt());
		outReq.setSystem(inReq.getSystem());
		outReq.getMessages().addAll(inReq.getMessages().stream().filter(m -> (m.isPruned()==false)).collect(Collectors.toList()));
		return outReq;
	}

	public OllamaResponse chat(OllamaRequest req) {
		return ClientUtil.post(OllamaResponse.class, ClientUtil.getResource(ollamaServer + "/api/" + (chatMode ? "chat" : "generate")), getPrunedRequest(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	private Pattern locationName = Pattern.compile("\\$\\{location.name\\}");
	private Pattern locationTerrain = Pattern.compile("\\$\\{location.terrain\\}");
	private Pattern locationTerrains = Pattern.compile("\\$\\{location.terrains\\}");
	private Pattern userFirstName = Pattern.compile("\\$\\{user.firstName\\}");
	private Pattern systemFirstName = Pattern.compile("\\$\\{system.firstName\\}");
	private Pattern userFullName = Pattern.compile("\\$\\{user.fullName\\}");
	private Pattern systemFullName = Pattern.compile("\\$\\{system.fullName\\}");

	private Pattern userCharDesc = Pattern.compile("\\$\\{user.characterDesc\\}");
	private Pattern systemCharDesc = Pattern.compile("\\$\\{system.characterDesc\\}");
	private Pattern userCharDescLight = Pattern.compile("\\$\\{user.characterDescLight\\}");
	private Pattern systemCharDescLight = Pattern.compile("\\$\\{system.characterDescLight\\}");

	private Pattern profileAgeCompat = Pattern.compile("\\$\\{profile.ageCompat\\}");
	private Pattern profileRomanceCompat = Pattern.compile("\\$\\{profile.romanceCompat\\}");
	private Pattern profileRaceCompat = Pattern.compile("\\$\\{profile.raceCompat\\}");
	private Pattern profileLeader = Pattern.compile("\\$\\{profile.leader\\}");
	private Pattern eventAlign = Pattern.compile("\\$\\{event.alignment\\}");
	private Pattern animalPop = Pattern.compile("\\$\\{population.animals\\}");
	private Pattern peoplePop = Pattern.compile("\\$\\{population.people\\}");
	private Pattern interactDesc = Pattern.compile("\\$\\{interaction.description\\}");

	private Pattern userASG = Pattern.compile("\\$\\{user.asg\\}");
	private Pattern systemASG = Pattern.compile("\\$\\{system.asg\\}");
	private Pattern userCPPro = Pattern.compile("\\$\\{user.capPPro\\}");
	private Pattern userCPro = Pattern.compile("\\$\\{user.capPro\\}");
	private Pattern userPro = Pattern.compile("\\$\\{user.pro\\}");
	private Pattern userPPro = Pattern.compile("\\$\\{user.ppro\\}");
	private Pattern userPrompt = Pattern.compile("\\$\\{userPrompt\\}");
	private Pattern scene = Pattern.compile("\\$\\{scene\\}"); 
	private Pattern nlpPat = Pattern.compile("\\$\\{nlp\\}");
	private Pattern nlpWarnPat = Pattern.compile("\\$\\{nlpWarn\\}");
	private Pattern setting = Pattern.compile("\\$\\{setting\\}");
	private Pattern ratingPat = Pattern.compile("\\$\\{rating\\}");
	private Pattern ratingName = Pattern.compile("\\$\\{ratingName\\}");
	private Pattern ratingMpa = Pattern.compile("\\$\\{ratingMpa\\}");
	private Pattern ratingDesc = Pattern.compile("\\$\\{ratingDesc\\}");
	private Pattern ratingRestrict = Pattern.compile("\\$\\{ratingRestrict\\}");
	private Pattern annotateSupplement = Pattern.compile("\\$\\{annotateSupplement\\}");
	private Pattern userRace = Pattern.compile("\\$\\{user.race\\}");
	private Pattern systemRace = Pattern.compile("\\$\\{system.race\\}");
	private Pattern censorWarn = Pattern.compile("\\$\\{censorWarn\\}");
	private Pattern userConsent = Pattern.compile("\\$\\{user.consent\\}");
	private Pattern assistCensorWarn = Pattern.compile("\\$\\{assistCensorWarn\\}");
	private Pattern firstSecondToBe = Pattern.compile("\\$\\{firstSecondToBe\\}");
	
	public String composeTemplate(List<String> list) {
		return Matcher.quoteReplacement(list.stream().collect(Collectors.joining(" ")));
	}
	
	public String getChatPromptTemplate(OlioContext ctx, String templ, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, templ, epoch, evt, systemChar, userChar, interaction, iPrompt, false);
	}
	public String getChatPromptTemplate(OlioContext ctx, String templ, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt, boolean firstPerson) {

		if(promptConfig == null) {
			logger.error("Prompt configuration is null");
			return null;
		}
		
		PersonalityProfile sysProf = ProfileUtil.getProfile(ctx, systemChar);
		PersonalityProfile usrProf = ProfileUtil.getProfile(ctx, userChar);
		ProfileComparison profComp = new ProfileComparison(ctx, sysProf, usrProf);
		
		String asupp = "";
		String srace = "";
		String urace = "";
		
		if(sysProf.getRace().contains("L") || sysProf.getRace().contains("S") || sysProf.getRace().contains("V") || sysProf.getRace().contains("R") || sysProf.getRace().contains("W") || sysProf.getRace().contains("X") || sysProf.getRace().contains("Y") || sysProf.getRace().contains("Z")) {
			Optional<PromptRaceConfiguration> osupp = promptConfig.getRaces().stream().filter(r -> sysProf.getRace().contains(r.getRaceType().toString())).findFirst();
			if(osupp.isPresent()) {
				srace = composeTemplate(osupp.get().getRace());
			}
		}

		if(usrProf.getRace().contains("L") || usrProf.getRace().contains("S") || usrProf.getRace().contains("V") || usrProf.getRace().contains("R") || usrProf.getRace().contains("W") || usrProf.getRace().contains("X") || usrProf.getRace().contains("Y") || usrProf.getRace().contains("Z")) {
			Optional<PromptRaceConfiguration> osupp = promptConfig.getRaces().stream().filter(r -> usrProf.getRace().contains(r.getRaceType().toString())).findFirst();
			if(osupp.isPresent()) {
				urace = composeTemplate(osupp.get().getRace());
			}
		}
		templ = userRace.matcher(templ).replaceAll(urace);
		templ = systemRace.matcher(templ).replaceAll(srace);
		templ = annotateSupplement.matcher(templ).replaceAll(Matcher.quoteReplacement(asupp));
		templ = firstSecondToBe.matcher(templ).replaceAll(firstPerson ? "I am" : "You are");

		String scenel = "";
		if(includeScene) {
			scenel = Matcher.quoteReplacement(promptConfig.getScene().stream().collect(Collectors.joining("\r\n")));
		}
		templ = scene.matcher(templ).replaceAll(scenel);
		
		String settingl = "";
		if(includeScene) {
			scenel = promptConfig.getScene().stream().collect(Collectors.joining("\r\n"));
		}
		templ = scene.matcher(templ).replaceAll(scenel);
		if(settingStr == null || settingStr.length() == 0) {
			templ = setting.matcher(templ).replaceAll(composeTemplate(promptConfig.getSetting()));
		}
		
		String sysNlp = "";
		String assistNlp = "";
		if(useNLP) {
			sysNlp = composeTemplate(promptConfig.getSystemNlp());
			assistNlp = composeTemplate(promptConfig.getAssistantNlp());
		}
		templ = nlpPat.matcher(templ).replaceAll(sysNlp);
		templ = nlpWarnPat.matcher(templ).replaceAll(assistNlp);
		String sysCens = "";
		String assistCens = "";
		if(rating == ESRBEnumType.AO || rating == ESRBEnumType.RC) {
			sysCens = composeTemplate(promptConfig.getSystemCensorWarning());
			assistCens = composeTemplate(promptConfig.getAssistantCensorWarning());
			
		}
		templ = censorWarn.matcher(templ).replaceAll(sysCens);
		templ = assistCensorWarn.matcher(templ).replaceAll(assistCens);

		String uconpref = composeTemplate(promptConfig.getUserConsentPrefix());
		String ucons = "";
		if(rating == ESRBEnumType.M || rating == ESRBEnumType.AO || rating == ESRBEnumType.RC) {
			ucons = composeTemplate(promptConfig.getUserConsentRating());
		}
		if(useNLP) {
			if(ucons.length() > 0) ucons += " and ";
			ucons += composeTemplate(promptConfig.getUserConsentNlp());
		}
		templ = userConsent.matcher(templ).replaceAll(ucons.length() > 0 ? uconpref + ucons + ".": "");
		
		String ugen = userChar.get("gender");
		String ucppro = "His";
		String uppro = "his";
		String ucpro = "His";
		String upro = "his";

		if(ugen.equals("female")) {
			ucppro = "Her";
			ucpro = "She";
			uppro = "her";
			upro = "she";
		}

		String ujobDesc = "";
		List<String> utrades = userChar.get("trades");
		if(utrades.size() > 0) {
			ujobDesc =" " + utrades.get(0).toLowerCase();
		}
		
		String sgen = systemChar.get("gender");
		String sjobDesc = "";
		List<String> strades = systemChar.get("trades");
		if(strades.size() > 0) {
			sjobDesc =" " + strades.get(0).toLowerCase();
		}		
		
		templ = systemASG.matcher(templ).replaceAll(systemChar.get("age") + " year old " + sgen + sjobDesc);
		templ = userASG.matcher(templ).replaceAll(userChar.get("age") + " year old " + ugen + ujobDesc);
		templ = userCPro.matcher(templ).replaceAll(ucppro);
		templ = userPro.matcher(templ).replaceAll(upro);
		templ = userPPro.matcher(templ).replaceAll(uppro);
		templ = userCPPro.matcher(templ).replaceAll(ucppro);
		templ = ratingName.matcher(templ).replaceAll(ESRBEnumType.getESRBName(rating));
		templ = ratingPat.matcher(templ).replaceAll(rating.toString());
		templ = ratingDesc.matcher(templ).replaceAll(ESRBEnumType.getESRBShortDescription(rating));
		templ = ratingRestrict.matcher(templ).replaceAll(ESRBEnumType.getESRBRestriction(rating));
		templ = ratingMpa.matcher(templ).replaceAll(ESRBEnumType.getESRBMPA(rating));
		templ = userFirstName.matcher(templ).replaceAll((String)userChar.get("firstName"));
		templ = systemFirstName.matcher(templ).replaceAll((String)systemChar.get("firstName"));
		templ = userFullName.matcher(templ).replaceAll((String)userChar.get("name"));
		templ = systemFullName.matcher(templ).replaceAll((String)systemChar.get("name"));

		templ = userCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(ctx, userChar));
		templ = systemCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(ctx, systemChar));
		
		templ = userCharDescLight.matcher(templ).replaceAll(NarrativeUtil.describe(ctx, userChar, false, false));
		templ = systemCharDescLight.matcher(templ).replaceAll(NarrativeUtil.describe(ctx, systemChar, false, false));

		
		String ageCompat = "about the same age";
		if(profComp.doesAgeCrossBoundary()) {
			ageCompat = "aware of the difference in our ages";
		}
		templ = profileAgeCompat.matcher(templ).replaceAll("We are " + ageCompat + ".");
		
		String raceCompat = "not compatible";
		if(CompatibilityEnumType.compare(profComp.getRacialCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
			raceCompat = "compatible";
		}
		templ = profileRaceCompat.matcher(templ).replaceAll("We are racially " + raceCompat + ".");
		
		String romCompat = "we'd be doomed to fail";
		if(CompatibilityEnumType.compare(profComp.getRomanticCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
			romCompat = "there could be something between us";
		}
		templ = profileRomanceCompat.matcher(templ).replaceAll("Romantically, " + romCompat + ".");
		
		BaseRecord cell = userChar.get("state.currentLocation");
		if(settingStr != null && settingStr.length() > 0) {
			if(settingStr.equalsIgnoreCase("random")) {
				settingStr = NarrativeUtil.getRandomSetting();
			}
			templ = setting.matcher(templ).replaceAll("The setting is: " + settingStr);
		}
		else {
			if(cell != null) {
				List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(ctx, cell, Rules.MAXIMUM_OBSERVATION_DISTANCE);
				TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
				Set<String> stets = acells.stream().filter(c -> TerrainEnumType.valueOf((String)c.get("terrainType")) != tet).map(c -> ((String)c.get("terrainType")).toLowerCase()).collect(Collectors.toSet());
				String tdesc = "an expanse of " + tet.toString().toLowerCase();
				if(stets.size() > 0) {
					tdesc = "a patch of " + tet.toString().toLowerCase() + " near " + stets.stream().collect(Collectors.joining(","));
				}
				templ = locationTerrains.matcher(templ).replaceAll(tdesc);	
				templ = locationTerrain.matcher(templ).replaceAll(tet.toString().toLowerCase());
			}
		}
		String pdesc = "";
		AlignmentEnumType align = AlignmentEnumType.NEUTRAL;
		if(evt != null) {
			align = epoch.getEnum("alignment");

			BaseRecord realm = ctx.getRealm(evt.get("location"));
			if(realm == null) {
				logger.error("Failed to find realm");
			}

			List<BaseRecord> apop = GeoLocationUtil.limitToAdjacent(ctx, realm.get("zoo"), cell);
			String anames = apop.stream().map(a -> (String)a.get("name")).collect(Collectors.toSet()).stream().collect(Collectors.joining(", "));
			List<Long> gids = Arrays.asList(new Long[] {userChar.get(FieldNames.FIELD_ID), systemChar.get(FieldNames.FIELD_ID)});
			List<BaseRecord> fpop = GeoLocationUtil.limitToAdjacent(ctx, ctx.getPopulation(evt.get("location")), cell);
			pdesc = "No one seems to be nearby.";
			if(fpop.size() > 0) {
				pdesc = "There are " + fpop.size() +" strangers nearby.";
			}
			
			String adesc = "No animals seem to be nearby.";
			if(anames.length() > 0) {
				adesc ="Some animals are close, including " + anames + ".";
			}
			templ = animalPop.matcher(templ).replaceAll(adesc);
			
			
		}
		templ = peoplePop.matcher(templ).replaceAll(pdesc);
		templ = eventAlign.matcher(templ).replaceAll(NarrativeUtil.getOthersActLikeSatan(align));

		String leadDesc = "Neither one of us is in charge.";
		
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(Arrays.asList(sysProf, usrProf));
		boolean isLeaderContest = false;
		String contest = "I";
		
		leadDesc = outLead.getRecord().get("firstName") + " is the leader.";
		if(outLead.getId() == sysProf.getId()) {
			isLeaderContest = GroupDynamicUtil.contestLeadership(ctx, null, Arrays.asList(usrProf), sysProf).size() > 0;
		}
		else {
			contest = outLead.getRecord().get("firstName");
			isLeaderContest = GroupDynamicUtil.contestLeadership(ctx, null, Arrays.asList(sysProf), usrProf).size() > 0;
		}
		if(isLeaderContest) {
			leadDesc += " " + contest + " may challenge who is leading.";
		}
		
		templ = profileLeader.matcher(templ).replaceAll(leadDesc);
		
		BaseRecord loc = userChar.get("state.currentLocation");
		if(loc != null) {
			templ = locationTerrain.matcher(templ).replaceAll(loc.getEnum("terrainType").toString().toLowerCase());	
		}
		
		templ = interactDesc.matcher(templ).replaceAll((interaction != null ? NarrativeUtil.describeInteraction(interaction) : ""));
		
		templ = userPrompt.matcher(templ).replaceAll((iPrompt != null ? iPrompt : ""));
		
		return templ.trim();
	}
	

	public String getSystemChatPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, promptConfig.getSystem().stream().collect(Collectors.joining("\r\n")), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}
	public String getUserChatPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, promptConfig.getUser().stream().collect(Collectors.joining("\r\n")), epoch, evt, systemChar, userChar, interaction, iPrompt, true);
	}
	
	public String getSystemChatRpgPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.system.rpg.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}
	public String getUserChatRpgPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.user.rpg.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt, true);
	}
	public String getAnnotateChatPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.annotate.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}

	public String getAssistChatPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, promptConfig.getAssistant().stream().collect(Collectors.joining("\r\n")), epoch, evt, systemChar, userChar, interaction, iPrompt, true);
	}

	public String getSystemAnalyzeTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, promptConfig.getSystemAnalyze().stream().collect(Collectors.joining("\r\n")), epoch, evt, systemChar, userChar, interaction, iPrompt, true);
	}

	public String getUserAnalyzeTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, promptConfig.getUserAnalyze().stream().collect(Collectors.joining("\r\n")), epoch, evt, systemChar, userChar, interaction, iPrompt, true);
	}

	
	public OllamaRequest getChatPrompt(OlioContext octx, String defPrompt, String iPrompt, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, boolean rpg) {
		
		setLlmSystemPrompt(defPrompt);
		OllamaRequest req = newRequest(getModel());
		
		if(systemChar != null && userChar != null) {
			String sysTemp = null;
			String userTemp = null;
			String assist = null;

			userAnalyze = getUserAnalyzeTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt);
			systemAnalyze = getUserAnalyzeTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt);
			
			if(useAssist) {
				assist = getAssistChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt);
			}
			if(rpg) {
				sysTemp = getSystemChatRpgPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt);
				userTemp = getUserChatRpgPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt);
			}
			else {
				sysTemp = getSystemChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt);
				userTemp = getUserChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt);
			}
			setLlmSystemPrompt(sysTemp);
			req = newRequest(getModel());
			setPruneSkip(2);
			if(useAssist) {
				setPruneSkip(3);
			}
			newMessage(req, userTemp);
			if(useAssist && assist != null && assist.length() > 0) {
				newMessage(req, assist, "assistant");
			}
			setAnnotation(getAnnotateChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
			// setAssist(getAssistChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
		}
		else {
			logger.warn("Either system or user characters were null - Defaulting to standard chat interface");
		}
		
		OllamaOptions opts = new OllamaOptions();
		opts.setNumGpu(32);
		opts.setNumCtx(4096);
		/*
		opts.setTemperature(1);
		opts.setTopP(1);
		opts.setTopK(0);
		*/
		
		
		opts.setTemperature(0.8);
		opts.setTopP(0.95);
		opts.setTopK(30);
		
		/*
		opts.setTemperature(1.0);
		opts.setTopP(0.6);
		opts.setTopK(35);
		*/
		/*
		opts.setTemperature(0.55);
		opts.setTopP(0.55);
		opts.setTopK(45);
		*/
		opts.setRepeatPenalty(1.1);
		
		req.setOptions(opts);

		return req;
	}

}
