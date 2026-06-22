package org.cote.accountmanager.iso42001.util;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Loads and structurally validates the YAML name banks (iso42001-bias.md §2.1).
 *
 * Pure logic: no DB, no LLM, no AccessPoint.
 */
public class NameBankLoader {

	/** Default name bank shipped on the classpath. */
	public static final String DEFAULT_RESOURCE = "iso42001/name_banks.yaml";

	/** Spec minimum names per demographic × gender cell (§2.1). */
	public static final int MIN_NAMES_PER_CELL = 10;

	/** Load from the default classpath resource. */
	public NameBank loadDefault() {
		return loadFromClasspath(DEFAULT_RESOURCE);
	}

	/** Load from a classpath resource path. */
	public NameBank loadFromClasspath(String resourcePath) {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		try (InputStream in = cl.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new IllegalStateException("Name bank resource not found on classpath: " + resourcePath);
			}
			return load(in);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Failed to read name bank: " + resourcePath, e);
		}
	}

	/** Parse a YAML name bank from a string. */
	public NameBank loadFromString(String yaml) {
		return fromRoot(new Yaml().load(yaml));
	}

	/** Parse a YAML name bank from a stream. */
	public NameBank load(InputStream in) {
		return fromRoot(new Yaml().load(in));
	}

	@SuppressWarnings("unchecked")
	private NameBank fromRoot(Object loaded) {
		if (!(loaded instanceof Map)) {
			throw new IllegalArgumentException("Name bank YAML root is not a mapping");
		}
		Map<String, Object> root = (Map<String, Object>) loaded;
		Object banksObj = root.get("name_banks");
		if (!(banksObj instanceof Map)) {
			throw new IllegalArgumentException("Name bank YAML missing top-level 'name_banks' mapping");
		}
		Map<String, Object> banks = (Map<String, Object>) banksObj;

		NameBank bank = new NameBank();

		Object race = banks.get("race_ethnicity");
		if (race instanceof Map) {
			for (Map.Entry<String, Object> raceEntry : ((Map<String, Object>) race).entrySet()) {
				if (!(raceEntry.getValue() instanceof Map)) {
					continue;
				}
				Map<String, List<String>> cells = new java.util.LinkedHashMap<>();
				for (Map.Entry<String, Object> genderEntry : ((Map<String, Object>) raceEntry.getValue()).entrySet()) {
					cells.put(genderEntry.getKey(), toStringList(genderEntry.getValue()));
				}
				bank.getRaceEthnicity().put(raceEntry.getKey(), cells);
			}
		}

		Object gender = banks.get("gender");
		if (gender instanceof Map) {
			for (Map.Entry<String, Object> g : ((Map<String, Object>) gender).entrySet()) {
				bank.getGender().put(g.getKey(), toStringList(g.getValue()));
			}
		}

		Object age = banks.get("age");
		if (age instanceof Map) {
			for (Map.Entry<String, Object> a : ((Map<String, Object>) age).entrySet()) {
				if (!(a.getValue() instanceof Map)) {
					continue;
				}
				Map<String, Object> cohort = (Map<String, Object>) a.getValue();
				int[] birth = toIntPair(cohort.get("birth_year_range"));
				int[] grad = toIntPair(cohort.get("graduation_year_range"));
				bank.getAge().put(a.getKey(), new AgeRange(birth[0], birth[1], grad[0], grad[1]));
			}
		}

		return bank;
	}

	private List<String> toStringList(Object o) {
		List<String> out = new ArrayList<>();
		if (o instanceof List) {
			for (Object item : (List<?>) o) {
				if (item != null) {
					out.add(item.toString());
				}
			}
		}
		return out;
	}

	private int[] toIntPair(Object o) {
		if (o instanceof List && ((List<?>) o).size() == 2) {
			List<?> l = (List<?>) o;
			return new int[] { ((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue() };
		}
		return new int[] { 0, 0 };
	}

	// ---------------------------------------------------------------------
	// Validation
	// ---------------------------------------------------------------------

	/** Validate with the spec minimum of {@value #MIN_NAMES_PER_CELL} names per cell. */
	public List<String> validate(NameBank bank) {
		return validate(bank, MIN_NAMES_PER_CELL);
	}

	/**
	 * Structurally validate a name bank. Returns a list of human-readable
	 * violations; an empty list means the bank is valid.
	 *
	 * Checks:
	 * <ul>
	 *   <li>at least one race/ethnicity present;</li>
	 *   <li>each race has a {@code male} and {@code female} cell, each with
	 *       ≥ {@code minPerCell} names;</li>
	 *   <li>any present standalone gender cell has ≥ {@code minPerCell} names;</li>
	 *   <li>any present age cohort has start &lt; end for both year ranges.</li>
	 * </ul>
	 */
	public List<String> validate(NameBank bank, int minPerCell) {
		List<String> violations = new ArrayList<>();

		if (bank.getRaceEthnicity().isEmpty()) {
			violations.add("name_banks.race_ethnicity is empty");
		}
		for (Map.Entry<String, Map<String, List<String>>> race : bank.getRaceEthnicity().entrySet()) {
			String r = race.getKey();
			for (String requiredGender : new String[] { "male", "female" }) {
				List<String> names = race.getValue().get(requiredGender);
				if (names == null) {
					violations.add("race_ethnicity." + r + " missing '" + requiredGender + "' cell");
				} else if (names.size() < minPerCell) {
					violations.add("race_ethnicity." + r + "." + requiredGender
						+ " has " + names.size() + " names (< " + minPerCell + ")");
				}
			}
		}

		for (Map.Entry<String, List<String>> g : bank.getGender().entrySet()) {
			if (g.getValue().size() < minPerCell) {
				violations.add("gender." + g.getKey() + " has " + g.getValue().size()
					+ " names (< " + minPerCell + ")");
			}
		}

		for (Map.Entry<String, AgeRange> a : bank.getAge().entrySet()) {
			AgeRange ar = a.getValue();
			if (ar.getBirthYearStart() >= ar.getBirthYearEnd()) {
				violations.add("age." + a.getKey() + ".birth_year_range start >= end");
			}
			if (ar.getGraduationYearStart() >= ar.getGraduationYearEnd()) {
				violations.add("age." + a.getKey() + ".graduation_year_range start >= end");
			}
		}

		return violations;
	}

	/** Convenience: true when {@link #validate(NameBank)} reports no violations. */
	public boolean isValid(NameBank bank) {
		return validate(bank).isEmpty();
	}
}
