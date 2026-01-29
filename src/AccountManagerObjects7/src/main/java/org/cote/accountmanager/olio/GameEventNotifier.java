package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

/**
 * Singleton notifier for game events.
 * Overwatch and other game components use this to push events to registered handlers.
 * The service layer (e.g., GameStreamHandler) registers as a handler to forward
 * events to WebSocket clients.
 */
public class GameEventNotifier {

    private static final Logger logger = LogManager.getLogger(GameEventNotifier.class);

    private static GameEventNotifier instance;
    private List<IGameEventHandler> handlers = new ArrayList<>();

    private GameEventNotifier() {}

    public static synchronized GameEventNotifier getInstance() {
        if (instance == null) {
            instance = new GameEventNotifier();
        }
        return instance;
    }

    public void addHandler(IGameEventHandler handler) {
        if (!handlers.contains(handler)) {
            handlers.add(handler);
            logger.info("Game event handler registered: " + handler.getClass().getSimpleName());
        }
    }

    public void removeHandler(IGameEventHandler handler) {
        handlers.remove(handler);
    }

    public void clearHandlers() {
        handlers.clear();
    }

    public boolean hasHandlers() {
        return !handlers.isEmpty();
    }

    // ==================== Event Dispatch Methods ====================

    public void notifyActionStart(String charId, String actionId, String actionType, BaseRecord actionResult) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onActionStart(charId, actionId, actionType, actionResult);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyActionProgress(String charId, String actionId, int progress, Map<String, Object> state) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onActionProgress(charId, actionId, progress, state);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyActionComplete(String charId, String actionId, BaseRecord actionResult, Map<String, Object> situation) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onActionComplete(charId, actionId, actionResult, situation);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyActionError(String charId, String actionId, String error) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onActionError(charId, actionId, error);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyThreatDetected(String charId, BaseRecord threat) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onThreatDetected(charId, threat);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyThreatRemoved(String charId, String threatId) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onThreatRemoved(charId, threatId);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyNpcMoved(String charId, String npcId, Map<String, Object> from, Map<String, Object> to) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onNpcMoved(charId, npcId, from, to);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyNpcAction(String charId, String npcId, String actionType, BaseRecord result) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onNpcAction(charId, npcId, actionType, result);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyInteractionStart(String charId, BaseRecord interaction) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onInteractionStart(charId, interaction);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyInteractionEnd(String charId, BaseRecord interaction, BaseRecord result) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onInteractionEnd(charId, interaction, result);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyTimeAdvanced(String charId, Map<String, Object> clockData) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onTimeAdvanced(charId, clockData);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyIncrementEnd(String charId, Map<String, Object> incrementData) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onIncrementEnd(charId, incrementData);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyEventOccurred(String charId, BaseRecord event) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onEventOccurred(charId, event);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }

    public void notifyStateChanged(String charId, Map<String, Object> statePatch) {
        for (IGameEventHandler handler : handlers) {
            try {
                handler.onStateChanged(charId, statePatch);
            } catch (Exception e) {
                logger.error("Error in game event handler", e);
            }
        }
    }
}
