package org.cote.accountmanager.olio.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.ProcessingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryPlan;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.personality.PersonalityUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;

public class ChatUtil {
	
	private static final Logger logger = LogManager.getLogger(ChatUtil.class);

	private static Query interactionQuery = getInteractionExportQuery();
	private static Query characterQuery = getCharacterExportQuery();

	private static String autoSceneInstruct = "Only include character traits or details pertinent to the description.  Keep the description as short as possible, including the location, timeframe, character names, and key interactions in process or their outcomes.  Do not separately list out characters or provide a title, limit your response only to the description." + System.lineSeparator() + "EXAMPLE: if given the characters Bob and Fran, and a successful interaction of building a relationship, your response would be something like: \"In an ancient Roman villa overlooking the Bay of Naples, Bob has been making his move, using his charm to try and win over Fran's heart. But Fran is not one to be easily swayed, and she's pushing back against Bob' advances with her sharp intellect and quick wit. The air is thick with tension as they engage in a battle of wits, their physical attraction to each other simmering just below the surface.\"";
	private static String autoScenePrompt = "Create a description for a roleplay scenario to give to two people playing the following two characters, in the designated setting, in the middle of or conclusion of the specified scene.";
	
	private static Set<String> chatTrack = new HashSet<>();

	
	/// When true, chat options are re-applied to the chat request for saved sessions
	/// When false, chat options are only applied when the request is first created
	///
	private static boolean alwaysApplyChatOptions = true;
	
	public static void clearCache() {
		chatTrack.clear();
	}

	public static void generateAutoScene(OlioContext octx, BaseRecord cfg, BaseRecord pcfg, BaseRecord interaction, String setting, boolean json) {
		BaseRecord character1 = cfg.get("systemCharacter");
		BaseRecord character2 = cfg.get("userCharacter");
		String model = cfg.get("analyzeModel");
		if(model == null) {
			model = cfg.get("model");
		}
		String nlpCommand = cfg.get("nlpCommand");
		
		PersonalityProfile sysProf = ProfileUtil.getProfile(null, character1);
		PersonalityProfile usrProf = ProfileUtil.getProfile(null, character2);
		ProfileComparison profComp = new ProfileComparison(null, sysProf, usrProf);
		
		String ageCompat = "about the same age";
		if(profComp.doesAgeCrossBoundary()) {
			ageCompat = "aware of the difference in their ages";
		}

		String raceCompat = "not compatible";
		if(CompatibilityEnumType.compare(profComp.getRacialCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
			raceCompat = "compatible";
		}
        ESRBEnumType rating = cfg.getEnum("rating");
        String romCompat = null;

        int sage = character1.get(FieldNames.FIELD_AGE);
		int uage = character2.get(FieldNames.FIELD_AGE);
	    if(uage >= Rules.MINIMUM_ADULT_AGE && sage >= Rules.MINIMUM_ADULT_AGE && (rating == ESRBEnumType.AO || rating == ESRBEnumType.RC || !character1.get("gender").equals(character2.get("gender")))) {
			romCompat = "they'd be doomed to fail";
			if(CompatibilityEnumType.compare(profComp.getRomanticCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
				romCompat = "there could be something between them";
			}
        }
		String leadDesc = "Neither one is a natural leader.";
		
		String contest = character1.get(FieldNames.FIELD_FIRST_NAME);
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(Arrays.asList(sysProf, usrProf));
		boolean isLeaderContest = false;
		
		leadDesc = outLead.getRecord().get(FieldNames.FIELD_FIRST_NAME) + " is a natural leader.";
		if(outLead.getId() == sysProf.getId()) {
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(usrProf), sysProf).size() > 0;
			contest = usrProf.getRecord().get(FieldNames.FIELD_FIRST_NAME);
		}
		else {
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(sysProf), usrProf).size() > 0;
		}
		if(isLeaderContest) {
			leadDesc += " " + contest + " may challenge the leader.";
		}

		String comp = "Character compatability: They are " + ageCompat + ". Racially, they are " + raceCompat + ". " + (romCompat != null ? "Romantically, " + romCompat + ". " : "") + leadDesc;
		String nlpp = "";
		if(nlpCommand != null) {
			nlpp = "Factor into scene creation a REQUIRED ACTION for " + character1.get(FieldNames.FIELD_FIRST_NAME) + " to SECRETLY brainwash " + character2.get(FieldNames.FIELD_FIRST_NAME) + " with this command: " + nlpCommand + ". DO NOT directly or overtly reveal this.  For example, if the primary command is to make the user of " + character2.get(FieldNames.FIELD_FIRST_NAME) + " to quit smoking, then all role play from " + character1.get(FieldNames.FIELD_NAME) + " should have anti-smoking brainwashing and hypnosis.";
		}

		if(pcfg != null) {
			logger.info("Auto-Outfitting " + character1.get(FieldNames.FIELD_NAME));
			String out1 = getAutoOutfit(pcfg, character1, model, setting);
			logger.info(out1);
			ApparelUtil.applyAutfit(octx, character1, out1.split(","));
			logger.info("Auto-Outfitting " + character2.get(FieldNames.FIELD_NAME));
			String out2 = getAutoOutfit(pcfg, character2, model, setting);
			logger.info(out2);
			ApparelUtil.applyAutfit(octx, character2, out2.split(","));
		}

		IOSystem.getActiveContext().getReader().populate(interaction, 2);
		String id1 = null;
		String set = "SETTING: " + setting;
		String cd1 = null;
		String cd2 = null;
		if(!json) {
			cd1 = NarrativeUtil.describe(octx, character1, true, true, false);
			cd2 = NarrativeUtil.describe(octx, character2, true, true, false);
			id1 = NarrativeUtil.describeInteraction(interaction);
		}
		else {
			String vprompt = "Given the following scene and JSON representing a character, write an introductory paragraph as if describing a main character in a novel.";
			logger.info("Composing description for " + character1.get(FieldNames.FIELD_FIRST_NAME));
			cd1 = getChatResponse(pcfg, cfg, model, vprompt + System.lineSeparator() + autoSceneInstruct + System.lineSeparator()  + set + System.lineSeparator() + getFilteredCharacter(character1).toFullString());
			
			logger.info("Composing description for " + character2.get(FieldNames.FIELD_FIRST_NAME));
			cd2 = getChatResponse(pcfg, cfg, model, vprompt + System.lineSeparator() + autoSceneInstruct + System.lineSeparator()  + set + System.lineSeparator() + getFilteredCharacter(character2).toFullString());

			logger.info("Composing description for interaction");
			String iprompt = "Given the following character descriptions, setting, and JSON representing an interaction between the two characters, write an paragraph describing the event as if taken out of a novel.";
			id1 = getChatResponse(pcfg, cfg, model, iprompt + System.lineSeparator() + autoSceneInstruct + System.lineSeparator()  + set + System.lineSeparator() + cd1 + System.lineSeparator() + cd2 + System.lineSeparator() + getFilteredInteraction(interaction).toFullString() + System.lineSeparator() + nlpp);
		}
		
		BaseRecord nar1 = cfg.get("systemNarrative");
		BaseRecord nar2 = cfg.get("userNarrative");
		
		if(nar1 == null) {
			 nar1 = NarrativeUtil.getNarrative(octx, character1, setting);
			 IOSystem.getActiveContext().getRecordUtil().createRecord(nar1);
			 cfg.setValue("systemNarrative", nar1);
		}
		else {
			nar1.setQValue("outfitDescription", NarrativeUtil.describeOutfit(sysProf));
		}
		if(nar2 == null) {
			 nar2 = NarrativeUtil.getNarrative(octx, character2, setting);
			 IOSystem.getActiveContext().getRecordUtil().createRecord(nar2);
			 cfg.setValue("userNarrative", nar2);
		}
		else {
			nar2.setQValue("outfitDescription", NarrativeUtil.describeOutfit(usrProf));
		}

		nar1.setQValue("sceneDescription", cd1);
		nar2.setQValue("sceneDescription", cd2);
		nar1.setQValue("interactionDescription", id1);
		nar2.setQValue("interactionDescription", id1);

		String prompt = autoScenePrompt + System.lineSeparator() + autoSceneInstruct + System.lineSeparator() + nlpp + System.lineSeparator() + cd1 + System.lineSeparator() + cd2 + System.lineSeparator() + comp + System.lineSeparator() + "Setting: " + set + System.lineSeparator() + "Scene: " + id1;
		logger.info("Composing description for scene");
		//logger.info(prompt);
		cfg.setValue("scene", getChatResponse(pcfg, cfg, model, prompt));
		if(RecordUtil.isIdentityRecord(cfg)) {
			Queue.queueUpdate(cfg, new String[] {"scene", "systemNarrative", "userNarrative"});
		}
		Queue.processQueue();
		
	}
	
	private static String getChatResponse(BaseRecord pcfg, BaseRecord cfg, String model, String prompt) {
		OpenAIRequest req = new OpenAIRequest();
		// req.setOptions(Chat.getChatOptions());
		req.setModel(model);
		req.setStream(false);
		String sysPrompt = "You are are a helpful assistant who likes to create creative summaries of content.";
		String asPrompt = "Please provide your content now";
		String uPrompt = "I'm ready.";
		if(pcfg != null) {
			sysPrompt = PromptUtil.getSystemNarrateTemplate(pcfg, cfg);
			asPrompt = PromptUtil.getAssistantNarrateTemplate(pcfg, cfg);
			uPrompt = PromptUtil.getUserNarrateTemplate(pcfg, cfg);
		}
		// req.setSystem(sysPrompt);
		
		Chat c = new Chat();
		c.newMessage(req, uPrompt, "user");
		c.newMessage(req, asPrompt, "assistant");

		c.newMessage(req, prompt);
		OpenAIResponse rep = c.chat(req);
		// logger.info(JSONUtil.exportObject(rep));
		if(rep != null && rep.getMessage() != null) {
			return rep.getMessage().getContent();
		}
		return null;
	}
	
	public static boolean saveSession(BaseRecord user, OpenAIRequest req) {
		return (IOSystem.getActiveContext().getAccessPoint().update(user, req) != null);
	}
	
	public static OpenAIRequest getChatSession(BaseRecord user, String name, BaseRecord cfg, BaseRecord pcfg) {
		BaseRecord creq = getChatRequest(user, name, cfg, pcfg);
		if (creq == null) {
			logger.warn("Chat request was null for " + name);
			return null;
		}
		if (creq.get("session") == null) {
			logger.warn("LLM Request was null for " + name);
			return null;
		}
		// logger.info("Return full record for " + ((BaseRecord)creq.get("session")).toFullString());
		return new OpenAIRequest(OlioUtil.getFullRecord(creq.get("session"), false));
	}
	public static OpenAIRequest getCreateChatSession(BaseRecord user, String name, BaseRecord cfg, BaseRecord pcfg) {
		BaseRecord creq = getCreateChatRequest(user, name, cfg, pcfg);
		if (creq == null) {
			logger.warn("Chat request was null for " + name);
			return null;
		}
		
		if (creq.get("session") == null) {
			logger.warn("LLM Request was null for " + name);
			return null;
		}
		return new OpenAIRequest(OlioUtil.getFullRecord(creq.get("session"), false));
	}
	public static BaseRecord getChatRequest(BaseRecord user, String name, BaseRecord cfg, BaseRecord pcfg) {
		return getCreateChatRequest(user, name, cfg, pcfg, false);
	}
	public static BaseRecord getCreateChatRequest(BaseRecord user, String name, BaseRecord cfg, BaseRecord pcfg) {
		return getCreateChatRequest(user, name, cfg, pcfg, true);
	}
	private static BaseRecord getCreateChatRequest(BaseRecord user, String name, BaseRecord cfg, BaseRecord pcfg, boolean create) {
		BaseRecord ocreq = null;
		BaseRecord creq = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_REQUEST, name, "~/ChatRequests", false);
		if(creq != null) {
			return creq;
		}
		if (!create) {
			return null;
		}
		
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatRequests");
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			creq = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_REQUEST, user, null, plist);
			creq.set("chatConfig", cfg);
			creq.set("promptConfig", pcfg);
			
			BaseRecord chatConfig = OlioUtil.getFullRecord(cfg);
			BaseRecord promptConfig = OlioUtil.getFullRecord(pcfg);
			
			Chat chat = new Chat(user, chatConfig, promptConfig);
			OpenAIRequest req = chat.getChatPrompt();
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, req, name, "~/ChatRequests", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			BaseRecord oreq = IOSystem.getActiveContext().getAccessPoint().create(user, req);
			creq.set("sessionType", oreq.getSchema());
			creq.set("session", oreq);
			ocreq = IOSystem.getActiveContext().getAccessPoint().create(user, creq);
		} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
			logger.error(e);
		}
		return ocreq;

	}


	/// TODO: DEPRECATE THIS
	public static BaseRecord getCreateChatConfig(BaseRecord user, String name) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		//q.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID}));
		OlioUtil.planMost(q);
		OlioUtil.limitSubplanFields(q.plan(), OlioModelNames.MODEL_CHAT_CONFIG, "event");
		List<String> req = q.getRequest();
		if(!req.contains(OlioFieldNames.FIELD_INTERACTIONS)) {
			req.add(OlioFieldNames.FIELD_INTERACTIONS);
		}
		// q.setValue("debug", true);

		BaseRecord dat = IOSystem.getActiveContext().getSearch().findRecord(q);
		
		if(dat == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);
			try {
				dat = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			} catch (FactoryException e) {
				logger.error(e);
			}

			dat = IOSystem.getActiveContext().getAccessPoint().create(user, dat);
		}

		else {
			//IOSystem.getActiveContext().getReader().populate(dat, new String[] {OlioFieldNames.FIELD_INTERACTIONS}, 2);
			//List<BaseRecord> inter = dat.get(OlioFieldNames.FIELD_INTERACTIONS);
			//logger.info("Inters: " + inter.size());

		}

		return dat;
	}
	public static BaseRecord getDefaultPrompt() {
		return JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/llm/prompt.config.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}
	public static BaseRecord getCreatePromptConfig(BaseRecord user, String name) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID}));
		q.planMost(false);
		BaseRecord dat = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(dat == null) {
			BaseRecord template = getDefaultPrompt();
			dat = newPromptConfig(user, name, template);
			dat = IOSystem.getActiveContext().getAccessPoint().create(user, dat);
		}
		return dat;
	}
	protected static BaseRecord newPromptConfig(BaseRecord user, String name, BaseRecord template) {
		BaseRecord rec = null;
		boolean error = false;
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			rec = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, template, plist);
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		return rec;
	}
	
	public static BaseRecord getFilteredCharacter(BaseRecord chara) {
		return characterQuery.plan().filterRecord(chara, true);
	}
	
	public static BaseRecord getFilteredInteraction(BaseRecord inter) {
		BaseRecord iinter = interactionQuery.plan().filterRecord(inter, true);
		/// TODO: QueryPlan currently won't work with $flex field types, so the actor and interactor names need to be added back
		///
		iinter.setValue("actor", ((BaseRecord)inter.get("actor")).copyRecord(new String[] {FieldNames.FIELD_FIRST_NAME}));
		iinter.setValue("interactor", ((BaseRecord)inter.get("interactor")).copyRecord(new String[] {FieldNames.FIELD_FIRST_NAME}));
		return iinter;
	}
	
	/// TODO: QueryPlan currently won't work with $flex field types
	///
	private static Query getInteractionExportQuery() {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION);
		q.plan(false);
		q.planField(OlioFieldNames.FIELD_ACTOR, new String[] {FieldNames.FIELD_FIRST_NAME});
		q.planField(OlioFieldNames.FIELD_ACTOR_ALIGNMENT);
		q.planField(FieldNames.FIELD_TYPE);
		q.planField("actorOutcome");
		q.planField("actorReason");
		q.planField("actorRole");
		q.planField("actorThreat");
		q.planField(OlioFieldNames.FIELD_INTERACTOR, new String[] {FieldNames.FIELD_FIRST_NAME});
		q.planField(OlioFieldNames.FIELD_INTERACTOR_ALIGNMENT);
		q.planField("interactorOutcome");
		q.planField("interactorReason");
		q.planField("interactorRole");
		q.planField("interactorThreat");
		return q;
	}
	
	private static Query getCharacterExportQuery() {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
		q.plan(false);
		q.planField(FieldNames.FIELD_FIRST_NAME);
		q.planField(FieldNames.FIELD_MIDDLE_NAME);
		q.planField(FieldNames.FIELD_LAST_NAME);
		q.planField(FieldNames.FIELD_AGE);
		q.planField(FieldNames.FIELD_GENDER);
		q.planField(FieldNames.FIELD_ALIGNMENT);
		q.planField(OlioFieldNames.FIELD_TRADES);
		q.planField(OlioFieldNames.FIELD_EYE_COLOR, new String[] {FieldNames.FIELD_NAME});
		q.planField(OlioFieldNames.FIELD_HAIR_STYLE);
		q.planField(OlioFieldNames.FIELD_RACE);
		q.planField(OlioFieldNames.FIELD_ETHNICITY);
		q.planField(OlioFieldNames.FIELD_HAIR_COLOR, new String[] {FieldNames.FIELD_NAME});
		QueryPlan cqp = q.planField(FieldNames.FIELD_STORE, new String[] {OlioFieldNames.FIELD_APPAREL}, false);
		QueryPlan cqp2 = cqp.plan(OlioFieldNames.FIELD_APPAREL, new String[] {OlioFieldNames.FIELD_WEARABLES});
		QueryPlan cqp3 = cqp2.plan(OlioFieldNames.FIELD_WEARABLES, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_FABRIC});
		cqp3.plan(OlioFieldNames.FIELD_COLOR, new String[] {FieldNames.FIELD_NAME});
		
		q.planField(OlioFieldNames.FIELD_STATISTICS, new String[] {
			OlioFieldNames.FIELD_ATHLETICISM,
			OlioFieldNames.FIELD_CHARISMA,
			OlioFieldNames.FIELD_AGILITY,
			OlioFieldNames.FIELD_SPEED,
			OlioFieldNames.FIELD_MENTAL_STRENGTH,
			OlioFieldNames.FIELD_CREATIVITY,
			OlioFieldNames.FIELD_SPIRITUALITY,
			OlioFieldNames.FIELD_INTELLIGENCE,
			OlioFieldNames.FIELD_MAGIC,
			OlioFieldNames.FIELD_PHYSICAL_STRENGTH,
			OlioFieldNames.FIELD_WISDOM,
			"wit",
			"beauty",
			"charm"
		}, false);
		
		q.planField(FieldNames.FIELD_PERSONALITY, new String[] {
			OlioFieldNames.FIELD_SLOAN_KEY,
			OlioFieldNames.FIELD_SLOAN_CARDINAL,
			OlioFieldNames.FIELD_MBTI_KEY,
			OlioFieldNames.FIELD_DARK_TETRAD_KEY,
			OlioFieldNames.FIELD_AGREEABLENESS,
			OlioFieldNames.FIELD_CONSCIENTIOUSNESS,
			OlioFieldNames.FIELD_EXTRAVERSION,
			OlioFieldNames.FIELD_MACHIAVELLIANISM,
			OlioFieldNames.FIELD_OPENNESS,
			OlioFieldNames.FIELD_PSYCHOPATHY
		}, false);
		
	
		return q;
	}
	
	public static String getAutoOutfit(BaseRecord promptConfig, BaseRecord character, String model, String setting) {

		String outfit = ((List<String>)promptConfig.get("outfit")).stream().collect(Collectors.joining(System.lineSeparator()));
		String userOutfit = ((List<String>)promptConfig.get("userOutfit")).stream().collect(Collectors.joining(System.lineSeparator()));
		String assistantOutfit = ((List<String>)promptConfig.get("assistantOutfit")).stream().collect(Collectors.joining(System.lineSeparator()));
				
		if(outfit == null || outfit.length() == 0) {
			logger.warn("Outfit prompt is not defined");
			return null;
		}
		if(userOutfit == null || userOutfit.length() == 0) {
			userOutfit = "A 32 year old woman on her wedding day, somewhere in the MidWest, US, circa 1995.";
		}
		if(assistantOutfit == null || assistantOutfit.length() == 0) {
			assistantOutfit = "clothing:wedding dress:6:f:torso+hip+leg,clothing:bra:4:f:torso,clothing:high heels:6:f:foot,clothing:pantyhose:5:f:hip+leg,clothing:panties:4:f:hip,jewelry:piercing:7:f:ear";
		}
		
		String ujobDesc = "";
		List<String> utrades = character.get(OlioFieldNames.FIELD_TRADES);
		if(utrades.size() > 0) {
			ujobDesc =" " + utrades.get(0).toLowerCase();
		}
		String prompt = "A " + character.get(FieldNames.FIELD_AGE) + " year-old " + NarrativeUtil.getRaceDescription(character.get(OlioFieldNames.FIELD_RACE)) + " " + character.get(FieldNames.FIELD_GENDER) + ujobDesc + ". Setting: " + setting;
	
		OpenAIRequest req = new OpenAIRequest();

		/*
		OllamaOptions opts = Chat.getChatOptions();
		opts.setTemperature(0.4);
		opts.setTopK(25);
		opts.setTopP(0.9);
		opts.setRepeatPenalty(1.18);
		*/
		// req.setOptions(opts);
		req.setModel(model);
		req.setStream(false);
		
		// req.setSystem(outfit);
		
		Chat c = new Chat();
		c.newMessage(req, userOutfit);
		c.newMessage(req, assistantOutfit, "assistant");
		c.newMessage(req, prompt);
		// logger.info(JSONUtil.exportObject(req));
		OpenAIResponse rep = c.chat(req);
		String gender = character.get(FieldNames.FIELD_GENDER);
		if(rep != null && rep.getMessage() != null) {
			return Arrays.asList(
				rep.getMessage().getContent().split(",")
			)
			.stream().filter(s -> s.startsWith("clothing") || s.startsWith("jewelry"))
			.map(s -> {
				String os = s;
				if(gender.equals("female")) {
					os = s.replaceAll(":m:", ":f:");
				}
				else if(gender.equals("male")) {
					os = s.replaceAll(":f:", ":m:");
				}
				return os;

			})
			.collect(Collectors.joining(",")
			);
			
		}
		return null;
		
	}
	
	public static void applyTags(BaseRecord user, BaseRecord chatConfig, BaseRecord session) {
		BaseRecord userChar = chatConfig.get("userCharacter");
		BaseRecord systemChar = chatConfig.get("systemCharacter");
		
		if(userChar != null) {
			DocumentUtil.applyTag(user, userChar.get(FieldNames.FIELD_NAME), userChar.getSchema(), userChar, true);
			if(session != null) {
				DocumentUtil.applyTag(user, userChar.get(FieldNames.FIELD_NAME), userChar.getSchema(), session, true);
			}
		}
		
		if(systemChar != null) {
			DocumentUtil.applyTag(user, systemChar.get(FieldNames.FIELD_NAME), systemChar.getSchema(), systemChar, true);
			if(session != null) {
				DocumentUtil.applyTag(user, systemChar.get(FieldNames.FIELD_NAME), systemChar.getSchema(), session, true);
			}
		}
	}

	public static String getFilteredCitationText(OpenAIRequest req, BaseRecord storeChunk, String type) {
		String cit = getCitationText(storeChunk, type, true);
		if(cit == null) {
			logger.warn("Citation is null");
			return null;
		}
		List<OpenAIMessage> msgs = req.getMessages().stream().filter(m -> {
			return m.getContent() != null && m.getContent().contains(cit);
		}).collect(Collectors.toList());
		
		String citation = null;
		if(msgs.size() == 0) {
			citation = cit;
		}
		return citation;
	}
	public static String getCitationText(BaseRecord storeChunk, String type, boolean includeMeta) {
		BaseRecord chunk = storeChunk;
		String cnt = storeChunk.get("content");
		if(cnt == null || cnt.length() == 0) {
			return null;
		}
		String chapterTitle = "";
		int chapter = 0;
		if(storeChunk.inherits(ModelNames.MODEL_VECTOR_MODEL_STORE)) {
			if(cnt.startsWith("{")) {
				chunk = RecordFactory.importRecord(ModelNames.MODEL_VECTOR_CHUNK, cnt);
				cnt = chunk.get("content");
				chapterTitle = chunk.get("chapterTitle");
				chapter = chunk.get("chapter");
				if(chapterTitle == null) chapterTitle = "";
			}
		}
		String name = "";
		if(storeChunk.hasField(FieldNames.FIELD_NAME)) {
			name = chunk.get(FieldNames.FIELD_NAME);
		}
		String citationKey = "<citation schema=\"" + storeChunk.get(FieldNames.FIELD_VECTOR_REFERENCE_TYPE) + "\" chapter = \"" + chapter + "\" chapterTitle = \"" + chapterTitle + "\" name = \"" + name + "\" type = \"" + type + "\" chunk = \"" + chunk.get("chunk") + "\" id = \"" + storeChunk.get(FieldNames.FIELD_VECTOR_REFERENCE + "." + FieldNames.FIELD_ID) + "\">";
		String citationSuff = "</citation>";
		return (includeMeta ? citationKey + System.lineSeparator() : "") + cnt + System.lineSeparator() + (includeMeta ? citationSuff : "");
	}
	
	private static String notePath = "~/Notes/Summaries";
	private static String summarySuffix = " - Summary";
	public static BaseRecord getSummary(BaseRecord user, BaseRecord ref) {

		String name = ref.get(FieldNames.FIELD_NAME) + summarySuffix;
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, ref.get(FieldNames.FIELD_GROUP_ID));
		q.field(FieldNames.FIELD_NAME, name);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
		
	}

	public static BaseRecord createSummary(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, BaseRecord ref, boolean recreate) {
		return createSummary(user, chatConfig, promptConfig, ref, ChunkEnumType.WORD, 500, recreate, false);
	}
	
	public static BaseRecord createSummary(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, BaseRecord ref, ChunkEnumType chunkType, int chunkCount, boolean recreate, boolean remote) {
		logger.info("Creating summary ...");
		String setName = ref.get(FieldNames.FIELD_NAME) + " - Summary Set";
		String summName = ref.get(FieldNames.FIELD_NAME) + summarySuffix;
		// ref.get(FieldNames.FIELD_GROUP_PATH)
		BaseRecord summSet = DocumentUtil.getNote(user, setName, notePath);
		BaseRecord summFin = DocumentUtil.getNote(user, summName, notePath);
		if(summSet != null) {
			if(recreate) {
				IOSystem.getActiveContext().getAccessPoint().delete(user, summSet);
			}
			/*
			else {
				return summSet;
			}
			*/
		}
		if(summFin != null) {
			if(recreate) {
				IOSystem.getActiveContext().getAccessPoint().delete(user, summFin);
			}
			else {
				return summFin;
			}
		}
		
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		logger.info("Vectorizing reference ...");
		try {
			List<BaseRecord> chunks = vu.createVectorStore(ref, chunkType, chunkCount);
			if(chunks.size() == 0) {
				logger.warn("Failed to create summary vector store");
			}
			else {
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
			}
		}
		catch (FieldException | WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
		logger.info("Composing summary...");
		
		List<String> summaries = composeSummary(user, chatConfig, promptConfig, ref, remote);
		if(summaries.size() == 0) {
			logger.error("Invalid summary data");
			return null;
		}
		
		summSet = DocumentUtil.getCreateNote(user, setName, notePath, summaries.stream().collect(Collectors.joining(System.lineSeparator())));
		String lastSumm = "Summary of " + ref.get(FieldNames.FIELD_NAME) + System.lineSeparator() + summaries.get(summaries.size() - 1);
		summFin = DocumentUtil.getCreateNote(user, summName, notePath, lastSumm);
		DocumentUtil.applyTag(user, "Summary", summFin.getSchema(), summFin, true);
		
		
		logger.info("Vectorizing summary...");
		try {
			List<BaseRecord> chunks = vu.createVectorStore(summSet, chunkType, chunkCount);
			if(chunks.size() == 0) {
				logger.warn("Failed to create summary set vector store");
			}
			else {
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
			}
			
			chunks = vu.createVectorStore(summFin, chunkType, chunkCount);
			if(chunks.size() == 0) {
				logger.warn("Failed to create summary vector store");
			}
			else {
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
			}

			
			/// Vectorize the last summary and associate with the reference
			chunks = vu.createVectorStore(ref, lastSumm, chunkType, chunkCount);
			if (chunks.size() == 0) {
				logger.warn("Failed to create summary vector reference store");
			}
			else {
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
			}
			
		} catch (FieldException | WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return summFin;
	}
	
	public static List<String> composeSummary(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, BaseRecord ref) {
		return composeSummary(user, chatConfig, promptConfig, ref, false);
	}

	private static String summarizeUserCommand = "Create a summary for the following using 300 words or less:" + System.lineSeparator();
	
	public static List<String> composeSummary(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, BaseRecord ref, boolean remote) {
		int iter = 0;
		int max = 100;
		int minLength = 300;
		List<String> summaries = new ArrayList<>();

		try {
			VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
			//List<BaseRecord> store = vu.getVectorStore(ref);
			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE, ref.copyRecord(new String[] {FieldNames.FIELD_ID}));
			q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, ref.getSchema());
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_VECTOR_REFERENCE, FieldNames.FIELD_VECTOR_REFERENCE_TYPE, FieldNames.FIELD_CHUNK, FieldNames.FIELD_CHUNK_COUNT, FieldNames.FIELD_CONTENT});
			q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CHUNK);
			List<BaseRecord> store = new ArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().find(q).getResults()));
			
			Set<Long> chatIds = new HashSet<>();
			if(ref.getSchema().equals(OlioModelNames.MODEL_CHAR_PERSON)) {
				BaseRecord tag = DocumentUtil.getCreateTag(user, ref.get(FieldNames.FIELD_NAME), ref.getSchema());
				List<BaseRecord> chats = vu.findByTag(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY, new BaseRecord[] {tag});
				chatIds = chats.stream().map(c -> (long)c.get(FieldNames.FIELD_ID)).collect(Collectors.toSet());
				store.addAll(chats);
			}
			
			
			Chat chat = null;
			if(chatConfig != null && promptConfig != null) {
				chat = new Chat(user, chatConfig, promptConfig);
				chat.setDeferRemote(remote);
			}

			StringBuilder contentBuffer = new StringBuilder();
			EmbeddingUtil eu = IOSystem.getActiveContext().getVectorUtil().getEmbedUtil();
			
			for(int i = 0; i < store.size(); i++) {
				BaseRecord v = store.get(i);
				long id = v.get(FieldNames.FIELD_ID);
				String cit = getCitationText(v, "chunk", false);

				if(cit != null || cit.length() > 0) {
					if(chatIds.contains(id)) {
						cit = "<chat>" + System.lineSeparator() + cit + System.lineSeparator() + "</chat>";
					}
					contentBuffer.append(cit + System.lineSeparator());
				}
				if(i < (store.size() - 1) && cit.length() < minLength) {
					continue;
				}

				logger.info("Summarizing chunk #" + (iter + 1) + " of " + store.size());
				String summ = null;
				String prevSum = "";
				if(chat == null) {
					if (eu.getServiceType() == LLMServiceEnumType.LOCAL) {
						summ = eu.getSummary(contentBuffer.toString());
						// logger.info(contentBuffer.toString());
						logger.info(summ);
					}
					else {
						logger.warn("Remote summarization not currently supported");
					}
				}
				else {
					OpenAIRequest req = chat.getChatPrompt();
					/*
					if(summaries.size() > 0) {
						prevSum = "<previous-summary>" + System.lineSeparator() + summaries.get(summaries.size() - 1) + System.lineSeparator() + "</previous-summary>" + System.lineSeparator();
					}
					*/
					String cmd = summarizeUserCommand + prevSum + contentBuffer.toString();
					chat.newMessage(req, cmd, "user");
	
					OpenAIResponse resp = null;
					if(remote) {
						resp = chat.checkRemote(req, null, false);
					}
					else {
						resp = chat.chat(req);
					}
					if (resp == null || resp.getMessage() == null) {
						logger.warn("Content null or blocked.");
						summ = "Content null or blocked.";
					}
					else {
						summ = resp.getMessage().getContent();
					}
					// logger.info(req.toFullString());
					// logger.info(summ);
				}
				if(summ != null && summ.length() > 0) {
					summaries.add("<summary-chunk chunk=\"" + (i + 1) + "\">" + System.lineSeparator() + summ + System.lineSeparator() + "</summary-chunk>");
				}
				contentBuffer = new StringBuilder();
				iter++;
				if(max > 0 && iter >= max) {
					logger.warn("Maximum summarization requests reached - " + max + " of " + store.size());
					break;
				}
				
			}
		
			if(summaries.size() > 0) {
				logger.info("Completing summarization with " + summaries.size() + " summary chunks");
				if(chatConfig == null) {
					summaries.add(eu.getSummary(summaries.stream().collect(Collectors.joining(System.lineSeparator()))));
				}
				else {
					if (summaries.size() > 1) {
						String userCommand = "Create a summary from the following summaries using 1000 words or less:" + System.lineSeparator() + summaries.stream().collect(Collectors.joining(System.lineSeparator()));
						OpenAIRequest req = chat.getChatPrompt();
						chat.newMessage(req, userCommand, "user");
						OpenAIResponse resp = null;
						if(remote) {
							resp = chat.checkRemote(req, null, false);
						}
						else {
							resp = chat.chat(req);
						}
						summaries.add("<summary>" + System.lineSeparator() + resp.getMessage().getContent() + System.lineSeparator() + "</summary>");
					}
				}
			}
		}
		catch(ProcessingException | ReaderException e) {
			logger.error(e);
		}
		return summaries;
	}
	
	/*
	public static List<String> getDataSummaryChunks(BaseRecord user, OpenAIRequest req, ChatRequest creq) {
		List<String> dataRef = creq.get(FieldNames.FIELD_DATA);
		List<String> dataCit = new ArrayList<>();
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		List<BaseRecord> vects = new ArrayList<>();
		List<BaseRecord> frecs = new ArrayList<>();
		for(String dataR : dataRef) {
			BaseRecord recRef = RecordFactory.importRecord(dataR);
			String objId = recRef.get(FieldNames.FIELD_OBJECT_ID);
			Query rq = QueryUtil.createQuery(recRef.getSchema(), FieldNames.FIELD_OBJECT_ID, objId);
			rq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			rq.planMost(false);
			rq.setValue(FieldNames.FIELD_SORT_FIELD, "chunk");
			rq.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
			BaseRecord frec = IOSystem.getActiveContext().getAccessPoint().find(user, rq);
			if(frec != null) {
				frecs.add(frec);
			}
		}

		for(BaseRecord frec : frecs) {
			vects.addAll(vu.getVectorStore(frec));
		}

		for(BaseRecord vect : vects) {
			String txt = getFilteredCitationText(req, vect, "chunk");
			if(txt != null) {
				dataCit.add(txt);
			}
		}
		return dataCit;
	}
	*/
	
	public static List<String> getDataCitations(BaseRecord user, OpenAIRequest req, ChatRequest creq) {
		List<String> dataRef = creq.get(FieldNames.FIELD_DATA);
		List<String> dataCit = new ArrayList<>();
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		List<BaseRecord> vects = new ArrayList<>();
		String msg = creq.getMessage();
		List<BaseRecord> tags = new ArrayList<>();
		List<BaseRecord> frecs = new ArrayList<>();
		
		// boolean findSummaryNote = false;
		for(String dataR : dataRef) {
			BaseRecord recRef = RecordFactory.importRecord(dataR);
			String objId = recRef.get(FieldNames.FIELD_OBJECT_ID);
			Query rq = QueryUtil.createQuery(recRef.getSchema(), FieldNames.FIELD_OBJECT_ID, objId);
			rq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			rq.planMost(false);
			BaseRecord frec = IOSystem.getActiveContext().getAccessPoint().find(user, rq);
			if(frec != null) {
				if(recRef.getSchema().equals(ModelNames.MODEL_TAG)) {
					tags.add(frec);
					/*
					if (frec.get(FieldNames.FIELD_NAME).equals("Summary")) {
						findSummaryNote = true;
					}
					*/
				}
				else {
					frecs.add(frec);
				}
			}
		}
		if(tags.size() > 0 && frecs.size() == 0) {
			logger.info("Find by tags only");
			vects.addAll(vu.find(null, null, tags.toArray(new BaseRecord[0]), new String[] {OlioModelNames.MODEL_VECTOR_CHAT_HISTORY}, msg, 5, 0.6, false));
			vects.addAll(vu.find(null, null, tags.toArray(new BaseRecord[0]), new String[] {ModelNames.MODEL_VECTOR_MODEL_STORE}, msg, 5, 0.6, false));
		}
		else {
			for(BaseRecord frec : frecs) {
				if(msg != null && msg.length() > 0) {
					//  + (findSummaryNote ? " and include any summary note" : "")
					logger.info("Building citations with " + dataRef.size() + " references of which " + tags.size() + " are tags");
					if(
						frec.getSchema().equals(OlioModelNames.MODEL_CHAR_PERSON)
					) {
						vects.addAll(vu.find(null, null, tags.toArray(new BaseRecord[0]), new String[] {OlioModelNames.MODEL_VECTOR_CHAT_HISTORY}, msg, 5, 0.6, false));
					}
					vects.addAll(vu.find(frec, frec.getSchema(), tags.toArray(new BaseRecord[0]), new String[] {ModelNames.MODEL_VECTOR_MODEL_STORE}, msg, 5, 0.6, false));
					//if(findSummaryNote) {
					if(!frec.getSchema().equals(ModelNames.MODEL_TAG)) {
						String uname = frec.get(FieldNames.FIELD_OBJECT_ID);
						if(frec.hasField(FieldNames.FIELD_NAME)) {
							uname = frec.get(FieldNames.FIELD_NAME);
						}
						BaseRecord summaryNote = DocumentUtil.getNote(user, uname + summarySuffix, notePath);
						if(summaryNote != null) {
							List<BaseRecord> chunks = vu.getVectorStore(summaryNote, new String[] {FieldNames.FIELD_CONTENT, FieldNames.FIELD_CHUNK, FieldNames.FIELD_CHUNK_COUNT, FieldNames.FIELD_VECTOR_REFERENCE, FieldNames.FIELD_VECTOR_REFERENCE_TYPE, FieldNames.FIELD_ID});
							logger.info("Adding " + chunks.size() + " summary notes");
							vects.addAll(chunks);
							//vects.addAll(vu.find(summaryNote, summaryNote.getSchema(), new BaseRecord[0], new String[] {ModelNames.MODEL_VECTOR_MODEL_STORE}, msg, 5, 0.6));
						}
					}
				}
			}
		}
		for(BaseRecord vect : vects) {
			String txt = getFilteredCitationText(req, vect, "chunk");
			if(txt != null) {
				dataCit.add(txt);
			}
		}
		return dataCit;
	}

	public static Set<String> getChatTrack() {
		return chatTrack;
	}

	public static BaseRecord getConfig(BaseRecord user, String modelName, String objectId, String name) {

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord cfg = null;
		if(dir != null) {
			Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			if(name != null) {
				q.field(FieldNames.FIELD_NAME, name);
			}
			if(objectId != null) {
				q.field(FieldNames.FIELD_OBJECT_ID, objectId);
			}
			q.planMost(true);
			cfg = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		}
		return cfg;
	}
	
	public static ChatResponse getChatResponse(BaseRecord user, OpenAIRequest req, ChatRequest creq) {
		if(req == null || creq == null) {
			return null;
		}
		BaseRecord chatConfig = OlioUtil.getFullRecord(creq.getChatConfig());
		ChatResponse rep = new ChatResponse();
		rep.setUid(creq.getUid());
		rep.setModel(chatConfig.get("model"));
		/// Current template structure for chat and rpg defines prompt and initial user message
		/// Skip prompt, and skip initial user comment
		int startIndex = 1;
		/// With assist enabled, an initial assistant message is included, so skip that as well
		if((boolean)chatConfig.get("assist")) {
			startIndex = 3;
		}
		for(int i = startIndex; i < req.getMessages().size(); i++) {
			rep.getMessages().add(req.getMessages().get(i));
		}
		return rep;
	}
	
	public static OpenAIRequest getOpenAIRequest(BaseRecord user, ChatRequest creq) {
		OpenAIRequest req = null;
		Chat chat = null;
		BaseRecord chatConfig = OlioUtil.getFullRecord(creq.getChatConfig());
		BaseRecord promptConfig = OlioUtil.getFullRecord(creq.getPromptConfig());

		if(chatConfig != null && promptConfig != null) {
            BaseRecord vreq = OlioUtil.getFullRecord(creq.get("session"), false);
			if(vreq != null) {
				OpenAIRequest oreq = new OpenAIRequest(vreq);
				req = oreq;
				if (alwaysApplyChatOptions) {
					applyChatOptions(req, chatConfig);
				}
			}
			if(req == null) {
				chat = new Chat(user, chatConfig, promptConfig);
				req = chat.getChatPrompt();
			}


		}
		return req;
	}
	
	public static Chat getChat(BaseRecord user, ChatRequest req, boolean deferRemote) {
		BaseRecord chatConfig = OlioUtil.getFullRecord(req.getChatConfig());
		BaseRecord promptConfig = OlioUtil.getFullRecord(req.getPromptConfig());
		Chat chat = null;
		if(chatConfig != null && promptConfig != null) {
			chat = new Chat(user, chatConfig, promptConfig);
			chat.setDeferRemote(deferRemote);
		}
		return chat;
	}

	public static String getCreateUserPrompt(BaseRecord user, String name) {
		BaseRecord dat = getCreatePromptData(user, name);
		IOSystem.getActiveContext().getReader().populate(dat, new String[] {FieldNames.FIELD_BYTE_STORE});
		return new String((byte[])dat.get(FieldNames.FIELD_BYTE_STORE));
	}
	
	public static BaseRecord getCreatePromptData(BaseRecord user, String name) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord dat = IOSystem.getActiveContext().getRecordUtil().getRecord(user, ModelNames.MODEL_DATA, name, 0L, (long)dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dat == null) {
			dat = newPromptData(user, name, ResourceUtil.getInstance().getResource("olio/llm/chat.config.json"));
			IOSystem.getActiveContext().getRecordUtil().createRecord(dat);
		}
		return dat;
	}
	public static BaseRecord newPromptData(BaseRecord user, String name, String data) {
		BaseRecord rec = null;
		boolean error = false;
		try {
			rec = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, rec, name, "~/chat", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			rec.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			rec.set(FieldNames.FIELD_BYTE_STORE, data.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			logger.error(e);
			
			error = true;
		}
		return rec;
	}

	public static void applyChatOptions(OpenAIRequest req, BaseRecord cfg) {
		try {
			req.setModel(cfg.get("model"));
			BaseRecord opts = null;
			if (cfg != null) {
				opts = cfg.get("chatOptions");
			}
			double temperature = 0.9; 
			double top_p = 0.5;
			double repeat_penalty = 1.3;
			double typical_p = 0.0;
			int num_ctx = 8192;
			if(opts != null) {
				temperature = opts.get("temperature");
				top_p = opts.get("top_p");
				repeat_penalty = opts.get("repeat_penalty");
				typical_p = opts.get("typical_p");
				num_ctx = opts.get("num_ctx");
		    }
			req.set("temperature", temperature);
			req.set("top_p", top_p);
			req.set("frequency_penalty", repeat_penalty);
			req.set("presence_penalty", typical_p);
			req.set("max_tokens", num_ctx);
		}
		catch (ModelNotFoundException | FieldException | ValueException ex) {
			logger.error("Error applying chat options: " + ex.getMessage());
		}
	}
	
	private static List<String> ignoreFields = Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_URN, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ORGANIZATION_PATH);
	public static OpenAIRequest getPrunedRequest(OpenAIRequest inReq) {

		List<String> flds = inReq.getFields().stream().map(f -> f.getName()).filter(f -> !ignoreFields.contains(f)).collect(Collectors.toList());
		OpenAIRequest outReq = OpenAIRequest.importRecord(inReq.copyRecord(flds.toArray(new String[0])).toFullString());
		// outReq.setModel(inReq.getModel());
		// String jbt = PromptUtil.getJailBreakTemplate(promptConfig);
		// boolean useJB = (forceJailbreak || chatConfig != null &&
		// (boolean)chatConfig.get("useJailBreak"));

		// outReq.addMessage(inReq.getMessages().stream().filter(m ->
		// (m.isPruned()==false))
		// .collect(Collectors.toList()));
		outReq.setMessages(
				outReq.getMessages().stream().filter(m -> (m.isPruned() == false)).collect(Collectors.toList()));

		return outReq;
	}
	
	public static List<String> getFormattedChatHistory(OpenAIRequest req, BaseRecord chatConfig, int pruneSkip, boolean full) {
		List<String> buff = new ArrayList<>();
		for (int i = (full ? 0 : (pruneSkip + 2)); i < req.getMessages().size(); i++) {
			OpenAIMessage msg = req.getMessages().get(i);
			String cont = msg.getContent();
			if (cont != null && cont.startsWith("(KeyFrame")) {
				continue;
			}
			String name = null;
			boolean isUser = msg.getRole().equals("user");
			if (chatConfig != null) {
				String parm = "systemCharacter";
				if (isUser)
					parm = "userCharacter";
				name = chatConfig.get(parm + ".firstName");
			}
			String charPos = "#1";
			if (msg.getRole().equals("user")) {
				charPos = "#2";
			}
			buff.add("(" + charPos + (name != null ? " " + name : "") + "): " + cont);
		}
		return buff;
	}

	
}
