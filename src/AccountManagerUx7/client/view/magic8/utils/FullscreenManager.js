/**
 * FullscreenManager - Fullscreen API wrapper for Magic8 immersive experience
 */
class FullscreenManager {
    constructor() {
        this.isFullscreen = false;
        this.element = null;
        this.changeCallbacks = [];

        // Bind event listener
        this._handleFullscreenChange = this._handleFullscreenChange.bind(this);
        document.addEventListener('fullscreenchange', this._handleFullscreenChange);
        document.addEventListener('webkitfullscreenchange', this._handleFullscreenChange);
        document.addEventListener('mozfullscreenchange', this._handleFullscreenChange);
        document.addEventListener('MSFullscreenChange', this._handleFullscreenChange);
    }

    /**
     * Enter fullscreen mode for the specified element
     * @param {HTMLElement} element - Element to make fullscreen
     * @returns {Promise<void>}
     */
    async enterFullscreen(element) {
        if (!element) {
            console.warn('FullscreenManager: No element provided');
            return;
        }

        this.element = element;

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
        } catch (err) {
            console.error('FullscreenManager: Error entering fullscreen:', err);
            throw err;
        }
    }

    /**
     * Exit fullscreen mode
     * @returns {Promise<void>}
     */
    async exitFullscreen() {
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
     * Check if currently in fullscreen mode
     * @returns {boolean}
     */
    getIsFullscreen() {
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
     * Check if fullscreen is supported
     * @returns {boolean}
     */
    static isSupported() {
        return !!(
            document.fullscreenEnabled ||
            document.webkitFullscreenEnabled ||
            document.mozFullScreenEnabled ||
            document.msFullscreenEnabled
        );
    }

    /**
     * Clean up event listeners
     */
    dispose() {
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
