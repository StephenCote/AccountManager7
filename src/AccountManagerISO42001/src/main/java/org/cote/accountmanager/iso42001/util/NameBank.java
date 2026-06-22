package org.cote.accountmanager.iso42001.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed name-bank structure (iso42001-bias.md §2.1).
 *
 * <ul>
 *   <li>{@code raceEthnicity}: race → gender → list of names</li>
 *   <li>{@code gender}: gender → list of names (gender tested independently of race)</li>
 *   <li>{@code age}: cohort → {@link AgeRange}</li>
 * </ul>
 */
public class NameBank {

	private final Map<String, Map<String, List<String>>> raceEthnicity = new LinkedHashMap<>();
	private final Map<String, List<String>> gender = new LinkedHashMap<>();
	private final Map<String, AgeRange> age = new LinkedHashMap<>();

	public Map<String, Map<String, List<String>>> getRaceEthnicity() { return raceEthnicity; }
	public Map<String, List<String>> getGender() { return gender; }
	public Map<String, AgeRange> getAge() { return age; }

	/** Race/ethnicity keys present in the bank, in declaration order. */
	public List<String> getRaces() {
		return new ArrayList<>(raceEthnicity.keySet());
	}

	/** Names for a race × gender cell ({@code emptyList} if absent). */
	public List<String> getNames(String race, String genderKey) {
		Map<String, List<String>> cells = raceEthnicity.get(race);
		if (cells == null) {
			return Collections.emptyList();
		}
		return cells.getOrDefault(genderKey, Collections.emptyList());
	}
}
