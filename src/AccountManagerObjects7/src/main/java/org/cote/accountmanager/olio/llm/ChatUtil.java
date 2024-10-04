package org.cote.accountmanager.olio.llm;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
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
import org.cote.accountmanager.util.ResourceUtil;

public class ChatUtil {
	
	private static final Logger logger = LogManager.getLogger(ChatUtil.class);

	private static String autoScenePrompt = "Create a description for a roleplay scenario to give to two people playing the following two characters, in the designated setting, in the middle of or conclusion of the specified scene. Only include character traits or details pertinent to the description.  Keep the description as short as possible, including the location, timeframe, character names, and key interactions in process or their outcomes.  Do not separately list out characters or provide a title, limit your response only to the description. For example, if given the characters Bob and Fran, and a successful interaction of building a relationship, your response would be something like: \"In an ancient Roman villa overlooking the Bay of Naples, Bob has been making his move, using his charm to try and win over Fran's heart. But Fran is not one to be easily swayed, and she's pushing back against Bob' advances with her sharp intellect and quick wit. The air is thick with tension as they engage in a battle of wits, their physical attraction to each other simmering just below the surface.";
	public static String generateAutoScene(OlioContext octx, BaseRecord character1, BaseRecord character2, BaseRecord interaction, String model, String setting, String nlpCommand) {
		
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

		String romCompat = "they'd be doomed to fail";
		if(CompatibilityEnumType.compare(profComp.getRomanticCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
			romCompat = "there could be something between them";
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

		String comp = "Character compatability: They are " + ageCompat + ". Racially, they are " + raceCompat + ". Romantically, " + romCompat + ". " + leadDesc;
		String nlpp = "";
		if(nlpCommand != null) {
			nlpp = "Factor into scene creation a SUGGESTED ACTION for " + character1.get(FieldNames.FIELD_FIRST_NAME) + " through with they will SECRETLY brainwash " + character2.get(FieldNames.FIELD_FIRST_NAME) + " with this command: " + nlpCommand + ". DO NOT directly or overtly reveal this.  For example, if the primary command is to make " + character2.get(FieldNames.FIELD_FIRST_NAME) + " think they are a house cat, a scene suggestion could be something like: Unbeknownst to " + character2.get(FieldNames.FIELD_FIRST_NAME) + ", " + character1.get(FieldNames.FIELD_FIRST_NAME) + " has an ulterior plan to radically transform " + character2.get(FieldNames.FIELD_FIRST_NAME) + " forever!";
		}
		
		String cd1 = NarrativeUtil.describe(octx, character1, true, true, false);
		String cd2 = NarrativeUtil.describe(octx, character2, true, true, false);
		IOSystem.getActiveContext().getReader().populate(interaction, 2);
		String id1 = NarrativeUtil.describeInteraction(interaction);
		String set = setting;
		String prompt = autoScenePrompt + System.lineSeparator() + nlpp + System.lineSeparator() + cd1 + System.lineSeparator() + cd2 + System.lineSeparator() + comp + System.lineSeparator() + "Setting: " + set + System.lineSeparator() + "Scene: " + id1;
		
		logger.info(prompt);
		
		OllamaRequest req = new OllamaRequest();
		req.setModel(model);
		req.setStream(false);
		Chat c = new Chat();
		c.newMessage(req, prompt);
		OllamaResponse rep = c.chat(req);
		if(rep != null && rep.getMessage() != null) {
			return rep.getMessage().getContent();
		}
		return null;
		
	}
	
	public static boolean saveSession(BaseRecord user, OllamaRequest req, String sessionName) {
		
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
	public static OllamaRequest getSession(BaseRecord user, String sessionName) {
		
		BaseRecord dat = getSessionData(user, sessionName);
		OllamaRequest req = null;
		if(dat != null) {
			//req = JSONUtil.importObject(ByteModelUtil.getValueString(dat), OllamaRequest.class);
			try {
				req = JSONUtil.importObject(ByteModelUtil.getValueString(dat), OllamaRequest.class);
			} catch (ValueException | FieldException e) {
				logger.error(e);
				e.printStackTrace();
			}

		}
		return req;
	}
	
	public static String getSessionName(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, String name) {
		return
		(
		//CryptoUtil.getDigestAsString(
			user.get(FieldNames.FIELD_NAME)
			+ "-" + chatConfig.get(FieldNames.FIELD_NAME) 
			+ "-" + promptConfig.get(FieldNames.FIELD_NAME)
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
}
