/**
 * CardGame AI -- Voice Synthesis & Emotion Detection
 * Extracted from cardGame-v2.js (lines ~7148-7280).
 *
 * Includes:
 *   - CardGameVoice          (TTS with queue, Web Audio API)
 *   - getPlayerEmotion       (Poker Face / moodRing integration)
 *   - getPlayerMoodColor     (mood colour from biometric)
 *   - buildEmotionContext     (builds context object for narrator)
 *
 * Depends on (optional, gracefully degrades):
 *   page.components.audio     (audio infrastructure)
 *   page.components.moodRing  (emotion / biometric capture)
 *   CardGame.UI.showNarrationSubtitle  (ui/phaseUI.js -- future)
 *
 * Exposes: window.CardGame.AI.{ CardGameVoice,
 *            getPlayerEmotion, getPlayerMoodColor, buildEmotionContext }
 */
(function() {
    "use strict";

    window.CardGame = window.CardGame || {};
    window.CardGame.AI = window.CardGame.AI || {};

    // Lazy accessor for showNarrationSubtitle (lives in a UI module loaded later)
    function showNarrationSubtitle(text) {
        if (typeof window.CardGame.UI?.showNarrationSubtitle === "function") {
            window.CardGame.UI.showNarrationSubtitle(text);
        } else {
            console.log("[CardGameVoice] Subtitle:", text);
        }
    }

    // ── CardGameVoice ────────────────────────────────────────────────
    // Voice synthesis for narrator using existing audio infrastructure
    let gameVoice = null;

    class CardGameVoice {
        constructor() {
            this.enabled = false;
            this.voiceProfileId = null;
            this.volume = 1.0;
            this.speaking = false;
            this.queue = [];
            this.subtitlesOnly = false;
        }

        async initialize(voiceConfig) {
            // Check if audio infrastructure exists
            if (typeof page?.components?.audio?.createAudioSource !== "function") {
                console.log("[CardGameVoice] Audio infrastructure not available, subtitles only");
                this.subtitlesOnly = true;
                this.enabled = true;
                return true;
            }

            this.voiceProfileId = voiceConfig?.voiceProfileId || null;
            this.volume = voiceConfig?.volume ?? 1.0;
            this.subtitlesOnly = voiceConfig?.subtitlesOnly ?? false;
            this.enabled = true;

            console.log("[CardGameVoice] Initialized. Profile:", this.voiceProfileId || "default");
            return true;
        }

        async speak(text, options = {}) {
            if (!this.enabled || !text) return;
            if (this.subtitlesOnly) {
                // Just show subtitles without audio
                showNarrationSubtitle(text);
                return;
            }

            // Queue if already speaking
            if (this.speaking) {
                this.queue.push({ text, options });
                return;
            }

            this.speaking = true;
            const name = "cardgame-voice-" + Date.now();

            try {
                // Use page.components.audio if available
                const audioComponent = page?.components?.audio;
                if (audioComponent?.createAudioSource) {
                    const source = await audioComponent.createAudioSource(name, this.voiceProfileId, text);
                    if (source) {
                        await this._playAudioSource(source);
                    }
                }
            } catch (err) {
                console.warn("[CardGameVoice] Speech synthesis failed:", err);
            } finally {
                this.speaking = false;
                // Process queue
                if (this.queue.length > 0) {
                    const next = this.queue.shift();
                    this.speak(next.text, next.options);
                }
            }
        }

        async _playAudioSource(source) {
            return new Promise((resolve) => {
                if (!source?.source || !source?.context) {
                    resolve();
                    return;
                }

                const sourceNode = source.context.createBufferSource();
                sourceNode.buffer = source.buffer;

                // Apply volume
                const gainNode = source.context.createGain();
                gainNode.gain.value = this.volume;

                sourceNode.connect(gainNode);
                gainNode.connect(source.context.destination);

                sourceNode.onended = () => resolve();
                sourceNode.start();
            });
        }

        setVolume(level) {
            this.volume = Math.max(0, Math.min(1, level));
        }

        stop() {
            this.queue = [];
            // Note: Stopping mid-playback would require tracking active source nodes
        }
    }

    // ── Poker Face Integration (Emotion Capture) ─────────────────────
    // Uses moodRing component for emotion detection

    function getPlayerEmotion() {
        if (!page?.components?.moodRing?.enabled()) return null;
        return page.components.moodRing.emotion();
    }

    function getPlayerMoodColor() {
        if (!page?.components?.moodRing?.enabled()) return null;
        return page.components.moodRing.moodColor();
    }

    // Build emotion context for narrator
    function buildEmotionContext() {
        const emotion = getPlayerEmotion();
        if (!emotion || emotion === "neutral") return null;

        const emotionDescriptions = {
            happy: "appears pleased",
            sad: "looks dejected",
            angry: "seems frustrated",
            fear: "appears nervous",
            surprise: "looks startled",
            disgust: "seems unimpressed"
        };

        return {
            emotion,
            description: emotionDescriptions[emotion] || null
        };
    }

    // ── Voice singleton accessor ─────────────────────────────────────
    function getVoice() { return gameVoice; }
    function setVoice(v) { gameVoice = v; }

    // ── Expose on CardGame.AI namespace ──────────────────────────────
    Object.assign(window.CardGame.AI, {
        CardGameVoice,
        getPlayerEmotion,
        getPlayerMoodColor,
        buildEmotionContext,
        getVoice,
        setVoice
    });

    console.log("[CardGame] AI/voice loaded");
}());
