/**
 * MoodEmojiDisplay - Shows large emojis for current emotion and gender in test mode
 * Supports LLM-suggested mood with crossfade animation
 */
(function() {

    const MoodEmojiDisplay = {
        _fadeState: 'visible',  // 'visible' | 'fading-out' | 'fading-in'
        _displayedEmotion: null,
        _displayedGender: null,
        _fadeTimeout: null,

        oninit(vnode) {
            this._displayedEmotion = vnode.attrs.emotion || 'neutral';
            this._displayedGender = vnode.attrs.gender || null;
        },

        onupdate(vnode) {
            const targetEmotion = vnode.attrs.suggestedEmotion || vnode.attrs.emotion || 'neutral';
            const targetGender = vnode.attrs.suggestedGender || vnode.attrs.gender || null;

            // Detect change — trigger crossfade
            if (targetEmotion !== this._displayedEmotion || targetGender !== this._displayedGender) {
                if (this._fadeState === 'visible') {
                    this._fadeState = 'fading-out';
                    if (this._fadeTimeout) clearTimeout(this._fadeTimeout);
                    this._fadeTimeout = setTimeout(() => {
                        this._displayedEmotion = targetEmotion;
                        this._displayedGender = targetGender;
                        this._fadeState = 'fading-in';
                        m.redraw();
                        this._fadeTimeout = setTimeout(() => {
                            this._fadeState = 'visible';
                            m.redraw();
                        }, 300);
                    }, 300);
                    m.redraw();
                }
            }
        },

        onremove() {
            if (this._fadeTimeout) clearTimeout(this._fadeTimeout);
        },

        view(vnode) {
            const BiometricThemer = Magic8.BiometricThemer;
            const emotionEmoji = BiometricThemer.emotionEmojis[this._displayedEmotion] || '\u{1F610}';
            const genderEmoji = this._displayedGender ? (BiometricThemer.genderEmojis[this._displayedGender] || '') : '';

            const opacity = this._fadeState === 'fading-out' ? 0 : 1;
            const isSuggested = vnode.attrs.suggestedEmotion || vnode.attrs.suggestedGender;

            return m('.fixed.z-45', {
                style: {
                    top: '16px',
                    left: '16px',
                    display: 'flex',
                    gap: '12px',
                    pointerEvents: 'none',
                    opacity: opacity,
                    transition: 'opacity 300ms ease-in-out'
                }
            }, [
                m('span', {
                    style: {
                        fontSize: '96px',
                        lineHeight: '1',
                        filter: isSuggested ? 'drop-shadow(0 0 8px rgba(255,255,255,0.5))' : 'none'
                    },
                    title: 'Emotion: ' + this._displayedEmotion
                }, emotionEmoji),
                genderEmoji && m('span', {
                    style: {
                        fontSize: '96px',
                        lineHeight: '1',
                        filter: isSuggested ? 'drop-shadow(0 0 8px rgba(255,255,255,0.5))' : 'none'
                    },
                    title: 'Gender: ' + this._displayedGender
                }, genderEmoji)
            ]);
        }
    };

    // Export
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = MoodEmojiDisplay;
    }
    if (typeof window !== 'undefined') {
        window.Magic8 = window.Magic8 || {};
        window.Magic8.MoodEmojiDisplay = MoodEmojiDisplay;
    }

}());
