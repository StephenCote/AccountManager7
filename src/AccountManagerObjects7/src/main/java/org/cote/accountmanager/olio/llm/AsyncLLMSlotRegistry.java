package org.cote.accountmanager.olio.llm;

import java.util.concurrent.ConcurrentHashMap;

/// Phase 5.2 (ConversationQualityPlan) per-chatConfig async-LLM slot
/// registry. Decoupled from Chat so it can be unit tested.
///
/// Semantics:
///   - tryAcquire(key, kind, nowMs) — CAS into the slot. Returns true if
///     acquired, false if another holder is current.
///   - If the existing holder has been there past `slotTimeoutMs`, treat
///     it as leaked and reclaim.
///   - release(key) — drop the slot for this key. Safe to call when not held.
///
/// Caller pairs tryAcquire / release in finally blocks. The registry does
/// NOT track ownership beyond the marker string — release is unconditional.
public final class AsyncLLMSlotRegistry {

	private final ConcurrentHashMap<String, String> slots = new ConcurrentHashMap<>();
	private final long slotTimeoutMs;

	public AsyncLLMSlotRegistry(long slotTimeoutMs) {
		this.slotTimeoutMs = slotTimeoutMs;
	}

	/// `kind` is recorded into the slot marker for diagnostics (e.g.,
	/// "keyframe", "compliance"). Returns true if the caller now holds
	/// the slot.
	public boolean tryAcquire(String key, String kind, long nowMs) {
		if (key == null) return true; // null key = ungated
		String marker = makeMarker(kind, nowMs);
		String existing = slots.get(key);
		if (existing != null) {
			long heldSince = parseTimestamp(existing);
			/// heldSince < 0 means parse failed — treat the marker as
			/// ancient/unreliable and reclaim.
			long heldFor = (heldSince >= 0) ? (nowMs - heldSince) : Long.MAX_VALUE;
			if (heldFor > slotTimeoutMs) {
				/// expired — try to reclaim. If something else swooped in
				/// between read and replace, putIfAbsent below will fail
				/// and the caller bails out.
				slots.remove(key, existing);
			} else {
				return false;
			}
		}
		return slots.putIfAbsent(key, marker) == null;
	}

	public void release(String key) {
		if (key == null) return;
		slots.remove(key);
	}

	/// Current holder marker or null. Useful for logging.
	public String currentHolder(String key) {
		if (key == null) return null;
		return slots.get(key);
	}

	/// Pure helper — build a marker from kind + nowMs.
	public static String makeMarker(String kind, long nowMs) {
		return (kind == null ? "?" : kind) + "|" + nowMs;
	}

	/// Pure helper — parse the trailing timestamp from a marker.
	/// Returns -1 if the marker is null or malformed (so a legitimate
	/// timestamp of 0 is distinguishable from a parse failure).
	public static long parseTimestamp(String marker) {
		if (marker == null) return -1L;
		int bar = marker.lastIndexOf('|');
		if (bar < 0 || bar >= marker.length() - 1) return -1L;
		try { return Long.parseLong(marker.substring(bar + 1)); }
		catch (NumberFormatException e) { return -1L; }
	}

	/// Pure helper — extract the kind from a marker, or "?" if unknown.
	public static String parseKind(String marker) {
		if (marker == null) return "?";
		int bar = marker.lastIndexOf('|');
		if (bar < 0) return marker;
		return marker.substring(0, bar);
	}

	/// Clear all slots — for testing or shutdown.
	public void clear() {
		slots.clear();
	}
}
