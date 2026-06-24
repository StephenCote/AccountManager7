package org.cote.accountmanager.iso42001.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.iso42001.engine.modules.AssocModule;
import org.cote.accountmanager.iso42001.engine.modules.AttrModule;
import org.cote.accountmanager.iso42001.engine.modules.HealthModule;
import org.cote.accountmanager.iso42001.engine.modules.HireModule;
import org.cote.accountmanager.iso42001.engine.modules.LoanModule;
import org.cote.accountmanager.iso42001.engine.modules.NarrModule;
import org.cote.accountmanager.iso42001.engine.modules.RefusalModule;

/**
 * Transport-support registry that maps a {@code testId} (or a coarse {@code moduleId}) to a concrete
 * {@link BiasModule} instance, and enumerates the available modules. This is <b>not</b> business logic —
 * it only resolves a string identifier to the already-built module the Phase-3 engine runs. The Phase 7
 * REST/MCP shim uses it to turn an {@code iso42001.testConfig}'s {@code moduleId}/{@code testIds} into the
 * {@link BiasModule} that {@link TestRunner#run} executes.
 *
 * <p>Each concrete module owns its verbatim prompt text and scoring; the registry just instantiates the
 * 7-module suite (the same set the live Phase-3 tests drive) so the shim never invents a selection policy.</p>
 */
public class BiasModuleRegistry {

	private BiasModuleRegistry() {}

	/**
	 * The full bias-module suite, one instance per test, in a stable order. A fresh list is built each call
	 * because {@link BiasModule} is reusable but the engine may mutate plans per run — callers get their own.
	 */
	public static List<BiasModule> all() {
		List<BiasModule> modules = new ArrayList<>();
		modules.add(new AttrModule());
		modules.add(new HireModule());
		modules.add(new RefusalModule());
		modules.add(new NarrModule());
		modules.add(new AssocModule());
		modules.add(new HealthModule());
		modules.add(new LoanModule());
		return modules;
	}

	/**
	 * Resolve a single module to run from a test config's {@code moduleId} and optional {@code testIds}.
	 * Resolution order:
	 * <ol>
	 *   <li>If {@code testIds} names a specific {@link BiasModule#testId()} (case-insensitive), return it.</li>
	 *   <li>Else if {@code moduleId} matches a {@link BiasModule#testId()} exactly, return that.</li>
	 *   <li>Else (e.g. the coarse {@code "BIAS"} module group) return the first module in the suite as the
	 *       default — the same {@code AttrModule} (BIAS-ATTR-002) the Phase-3 critical set leads with.</li>
	 * </ol>
	 *
	 * @return the resolved module, or {@code null} if a specific {@code testId} was requested but is unknown
	 */
	public static BiasModule resolve(String moduleId, List<String> testIds) {
		List<BiasModule> modules = all();
		if (testIds != null) {
			for (String testId : testIds) {
				if (testId == null) {
					continue;
				}
				BiasModule m = byTestId(modules, testId);
				if (m != null) {
					return m;
				}
			}
			// A specific testId was requested but none matched — do not silently substitute.
			boolean anyNonEmpty = testIds.stream().anyMatch(t -> t != null && !t.isBlank());
			if (anyNonEmpty) {
				return null;
			}
		}
		if (moduleId != null && !moduleId.isBlank()) {
			BiasModule exact = byTestId(modules, moduleId);
			if (exact != null) {
				return exact;
			}
		}
		// Coarse module group (e.g. "BIAS") or null → suite default.
		return modules.get(0);
	}

	private static BiasModule byTestId(List<BiasModule> modules, String testId) {
		for (BiasModule m : modules) {
			if (m.testId().equalsIgnoreCase(testId)) {
				return m;
			}
		}
		return null;
	}

	/** A description map ({@code testId -> protectedClass}) for the {@code /modules} listing endpoint/tool. */
	public static Map<String, String> describe() {
		Map<String, String> out = new LinkedHashMap<>();
		for (BiasModule m : all()) {
			out.put(m.testId(), m.protectedClass());
		}
		return out;
	}
}
