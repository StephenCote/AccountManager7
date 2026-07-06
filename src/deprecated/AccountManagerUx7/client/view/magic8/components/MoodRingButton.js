/**
 * MoodRingButton - Top-right toggle button showing emotion/gender emojis
 * When enabled, acts as a mood ring with mood-colored background
 */
(function() {

    const MoodRingButton = {
        view(vnode) {
            const { enabled, emotion, gender, suggestedEmotion, suggestedGender, moodColor, onclick } = vnode.attrs;

            const BiometricThemer = Magic8.BiometricThemer;
            const displayEmotion = suggestedEmotion || emotion || 'neutral';
            const displayGender = suggestedGender || gender || null;
            const emotionEmoji = BiometricThemer.emotionEmojis[displayEmotion] || '\u{1F610}';
            const genderEmoji = displayGender ? (BiometricThemer.genderEmojis[displayGender] || '') : '';

            // Background color from mood when enabled
            let bgStyle;
            if (enabled && moodColor) {
                const [r, g, b] = moodColor;
                bgStyle = `rgba(${r}, ${g}, ${b}, 0.6)`;
            } else {
                bgStyle = 'rgba(30, 30, 40, 0.7)';
            }

            return m('.fixed.z-50', {
                style: {
                    top: '16px',
                    right: '16px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '4px',
                    padding: '6px 12px',
                    borderRadius: '24px',
                    backgroundColor: bgStyle,
                    border: enabled ? '2px solid rgba(255,255,255,0.4)' : '2px solid rgba(255,255,255,0.15)',
                    cursor: 'pointer',
                    transition: 'background-color 1s ease-in-out, border-color 0.3s ease',
                    userSelect: 'none',
                    backdropFilter: 'blur(4px)'
                },
                onclick: onclick,
                title: enabled ? 'Mood Ring: ON (click to disable)' : 'Mood Ring: OFF (click to enable)'
            }, [
                m('span', { style: { fontSize: '32px', lineHeight: '1' } }, emotionEmoji),
                genderEmoji && m('span', { style: { fontSize: '32px', lineHeight: '1' } }, genderEmoji)
            ]);
        }
    };

    // Export
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = MoodRingButton;
    }
    if (typeof window !== 'undefined') {
        window.Magic8 = window.Magic8 || {};
        window.Magic8.MoodRingButton = MoodRingButton;
    }

}());
