package org.cote.accountmanager.console.actions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.olio.picturebook.PbLegacyCleanupUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.record.BaseRecord;

/// PictureBook V3 Phase 10 P10-5: enumerate, and only on an explicit flag delete, the rows that only
/// the removed PB1 / FLUX Kontext pipeline ever wrote (legacy REFERENCE_STRIP / COMPOSITE nodes and
/// their bindings and artifacts, PB1 book settings rows, olio.sd.config rows with compositeMode
/// 'kontext'). See PbLegacyCleanupUtil for what is deletable and what is retained.
///
/// Options (authenticated form only; the organization administrator of the organization logged into):
///   -pbLegacyCleanup                                   dry run: log every row it would delete, every row
///                                                      it retains and why, and the counts; deletes nothing
///   -pbLegacyCleanup -pbLegacyPurge                    delete the dry-run set (re-scanned at purge time)
///   -pbLegacyCleanup -pbLegacyPurge -pbLegacyCodes a,b restrict the purge to the named categories
///
/// Nothing here runs on a read path, and nothing runs without -organization/-username/-password: the
/// unauthenticated pass only says so.
public class PbCleanupAction extends CommonAction implements IAction {

	public static final String OPT_ACTION = "pbLegacyCleanup";
	public static final String OPT_PURGE = "pbLegacyPurge";
	public static final String OPT_CODES = "pbLegacyCodes";

	@Override
	public void addOptions(Options options) {
		options.addOption(OPT_ACTION, false, "PictureBook PB1-only data cleanup: dry run (log what would be deleted and what is retained)");
		options.addOption(OPT_PURGE, false, "With -" + OPT_ACTION + ": actually delete the dry-run set");
		options.addOption(OPT_CODES, true, "With -" + OPT_PURGE + ": comma-separated categories to purge; default all of "
			+ String.join(",", PbLegacyCleanupUtil.PURGE_ORDER));
	}

	/// Unauthenticated pass: never scans, never writes.
	@Override
	public void handleCommand(CommandLine cmd) {
		if(!cmd.hasOption(OPT_ACTION)) {
			return;
		}
		if(!(cmd.hasOption("organization") && cmd.hasOption("username") && cmd.hasOption("password"))) {
			logger.error("-" + OPT_ACTION + " needs -organization <path> -username <name> -password <pw> for an AccountAdministrators member of that organization");
		}
	}

	/// Authenticated pass: dry run always; purge only with -pbLegacyPurge.
	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		if(!cmd.hasOption(OPT_ACTION)) {
			return;
		}
		boolean purge = cmd.hasOption(OPT_PURGE);
		List<String> codes = null;
		if(cmd.hasOption(OPT_CODES)) {
			codes = Arrays.asList(cmd.getOptionValue(OPT_CODES).split(","));
		}
		try {
			Map<String, Object> scan = PbLegacyCleanupUtil.scan(user);
			logScan(scan, purge);
			if(!purge) {
				logger.info("Dry run only. Add -" + OPT_PURGE + " to delete the " + scan.get("total") + " row(s) listed above (retained rows are never deleted).");
				return;
			}
			if(codes != null) {
				logger.info("Purge restricted to categories: " + codes);
			}
			Map<String, Object> result = PbLegacyCleanupUtil.purge(user, codes);
			logPurge(result);
		}
		catch(PictureBookException e) {
			logger.error("PB1 legacy cleanup refused (" + e.getStatus() + "): " + e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private void logScan(Map<String, Object> scan, boolean purge) {
		logger.info("PB1 legacy " + (purge ? "purge - pre-scan" : "dry run") + " for organization " + scan.get("organizationId") + " at " + scan.get("checkedAt"));
		for(Map<String, Object> cat : (List<Map<String, Object>>) scan.get("categories")) {
			String code = String.valueOf(cat.get("code"));
			logger.info("  " + code + ": " + cat.get("count") + " deletable - " + PbLegacyCleanupUtil.describe(code));
			for(Map<String, Object> item : (List<Map<String, Object>>) cat.get("items")) {
				logger.info("    would delete " + describeItem(item));
			}
		}
		List<Map<String, Object>> retained = (List<Map<String, Object>>) scan.get("retained");
		logger.info("  retained (never deleted): " + retained.size());
		for(Map<String, Object> item : retained) {
			logger.info("    retained " + describeItem(item));
		}
		for(Object w : (List<Object>) scan.get("warnings")) {
			logger.warn("  " + w);
		}
		logger.info("  total deletable: " + scan.get("total") + ", retained: " + scan.get("retainedTotal"));
	}

	@SuppressWarnings("unchecked")
	private void logPurge(Map<String, Object> result) {
		for(Map<String, Object> row : (List<Map<String, Object>>) result.get("results")) {
			String outcome = String.valueOf(row.get("outcome"));
			String line = outcome + " " + describeItem(row) + " (rows: " + row.get("count") + ")";
			if(PbLegacyCleanupUtil.OUTCOME_DELETED.equals(outcome)) {
				logger.info("  " + line);
			}
			else {
				logger.warn("  " + line);
			}
		}
		for(Object w : (List<Object>) result.get("warnings")) {
			logger.warn("  " + w);
		}
		logger.info("PB1 legacy purge for organization " + result.get("organizationId") + ": deleted " + result.get("deleted")
			+ ", denied " + result.get("denied") + ", failed " + result.get("failed") + ", retained " + result.get("retainedTotal"));
		/// State the REAL propagation bound. This JVM's AccessPoint/CacheDBSearch were invalidated by the
		/// deletes; a running AccountManagerService7 is a separate JVM with its own in-process query cache.
		logger.warn("Propagation: a running AccountManagerService7 keeps its own in-process CacheDBSearch entries for"
			+ " these models until they expire or it is restarted, so a page that listed a deleted row may show it"
			+ " until then. Its next uncached read (cache:false, or a cache miss) sees the deletes immediately.");
	}

	private static String describeItem(Map<String, Object> item) {
		StringBuilder sb = new StringBuilder();
		sb.append(item.get("code")).append(' ').append(item.get("model")).append(" id=").append(item.get("id"))
			.append(" objectId=").append(item.get("objectId"));
		if(item.get("name") != null) {
			sb.append(" name='").append(item.get("name")).append('\'');
		}
		for(String k : new String[] {"nodeType", "handle", "role", "compositeMode", "producedByNode", "dataId", "artifacts", "bindings", "referencedBy"}) {
			if(item.get(k) != null) {
				sb.append(' ').append(k).append('=').append(item.get(k));
			}
		}
		if(item.get("reason") != null) {
			sb.append(" - ").append(item.get("reason"));
		}
		return sb.toString();
	}
}
