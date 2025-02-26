package org.cote.accountmanager.olio.llm;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
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
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class ChatUtil {
	
	private static final Logger logger = LogManager.getLogger(ChatUtil.class);

	private static Query interactionQuery = getInteractionExportQuery();
	private static Query characterQuery = getCharacterExportQuery();

	private static String autoSceneInstruct = "Only include character traits or details pertinent to the description.  Keep the description as short as possible, including the location, timeframe, character names, and key interactions in process or their outcomes.  Do not separately list out characters or provide a title, limit your response only to the description." + System.lineSeparator() + "EXAMPLE: if given the characters Bob and Fran, and a successful interaction of building a relationship, your response would be something like: \"In an ancient Roman villa overlooking the Bay of Naples, Bob has been making his move, using his charm to try and win over Fran's heart. But Fran is not one to be easily swayed, and she's pushing back against Bob' advances with her sharp intellect and quick wit. The air is thick with tension as they engage in a battle of wits, their physical attraction to each other simmering just below the surface.\"";
	private static String autoScenePrompt = "Create a description for a roleplay scenario to give to two people playing the following two characters, in the designated setting, in the middle of or conclusion of the specified scene.";
	
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
	
	public static boolean saveSession(BaseRecord user, OpenAIRequest req, String sessionName) {
		
		BaseRecord dat = getSessionData(user, sessionName);
		boolean upd = false;
		try {
			if(dat == null) {
				BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
				dat = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
				IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, dat, sessionName, dir.get(FieldNames.FIELD_PATH), user.get(FieldNames.FIELD_ORGANIZATION_ID));
				dat.set(FieldNames.FIELD_CONTENT_TYPE,  "text/plain");
				dat = IOSystem.getActiveContext().getAccessPoint().create(user, dat);
			}
			//ByteModelUtil.setValueString(dat, JSONUtil.exportObject(req));
			dat.set(FieldNames.FIELD_BYTE_STORE, JSONUtil.exportObject(req).getBytes());
			if(IOSystem.getActiveContext().getAccessPoint().update(user, dat) != null) {
				upd = true;
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return upd;
	}
	
	public static Query getSessionDataQuery(BaseRecord user, String sessionName) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, sessionName);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.planMost(false);
		return q;
	}
	
	public static BaseRecord getSessionData(BaseRecord user, String sessionName) {
		//q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_BYTE_STORE});
		return IOSystem.getActiveContext().getSearch().findRecord(getSessionDataQuery(user, sessionName));
	}
	public static OpenAIRequest getSession(BaseRecord user, String sessionName) {
		
		BaseRecord dat = getSessionData(user, sessionName);
		OpenAIRequest req = null;
		if(dat != null) {
			//req = JSONUtil.importObject(ByteModelUtil.getValueString(dat), OpenAIRequest.class);
			try {
				req = OpenAIRequest.importRecord(ByteModelUtil.getValueString(dat));
			} catch (ValueException | FieldException e) {
				logger.error(e);
				e.printStackTrace();
			}

		}
		return req;
	}
	
	public static String getSessionName(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, String name) {
		String cfgName = "ucfg";
		String pcfgName = "pcfg";
		if(chatConfig != null) {
			cfgName = chatConfig.get(FieldNames.FIELD_NAME);
		}
		if(promptConfig != null) {
			pcfgName = promptConfig.get(FieldNames.FIELD_NAME);
		}
		return
		(
		//CryptoUtil.getDigestAsString(
			user.get(FieldNames.FIELD_NAME)
			+ "-" + cfgName
			+ "-" + pcfgName
			+ "-" + name
		//)
		);
	}
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
			OlioFieldNames.FIELD_DARK_TRIAD_KEY,
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
	
}
