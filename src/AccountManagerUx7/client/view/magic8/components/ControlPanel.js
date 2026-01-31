/**
 * ControlPanel - Floating controls with auto-hide behavior
 * Mithril.js component for Magic8 session controls
 */
const ControlPanel = {
    oninit(vnode) {
        this.isVisible = true;
        this.hideTimeout = null;
        this.autoHideDelay = vnode.attrs.autoHideDelay || 5000;
        this.mouseInside = false;

        // Start auto-hide timer
        this._scheduleHide();
    },

    oncreate(vnode) {
        // Track mouse movement to show panel
        this._mouseMoveHandler = (e) => {
            // Show panel on mouse move near bottom of screen
            const threshold = window.innerHeight * 0.8;
            if (e.clientY > threshold || this.mouseInside) {
                this._showPanel();
            }
        };

        document.addEventListener('mousemove', this._mouseMoveHandler);

        // Track touch for mobile
        this._touchHandler = () => {
            this._showPanel();
        };
        document.addEventListener('touchstart', this._touchHandler);
    },

    onremove(vnode) {
        if (this.hideTimeout) {
            clearTimeout(this.hideTimeout);
        }
        document.removeEventListener('mousemove', this._mouseMoveHandler);
        document.removeEventListener('touchstart', this._touchHandler);
    },

    /**
     * Show the control panel
     * @private
     */
    _showPanel() {
        this.isVisible = true;
        this._scheduleHide();
        m.redraw();
    },

    /**
     * Schedule auto-hide
     * @private
     */
    _scheduleHide() {
        if (this.hideTimeout) {
            clearTimeout(this.hideTimeout);
        }

        if (this.autoHideDelay > 0 && !this.mouseInside) {
            this.hideTimeout = setTimeout(() => {
                this.isVisible = false;
                m.redraw();
            }, this.autoHideDelay);
        }
    },

    view(vnode) {
        const {
            config,
            state,
            onToggleRecording,
            onToggleAudio,
            onToggleFullscreen,
            onToggleMoodRing,
            onOpenConfig,
            onExit
        } = vnode.attrs;

        const isRecording = state?.isRecording || false;
        const isAudioEnabled = state?.audioEnabled || false;
        const isFullscreen = state?.isFullscreen || false;

        return m('.control-panel', {
            class: `
                fixed bottom-0 left-0 right-0 z-50
                flex justify-center items-center gap-3 sm:gap-4 p-4
                bg-gradient-to-t from-black/60 to-transparent
                transition-all duration-300
                ${this.isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-full pointer-events-none'}
            `,
            style: {
                paddingBottom: 'max(16px, env(safe-area-inset-bottom))'
            },
            onmouseenter: () => {
                this.mouseInside = true;
                this._showPanel();
            },
            onmouseleave: () => {
                this.mouseInside = false;
                this._scheduleHide();
            }
        }, [
            // Recording button
            config?.recording?.enabled && m('button.control-btn', {
                class: `
                    flex items-center gap-2 px-4 py-2 rounded-full
                    ${isRecording ? 'bg-red-600 animate-pulse' : 'bg-gray-800/80'}
                    hover:bg-gray-700 transition-colors
                    text-white font-medium
                `,
                onclick: onToggleRecording,
                title: isRecording ? 'Stop Recording' : 'Start Recording'
            }, [
                m('span.material-symbols-outlined', {
                    class: isRecording ? 'text-white' : 'text-red-500'
                }, isRecording ? 'stop_circle' : 'radio_button_checked'),
                m('span.hidden.sm:inline', isRecording ? 'Stop' : 'Record')
            ]),

            // Audio toggle button
            m('button.control-btn', {
                class: `
                    flex items-center gap-2 px-4 py-2 rounded-full
                    bg-gray-800/80 hover:bg-gray-700 transition-colors
                    text-white font-medium
                `,
                onclick: onToggleAudio,
                title: isAudioEnabled ? 'Disable Audio' : 'Enable Audio'
            }, [
                m('span.material-symbols-outlined',
                    isAudioEnabled ? 'volume_up' : 'volume_off'
                ),
                m('span.hidden.sm:inline', isAudioEnabled ? 'Audio On' : 'Audio Off')
            ]),

            // Fullscreen toggle
            m('button.control-btn', {
                class: `
                    flex items-center gap-2 px-4 py-2 rounded-full
                    bg-gray-800/80 hover:bg-gray-700 transition-colors
                    text-white font-medium
                `,
                onclick: onToggleFullscreen,
                title: isFullscreen ? 'Exit Fullscreen' : 'Enter Fullscreen'
            }, [
                m('span.material-symbols-outlined',
                    isFullscreen ? 'fullscreen_exit' : 'fullscreen'
                ),
                m('span.hidden.sm:inline', isFullscreen ? 'Exit' : 'Fullscreen')
            ]),

            // Mood ring toggle button
            (config?.biometrics?.enabled || config?.director?.enabled) && (() => {
                const BiometricThemer = window.Magic8?.BiometricThemer;
                const displayEmotion = state?.suggestedEmotion || state?.emotion || 'neutral';
                const displayGender = state?.suggestedGender || state?.gender || null;
                const emotionEmoji = BiometricThemer?.emotionEmojis?.[displayEmotion] || '\u{1F610}';
                const genderEmoji = displayGender ? (BiometricThemer?.genderEmojis?.[displayGender] || '') : '';
                return m('button.control-btn', {
                    class: `
                        flex items-center gap-2 px-4 py-2 rounded-full
                        ${state?.moodRingEnabled ? 'bg-purple-700/80' : 'bg-gray-800/80'}
                        hover:bg-gray-700 transition-colors
                        text-white font-medium
                    `,
                    onclick: onToggleMoodRing,
                    title: state?.moodRingEnabled ? 'Disable Mood Ring' : 'Enable Mood Ring'
                }, [
                    m('span', { style: { fontSize: '20px', lineHeight: '1' } }, emotionEmoji),
                    genderEmoji && m('span', { style: { fontSize: '20px', lineHeight: '1' } }, genderEmoji),
                    m('span.hidden.sm:inline', state?.moodRingEnabled ? 'Mood On' : 'Mood Ring')
                ]);
            })(),

            // Config button
            m('button.control-btn', {
                class: `
                    flex items-center gap-2 px-4 py-2 rounded-full
                    bg-gray-800/80 hover:bg-gray-700 transition-colors
                    text-white font-medium
                `,
                onclick: onOpenConfig,
                title: 'Session Settings'
            }, [
                m('span.material-symbols-outlined', 'settings'),
                m('span.hidden.sm:inline', 'Config')
            ]),

            // Spacer
            m('.flex-1'),

            // Exit button
            m('button.control-btn', {
                class: `
                    flex items-center gap-2 px-4 py-2 rounded-full
                    bg-red-900/80 hover:bg-red-800 transition-colors
                    text-white font-medium
                `,
                onclick: onExit,
                title: 'Exit Magic8'
            }, [
                m('span.material-symbols-outlined', 'close'),
                m('span.hidden.sm:inline', 'Exit')
            ])
        ]);
    }
};

/**
 * RecordingIndicator - Badge showing recording status
 */
const RecordingIndicator = {
    oninit(vnode) {
        this.elapsed = '00:00';
        this.updateInterval = null;
    },

    oncreate(vnode) {
        if (vnode.attrs.recorder) {
            this.updateInterval = setInterval(() => {
                this.elapsed = vnode.attrs.recorder.getFormattedDuration();
                m.redraw();
            }, 1000);
        }
    },

    onremove(vnode) {
        if (this.updateInterval) {
            clearInterval(this.updateInterval);
        }
    },

    view(vnode) {
        const { recorder } = vnode.attrs;

        if (!recorder || !recorder.isRecording) {
            return null;
        }

        return m('.recording-indicator', {
            class: `
                fixed left-4 z-50
                flex items-center gap-2 px-3 py-2 rounded-full
                bg-red-600 text-white font-medium
                animate-pulse
            `,
            style: {
                top: 'max(16px, env(safe-area-inset-top))'
            }
        }, [
            m('.w-3.h-3.rounded-full.bg-white.animate-ping'),
            m('span', 'REC'),
            m('span.font-mono', this.elapsed)
        ]);
    }
};

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { ControlPanel, RecordingIndicator };
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.ControlPanel = ControlPanel;
    window.Magic8.RecordingIndicator = RecordingIndicator;
}
