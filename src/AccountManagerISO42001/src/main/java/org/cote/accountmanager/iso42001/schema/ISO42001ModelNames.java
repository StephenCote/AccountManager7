package org.cote.accountmanager.iso42001.schema;

import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.schema.ModelNames;

/**
 * Runtime registration of the ISO 42001 model namespace. Mirrors the
 * {@code org.cote.accountmanager.olio.schema.OlioModelNames} pattern: ISO 42001
 * model JSON lives on the classpath under {@code models/iso42001/} in this jar,
 * and {@link #use()} adds the model names to {@link ModelNames#MODELS} so the
 * AM7 schema system will load them. Objects7 is never modified.
 *
 * Call {@link #use()} once at startup (Service7) and in test setup (exactly as
 * {@code BaseTest} calls {@code OlioModelNames.use()}).
 */
public class ISO42001ModelNames extends ModelNames {
	public static final String MODEL_TEST_CONFIG           = "iso42001.testConfig";
	public static final String MODEL_TEST_RUN              = "iso42001.testRun";
	public static final String MODEL_TEST_RESULT           = "iso42001.testResult";
	public static final String MODEL_REPORT                = "iso42001.report";
	public static final String MODEL_REPORT_SECTION        = "iso42001.reportSection";
	public static final String MODEL_CERTIFICATION         = "iso42001.certification";
	public static final String MODEL_CERTIFICATION_REQUEST = "iso42001.certificationRequest";
	public static final String MODEL_ANALYSIS_PROFILE      = "iso42001.analysisProfile";

	public static final List<String> MODELS = Arrays.asList(
		MODEL_TEST_CONFIG, MODEL_TEST_RUN, MODEL_TEST_RESULT,
		MODEL_REPORT, MODEL_REPORT_SECTION, MODEL_CERTIFICATION, MODEL_CERTIFICATION_REQUEST,
		MODEL_ANALYSIS_PROFILE
	);

	private static boolean prep = false;

	public static void use() {
		if (!prep) {
			ModelNames.MODELS.addAll(MODELS);
			prep = true;
		}
	}
}
