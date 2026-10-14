package org.cote.accountmanager.olio.illustration;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Matches a requested checkpoint spelling against the names a server lists (PictureBook V3 §16).
/// Pure.
///
/// Swarm lists "folder/name.safetensors"; configs, init-params and styles spell the same checkpoint
/// as "name", "name.safetensors" or "folder/name". The match is tried, case-insensitively, as the full
/// name, then with ".safetensors" stripped from BOTH sides, then - only when the candidate names no
/// folder of its own - on the listed basename after the last '/'. A candidate that does name a folder
/// must match it: "other/name" against a listed "SDXL/name.safetensors" is NOT_INSTALLED, not a silent
/// substitution of a same-named file from another folder. The listed name is returned so callers send
/// what the server accepts (Swarm needs the extension) - a resolver must not re-strip it.
///
/// Looser than SdTestGate.isInstalled, which never basename-matches: a bare basename listed under more
/// than one folder is ambiguous, logged, and the first listed wins.
public final class CheckpointNames {

	private static final Logger logger = LogManager.getLogger(CheckpointNames.class);
	public static final String EXTENSION = ".safetensors";

	private CheckpointNames() { }

	/// The listed name the candidate refers to, or null when no listed name matches.
	public static String matchInstalled(List<String> installed, String candidate) {
		if(installed == null || installed.isEmpty() || candidate == null || candidate.isBlank()) {
			return null;
		}
		String want = candidate.trim();
		for(String m : installed) {
			if(m != null && m.trim().equalsIgnoreCase(want)) {
				return m;
			}
		}
		String wantBare = stripExtension(want);
		for(String m : installed) {
			if(m != null && stripExtension(m.trim()).equalsIgnoreCase(wantBare)) {
				return m;
			}
		}
		if(wantBare.indexOf('/') >= 0) {
			return null;
		}
		String first = null;
		int hits = 0;
		for(String m : installed) {
			if(m != null && basename(stripExtension(m.trim())).equalsIgnoreCase(wantBare)) {
				if(first == null) {
					first = m;
				}
				hits++;
			}
		}
		if(hits > 1) {
			logger.warn("Checkpoint '" + candidate + "' matches " + hits + " listed names by basename; using the first listed: " + first);
		}
		return first;
	}

	/// "folder/name.safetensors" -> "folder/name"; case-insensitive on the extension; no-op otherwise.
	public static String stripExtension(String name) {
		if(name == null) {
			return null;
		}
		String n = name.trim();
		if(n.length() > EXTENSION.length() && n.toLowerCase().endsWith(EXTENSION)) {
			return n.substring(0, n.length() - EXTENSION.length());
		}
		return n;
	}

	/// "folder/name" -> "name"; a trailing '/' or no '/' returns the input trimmed.
	public static String basename(String name) {
		if(name == null) {
			return null;
		}
		String n = name.trim();
		int slash = n.lastIndexOf('/');
		if(slash >= 0 && slash < n.length() - 1) {
			return n.substring(slash + 1);
		}
		return n;
	}
}
