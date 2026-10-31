package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.illustration.QaProvider;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmQaProvider;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;

/**
 * Storage and selection for visual QA (PictureBook V3 design §17; ledger §6.C 220 a, f).
 * <p>
 * <b>Storage.</b> A {@link QaReport} is persisted as a {@code qa} JSON artifact on the SAME node as the image it
 * judged, stamped with that image's attempt and variantIndex and carrying the image's objectId, so a report is
 * bound to exactly one variant of one attempt with no new node type, model or field. Read back through the
 * node's {@code qa} chain.
 * <p>
 * <b>Selection</b> ({@link #providerFor}; P8-4). The vision-LLM provider when its route lists the QA model, else
 * the Swarm YOLO fallback when the renderer lists the detector, else none. Probed once per attempt, as the
 * requesting user, with nothing process-global: the chosen provider is an instance the hook holds for one attempt.
 * <p>
 * When QA runs and what a verdict does to auto-approve live in {@code PbNodeExecutor} /
 * {@link PbArtifactUtil#autoApprove(BaseRecord, List, Map, List, String)}.
 */
public class PbQaUtil {
	public static final Logger logger = LogManager.getLogger(PbQaUtil.class);

	private PbQaUtil() {
		/// static utility
	}

	// ─────────────────────────────── selection (220 f) ───────────────────────────────

	/// What {@link #providerFor} decided: the provider to run (null when none answers) and a one-line
	/// account of the probes, quoted in the {@code QA_UNAVAILABLE} degradation when {@code provider} is null.
	public static final class Selection {
		private final QaProvider provider;
		private final String note;

		Selection(QaProvider provider, String note) {
			this.provider = provider;
			this.note = note;
		}

		/// The provider to run this attempt's checks through, or null when neither answered.
		public QaProvider getProvider() {
			return provider;
		}

		/// Which provider was chosen and why the other was not (or why neither was).
		public String getNote() {
			return note;
		}

		@Override
		public String toString() {
			return (provider != null ? provider.name() : "none") + " (" + note + ")";
		}
	}

	/**
	 * Choose the attempt's provider (220 f): {@link VlmQaProvider} for {@code user}'s route when it lists the QA
	 * model, else {@link SwarmQaProvider} on {@code client} when the renderer's {@code object_info} lists the
	 * face detector, else none. Each probe runs once here; neither provider throws for a down backend, and a
	 * probe that does throw is logged and read as unavailable. Timings are logged, never asserted.
	 *
	 * @param chatConfig the run's chat config, or null for the user's default analysis config (220 e)
	 * @param client the graph client of the renderer this attempt rendered on (the Swarm fallback posts its
	 *        detector graph there); null skips the fallback
	 * @param checkpoint the checkpoint the render used, which the raw detector post must name (design §38)
	 */
	public static Selection providerFor(BaseRecord user, BaseRecord chatConfig, SwarmGraphClient client, String checkpoint) {
		StringBuilder why = new StringBuilder();
		long t0 = System.currentTimeMillis();
		QaProvider vlm = probe(new VlmQaProvider(user, chatConfig), why);
		long t1 = System.currentTimeMillis();
		if(vlm != null) {
			logger.info("Visual QA provider: " + vlm.name() + " (probe " + (t1 - t0) + " ms)");
			return new Selection(vlm, "vlm available (probe " + (t1 - t0) + " ms)");
		}
		QaProvider swarm = null;
		if(client != null) {
			swarm = probe(new SwarmQaProvider(client, checkpoint), why);
		}
		else {
			why.append("; swarm: no renderer client");
		}
		long t2 = System.currentTimeMillis();
		String note = why.toString().replaceFirst("^; ", "") + " (probes " + (t1 - t0) + " + " + (t2 - t1) + " ms)";
		if(swarm != null) {
			logger.info("Visual QA provider: " + swarm.name() + " on " + ((SwarmQaProvider) swarm).getServer() + "; " + note);
			return new Selection(swarm, note);
		}
		logger.warn("Visual QA provider: none; " + note);
		return new Selection(null, note);
	}

	/// {@code p} when it answers available, else null with its reason appended to {@code why}.
	private static QaProvider probe(QaProvider p, StringBuilder why) {
		boolean avail;
		try {
			avail = p.isAvailable();
		}
		catch(RuntimeException e) {
			logger.warn("Visual QA provider " + p.name() + " probe threw: " + e.getMessage());
			why.append("; ").append(p.name()).append(": probe threw ").append(e.getMessage());
			return null;
		}
		if(avail) {
			return p;
		}
		String note = p.availabilityNote();
		why.append("; ").append(p.name()).append(": ").append(note != null ? note : "unavailable");
		return null;
	}

	/**
	 * Persist {@code report} for {@code imageArtifact} as {@code user} through PBAC: a {@code qa} JSON artifact on
	 * the image's producing node, carrying the image's attempt + variantIndex and its objectId
	 * ({@link QaReport#forImage}). {@code supersedes} is never resolved for a report: the {@code qa} chain is a
	 * log keyed by {@code imageArtifactObjectId}, not a version chain of one thing.
	 *
	 * @param groupPath the world's {@code Artifacts} group path (the image's own group)
	 * @param imageArtifact the judged image, read with {@link PbArtifactUtil#artifactRequest()} (needs
	 *        {@code producedByNode}, {@code attempt}, {@code variantIndex})
	 * @return the persisted {@code qa} artifact
	 */
	public static BaseRecord persistReport(BaseRecord user, String groupPath, QaReport report, BaseRecord imageArtifact) {
		if(report == null) {
			throw new PictureBookException(400, "A QA report is required");
		}
		if(imageArtifact == null) {
			throw new PictureBookException(400, "A QA report must name the image artifact it judged");
		}
		BaseRecord node = imageArtifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		if(node == null || node.get(FieldNames.FIELD_OBJECT_ID) == null) {
			throw new PictureBookException(400, "Image artifact " + imageArtifact.get(FieldNames.FIELD_NAME)
				+ " has no producedByNode, so its QA report cannot be placed. Read it with PbArtifactUtil.artifactRequest() projected.");
		}
		String imageOid = imageArtifact.get(FieldNames.FIELD_OBJECT_ID);
		QaReport bound = report.forImage(imageOid);
		return PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_QA, PbArtifactTypeEnumType.JSON, groupPath,
			null, bound.toJson(), null, null, null, PbArtifactUtil.variantIndexOf(imageArtifact),
			PbArtifactUtil.attemptOf(imageArtifact), false);
	}

	/// The report carried by a {@code qa} artifact, or null when the artifact carries none (or is not one).
	public static QaReport readReport(BaseRecord artifact) {
		if(artifact == null || !artifact.hasField(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT)) {
			return null;
		}
		if(artifact.hasField(OlioFieldNames.FIELD_PB_ROLE) && !PbPipelineUtil.ROLE_QA.equals(artifact.get(OlioFieldNames.FIELD_PB_ROLE))) {
			return null;
		}
		String text = artifact.get(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT);
		if(text == null || text.isBlank()) {
			return null;
		}
		try {
			return QaReport.fromJson(text);
		}
		catch(RuntimeException e) {
			logger.warn("QA artifact " + artifact.get(FieldNames.FIELD_NAME) + " did not parse: " + e.getMessage());
			return null;
		}
	}

	/// Every report on {@code node}, ascending by revision (oldest first). Uncached.
	public static List<QaReport> reportsOf(BaseRecord user, BaseRecord node) {
		List<QaReport> out = new ArrayList<>();
		for(BaseRecord a : PbArtifactUtil.listChain(user, node, PbPipelineUtil.ROLE_QA)) {
			QaReport r = readReport(a);
			if(r != null) {
				out.add(r);
			}
		}
		return out;
	}

	/// The newest report for one image, or null when it was never judged.
	public static QaReport reportFor(BaseRecord user, BaseRecord node, String imageArtifactObjectId) {
		if(imageArtifactObjectId == null) {
			return null;
		}
		QaReport latest = null;
		for(QaReport r : reportsOf(user, node)) {
			if(imageArtifactObjectId.equals(r.getImageArtifactObjectId())) {
				latest = r;
			}
		}
		return latest;
	}

	/// The newest report for each image of {@code node}, keyed by image objectId; unjudged images are absent.
	public static Map<String, QaReport> latestReportsOf(BaseRecord user, BaseRecord node) {
		Map<String, QaReport> out = new LinkedHashMap<>();
		for(QaReport r : reportsOf(user, node)) {
			if(r.getImageArtifactObjectId() != null) {
				out.put(r.getImageArtifactObjectId(), r);
			}
		}
		return (out.isEmpty() ? Collections.emptyMap() : out);
	}

	/// The QA mode the plan's preset asks for ({@code QualityPresets.QA_OFF} / {@code QA_CANONICAL} / {@code QA_ALL});
	/// OFF for a plan with no preset values or an unknown value, so an old plan never turns QA on.
	public static String qaModeOf(RenderPlan plan) {
		if(plan == null || plan.getPreset() == null) {
			return QualityPresets.QA_OFF;
		}
		String qa = plan.getPreset().getQa();
		if(qa == null) {
			return QualityPresets.QA_OFF;
		}
		String mode = qa.trim().toUpperCase();
		if(QualityPresets.QA_CANONICAL.equals(mode) || QualityPresets.QA_ALL.equals(mode)) {
			return mode;
		}
		return QualityPresets.QA_OFF;
	}
}
