(function(){
    /**
     * Game Stream Module
     *
     * Provides WebSocket-based streaming for game actions, using the existing
     * page.wss infrastructure from pageClient.js
     *
     * Usage:
     *   gameStream.executeAction("move", { charId, direction }, callbacks);
     *   gameStream.subscribe(charId);
     */

    let activeActions = new Map();  // actionId -> callbacks
    let subscribedCharacters = new Set();

    function generateUUID() {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }

    let gameStream = {

        /**
         * Execute a game action with streaming callbacks
         *
         * @param {string} actionType - Action type: move, moveTo, resolve, consume, investigate, interact, advance, claim, release, save, load
         * @param {object} params - Action parameters (charId, direction, targetId, etc.)
         * @param {object} callbacks - Callback functions: { onStart, onProgress, onComplete, onError, onInterrupt }
         * @returns {string} actionId - Unique ID for this action (can be used to cancel)
         */
        executeAction: function(actionType, params, callbacks) {
            const actionId = generateUUID();

            // Register callbacks for this action
            activeActions.set(actionId, {
                actionType: actionType,
                onStart: callbacks?.onStart || function(){},
                onProgress: callbacks?.onProgress || function(){},
                onComplete: callbacks?.onComplete || function(){},
                onError: callbacks?.onError || function(){},
                onInterrupt: callbacks?.onInterrupt || function(){}
            });

            // Send action request via existing websocket
            page.wss.send("game", JSON.stringify({
                actionId: actionId,
                actionType: actionType,
                params: params
            }), undefined, "game.action." + actionType);

            return actionId;
        },

        /**
         * Cancel an in-progress action
         *
         * @param {string} actionId - The action ID returned from executeAction
         */
        cancelAction: function(actionId) {
            if (!activeActions.has(actionId)) {
                console.warn("No active action with id: " + actionId);
                return;
            }

            page.wss.send("game", JSON.stringify({
                actionId: actionId,
                cancel: true
            }), undefined, "game.action.cancel");
        },

        /**
         * Check if an action is still active
         *
         * @param {string} actionId
         * @returns {boolean}
         */
        isActionActive: function(actionId) {
            return activeActions.has(actionId);
        },

        /**
         * Get the action type for an active action
         *
         * @param {string} actionId
         * @returns {string|undefined}
         */
        getActionType: function(actionId) {
            const action = activeActions.get(actionId);
            return action ? action.actionType : undefined;
        },

        /**
         * Route incoming game action messages from websocket
         * Called by pageClient.js routeMessage()
         *
         * @param {string} chirpType - Message type: game.action.start, game.action.progress, etc.
         * @param {array} args - Remaining chirp arguments
         */
        routeActionMessage: function(chirpType, ...args) {
            const actionId = args[0];
            const dataStr = args[1];

            const callbacks = activeActions.get(actionId);
            if (!callbacks) {
                // Could be a stale message or action already completed
                return;
            }

            let data;
            try {
                data = dataStr ? JSON.parse(dataStr) : {};
            } catch(e) {
                data = { raw: dataStr };
            }

            switch(chirpType) {
                case "game.action.start":
                    callbacks.onStart(actionId, data);
                    break;
                case "game.action.progress":
                    callbacks.onProgress(actionId, data);
                    break;
                case "game.action.complete":
                    callbacks.onComplete(actionId, data);
                    activeActions.delete(actionId);
                    break;
                case "game.action.error":
                    callbacks.onError(actionId, data);
                    activeActions.delete(actionId);
                    break;
                case "game.action.interrupt":
                    callbacks.onInterrupt(actionId, data);
                    break;
                case "game.action.cancel":
                    // Server confirmed cancellation
                    activeActions.delete(actionId);
                    break;
            }
        },

        /**
         * Push event subscription callbacks
         * Set these to receive server-pushed events
         */
        subscriptions: {
            onSituationUpdate: null,
            onStateUpdate: null,
            onThreatDetected: null,
            onThreatRemoved: null,
            onThreatChanged: null,
            onNpcMoved: null,
            onNpcAction: null,
            onNpcDied: null,
            onInteractionStart: null,
            onInteractionPhase: null,
            onInteractionEnd: null,
            onTimeAdvanced: null,
            onIncrementEnd: null,
            onEventOccurred: null
        },

        /**
         * Subscribe to push events for a character
         *
         * @param {string} charId - Character objectId to subscribe for
         */
        subscribe: function(charId) {
            if (subscribedCharacters.has(charId)) {
                return; // Already subscribed
            }

            page.wss.send("game", JSON.stringify({
                subscribe: true,
                charId: charId
            }), undefined, "game.subscribe");

            subscribedCharacters.add(charId);
        },

        /**
         * Unsubscribe from push events for a character
         *
         * @param {string} charId - Character objectId to unsubscribe
         */
        unsubscribe: function(charId) {
            if (!subscribedCharacters.has(charId)) {
                return;
            }

            page.wss.send("game", JSON.stringify({
                unsubscribe: true,
                charId: charId
            }), undefined, "game.unsubscribe");

            subscribedCharacters.delete(charId);
        },

        /**
         * Unsubscribe from all characters and clear subscriptions
         */
        unsubscribeAll: function() {
            subscribedCharacters.forEach(charId => {
                page.wss.send("game", JSON.stringify({
                    unsubscribe: true,
                    charId: charId
                }), undefined, "game.unsubscribe");
            });
            subscribedCharacters.clear();

            // Clear all subscription callbacks
            Object.keys(this.subscriptions).forEach(key => {
                this.subscriptions[key] = null;
            });
        },

        /**
         * Route incoming game push messages from websocket
         * Called by pageClient.js routeMessage()
         *
         * @param {string} chirpType - Message type: game.situation.update, game.threat.detected, etc.
         * @param {array} args - Remaining chirp arguments
         */
        routePushMessage: function(chirpType, ...args) {
            const handlers = {
                "game.situation.update": "onSituationUpdate",
                "game.state.update": "onStateUpdate",
                "game.threat.detected": "onThreatDetected",
                "game.threat.removed": "onThreatRemoved",
                "game.threat.changed": "onThreatChanged",
                "game.npc.moved": "onNpcMoved",
                "game.npc.action": "onNpcAction",
                "game.npc.died": "onNpcDied",
                "game.interaction.start": "onInteractionStart",
                "game.interaction.phase": "onInteractionPhase",
                "game.interaction.end": "onInteractionEnd",
                "game.time.advanced": "onTimeAdvanced",
                "game.increment.end": "onIncrementEnd",
                "game.event.occurred": "onEventOccurred"
            };

            const handlerName = handlers[chirpType];
            if (!handlerName) {
                console.warn("Unknown game push message type: " + chirpType);
                return;
            }

            const handler = this.subscriptions[handlerName];
            if (!handler) {
                // No handler registered for this event type
                return;
            }

            // Parse arguments
            const parsedArgs = args.map(arg => {
                if (typeof arg === 'string') {
                    try {
                        return JSON.parse(arg);
                    } catch(e) {
                        return arg;
                    }
                }
                return arg;
            });

            handler(...parsedArgs);
        },

        /**
         * Check if subscribed to a character
         *
         * @param {string} charId
         * @returns {boolean}
         */
        isSubscribed: function(charId) {
            return subscribedCharacters.has(charId);
        },

        /**
         * Get all subscribed character IDs
         *
         * @returns {string[]}
         */
        getSubscribedCharacters: function() {
            return Array.from(subscribedCharacters);
        },

        /**
         * Clear all active actions (e.g., on disconnect)
         */
        clearActiveActions: function() {
            activeActions.clear();
        }
    };

    // Export
    if (typeof module !== "undefined") {
        module.exports = gameStream;
    } else {
        window.gameStream = gameStream;
    }

}());
