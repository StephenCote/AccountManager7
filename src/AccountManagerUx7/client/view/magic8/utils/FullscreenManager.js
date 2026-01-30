/**
 * FullscreenManager - Fullscreen API wrapper for Magic8 immersive experience
 * Falls back to CSS-based fullscreen on iOS/browsers without Fullscreen API support
 */
class FullscreenManager {
    constructor() {
        this.isFullscreen = false;
        this.element = null;
        this.changeCallbacks = [];
        this._usingFallback = false;

        // Bind event listener
        this._handleFullscreenChange = this._handleFullscreenChange.bind(this);
        document.addEventListener('fullscreenchange', this._handleFullscreenChange);
        document.addEventListener('webkitfullscreenchange', this._handleFullscreenChange);
        document.addEventListener('mozfullscreenchange', this._handleFullscreenChange);
        document.addEventListener('MSFullscreenChange', this._handleFullscreenChange);
    }

    /**
     * Check if the native Fullscreen API is available
     * @returns {boolean}
     * @private
     */
    _hasNativeFullscreen() {
        return !!(
            document.documentElement.requestFullscreen ||
            document.documentElement.webkitRequestFullscreen ||
            document.documentElement.mozRequestFullScreen ||
            document.documentElement.msRequestFullscreen
        );
    }

    /**
     * Enter fullscreen mode for the specified element.
     * Falls back to CSS-based fullscreen on iOS/unsupported browsers.
     * @param {HTMLElement} element - Element to make fullscreen
     * @returns {Promise<void>}
     */
    async enterFullscreen(element) {
        if (!element) {
            console.warn('FullscreenManager: No element provided');
            return;
        }

        this.element = element;

        if (this._hasNativeFullscreen()) {
            try {
                if (element.requestFullscreen) {
                    await element.requestFullscreen();
                } else if (element.webkitRequestFullscreen) {
                    await element.webkitRequestFullscreen();
                } else if (element.mozRequestFullScreen) {
                    await element.mozRequestFullScreen();
                } else if (element.msRequestFullscreen) {
                    await element.msRequestFullscreen();
                }
                this.isFullscreen = true;
                return;
            } catch (err) {
                console.warn('FullscreenManager: Native fullscreen failed, using fallback:', err);
            }
        }

        // CSS-based fallback (iOS Safari, Brave on iOS, etc.)
        this._enterFallbackFullscreen(element);
    }

    /**
     * CSS-based fullscreen fallback for iOS and other unsupported browsers
     * @param {HTMLElement} element
     * @private
     */
    _enterFallbackFullscreen(element) {
        this._usingFallback = true;

        // Apply fullscreen-like styles
        element.style.position = 'fixed';
        element.style.top = '0';
        element.style.left = '0';
        element.style.width = '100vw';
        element.style.height = '100vh';
        element.style.zIndex = '99999';

        // Prevent body scrolling
        this._origBodyOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';

        // Scroll to minimize address bar on iOS
        window.scrollTo(0, 1);

        this.isFullscreen = true;
        console.log('FullscreenManager: Using CSS fallback fullscreen');

        // Notify callbacks
        this.changeCallbacks.forEach(cb => {
            try { cb(true, element); } catch (e) { /* ignore */ }
        });
    }

    /**
     * Exit fullscreen mode
     * @returns {Promise<void>}
     */
    async exitFullscreen() {
        if (this._usingFallback) {
            this._exitFallbackFullscreen();
            return;
        }

        if (!this.getIsFullscreen()) {
            this.isFullscreen = false;
            this.element = null;
            return;
        }

        try {
            if (document.exitFullscreen) {
                await document.exitFullscreen();
            } else if (document.webkitExitFullscreen) {
                await document.webkitExitFullscreen();
            } else if (document.mozCancelFullScreen) {
                await document.mozCancelFullScreen();
            } else if (document.msExitFullscreen) {
                await document.msExitFullscreen();
            }
            this.isFullscreen = false;
            this.element = null;
        } catch (err) {
            console.error('FullscreenManager: Error exiting fullscreen:', err);
        }
    }

    /**
     * Exit CSS-based fallback fullscreen
     * @private
     */
    _exitFallbackFullscreen() {
        if (this.element) {
            this.element.style.position = '';
            this.element.style.top = '';
            this.element.style.left = '';
            this.element.style.width = '';
            this.element.style.height = '';
            this.element.style.zIndex = '';
        }

        document.body.style.overflow = this._origBodyOverflow || '';
        this._origBodyOverflow = null;

        this._usingFallback = false;
        this.isFullscreen = false;

        // Notify callbacks
        this.changeCallbacks.forEach(cb => {
            try { cb(false, null); } catch (e) { /* ignore */ }
        });

        this.element = null;
    }

    /**
     * Toggle fullscreen mode
     * @param {HTMLElement} element - Element to toggle fullscreen (required when entering)
     * @returns {Promise<void>}
     */
    async toggleFullscreen(element) {
        if (this.isFullscreen) {
            await this.exitFullscreen();
        } else {
            await this.enterFullscreen(element);
        }
    }

    /**
     * Check if currently in fullscreen mode (native or fallback)
     * @returns {boolean}
     */
    getIsFullscreen() {
        if (this._usingFallback) return true;
        return !!(
            document.fullscreenElement ||
            document.webkitFullscreenElement ||
            document.mozFullScreenElement ||
            document.msFullscreenElement
        );
    }

    /**
     * Get the current fullscreen element
     * @returns {Element|null}
     */
    getFullscreenElement() {
        return (
            document.fullscreenElement ||
            document.webkitFullscreenElement ||
            document.mozFullScreenElement ||
            document.msFullscreenElement ||
            null
        );
    }

    /**
     * Register a callback for fullscreen changes
     * @param {Function} callback - Function to call on fullscreen change
     */
    onFullscreenChange(callback) {
        if (typeof callback === 'function') {
            this.changeCallbacks.push(callback);
        }
    }

    /**
     * Remove a fullscreen change callback
     * @param {Function} callback - Callback to remove
     */
    offFullscreenChange(callback) {
        this.changeCallbacks = this.changeCallbacks.filter(cb => cb !== callback);
    }

    /**
     * Handle fullscreen change events
     * @private
     */
    _handleFullscreenChange() {
        this.isFullscreen = this.getIsFullscreen();
        if (!this.isFullscreen) {
            this.element = null;
        }

        // Notify all registered callbacks
        this.changeCallbacks.forEach(callback => {
            try {
                callback(this.isFullscreen, this.getFullscreenElement());
            } catch (err) {
                console.error('FullscreenManager: Callback error:', err);
            }
        });
    }

    /**
     * Check if fullscreen is supported (always true with CSS fallback)
     * @returns {boolean}
     */
    static isSupported() {
        return true;
    }

    /**
     * Check if native Fullscreen API is supported (without fallback)
     * @returns {boolean}
     */
    static isNativeSupported() {
        return !!(
            document.fullscreenEnabled ||
            document.webkitFullscreenEnabled ||
            document.mozFullScreenEnabled ||
            document.msFullscreenEnabled
        );
    }

    /**
     * Clean up event listeners and fallback state
     */
    dispose() {
        if (this._usingFallback) {
            this._exitFallbackFullscreen();
        }
        document.removeEventListener('fullscreenchange', this._handleFullscreenChange);
        document.removeEventListener('webkitfullscreenchange', this._handleFullscreenChange);
        document.removeEventListener('mozfullscreenchange', this._handleFullscreenChange);
        document.removeEventListener('MSFullscreenChange', this._handleFullscreenChange);
        this.changeCallbacks = [];
        this.element = null;
    }
}

// Export for module systems and attach to window for direct access
if (typeof module !== 'undefined' && module.exports) {
    module.exports = FullscreenManager;
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.FullscreenManager = FullscreenManager;
}
