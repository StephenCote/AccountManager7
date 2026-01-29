package org.cote.sockets;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GameEventNotifier;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.IGameEventHandler;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.util.JSONUtil;

import jakarta.websocket.Session;

/**
 * Handles game-related WebSocket messages.
 * Routes actions to GameUtil methods and sends streaming updates back to clients.
 * Also implements IGameEventHandler to receive push events from Overwatch.
 */
public class GameStreamHandler implements IGameEventHandler {

    private static final Logger logger = LogManager.getLogger(GameStreamHandler.class);

    // Character subscriptions: charId -> Set of subscribed sessions
    private static Map<String, Set<Session>> characterSubscriptions = new ConcurrentHashMap<>();

    // Session subscriptions: session -> Set of subscribed charIds
    private static Map<Session, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();

    // Executor for async game operations
    private static ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Handle incoming game request from WebSocket
     */
    public static void handleRequest(Session session, BaseRecord user, BaseRecord request) {
        String actionType = request.get("actionType");
        String actionId = request.get("actionId");
        BaseRecord params = request.get("params");

        // Handle subscription requests
        if (request.get("subscribe") != null && (Boolean) request.get("subscribe")) {
            String charId = request.get("charId");
            subscribe(session, charId);
            return;
        }
        if (request.get("unsubscribe") != null && (Boolean) request.get("unsubscribe")) {
            String charId = request.get("charId");
            unsubscribe(session, charId);
            return;
        }

        // Handle cancel requests
        if (request.get("cancel") != null && (Boolean) request.get("cancel")) {
            // TODO: Implement action cancellation
            chirp(session, "game.action.cancel", actionId, "{}");
            return;
        }

        if (actionType == null || actionId == null) {
            logger.warn("Game request missing actionType or actionId");
            return;
        }

        // Execute action asynchronously
        executor.submit(() -> {
            try {
                executeAction(session, user, actionId, actionType, params);
            } catch (Exception e) {
                logger.error("Error executing game action: " + actionType, e);
                chirpError(session, actionId, e.getMessage());
            }
        });
    }

    /**
     * Execute a game action and send streaming updates
     */
    private static void executeAction(Session session, BaseRecord user, String actionId, String actionType, BaseRecord params) {
        // Get OlioContext
        OlioContext octx = OlioContextUtil.getOlioContext(user, null);
        if (octx == null) {
            chirpError(session, actionId, "Failed to initialize game context");
            return;
        }

        switch (actionType.toLowerCase()) {
            case "move":
                handleMove(session, user, octx, actionId, params);
                break;
            case "moveto":
                handleMoveTo(session, user, octx, actionId, params);
                break;
            case "resolve":
                handleResolve(session, user, octx, actionId, params);
                break;
            case "interact":
                handleInteract(session, user, octx, actionId, params);
                break;
            case "consume":
                handleConsume(session, user, octx, actionId, params);
                break;
            case "investigate":
                handleInvestigate(session, user, octx, actionId, params);
                break;
            case "advance":
                handleAdvance(session, user, octx, actionId, params);
                break;
            case "claim":
                handleClaim(session, actionId, params);
                break;
            case "release":
                handleRelease(session, actionId, params);
                break;
            case "save":
                handleSave(session, user, actionId, params);
                break;
            case "load":
                handleLoad(session, actionId, params);
                break;
            case "situation":
                handleSituation(session, octx, actionId, params);
                break;
            default:
                chirpError(session, actionId, "Unknown action type: " + actionType);
        }
    }

    // ==================== Action Handlers ====================

    private static void handleMove(Session session, BaseRecord user, OlioContext octx, String actionId, BaseRecord params) {
        String charId = params.get("charId");
        String directionStr = params.get("direction");
        double distance = params.get("distance") != null ? ((Number) params.get("distance")).doubleValue() : 1.0;

        BaseRecord person = GameUtil.findCharacter(charId);
        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        DirectionEnumType direction;
        try {
            direction = DirectionEnumType.valueOf(directionStr.toUpperCase());
        } catch (Exception e) {
            chirpError(session, actionId, "Invalid direction: " + directionStr);
            return;
        }

        // Send start
        chirpStart(session, actionId, "move", charId);

        try {
            boolean moved = GameUtil.moveCharacter(octx, person, direction, distance);

            // Get updated situation
            Map<String, Object> situation = GameUtil.getSituation(octx, person);

            // Send complete with situation
            chirpComplete(session, actionId, situation);

        } catch (OlioException e) {
            chirpError(session, actionId, e.getMessage());
        }
    }

    private static void handleMoveTo(Session session, BaseRecord user, OlioContext octx, String actionId, BaseRecord params) {
        String charId = params.get("charId");
        int targetCellEast = params.get("targetCellEast") != null ? ((Number) params.get("targetCellEast")).intValue() : 0;
        int targetCellNorth = params.get("targetCellNorth") != null ? ((Number) params.get("targetCellNorth")).intValue() : 0;
        int targetPosEast = params.get("targetPosEast") != null ? ((Number) params.get("targetPosEast")).intValue() : 50;
        int targetPosNorth = params.get("targetPosNorth") != null ? ((Number) params.get("targetPosNorth")).intValue() : 50;
        double distance = params.get("distance") != null ? ((Number) params.get("distance")).doubleValue() : 1.0;

        BaseRecord person = GameUtil.findCharacter(charId);
        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        chirpStart(session, actionId, "moveTo", charId);

        try {
            GameUtil.moveTowardsPosition(octx, person, targetCellEast, targetCellNorth, targetPosEast, targetPosNorth, distance);

            Map<String, Object> situation = GameUtil.getSituation(octx, person);
            chirpComplete(session, actionId, situation);

        } catch (OlioException e) {
            chirpError(session, actionId, e.getMessage());
        }
    }

    private static void handleResolve(Session session, BaseRecord user, OlioContext octx, String actionId, BaseRecord params) {
        String charId = params.get("charId");
        String targetId = params.get("targetId");
        String actionTypeStr = params.get("actionType");

        BaseRecord person = GameUtil.findCharacter(charId);
        BaseRecord target = targetId != null ? GameUtil.findCharacter(targetId) : null;

        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        InteractionEnumType interactionType = InteractionEnumType.UNKNOWN;
        if (actionTypeStr != null) {
            try {
                interactionType = InteractionEnumType.valueOf(actionTypeStr.toUpperCase());
            } catch (Exception e) {
                // Use default
            }
        }

        chirpStart(session, actionId, "resolve", charId);

        try {
            Map<String, Object> result = GameUtil.resolveAction(octx, person, target, interactionType);
            chirpComplete(session, actionId, result);
        } catch (OlioException e) {
            chirpError(session, actionId, e.getMessage());
        }
    }

    private static void handleInteract(Session session, BaseRecord user, OlioContext octx, String actionId, BaseRecord params) {
        String actorId = params.get("actorId");
        String targetId = params.get("targetId");
        String interactionTypeStr = params.get("interactionType");

        BaseRecord actor = GameUtil.findCharacter(actorId);
        BaseRecord target = GameUtil.findCharacter(targetId);

        if (actor == null || target == null) {
            chirpError(session, actionId, "Actor or target not found");
            return;
        }

        InteractionEnumType interType;
        try {
            interType = InteractionEnumType.valueOf(interactionTypeStr.toUpperCase());
        } catch (Exception e) {
            chirpError(session, actionId, "Invalid interaction type");
            return;
        }

        chirpStart(session, actionId, "interact", actorId);

        try {
            BaseRecord interaction = GameUtil.interact(octx, actor, target, interType);
            Map<String, Object> situation = GameUtil.getSituation(octx, actor);
            situation.put("interaction", interaction != null ? JSONUtil.importObject(interaction.toFullString(), LooseRecord.class) : null);
            chirpComplete(session, actionId, situation);
        } catch (OlioException e) {
            chirpError(session, actionId, e.getMessage());
        }
    }

    private static void handleConsume(Session session, BaseRecord user, OlioContext octx, String actionId, BaseRecord params) {
        String charId = params.get("charId");
        String itemName = params.get("itemName");
        int quantity = params.get("quantity") != null ? ((Number) params.get("quantity")).intValue() : 1;

        BaseRecord person = GameUtil.findCharacter(charId);
        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        chirpStart(session, actionId, "consume", charId);

        boolean consumed = GameUtil.consumeItem(octx, person, itemName, quantity);
        Map<String, Object> situation = GameUtil.getSituation(octx, person);
        situation.put("consumed", consumed);
        chirpComplete(session, actionId, situation);
    }

    private static void handleInvestigate(Session session, BaseRecord user, OlioContext octx, String actionId, BaseRecord params) {
        String charId = params.get("charId");

        BaseRecord person = GameUtil.findCharacter(charId);
        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        chirpStart(session, actionId, "investigate", charId);

        Map<String, Object> result = GameUtil.investigate(octx, person);
        chirpComplete(session, actionId, result);
    }

    private static void handleAdvance(Session session, BaseRecord user, OlioContext octx, String actionId, BaseRecord params) {
        chirpStart(session, actionId, "advance", null);

        java.util.List<BaseRecord> realms = octx.getRealms();
        if (realms.isEmpty()) {
            chirpError(session, actionId, "No realm available");
            return;
        }

        java.util.List<BaseRecord> population = octx.getRealmPopulation(realms.get(0));
        int advanced = GameUtil.advanceTurn(octx, population);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("advanced", advanced);
        chirpComplete(session, actionId, result);
    }

    private static void handleClaim(Session session, String actionId, BaseRecord params) {
        String charId = params.get("charId");

        BaseRecord person = GameUtil.findCharacter(charId);
        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        chirpStart(session, actionId, "claim", charId);

        BaseRecord state = GameUtil.claimCharacter(person);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("state", state != null ? JSONUtil.importObject(state.toFullString(), LooseRecord.class) : null);
        chirpComplete(session, actionId, result);
    }

    private static void handleRelease(Session session, String actionId, BaseRecord params) {
        String charId = params.get("charId");

        BaseRecord person = GameUtil.findCharacter(charId);
        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        chirpStart(session, actionId, "release", charId);

        BaseRecord state = GameUtil.releaseCharacter(person);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("state", state != null ? JSONUtil.importObject(state.toFullString(), LooseRecord.class) : null);
        chirpComplete(session, actionId, result);
    }

    private static void handleSave(Session session, BaseRecord user, String actionId, BaseRecord params) {
        String name = params.get("name");
        String characterId = params.get("characterId");
        Object eventLog = params.get("eventLog");

        chirpStart(session, actionId, "save", characterId);

        Map<String, Object> result = GameUtil.saveGame(user, name, characterId, eventLog);
        chirpComplete(session, actionId, result);
    }

    private static void handleLoad(Session session, String actionId, BaseRecord params) {
        String saveId = params.get("saveId");

        chirpStart(session, actionId, "load", saveId);

        Map<String, Object> result = GameUtil.loadGame(saveId);
        chirpComplete(session, actionId, result);
    }

    private static void handleSituation(Session session, OlioContext octx, String actionId, BaseRecord params) {
        String charId = params.get("charId");

        BaseRecord person = GameUtil.findCharacter(charId);
        if (person == null) {
            chirpError(session, actionId, "Character not found");
            return;
        }

        chirpStart(session, actionId, "situation", charId);

        Map<String, Object> situation = GameUtil.getSituation(octx, person);
        chirpComplete(session, actionId, situation);
    }

    // ==================== Subscription Management ====================

    public static void subscribe(Session session, String charId) {
        if (charId == null) return;

        characterSubscriptions.computeIfAbsent(charId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionSubscriptions.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet()).add(charId);

        logger.info("Session subscribed to character: " + charId);
    }

    public static void unsubscribe(Session session, String charId) {
        if (charId == null) return;

        Set<Session> sessions = characterSubscriptions.get(charId);
        if (sessions != null) {
            sessions.remove(session);
        }

        Set<String> chars = sessionSubscriptions.get(session);
        if (chars != null) {
            chars.remove(charId);
        }

        logger.info("Session unsubscribed from character: " + charId);
    }

    public static void cleanupSession(Session session) {
        Set<String> chars = sessionSubscriptions.remove(session);
        if (chars != null) {
            for (String charId : chars) {
                Set<Session> sessions = characterSubscriptions.get(charId);
                if (sessions != null) {
                    sessions.remove(session);
                }
            }
        }
    }

    public static Set<String> getSubscribedCharacters() {
        return characterSubscriptions.keySet();
    }

    // ==================== Push Notifications ====================

    /**
     * Push a message to all sessions subscribed to a character
     */
    public static void pushToCharacter(String charId, String eventType, Object data) {
        Set<Session> sessions = characterSubscriptions.get(charId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        String dataStr = data instanceof String ? (String) data : JSONUtil.exportObject(data, RecordSerializerConfig.getUnfilteredModule());

        for (Session session : sessions) {
            if (session.isOpen()) {
                WebSocketService.sendMessage(session, new String[] { eventType, charId, dataStr }, false, false, true);
            }
        }
    }

    /**
     * Push a situation update to subscribed sessions
     */
    public static void pushSituationUpdate(String charId, Map<String, Object> situation) {
        pushToCharacter(charId, "game.situation.update", situation);
    }

    /**
     * Push a state update to subscribed sessions
     */
    public static void pushStateUpdate(String charId, Map<String, Object> state) {
        pushToCharacter(charId, "game.state.update", state);
    }

    /**
     * Push a threat detection event
     */
    public static void pushThreatDetected(String charId, Object threat) {
        pushToCharacter(charId, "game.threat.detected", threat);
    }

    /**
     * Push a threat removal event
     */
    public static void pushThreatRemoved(String charId, String threatId) {
        pushToCharacter(charId, "game.threat.removed", threatId);
    }

    /**
     * Push an NPC movement event
     */
    public static void pushNpcMoved(String charId, String npcId, Object from, Object to) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("npcId", npcId);
        data.put("from", from);
        data.put("to", to);
        pushToCharacter(charId, "game.npc.moved", data);
    }

    /**
     * Push a time advancement event
     */
    public static void pushTimeAdvanced(String charId, Object clockData) {
        pushToCharacter(charId, "game.time.advanced", clockData);
    }

    /**
     * Push a world event
     */
    public static void pushEventOccurred(String charId, Object event) {
        pushToCharacter(charId, "game.event.occurred", event);
    }

    // ==================== Chirp Helpers ====================

    private static void chirp(Session session, String type, String actionId, String data) {
        if (session != null && session.isOpen()) {
            WebSocketService.sendMessage(session, new String[] { type, actionId, data }, false, false, true);
        }
    }

    private static void chirpStart(Session session, String actionId, String actionType, String charId) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("actionType", actionType);
        data.put("charId", charId);
        chirp(session, "game.action.start", actionId, JSONUtil.exportObject(data));
    }

    private static void chirpProgress(Session session, String actionId, int progress, Object currentState) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("progress", progress);
        data.put("currentState", currentState);
        chirp(session, "game.action.progress", actionId, JSONUtil.exportObject(data));
    }

    private static void chirpComplete(Session session, String actionId, Object result) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("situation", result);
        chirp(session, "game.action.complete", actionId, JSONUtil.exportObject(data));
    }

    private static void chirpError(Session session, String actionId, String message) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("message", message);
        chirp(session, "game.action.error", actionId, JSONUtil.exportObject(data));
    }

    private static void chirpInterrupt(Session session, String actionId, Object interruptData) {
        chirp(session, "game.action.interrupt", actionId, JSONUtil.exportObject(interruptData));
    }

    // ==================== IGameEventHandler Implementation ====================
    // These methods are called by GameEventNotifier when Overwatch triggers events

    // Singleton instance for handler registration
    private static GameStreamHandler handlerInstance;

    public static GameStreamHandler getHandlerInstance() {
        if (handlerInstance == null) {
            handlerInstance = new GameStreamHandler();
            GameEventNotifier.getInstance().addHandler(handlerInstance);
            logger.info("GameStreamHandler registered with GameEventNotifier");
        }
        return handlerInstance;
    }

    @Override
    public void onActionStart(String charId, String actionId, String actionType, BaseRecord actionResult) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("actionType", actionType);
        data.put("charId", charId);
        pushToCharacter(charId, "game.action.start", data);
    }

    @Override
    public void onActionProgress(String charId, String actionId, int progress, Map<String, Object> state) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("actionId", actionId);
        data.put("progress", progress);
        data.put("currentState", state);
        pushToCharacter(charId, "game.action.progress", data);
    }

    @Override
    public void onActionComplete(String charId, String actionId, BaseRecord actionResult, Map<String, Object> situation) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("actionId", actionId);
        data.put("situation", situation);
        pushToCharacter(charId, "game.action.complete", data);
    }

    @Override
    public void onActionError(String charId, String actionId, String error) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("actionId", actionId);
        data.put("message", error);
        pushToCharacter(charId, "game.action.error", data);
    }

    @Override
    public void onThreatDetected(String charId, BaseRecord threat) {
        pushThreatDetected(charId, threat);
    }

    @Override
    public void onThreatRemoved(String charId, String threatId) {
        pushThreatRemoved(charId, threatId);
    }

    @Override
    public void onNpcMoved(String charId, String npcId, Map<String, Object> from, Map<String, Object> to) {
        pushNpcMoved(charId, npcId, from, to);
    }

    @Override
    public void onNpcAction(String charId, String npcId, String actionType, BaseRecord result) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("npcId", npcId);
        data.put("actionType", actionType);
        data.put("result", result != null ? JSONUtil.importObject(result.toFullString(), LooseRecord.class) : null);
        pushToCharacter(charId, "game.npc.action", data);
    }

    @Override
    public void onInteractionStart(String charId, BaseRecord interaction) {
        pushToCharacter(charId, "game.interaction.start", interaction);
    }

    @Override
    public void onInteractionEnd(String charId, BaseRecord interaction, BaseRecord result) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("interaction", interaction != null ? JSONUtil.importObject(interaction.toFullString(), LooseRecord.class) : null);
        data.put("result", result != null ? JSONUtil.importObject(result.toFullString(), LooseRecord.class) : null);
        pushToCharacter(charId, "game.interaction.end", data);
    }

    @Override
    public void onTimeAdvanced(String charId, Map<String, Object> clockData) {
        pushTimeAdvanced(charId, clockData);
    }

    @Override
    public void onIncrementEnd(String charId, Map<String, Object> incrementData) {
        pushToCharacter(charId, "game.increment.end", incrementData);
    }

    @Override
    public void onEventOccurred(String charId, BaseRecord event) {
        pushEventOccurred(charId, event);
    }

    @Override
    public void onStateChanged(String charId, Map<String, Object> statePatch) {
        pushStateUpdate(charId, statePatch);
    }
}
