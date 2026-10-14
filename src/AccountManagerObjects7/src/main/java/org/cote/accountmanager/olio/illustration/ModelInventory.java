package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// The checkpoints one SD server reports as installed (PictureBook V3 design §16), with the
/// provenance a Resolution must carry: when the list was read and whether the read failed.
///
/// error() non-null means the list could NOT be read - installedModels() is then empty but that
/// emptiness is not an answer. ModelResolver turns it into Reason.INVENTORY_UNAVAILABLE ("renderer
/// unavailable", §20) rather than NOT_INSTALLED ("model missing"). A successful read that lists
/// nothing has error() == null and is a real answer.
public interface ModelInventory {

	/// The server URL the list was read from, or null for an in-memory inventory.
	String server();

	/// Names exactly as the server lists them (folder + extension on Swarm). Never null; unmodifiable.
	List<String> installedModels();

	/// Epoch millis of the read this snapshot came from.
	long fetchedAt();

	/// Why the list could not be read, or null when installedModels() is a real answer.
	String error();

	/// An in-memory inventory over a fixed list - what the pure tests and the List-taking
	/// ModelResolver.resolve overload use. A null list reads as an empty successful answer.
	static ModelInventory of(List<String> installed) {
		final List<String> names = (installed != null)
			? Collections.unmodifiableList(new ArrayList<>(installed))
			: Collections.emptyList();
		final long at = System.currentTimeMillis();
		return new ModelInventory() {
			@Override public String server() { return null; }
			@Override public List<String> installedModels() { return names; }
			@Override public long fetchedAt() { return at; }
			@Override public String error() { return null; }
			@Override public String toString() { return "ModelInventory{in-memory, " + names.size() + " models}"; }
		};
	}

	/// An inventory that could not be read: empty list plus the reason.
	static ModelInventory unavailable(String server, String error) {
		final String reason = (error != null && !error.isBlank()) ? error : "installed-model list unavailable";
		final long at = System.currentTimeMillis();
		return new ModelInventory() {
			@Override public String server() { return server; }
			@Override public List<String> installedModels() { return Collections.emptyList(); }
			@Override public long fetchedAt() { return at; }
			@Override public String error() { return reason; }
			@Override public String toString() { return "ModelInventory{unavailable: " + reason + "}"; }
		};
	}
}
