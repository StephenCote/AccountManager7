package org.cote.accountmanager.olio;

import java.util.Map;

import org.cote.accountmanager.record.BaseRecord;

/**
 * Interface for handling game events pushed from Overwatch.
 * Implement this interface in the service layer (e.g., GameStreamHandler)
 * to receive push notifications for game events.
 */
public interface IGameEventHandler {

    /**
     * Called when an action starts for a character
     */
    void onActionStart(String charId, String actionId, String actionType, BaseRecord actionResult);

    /**
     * Called when an action progresses
     */
    void onActionProgress(String charId, String actionId, int progress, Map<String, Object> state);

    /**
     * Called when an action completes
     */
    void onActionComplete(String charId, String actionId, BaseRecord actionResult, Map<String, Object> situation);

    /**
     * Called when an action fails or is interrupted
     */
    void onActionError(String charId, String actionId, String error);

    /**
     * Called when a threat is detected near a character
     */
    void onThreatDetected(String charId, BaseRecord threat);

    /**
     * Called when a threat is eliminated or moves away
     */
    void onThreatRemoved(String charId, String threatId);

    /**
     * Called when an NPC moves
     */
    void onNpcMoved(String charId, String npcId, Map<String, Object> from, Map<String, Object> to);

    /**
     * Called when an NPC performs an action
     */
    void onNpcAction(String charId, String npcId, String actionType, BaseRecord result);

    /**
     * Called when an interaction starts involving a character
     */
    void onInteractionStart(String charId, BaseRecord interaction);

    /**
     * Called when an interaction completes
     */
    void onInteractionEnd(String charId, BaseRecord interaction, BaseRecord result);

    /**
     * Called when the game clock advances
     */
    void onTimeAdvanced(String charId, Map<String, Object> clockData);

    /**
     * Called when a realm increment ends
     */
    void onIncrementEnd(String charId, Map<String, Object> incrementData);

    /**
     * Called when a world event occurs
     */
    void onEventOccurred(String charId, BaseRecord event);

    /**
     * Called when a character's state changes (health, hunger, etc.)
     */
    void onStateChanged(String charId, Map<String, Object> statePatch);
}
