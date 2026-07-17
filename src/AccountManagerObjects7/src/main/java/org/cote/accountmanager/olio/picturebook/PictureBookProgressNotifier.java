package org.cote.accountmanager.olio.picturebook;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

/**
 * Singleton notifier for picture-book pipeline progress events, mirroring
 * {@code org.cote.accountmanager.olio.GameEventNotifier}. {@link PictureBookUtil} pushes
 * progress notifications here instead of calling Service7's {@code WebSocketService} directly
 * (Objects7 cannot depend on Service7 — see .claude/rules/architecture.md). The service layer
 * registers a handler (e.g. {@code PictureBookService}) that forwards each event to
 * {@code WebSocketService.chirpUser(user, new String[] {"bgActivity", icon, message})}.
 *
 * When no handler is registered (e.g. Objects7-tree unit tests calling {@link PictureBookUtil}
 * directly), notifications are simply no-ops — there is no client to forward them to, and no
 * ServletContext/WebSocket mocking is required to exercise the pipeline logic.
 */
public class PictureBookProgressNotifier {

	private static final Logger logger = LogManager.getLogger(PictureBookProgressNotifier.class);

	private static PictureBookProgressNotifier instance;

	private final List<IPictureBookProgressHandler> handlers = new CopyOnWriteArrayList<>();

	private PictureBookProgressNotifier() {
	}

	public static synchronized PictureBookProgressNotifier getInstance() {
		if (instance == null) {
			instance = new PictureBookProgressNotifier();
		}
		return instance;
	}

	public void addHandler(IPictureBookProgressHandler handler) {
		if (handler != null && !handlers.contains(handler)) {
			handlers.add(handler);
			logger.info("Picture book progress handler registered: " + handler.getClass().getSimpleName());
		}
	}

	public void removeHandler(IPictureBookProgressHandler handler) {
		handlers.remove(handler);
	}

	public void clearHandlers() {
		handlers.clear();
	}

	public void notifyProgress(BaseRecord user, String icon, String message) {
		for (IPictureBookProgressHandler handler : handlers) {
			try {
				handler.onProgress(user, icon, message);
			} catch (Exception e) {
				logger.error("Error in picture book progress handler", e);
			}
		}
	}
}
