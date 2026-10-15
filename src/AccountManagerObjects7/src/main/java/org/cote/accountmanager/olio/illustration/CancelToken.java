package org.cote.accountmanager.olio.illustration;

/// Cooperative cancellation for a stage (PictureBook V3 design §15). The adapter checks it before
/// each remote call; cancelling a request already in flight is left to the backend (Swarm's
/// cancel endpoint is not wired in Phase 2 - cancelActive is deliberately absent from the adapter).
public interface CancelToken {

	boolean isCancelled();

	CancelToken NONE = () -> false;
}
