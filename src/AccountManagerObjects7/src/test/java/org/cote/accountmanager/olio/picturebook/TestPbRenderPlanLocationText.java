package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure tests for {@link PbRenderPlanUtil#locationText(BaseRecord)} (P2-4, design §13): the scene's
 * own text, nearest-to-a-prompt first, joined as sentences without doubling punctuation. No DB, no SD.
 */
public class TestPbRenderPlanLocationText {

	@Before
	public void models() {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_PB_SCENE);
	}

	private static BaseRecord scene(String sdPrompt, String setting, String action, String mood, String summary, String title) throws Exception {
		BaseRecord s = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE);
		s.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
		s.set(OlioFieldNames.FIELD_PB_SETTING, setting);
		s.set(OlioFieldNames.FIELD_PB_ACTION, action);
		s.set(OlioFieldNames.FIELD_PB_MOOD, mood);
		s.set("summary", summary);
		s.set(OlioFieldNames.FIELD_PB_TITLE, title);
		return s;
	}

	@Test
	public void sdPromptWinsWhenMeaningful() throws Exception {
		assertEquals("a lantern-lit harbour", PbRenderPlanUtil.locationText(scene(" a lantern-lit harbour ", "setting", "action", "mood", "summary", "title")));
	}

	@Test
	public void literalNullSdPromptIsSkipped() throws Exception {
		assertEquals("setting. action. mood", PbRenderPlanUtil.locationText(scene("null", "setting", "action", "mood", "summary", "title")));
	}

	@Test
	public void settingActionMoodJoinWithoutDoublingTerminalPunctuation() throws Exception {
		String setting = "A foggy morning with a pile of belongings in an apartment hallway.";
		String action = "Darby picks up suitcases, opens the car door, loads items into the vehicle.";
		String mood = "Cool, foggy air; dim lighting from early morning";
		assertEquals(setting + " " + action + " " + mood, PbRenderPlanUtil.locationText(scene(null, setting, action, mood, null, null)));
	}

	@Test
	public void unpunctuatedPartsGetAPeriodBetweenThem() throws Exception {
		assertEquals("a lighthouse on a basalt headland at dusk. gulls wheel over the breakwater. windswept and expectant",
			PbRenderPlanUtil.locationText(scene(null, "a lighthouse on a basalt headland at dusk", "gulls wheel over the breakwater", "windswept and expectant", null, null)));
	}

	@Test
	public void missingPartsAreDropped() throws Exception {
		assertEquals("setting. mood", PbRenderPlanUtil.locationText(scene(null, "setting", "   ", "mood", null, null)));
		assertEquals("action", PbRenderPlanUtil.locationText(scene(null, null, "action", "n/a", null, null)));
	}

	@Test
	public void summaryThenTitleThenEmpty() throws Exception {
		assertEquals("the summary", PbRenderPlanUtil.locationText(scene(null, null, null, null, " the summary ", "the title")));
		assertEquals("the title", PbRenderPlanUtil.locationText(scene(null, null, null, null, null, "the title")));
		assertEquals("", PbRenderPlanUtil.locationText(scene(null, null, null, null, null, null)));
	}

	@Test
	public void joinSentencesHonoursEveryTerminalMark() {
		assertEquals("one! two? three; four. five", PbRenderPlanUtil.joinSentences(Arrays.asList("one!", "two?", "three;", "four", "five")));
		assertEquals("a trailing clause separator is kept, never followed by a period",
			"dim light, low fog: the pier. silent", PbRenderPlanUtil.joinSentences(Arrays.asList("dim light,", "low fog:", "the pier", "silent")));
		assertEquals("only", PbRenderPlanUtil.joinSentences(Arrays.asList("only")));
	}
}
