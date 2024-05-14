package org.cote.accountmanager.olio.llm;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
	
	private boolean chatMode = true;
	private boolean includeMessageHistory = chatMode;
	private boolean includeContextHistory = !chatMode;
	private boolean enablePrune = false;
	private int tokenLength = 2048;
	//private String model = "llama2-uncensored:7b-chat-q8_0";
	private String model = "dolphin-mistral";
	//private String model = "blue-orchid";
	private String saveName = "chat.save";
	private BaseRecord user = null;
	private int pruneSkip = 1;
	private boolean randomSetting = false;
	private boolean includeScene = false;
	private ESRBEnumType rating = ESRBEnumType.E;
	private int remind = 0;
	private String annotation = null;
	private String assist = null;
	private boolean useAssist = false;
	private String llmSystemPrompt = """
You play the role of an assistant named Siren.
Begin conversationally.
""";
	
	public Chat(BaseRecord user) {
		this.user = user;
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

	public boolean isRandomSetting() {
		return randomSetting;
	}

	public void setRandomSetting(boolean randomSetting) {
		this.randomSetting = randomSetting;
	}

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
		if(req.getMessages().size() > 0) {
			logger.info("Initializing ...");
			lastRep = chat(req);
			if(lastRep != null) {
				handleResponse(req, lastRep);
			}
		}
		if(remind > 0 && annotation != null && (req.getMessages().size() % remind == 0)) {
			addReminder(req);
		}
		newMessage(req, message);
		lastRep = chat(req);
		if(lastRep != null) {
			handleResponse(req, lastRep);
		}
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
						handleResponse(req, lastRep);
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
					handleResponse(req, lastRep);
				}
			}
		    is.close();
		}
		catch(IOException e){
			logger.error(e.getMessage());
		} 
	}
	private void addReminder(OllamaRequest req) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent(annotation);
		req.getMessages().add(msg);
	}
	private void handleResponse(OllamaRequest req, OllamaResponse rep) {
		if(includeContextHistory) {
			req.setContext(rep.getContext());
		}

		/// System.out.println(JSONUtil.exportObject(lastRep));
		if(rep.getMessage() != null) {
			if(includeMessageHistory) {
				req.getMessages().add(rep.getMessage());
			}
			String cont = rep.getMessage().getContent();
			if(cont != null) {
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

		int curLength = ctxMsg.getContent().split("\\W+").length;
		int marker = -1;

		/// Skip the first message used to set any context prompt
		///
		for(int i = pruneSkip; i < req.getMessages().size(); i++) {
			curLength += req.getMessages().get(i).getContent().split("\\W+").length;
			if(curLength >= tokenLength) {
				System.out.println("Prune prior to " + i + " of " + req.getMessages().size() + " / Token Size = " + curLength);
				marker = i - 1;
				break;
			}
		}
		if(force) {
			System.out.println("Found " + curLength + " tokens");
		}
		if(marker > -1) {
			List<OllamaMessage> msgs = Arrays.asList(Arrays.copyOfRange(req.getMessages().toArray(new OllamaMessage[0]), marker, req.getMessages().size()));
			req.getMessages().clear();
			req.getMessages().add(ctxMsg);
			req.getMessages().addAll(msgs);
		}
	}

	public OllamaMessage newMessage(OllamaRequest req, String message) {
		return newMessage(req, message, "user");
	}
	public OllamaMessage newMessage(OllamaRequest req, String message, String role) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole(role);
		msg.setContent(message);

		req.getMessages().add(msg);
		prune(req, false);
		return msg;
	}
	
	public OllamaResponse chat(OllamaRequest req) {
		return ClientUtil.post(OllamaResponse.class, ClientUtil.getResource(ollamaServer + "/api/" + (chatMode ? "chat" : "generate")), req, MediaType.APPLICATION_JSON_TYPE);
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

	private Pattern userPrompt = Pattern.compile("\\$\\{userPrompt\\}");
	private Pattern scene = Pattern.compile("\\$\\{scene\\}"); 
	private Pattern setting = Pattern.compile("\\$\\{setting\\}");
	private Pattern ratingPat = Pattern.compile("\\$\\{rating\\}");
	private Pattern ratingName = Pattern.compile("\\$\\{ratingName\\}");
	private Pattern ratingMpa = Pattern.compile("\\$\\{ratingMpa\\}");
	private Pattern ratingDesc = Pattern.compile("\\$\\{ratingDesc\\}");
	private Pattern ratingRestrict = Pattern.compile("\\$\\{ratingRestrict\\}");
	private Pattern annotateSupplement = Pattern.compile("\\$\\{annotateSupplement\\}");
	public String getChatPromptTemplate(OlioContext ctx, String templ, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, templ, epoch, evt, systemChar, userChar, interaction, iPrompt, false);
	}
	public String getChatPromptTemplate(OlioContext ctx, String templ, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt, boolean firstPerson) {
		
		PersonalityProfile sysProf = ProfileUtil.getProfile(ctx, systemChar);
		PersonalityProfile usrProf = ProfileUtil.getProfile(ctx, userChar);
		ProfileComparison profComp = new ProfileComparison(ctx, sysProf, usrProf);
		
		String asupp = "";
		if(sysProf.getRace().contains("S") || sysProf.getRace().contains("V") || sysProf.getRace().contains("R") || sysProf.getRace().contains("W") || sysProf.getRace().contains("X") || sysProf.getRace().contains("Y") || sysProf.getRace().contains("Z")) {
			String uper = "You are";
			if(firstPerson) {
				uper = "I am";
			}

			asupp = " " + uper + " not entirely human.";

			if(sysProf.getRace().contains("V")) {
				asupp = " " + uper + " undead and must drink the blood of living people to survive.";
			}
			if(sysProf.getRace().contains("R")) {
				asupp = " " + uper + " at least partially a robot and require a source of power to survive.";
			}
			if(sysProf.getRace().contains("S")) {
				asupp = " " + uper + " infused with a lascivious spirit and must have intimate contact to survive.";
			}

		}
		templ = annotateSupplement.matcher(templ).replaceAll(asupp);
		
		templ = scene.matcher(templ).replaceAll(includeScene ? "Scene: \\${profile.ageCompat} \\${profile.romanceCompat} \\${profile.raceCompat} \\${profile.leader} \\${interaction.description}" : "");
		if(!randomSetting) {
			templ = setting.matcher(templ).replaceAll("Setting: In this land, people generally ${event.alignment}. ${system.firstName} and ${user.firstName} are currently located in ${location.terrains}. ${population.people} ${population.animals}");
		}
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
		if(randomSetting) {
			templ = setting.matcher(templ).replaceAll("The setting is: " + NarrativeUtil.getRandomSetting());
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
		
		if(outLead.getId() == sysProf.getId()) {
			leadDesc = "You are the leader.";
			isLeaderContest = GroupDynamicUtil.contestLeadership(ctx, null, Arrays.asList(usrProf), sysProf).size() > 0;
		}
		else {
			leadDesc = "I am the leader.";
			contest = "You";
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
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.system.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}
	public String getUserChatPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.user.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}
	
	public String getSystemChatRpgPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.system.rpg.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}
	public String getUserChatRpgPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.user.rpg.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}
	public String getAnnotateChatPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.annotate.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt);
	}

	public String getAssistChatPromptTemplate(OlioContext ctx, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("olio/llm/chat.assistant.prompt.txt"), epoch, evt, systemChar, userChar, interaction, iPrompt, true);
	}

	
	public OllamaRequest getChatPrompt(OlioContext octx, String defPrompt, String iPrompt, BaseRecord epoch, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, boolean rpg) {
		
		setLlmSystemPrompt(defPrompt);
		OllamaRequest req = newRequest(getModel());
		
		if(systemChar != null && userChar != null) {
			String sysTemp = null;
			String userTemp = null;
			String assist = null;
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
			newMessage(req, userTemp);
			if(useAssist && assist != null && assist.length() > 0) {
				newMessage(req, assist, "assistant");
			}
			setAnnotation(getAnnotateChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
			// setAssist(getAssistChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
		}
		
		OllamaOptions opts = new OllamaOptions();
		opts.setNumGpu(50);
		opts.setNumCtx(4096);
		opts.setTemperature(0.85);
		req.setOptions(opts);

		return req;
	}

}
