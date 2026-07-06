/**
 * CardGame UI - Talk/Chat Interface
 * Message display with player/NPC styling, text input and send button,
 * end conversation button, LLM integration with fallback responses,
 * and auto-scroll to bottom.
 *
 * Extracted from cardGame-v2.js (lines ~9233-9455).
 *
 * Depends on:
 *   - CardGame.Constants (GAME_PHASES)
 *   - CardGame.ctx (gameState, gameChatManager, gameVoice)
 *   - CardGame.Actions (advanceResolution)
 *
 * Exposes: window.CardGame.UI.TalkChatUI, window.CardGame.UI.openTalkChat
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.UI = window.CardGame.UI || {};

    // ── Talk Card Chat UI (Phase 8) ───────────────────────────────────
    function TalkChatUI() {
        let inputRef = null;

        async function sendMessage() {
            let ctx = window.CardGame.ctx || {};
            let gameState = ctx.gameState;
            let gameChatManager = ctx.gameChatManager;
            let gameVoice = ctx.gameVoice;

            if (!gameState?.chat?.active || gameState.chat.pending) return;
            const text = gameState.chat.inputText?.trim();
            if (!text) return;

            // Add player message
            gameState.chat.messages.push({
                role: "player",
                text: text,
                timestamp: Date.now()
            });
            gameState.chat.inputText = "";
            gameState.chat.pending = true;
            m.redraw();

            // Build game context for richer NPC responses
            let gameContext = null;
            if (gameState) {
                const buildEmotionContext = window.CardGame.AI?.buildEmotionContext;
                gameContext = {
                    playerName: gameState.player?.character?.name || "Player",
                    playerHp: gameState.player?.hp,
                    opponentHp: gameState.opponent?.hp,
                    opponentMorale: gameState.opponent?.morale,
                    round: gameState.round,
                    playerEmotion: buildEmotionContext ? buildEmotionContext() : null
                };
            }

            // Get NPC response
            if (gameChatManager?.initialized) {
                try {
                    const response = await gameChatManager.sendMessage(text, gameContext);
                    if (response && !response.error) {
                        gameState.chat.messages.push({
                            role: "npc",
                            text: response.text,
                            speaker: response.speaker,
                            timestamp: Date.now()
                        });
                        // Speak with opponent voice if available
                        if (gameVoice?.enabled && !gameVoice.subtitlesOnly && response.text) {
                            gameVoice.speak(response.text);
                        }
                    } else {
                        // LLM returned error
                        gameState.chat.messages.push({
                            role: "npc",
                            text: "*looks at you thoughtfully but says nothing*",
                            timestamp: Date.now()
                        });
                    }
                } catch (err) {
                    console.warn("[TalkChatUI] Chat failed:", err);
                    gameState.chat.messages.push({
                        role: "npc",
                        text: "*pauses, distracted*",
                        timestamp: Date.now()
                    });
                }
            } else {
                // Fallback: generate contextual responses based on NPC and player input
                const npcName = gameState.chat.npcName || "Opponent";
                const fallbackResponses = [
                    `*${npcName} considers your words carefully*`,
                    `*${npcName} narrows their eyes at you*`,
                    `"Hmm..." *${npcName} seems unimpressed*`,
                    `*${npcName} tilts their head, listening*`,
                    `"We shall see..." *${npcName} mutters*`
                ];
                const randomResponse = fallbackResponses[Math.floor(Math.random() * fallbackResponses.length)];
                gameState.chat.messages.push({
                    role: "npc",
                    text: randomResponse,
                    timestamp: Date.now()
                });
                console.log("[TalkChatUI] Using fallback response - LLM not initialized. Reason:", gameChatManager?.lastError || "gameChatManager is null");
            }

            gameState.chat.pending = false;
            m.redraw();

            // Auto-scroll to bottom
            setTimeout(() => {
                const msgArea = document.querySelector(".cg2-chat-messages");
                if (msgArea) msgArea.scrollTop = msgArea.scrollHeight;
            }, 50);
        }

        function endChat() {
            let ctx = window.CardGame.ctx || {};
            let gameState = ctx.gameState;
            let gameChatManager = ctx.gameChatManager;
            let GAME_PHASES = CardGame.Constants.GAME_PHASES;

            if (!gameState?.chat?.active) return;

            // Conclude conversation
            if (gameChatManager?.initialized) {
                gameChatManager.concludeConversation();
            }

            // Calculate morale effect based on conversation
            const msgCount = gameState.chat.messages.length;
            const playerMsgs = gameState.chat.messages.filter(m => m.role === "player").length;

            // Apply morale effects based on conversation quality
            if (msgCount >= 4 && playerMsgs >= 2) {
                // Good conversation: player gains morale, opponent loses some
                gameState.player.morale = Math.min(gameState.player.maxMorale, gameState.player.morale + 2);
                gameState.opponent.morale = Math.max(0, gameState.opponent.morale - 1);
                page.toast("info", "Productive conversation! +2 morale");
            } else if (msgCount >= 2) {
                // Brief exchange
                gameState.player.morale = Math.min(gameState.player.maxMorale, gameState.player.morale + 1);
                page.toast("info", "Brief exchange. +1 morale");
            }

            // Mark Talk card position as resolved
            if (gameState.chat.talkPosition !== null) {
                const pos = gameState.actionBar.positions[gameState.chat.talkPosition];
                if (pos) pos.resolved = true;
            }

            // Close chat (Silence Rule: lock chat again)
            gameState.chat.active = false;
            gameState.chat.unlocked = false;  // Silence Rule: chat locked until next Talk card
            gameState.chat.messages = [];
            gameState.chat.talkCard = null;
            gameState.chat.talkPosition = null;

            console.log("[CardGame v2] Chat ended (chat locked)");
            m.redraw();

            // Continue resolution after chat ends
            if (gameState.phase === GAME_PHASES.RESOLUTION) {
                setTimeout(() => CardGame.GameState.advanceResolution(), 500);
            }
        }

        return {
            view() {
                let ctx = window.CardGame.ctx || {};
                let gameState = ctx.gameState;

                if (!gameState?.chat?.active) return null;

                const npcName = gameState.chat.npcName || "Opponent";
                const npcPortrait = gameState.opponent?.character?.portraitUrl;

                return m("div", { class: "cg2-chat-overlay" }, [
                    m("div", { class: "cg2-chat-panel" }, [
                        // Header
                        m("div", { class: "cg2-chat-header" }, [
                            npcPortrait
                                ? m("img", { class: "cg2-chat-portrait", src: npcPortrait, alt: npcName })
                                : m("span", { class: "material-symbols-outlined cg2-chat-portrait-icon" }, "person"),
                            m("span", { class: "cg2-chat-npc-name" }, npcName),
                            m("button", {
                                class: "cg2-btn cg2-btn-sm",
                                onclick: endChat,
                                title: "End conversation"
                            }, [
                                m("span", { class: "material-symbols-outlined" }, "close")
                            ])
                        ]),

                        // Messages
                        m("div", { class: "cg2-chat-messages" },
                            gameState.chat.messages.length === 0
                                ? m("div", { class: "cg2-chat-hint" }, `Start a conversation with ${npcName}...`)
                                : gameState.chat.messages.map(msg =>
                                    m("div", {
                                        class: "cg2-chat-message cg2-chat-" + msg.role
                                    }, [
                                        m("span", { class: "cg2-chat-speaker" },
                                            msg.role === "player" ? "You" : (msg.speaker || npcName)),
                                        m("span", { class: "cg2-chat-text" }, msg.text)
                                    ])
                                )
                        ),

                        // Input
                        m("div", { class: "cg2-chat-input-area" }, [
                            m("input", {
                                type: "text",
                                class: "cg2-chat-input",
                                placeholder: "Type your message...",
                                value: gameState.chat.inputText || "",
                                disabled: gameState.chat.pending,
                                oninput: e => { gameState.chat.inputText = e.target.value; },
                                onkeydown: e => { if (e.key === "Enter") sendMessage(); },
                                oncreate: v => { inputRef = v.dom; v.dom.focus(); }
                            }),
                            m("button", {
                                class: "cg2-btn cg2-btn-primary cg2-chat-send",
                                onclick: sendMessage,
                                disabled: gameState.chat.pending || !gameState.chat.inputText?.trim()
                            }, gameState.chat.pending
                                ? m("span", { class: "material-symbols-outlined cg2-spin" }, "sync")
                                : m("span", { class: "material-symbols-outlined" }, "send")
                            )
                        ]),

                        // End conversation button
                        m("div", { class: "cg2-chat-actions" }, [
                            m("button", {
                                class: "cg2-btn cg2-btn-secondary",
                                onclick: endChat
                            }, "End Conversation")
                        ])
                    ])
                ]);
            }
        };
    }

    // Open chat for Talk card
    function openTalkChat(talkCard, positionIndex) {
        let ctx = window.CardGame.ctx || {};
        let gameState = ctx.gameState;
        let gameChatManager = ctx.gameChatManager;

        if (!gameState) return;

        const npcName = gameState.opponent?.character?.name || "Opponent";

        // Initialize chat state (Silence Rule: unlock chat)
        gameState.chat.active = true;
        gameState.chat.unlocked = true;  // Silence Rule: chat now available
        gameState.chat.messages = [];
        gameState.chat.npcName = npcName;
        gameState.chat.inputText = "";
        gameState.chat.pending = false;
        gameState.chat.talkCard = talkCard;
        gameState.chat.talkPosition = positionIndex;

        // Start conversation in chat manager
        if (gameChatManager?.initialized) {
            gameChatManager.startConversation();
        }

        console.log("[CardGame v2] Talk chat opened with:", npcName, "(chat unlocked)");
        m.redraw();
    }

    window.CardGame.UI.TalkChatUI = TalkChatUI;
    window.CardGame.UI.openTalkChat = openTalkChat;

})();
