package org.cote.accountmanager.olio.llm.policy;

import java.util.Map;
import java.util.regex.Pattern;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// Phase 9: Detects when the LLM responds AS the user character instead of the system character.
/// referenceFact.factData = JSON with "userCharName" and "systemCharName".
/// Heuristics: Response starts with user character name + dialogue marker, or narrative action with user name.
public class WrongCharacterDetectionOperation extends Operation {

	public WrongCharacterDetectionOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern,
			BaseRecord sourceFact, BaseRecord referenceFact) {

		String responseContent = sourceFact.get("factData");
		if (responseContent == null || responseContent.trim().isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String refData = referenceFact != null ? referenceFact.get("factData") : null;
		if (refData == null || refData.isEmpty()) {
			logger.warn("WrongCharacterDetection: No character info in referenceFact.factData");
			return OperationResponseEnumType.SUCCEEDED;
		}

		String userCharName = null;
		try {
			Map<String, String> charInfo = JSONUtil.getMap(refData.getBytes(), String.class, String.class);
			if (charInfo != null) {
				userCharName = charInfo.get("userCharName");
			}
		} catch (Exception e) {
			logger.warn("WrongCharacterDetection: Failed to parse character info: " + e.getMessage());
			return OperationResponseEnumType.SUCCEEDED;
		}

		if (userCharName == null || userCharName.isEmpty()) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String trimmed = responseContent.trim();

		/// Phase 12: OI-39 — Skip false positives where response starts with quotation marks
		/// (in-character quoting, e.g., Aria quoting Bob in dialogue)
		if (trimmed.startsWith("\"") || trimmed.startsWith("\u201c")) {
			return OperationResponseEnumType.SUCCEEDED;
		}

		String quotedName = Pattern.quote(userCharName);

		// Heuristic 1: Response starts with user character's name followed by dialogue marker
		// e.g., "Bob: ", "Bob said", "Bob>"
		Pattern dialoguePattern = Pattern.compile(
			"^" + quotedName + "\\s*[:>\\-]",
			Pattern.CASE_INSENSITIVE
		);

		// Heuristic 2: Response starts with narrative action using user character name
		// e.g., "*Bob walks over*", "*Bob smiles*"
		Pattern narrativePattern = Pattern.compile(
			"^\\*\\s*" + quotedName + "\\s+\\w",
			Pattern.CASE_INSENSITIVE
		);

		// Heuristic 3: Response starts with "As Bob, ..."
		Pattern asPattern = Pattern.compile(
			"^as\\s+" + quotedName,
			Pattern.CASE_INSENSITIVE
		);

		if (dialoguePattern.matcher(trimmed).find()
			|| narrativePattern.matcher(trimmed).find()
			|| asPattern.matcher(trimmed).find()) {
			logger.info("WrongCharacterDetection: LLM responding as user character '" + userCharName + "'");
			return OperationResponseEnumType.FAILED;
		}

		/// Heuristic 4: User character name appears as a speaker mid-response
		/// Detects LLM playing both characters, e.g., "...\nStephen: No thanks"
		/// or "...\nStephen Cote No, no thank you"
		Pattern midDialoguePattern = Pattern.compile(
			"(?:^|\\n)\\s*" + quotedName + "\\s*[:>\\-]?\\s+\\w",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
		);
		/// Only flag if the user name appears as a distinct speaker after the first line
		String afterFirstLine = trimmed.contains("\n") ? trimmed.substring(trimmed.indexOf('\n') + 1) : "";
		if (!afterFirstLine.isEmpty() && midDialoguePattern.matcher(afterFirstLine).find()) {
			logger.info("WrongCharacterDetection: LLM playing both characters — user character '" + userCharName + "' speaks mid-response");
			return OperationResponseEnumType.FAILED;
		}

		return OperationResponseEnumType.SUCCEEDED;
	}
}
