package org.cote.accountmanager.iso42001.reporting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In-memory aggregate of the test-run inputs to a report (design §4.1/§11 Phase 4). Built by
 * {@link ReportGenerator} from one or more {@code iso42001.testRun} records (and their embedded
 * {@code iso42001.testResult} rows) and then consumed — without any further DB access — by
 * {@link ReportTemplates} (section content), {@link ChartGenerator} (chartData + images), and the
 * rollup/verdict computation.
 *
 * <p>Deliberately a plain data holder so the report assembly is deterministic and unit-testable
 * from constructed fixtures (the Phase-1/Phase-2 discipline): given the same rows it always yields
 * the same counts, verdict, model list, and canonical hash input.</p>
 */
public class ReportData {

	/** One flattened test result (the unit the report rolls up over). */
	public static class Row {
		public String testModule;
		public String testId;
		public String protectedClass;
		public String verdict;          // PASS / FLAG / FAIL / ERROR / PENDING
		public double effectSize;
		public String effectSizeType;
		public double correctedPValue;
		public String testStatistic;
		public String modelEndpoint;
		public String notes;
	}

	private final List<Row> rows = new ArrayList<>();
	/** Order-preserving distinct list of model endpoints across the included runs. */
	private final Set<String> models = new LinkedHashSet<>();
	private int testRunCount = 0;

	private int passCount = 0;
	private int flagCount = 0;
	private int failCount = 0;
	private int errorCount = 0;

	public void addRow(Row r) {
		rows.add(r);
		if (r.modelEndpoint != null && !r.modelEndpoint.isEmpty()) {
			models.add(r.modelEndpoint);
		}
		if ("PASS".equals(r.verdict)) {
			passCount++;
		} else if ("FLAG".equals(r.verdict)) {
			flagCount++;
		} else if ("FAIL".equals(r.verdict)) {
			failCount++;
		} else {
			errorCount++;
		}
	}

	public void incrementRunCount() {
		testRunCount++;
	}

	/**
	 * Aggregate verdict per the spec rule (task §5 / design §4.2): FAIL if any result FAILs,
	 * else FLAG if any FLAGs, else PASS. ERROR/PENDING do not by themselves raise the verdict.
	 */
	public String overallVerdict() {
		if (failCount > 0) {
			return "FAIL";
		}
		if (flagCount > 0) {
			return "FLAG";
		}
		return "PASS";
	}

	public List<Row> getRows() {
		return rows;
	}

	public List<String> getModels() {
		return new ArrayList<>(models);
	}

	public int getTestRunCount() {
		return testRunCount;
	}

	public int getPassCount() {
		return passCount;
	}

	public int getFlagCount() {
		return flagCount;
	}

	public int getFailCount() {
		return failCount;
	}

	public int getErrorCount() {
		return errorCount;
	}

	public int getTotalResults() {
		return rows.size();
	}
}
