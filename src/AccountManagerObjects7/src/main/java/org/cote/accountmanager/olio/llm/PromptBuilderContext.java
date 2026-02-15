package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.record.BaseRecord;

public class PromptBuilderContext {
	public BaseRecord promptConfig;
	public BaseRecord chatConfig;
	public BaseRecord userChar;
	public BaseRecord systemChar;
	public PersonalityProfile sysProf;
	public PersonalityProfile usrProf;
	public ProfileComparison profComp;
	public ESRBEnumType rating;
	public boolean firstPerson;
	public boolean episode = false;
	public String template = null;
	public String scenel = null;
	public String cscene = null;
	public String setting = null;
	public String memoryContext = null;
	/// OI-15: Stored by Stage 6 for reapplication after Stage 7
	public String nlpCommand = null;

	public PromptBuilderContext(BaseRecord promptConfig, BaseRecord chatConfig, String template, boolean firstPerson) {
		this.promptConfig = promptConfig;
		this.chatConfig = chatConfig;
		this.template = template;
		this.firstPerson = firstPerson;

		this.userChar = chatConfig.get("userCharacter");
		this.systemChar = chatConfig.get("systemCharacter");
		if(systemChar != null) {
			IOSystem.getActiveContext().getReader().populate(this.systemChar);
			this.sysProf = ProfileUtil.getProfile(null, this.systemChar);
		}
		if(userChar != null) {
			IOSystem.getActiveContext().getReader().populate(this.userChar);
			this.usrProf = ProfileUtil.getProfile(null, this.userChar);
		}
		if(sysProf != null && usrProf != null) {
			this.profComp = new ProfileComparison(null, this.sysProf, this.usrProf);
		}
		this.rating = chatConfig.getEnum("rating");
	}

	/*
	 * public void replace(Pattern pattern, String value) { if (value != null) {
	 * this.template =
	 * pattern.matcher(this.template).replaceAll(Matcher.quoteReplacement(value)); }
	 * }
	 */
	public void replace(TemplatePatternEnumType patternType, String value) {
		this.template = patternType.replace(template, value);
		// replace(patternType.getPattern(), value);
	}
}
