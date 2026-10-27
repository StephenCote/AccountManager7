package org.cote.accountmanager.olio.sd;

import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.illustration.CalibrationSample;
import org.cote.accountmanager.olio.illustration.RenderCalibration;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;

/// Render timing calibration (PictureBook V3 design §34 Q14 (b), P5-2 §4): the in-JVM fold
/// ({@link RenderCalibration}) plus one persisted {@code olio.sd.calibration} row per
/// server + family + stage in the requesting user's {@code ~/Illustration/Calibration}.
///
/// The write path ({@link #recordSample}) is the only thing that creates the group or a row, and it
/// does so as the requesting user through {@code AccessPoint} - never the org admin. The read path
/// ({@link #sample}) is find-only: the in-JVM entry when this process measured one, else the user's
/// persisted row (which primes the in-JVM table, flagged persisted so the estimate never calls it
/// "measured in-process"), else the family's {@code capabilities.json} seed, else nothing. A failed
/// persist is a WARN and a false return, never a thrown error - the render already succeeded.
public class PbCalibrationUtil {
	public static final Logger logger = LogManager.getLogger(PbCalibrationUtil.class);

	public static final String CALIBRATION_PATH = "~/Illustration/Calibration";
	public static final String NAME_PREFIX = "calibration:";

	private PbCalibrationUtil() {
		/// static utility
	}

	// ─────────────────────────────── keys ───────────────────────────────

	/// {@code host:port} of a Swarm URL ({@code http://192.168.1.39:7801/} -> {@code 192.168.1.39:7801});
	/// a bare {@code host:port} is returned trimmed, anything unparseable as given.
	public static String hostPort(String server) {
		if(server == null) {
			return "";
		}
		String s = server.trim();
		try {
			URI u = URI.create(s.contains("://") ? s : "http://" + s);
			if(u.getHost() != null) {
				return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
			}
		}
		catch(IllegalArgumentException e) {
			/// fall through: the string as given
		}
		return s;
	}

	/// {@code calibration:<family>:<stage>:<host:port>}
	public static String rowName(String server, String family, String stage) {
		return NAME_PREFIX + (family == null ? "" : family.trim()) + ":" + RenderCalibration.stageOrBase(stage) + ":" + hostPort(server);
	}

	// ─────────────────────────────── write ───────────────────────────────

	/// Fold one completed graph stage: in-JVM always; then the user's persisted row (EWMA over the stored
	/// figure, {@code seeded=false}) or a fresh one. Non-positive inputs are refused (false). Returns
	/// whether the row was persisted.
	public static boolean recordSample(BaseRecord user, String server, String family, String stage, String graphHash,
			long durationMs, int effectiveSteps, int width, int height, int references, String swarmVersion) {
		RenderCalibration.Sample inProc = RenderCalibration.record(server, family, stage, durationMs, effectiveSteps, width, height);
		if(inProc == null) {
			logger.warn("Calibration sample refused (family=" + family + ", stage=" + stage + ", durationMs=" + durationMs + ", steps=" + effectiveSteps + ", " + width + "x" + height + ")");
			return false;
		}
		if(user == null) {
			return false;
		}
		double megapixels = (width * (double) height) / 1_000_000.0;
		double value = (durationMs / 1000.0) / effectiveSteps / megapixels;
		String st = RenderCalibration.stageOrBase(stage);
		String name = rowName(server, family, st);
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		try {
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, CALIBRATION_PATH, GroupEnumType.DATA.toString(), orgId);
			if(dir == null) {
				logger.warn("Calibration group " + CALIBRATION_PATH + " could not be created for " + user.get(FieldNames.FIELD_NAME));
				return false;
			}
			long groupId = dir.get(FieldNames.FIELD_ID);
			BaseRecord existing = findRow(user, name, groupId, orgId);
			Date now = new Date();
			if(existing != null) {
				double stored = existing.get(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL);
				int count = existing.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT);
				boolean seeded = existing.get(OlioFieldNames.FIELD_SD_CAL_SEEDED);
				/// a seeded row holds the capabilities figure, not a measurement: the first real sample replaces it
				double next = (seeded || count <= 0 || stored <= 0) ? value : stored + RenderCalibration.ALPHA * (value - stored);
				int nextCount = (seeded || count <= 0) ? 1 : count + 1;
				BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CALIBRATION, new String[] {
					FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
					OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL, OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT,
					OlioFieldNames.FIELD_SD_CAL_LAST_DURATION_MS, OlioFieldNames.FIELD_SD_CAL_LAST_STEPS,
					OlioFieldNames.FIELD_SD_CAL_LAST_WIDTH, OlioFieldNames.FIELD_SD_CAL_LAST_HEIGHT,
					OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES, OlioFieldNames.FIELD_SD_CAL_GRAPH_HASH,
					OlioFieldNames.FIELD_SD_CAL_SWARM_VERSION, OlioFieldNames.FIELD_SD_CAL_SEEDED,
					OlioFieldNames.FIELD_SD_CAL_LAST_SAMPLE_AT
				});
				patch.set(FieldNames.FIELD_ID, existing.get(FieldNames.FIELD_ID));
				patch.set(FieldNames.FIELD_OBJECT_ID, existing.get(FieldNames.FIELD_OBJECT_ID));
				patch.set(FieldNames.FIELD_NAME, existing.get(FieldNames.FIELD_NAME));
				patch.set(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL, next);
				patch.set(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT, nextCount);
				setLast(patch, durationMs, effectiveSteps, width, height, references, graphHash, swarmVersion, now);
				patch.set(OlioFieldNames.FIELD_SD_CAL_SEEDED, false);
				if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
					logger.warn("Calibration row " + name + " was not updated");
					return false;
				}
				return true;
			}
			BaseRecord row = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CALIBRATION);
			row.set(FieldNames.FIELD_NAME, name);
			row.set(FieldNames.FIELD_GROUP_ID, groupId);
			IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, row, orgId);
			row.set(OlioFieldNames.FIELD_SD_CAL_SERVER, hostPort(server));
			row.set(OlioFieldNames.FIELD_SD_CAL_FAMILY, family.trim());
			row.set(OlioFieldNames.FIELD_SD_CAL_STAGE, st);
			row.set(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL, value);
			row.set(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT, 1);
			setLast(row, durationMs, effectiveSteps, width, height, references, graphHash, swarmVersion, now);
			row.set(OlioFieldNames.FIELD_SD_CAL_SEEDED, false);
			if(IOSystem.getActiveContext().getAccessPoint().create(user, row) == null) {
				logger.warn("Calibration row " + name + " was not created");
				return false;
			}
			return true;
		}
		catch(FieldException | ValueException | ModelNotFoundException | ClassCastException | NullPointerException e) {
			logger.warn("Calibration row " + name + " was not persisted: " + e.getMessage());
			return false;
		}
	}

	private static void setLast(BaseRecord rec, long durationMs, int steps, int width, int height, int references,
			String graphHash, String swarmVersion, Date at) throws FieldException, ValueException, ModelNotFoundException {
		rec.set(OlioFieldNames.FIELD_SD_CAL_LAST_DURATION_MS, durationMs);
		rec.set(OlioFieldNames.FIELD_SD_CAL_LAST_STEPS, steps);
		rec.set(OlioFieldNames.FIELD_SD_CAL_LAST_WIDTH, width);
		rec.set(OlioFieldNames.FIELD_SD_CAL_LAST_HEIGHT, height);
		rec.set(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES, Math.max(0, references));
		if(graphHash != null && !graphHash.isBlank()) {
			rec.set(OlioFieldNames.FIELD_SD_CAL_GRAPH_HASH, graphHash);
		}
		if(swarmVersion != null && !swarmVersion.isBlank()) {
			rec.set(OlioFieldNames.FIELD_SD_CAL_SWARM_VERSION, swarmVersion);
		}
		rec.set(OlioFieldNames.FIELD_SD_CAL_LAST_SAMPLE_AT, at);
	}

	// ─────────────────────────────── read ───────────────────────────────

	/// The figure for one server + family + stage, or null when there is none anywhere: in-JVM, else the
	/// user's persisted row (find-only; primes the in-JVM table as persisted-origin), else the family's seed.
	public static CalibrationSample sample(BaseRecord user, String server, String family, String stage) {
		if(family == null || family.isBlank()) {
			return null;
		}
		String st = RenderCalibration.stageOrBase(stage);
		String hp = hostPort(server);
		RenderCalibration.Sample held = RenderCalibration.sample(server, family, st);
		if(held != null && held.isMeasured()) {
			return CalibrationSample.measured(st, held.getSecondsPerStepPerMegapixel(), held.getCount(), held.getMeasuredAt());
		}
		BaseRecord row = findRow(user, server, family, st);
		if(row != null) {
			Double stored = row.get(OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL);
			Integer count = row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT);
			Boolean seeded = row.get(OlioFieldNames.FIELD_SD_CAL_SEEDED);
			Date at = row.get(OlioFieldNames.FIELD_SD_CAL_LAST_SAMPLE_AT);
			if(stored != null && stored > 0 && count != null && count > 0 && !Boolean.TRUE.equals(seeded)) {
				Instant measuredAt = (at == null ? null : at.toInstant());
				RenderCalibration.prime(server, family, st, stored, count, measuredAt);
				Long ownerId = row.get(FieldNames.FIELD_OWNER_ID);
				Long userId = (user == null ? null : user.get(FieldNames.FIELD_ID));
				boolean own = ownerId != null && ownerId.equals(userId);
				return CalibrationSample.persisted(st, stored, count, measuredAt, hp, own);
			}
		}
		if(held != null) {
			/// primed earlier in this JVM from a row the reader may no longer see
			return CalibrationSample.persisted(st, held.getSecondsPerStepPerMegapixel(), held.getCount(), held.getMeasuredAt(), hp, false);
		}
		RendererCapabilities caps = capsForFamily(family);
		Double seed = (caps == null ? null : caps.getCalibrationSeed(st));
		return (seed == null ? null : CalibrationSample.seed(st, seed));
	}

	/// The samples the estimate needs for a plan, keyed by stage kind: BASE from the BASE pick's family and,
	/// when the strategy has an ACTOR_REGION stage, ACTOR_REGION from that pick's family. Absent keys mean
	/// "no figure".
	public static Map<String, CalibrationSample> samplesFor(BaseRecord user, String server, RenderPlan plan) {
		Map<String, CalibrationSample> out = new LinkedHashMap<>();
		if(plan == null) {
			return out;
		}
		RenderPlan.ModelPick base = plan.pickForStage(RenderPlan.STAGE_BASE);
		CalibrationSample b = (base == null ? null : sample(user, server, base.getFamily(), RenderPlan.STAGE_BASE));
		if(b != null) {
			out.put(RenderPlan.STAGE_BASE, b);
		}
		if(plan.getStrategy() != null && plan.getStrategy().getStages() != null && plan.getStrategy().getStages().contains(RenderPlan.STAGE_ACTOR_REGION)) {
			RenderPlan.ModelPick region = plan.pickForStage(RenderPlan.STAGE_ACTOR_REGION);
			CalibrationSample r = (region == null ? null : sample(user, server, region.getFamily(), RenderPlan.STAGE_ACTOR_REGION));
			if(r != null) {
				out.put(RenderPlan.STAGE_ACTOR_REGION, r);
			}
		}
		return out;
	}

	/// Find-only: the user's row for the key, or null. Never creates the group.
	public static BaseRecord findRow(BaseRecord user, String server, String family, String stage) {
		if(user == null || family == null || family.isBlank()) {
			return null;
		}
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, CALIBRATION_PATH, GroupEnumType.DATA.toString(), orgId);
		if(dir == null) {
			return null;
		}
		long groupId = dir.get(FieldNames.FIELD_ID);
		return findRow(user, rowName(server, family, stage), groupId, orgId);
	}

	private static BaseRecord findRow(BaseRecord user, String name, long groupId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CALIBRATION, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_SD_CAL_SERVER, OlioFieldNames.FIELD_SD_CAL_FAMILY, OlioFieldNames.FIELD_SD_CAL_STAGE,
			OlioFieldNames.FIELD_SD_CAL_SECONDS_PER_STEP_PER_MEGAPIXEL, OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT,
			OlioFieldNames.FIELD_SD_CAL_LAST_DURATION_MS, OlioFieldNames.FIELD_SD_CAL_LAST_STEPS,
			OlioFieldNames.FIELD_SD_CAL_LAST_WIDTH, OlioFieldNames.FIELD_SD_CAL_LAST_HEIGHT,
			OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES, OlioFieldNames.FIELD_SD_CAL_GRAPH_HASH,
			OlioFieldNames.FIELD_SD_CAL_SWARM_VERSION, OlioFieldNames.FIELD_SD_CAL_SEEDED,
			OlioFieldNames.FIELD_SD_CAL_LAST_SAMPLE_AT
		});
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static RendererCapabilities capsForFamily(String family) {
		String f = family.trim();
		for(RendererCapabilities rc : RendererCapabilities.families()) {
			if(f.equals(rc.getPattern())) {
				return rc;
			}
		}
		return null;
	}
}
