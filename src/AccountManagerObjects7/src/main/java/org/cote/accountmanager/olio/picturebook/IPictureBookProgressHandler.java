package org.cote.accountmanager.olio.picturebook;

import org.cote.accountmanager.record.BaseRecord;

/**
 * Registered by the service/transport layer (e.g. {@code PictureBookService} in Service7) to
 * forward picture-book pipeline progress ("Generating portraits...", "Stitching reference...",
 * etc.) to a live client (WebSocket chirp). Mirrors the {@code IGameEventHandler}/
 * {@code GameEventNotifier} pattern already used to keep Overwatch/game-loop progress events
 * out of Objects7's dependency on Service7's WebSocket transport.
 */
public interface IPictureBookProgressHandler {
	void onProgress(BaseRecord user, String icon, String message);
}
