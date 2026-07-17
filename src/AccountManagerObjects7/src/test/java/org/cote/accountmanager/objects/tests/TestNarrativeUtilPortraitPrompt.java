package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.junit.Test;

/**
 * Focused unit test for {@link NarrativeUtil#buildPortraitPromptFromExtractedData(String, Map)}.
 *
 * Root cause under test: LLM extraction pipelines frequently emit the literal string "null"
 * (or similar placeholder tokens) for fields they could not determine, rather than omitting the
 * key or returning a real blank string. The pre-fix guards (`x != null && !x.isBlank()`) do not
 * catch this because the literal string "null" is neither null nor blank, so it was appended
 * verbatim into the generated SDXL prompt (e.g. "a null null null woman with ... and null eyes").
 *
 * This test is a pure unit test (no DB/IOSystem required) since the method under test is a
 * static, side-effect-free string builder.
 */
public class TestNarrativeUtilPortraitPrompt {

    /** Literal "null" strings (as an LLM would emit them) must be treated as blank/absent. */
    @Test
    public void TestLiteralNullStringsAreSuppressed() {
        Map<String, Object> charData = new HashMap<>();
        charData.put("gender", "female");
        charData.put("age_approx", "null");
        charData.put("outfit_notes", "null");
        Map<String, Object> phys = new HashMap<>();
        phys.put("build", "null");
        phys.put("hair", "short-cropped auburn");
        phys.put("eyes", "null");
        phys.put("skin", "null");
        charData.put("physical", phys);

        String prompt = NarrativeUtil.buildPortraitPromptFromExtractedData("TestChar1", charData);
        assertNotNull(prompt);

        String lower = prompt.toLowerCase();
        assertFalse("Prompt must not contain the literal placeholder token 'null' anywhere: " + prompt,
                lower.matches("(?s).*\\bnull\\b.*"));
        assertFalse("Prompt must not contain doubled/empty punctuation artifacts like '.. ': " + prompt,
                prompt.contains(".. "));
        assertFalse("Prompt must not contain doubled parens from a suppressed field: " + prompt,
                prompt.contains("(( )"));

        // outfit_notes was itself the literal placeholder token "null", so it must hit the real
        // fallback text rather than being appended verbatim.
        assertTrue("Placeholder outfit_notes must hit the real fallback text: " + prompt,
                lower.contains("fully clothed in appropriate attire"));
        assertTrue("Hair (a genuinely meaningful field) must still be present", lower.contains("auburn"));
        assertTrue("Gender-derived label 'woman' must still be present", lower.contains("woman"));
    }

    /** Genuinely blank/absent fields must still hit the real fallback text (regression guard). */
    @Test
    public void TestGenuinelyBlankFieldsStillHitFallback() {
        Map<String, Object> charData = new HashMap<>();
        charData.put("gender", "methodical and unhurried woman");
        // age_approx, outfit_notes intentionally absent
        Map<String, Object> phys = new HashMap<>();
        // build/hair/eyes/skin intentionally absent
        charData.put("physical", phys);

        String prompt = NarrativeUtil.buildPortraitPromptFromExtractedData("TestChar2", charData);
        assertNotNull(prompt);

        assertTrue("Genuinely absent outfit_notes must hit the real fallback text: " + prompt,
                prompt.contains("fully clothed in appropriate attire"));
        assertFalse("Fallback-covered prompt must not contain the literal token 'null': " + prompt,
                prompt.toLowerCase().matches("(?s).*\\bnull\\b.*"));
    }

    /** Normal case with real, fully-populated values must still work as before. */
    @Test
    public void TestNormalRealValuesStillWork() {
        Map<String, Object> charData = new HashMap<>();
        charData.put("gender", "male");
        charData.put("age_approx", "mid-30s");
        charData.put("outfit_notes", "wearing a tailored grey suit");
        Map<String, Object> phys = new HashMap<>();
        phys.put("build", "athletic");
        phys.put("hair", "close-cropped black hair");
        phys.put("eyes", "brown");
        phys.put("skin", "olive");
        charData.put("physical", phys);

        String prompt = NarrativeUtil.buildPortraitPromptFromExtractedData("TestChar3", charData);
        assertNotNull(prompt);
        String lower = prompt.toLowerCase();

        assertTrue(lower.contains("athletic"));
        assertTrue(lower.contains("mid-30s"));
        assertTrue(lower.contains("olive"));
        assertTrue(lower.contains("black hair"));
        assertTrue(lower.contains("brown"));
        assertTrue(lower.contains("tailored grey suit"));
        assertTrue(lower.contains("man"));
        assertFalse(lower.matches("(?s).*\\bnull\\b.*"));
    }

    /** Other recognized placeholder tokens ("n/a", "none", "unknown", "unspecified") are suppressed too. */
    @Test
    public void TestOtherPlaceholderTokensAreSuppressed() {
        Map<String, Object> charData = new HashMap<>();
        charData.put("gender", "female");
        charData.put("age_approx", "N/A");
        charData.put("outfit_notes", "unknown");
        Map<String, Object> phys = new HashMap<>();
        phys.put("build", "none");
        phys.put("hair", "Unspecified");
        phys.put("eyes", "curly red");
        phys.put("skin", "fair");
        charData.put("physical", phys);

        String prompt = NarrativeUtil.buildPortraitPromptFromExtractedData("TestChar4", charData);
        assertNotNull(prompt);
        String lower = prompt.toLowerCase();

        assertFalse("n/a token must not leak into prompt: " + prompt, lower.contains("n/a"));
        assertFalse("none token must not leak into prompt: " + prompt, lower.contains(" none"));
        assertFalse("unspecified token must not leak into prompt: " + prompt, lower.contains("unspecified"));
        assertTrue("outfit_notes was a placeholder ('unknown') so real fallback text must be used: " + prompt,
                lower.contains("fully clothed in appropriate attire"));
        assertTrue(lower.contains("fair"));
        assertTrue(lower.contains("curly red"));
    }
}
