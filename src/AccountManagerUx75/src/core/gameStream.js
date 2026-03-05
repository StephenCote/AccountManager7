/**
 * gameStream.js — WebSocket game action streaming (ESM)
 * Port of Ux7 client/gameStream.js
 *
 * Provides: executeAction, cancelAction, subscribe/unsubscribe,
 * routeActionMessage, routePushMessage for game event streaming.
 */
import { am7model } from './model.js';

function getPage() { return am7model._page; }

let activeActions = new Map();
let subscribedCharacters = new Set();

function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

const gameStream = {
    executeAction: function(actionType, params, callbacks) {
        let page = getPage();
        const actionId = generateUUID();
        activeActions.set(actionId, {
            actionType,
            onStart: callbacks?.onStart || function(){},
            onProgress: callbacks?.onProgress || function(){},
            onComplete: callbacks?.onComplete || function(){},
            onError: callbacks?.onError || function(){},
            onInterrupt: callbacks?.onInterrupt || function(){}
        });

        if (page.wss) {
            page.wss.send("game", JSON.stringify({ actionId, actionType, params }), undefined, "game.action." + actionType);
        }
        return actionId;
    },

    cancelAction: function(actionId) {
        let page = getPage();
        if (!activeActions.has(actionId)) return;
        if (page.wss) {
            page.wss.send("game", JSON.stringify({ actionId, cancel: true }), undefined, "game.action.cancel");
        }
    },

    isActionActive: function(actionId) { return activeActions.has(actionId); },
    getActionType: function(actionId) { let a = activeActions.get(actionId); return a ? a.actionType : undefined; },

    routeActionMessage: function(chirpType, ...args) {
        const actionId = args[0];
        const dataStr = args[1];
        const callbacks = activeActions.get(actionId);
        if (!callbacks) return;

        let data;
        try { data = dataStr ? JSON.parse(dataStr) : {}; } catch(e) { data = { raw: dataStr }; }

        switch(chirpType) {
            case "game.action.start": callbacks.onStart(actionId, data); break;
            case "game.action.progress": callbacks.onProgress(actionId, data); break;
            case "game.action.complete": callbacks.onComplete(actionId, data); activeActions.delete(actionId); break;
            case "game.action.error": callbacks.onError(actionId, data); activeActions.delete(actionId); break;
            case "game.action.interrupt": callbacks.onInterrupt(actionId, data); break;
            case "game.action.cancel": activeActions.delete(actionId); break;
        }
    },

    subscriptions: {
        onSituationUpdate: null, onStateUpdate: null,
        onThreatDetected: null, onThreatRemoved: null, onThreatChanged: null,
        onNpcMoved: null, onNpcAction: null, onNpcDied: null,
        onInteractionStart: null, onInteractionPhase: null, onInteractionEnd: null,
        onTimeAdvanced: null, onIncrementEnd: null, onEventOccurred: null
    },

    subscribe: function(charId) {
        let page = getPage();
        if (subscribedCharacters.has(charId)) return;
        if (page.wss) page.wss.send("game", JSON.stringify({ subscribe: true, charId }), undefined, "game.subscribe");
        subscribedCharacters.add(charId);
    },

    unsubscribe: function(charId) {
        let page = getPage();
        if (!subscribedCharacters.has(charId)) return;
        if (page.wss) page.wss.send("game", JSON.stringify({ unsubscribe: true, charId }), undefined, "game.unsubscribe");
        subscribedCharacters.delete(charId);
    },

    unsubscribeAll: function() {
        let page = getPage();
        subscribedCharacters.forEach(charId => {
            if (page.wss) page.wss.send("game", JSON.stringify({ unsubscribe: true, charId }), undefined, "game.unsubscribe");
        });
        subscribedCharacters.clear();
        Object.keys(this.subscriptions).forEach(key => { this.subscriptions[key] = null; });
    },

    routePushMessage: function(chirpType, ...args) {
        const handlers = {
            "game.situation.update": "onSituationUpdate", "game.state.update": "onStateUpdate",
            "game.threat.detected": "onThreatDetected", "game.threat.removed": "onThreatRemoved",
            "game.threat.changed": "onThreatChanged", "game.npc.moved": "onNpcMoved",
            "game.npc.action": "onNpcAction", "game.npc.died": "onNpcDied",
            "game.interaction.start": "onInteractionStart", "game.interaction.phase": "onInteractionPhase",
            "game.interaction.end": "onInteractionEnd", "game.time.advanced": "onTimeAdvanced",
            "game.increment.end": "onIncrementEnd", "game.event.occurred": "onEventOccurred"
        };

        const handlerName = handlers[chirpType];
        if (!handlerName) return;
        const handler = this.subscriptions[handlerName];
        if (!handler) return;

        const parsedArgs = args.map(arg => {
            if (typeof arg === 'string') { try { return JSON.parse(arg); } catch(e) { return arg; } }
            return arg;
        });
        handler(...parsedArgs);
    },

    isSubscribed: function(charId) { return subscribedCharacters.has(charId); },
    getSubscribedCharacters: function() { return Array.from(subscribedCharacters); },
    clearActiveActions: function() { activeActions.clear(); }
};

export { gameStream };
export default gameStream;
