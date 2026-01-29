/**
 * HypnoCanvas - Full-screen particle animation canvas with theme-responsive colors
 * Mithril.js component for the visual particle effect layer
 */
const HypnoCanvas = {
    PARTICLE_COUNT: 100,
    particles: [],
    canvas: null,
    ctx: null,
    animationFrame: null,
    isInitialized: false,

    /**
     * Particle color from theme (updated dynamically)
     */
    particleColor: { r: 150, g: 180, b: 255 },

    /**
     * Create a new particle
     * @param {Object} existing - Existing particle to reset (optional)
     * @returns {Object} Particle object
     */
    createParticle(existing) {
        const canvas = this.canvas;
        return {
            x: existing ? existing.x : Math.random() * (canvas?.width || window.innerWidth),
            y: existing ? existing.y : Math.random() * (canvas?.height || window.innerHeight),
            vx: (Math.random() - 0.5) * 0.5,
            vy: (Math.random() - 0.5) * 0.5,
            radius: Math.random() * 3 + 1,
            alpha: Math.random() * 0.5 + 0.1,
            pulsePhase: Math.random() * Math.PI * 2,
            pulseSpeed: 0.02 + Math.random() * 0.02
        };
    },

    /**
     * Initialize the canvas and particles
     * @param {HTMLCanvasElement} canvasElement - Canvas DOM element
     */
    setupCanvas(canvasElement) {
        if (this.isInitialized) return;

        this.canvas = canvasElement;
        this.ctx = canvasElement.getContext('2d');

        // Set canvas size to full viewport
        this.resizeCanvas();

        // Create particles
        this.particles = [];
        for (let i = 0; i < this.PARTICLE_COUNT; i++) {
            this.particles.push(this.createParticle());
        }

        this.isInitialized = true;
        this.animate();
    },

    /**
     * Resize canvas to fill viewport
     */
    resizeCanvas() {
        if (!this.canvas) return;
        this.canvas.width = window.innerWidth;
        this.canvas.height = window.innerHeight;
    },

    /**
     * Update particle color from theme
     * @param {Object} theme - Theme object with particle color
     */
    updateTheme(theme) {
        if (theme && theme.particle) {
            this.particleColor = {
                r: theme.particle[0],
                g: theme.particle[1],
                b: theme.particle[2]
            };
        } else if (theme && theme.accent) {
            // Fallback to accent color
            this.particleColor = {
                r: theme.accent[0],
                g: theme.accent[1],
                b: theme.accent[2]
            };
        }
    },

    /**
     * Animation loop
     */
    animate() {
        if (!this.ctx || !this.canvas) return;

        // Clear canvas
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

        // Update and draw particles
        this.particles.forEach(p => {
            // Move particle
            p.x += p.vx;
            p.y += p.vy;

            // Bounce off edges
            if (p.x < 0 || p.x > this.canvas.width) p.vx *= -1;
            if (p.y < 0 || p.y > this.canvas.height) p.vy *= -1;

            // Pulse alpha
            p.pulsePhase += p.pulseSpeed;
            const pulseFactor = 0.5 + 0.5 * Math.sin(p.pulsePhase);

            // Fade out over time
            p.alpha -= 0.001;
            if (p.alpha <= 0) {
                Object.assign(p, this.createParticle());
            }

            // Draw particle with glow effect
            const alpha = p.alpha * pulseFactor;
            const { r, g, b } = this.particleColor;

            // Outer glow
            this.ctx.beginPath();
            this.ctx.arc(p.x, p.y, p.radius * 2, 0, Math.PI * 2);
            this.ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha * 0.3})`;
            this.ctx.fill();

            // Inner particle
            this.ctx.beginPath();
            this.ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
            this.ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha})`;
            this.ctx.fill();
        });

        // Continue animation
        this.animationFrame = requestAnimationFrame(() => this.animate());
    },

    /**
     * Stop animation
     */
    stopAnimation() {
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
            this.animationFrame = null;
        }
    },

    /**
     * Resume animation
     */
    resumeAnimation() {
        if (!this.animationFrame && this.isInitialized) {
            this.animate();
        }
    },

    /**
     * Clean up resources
     */
    cleanup() {
        this.stopAnimation();
        this.particles = [];
        this.canvas = null;
        this.ctx = null;
        this.isInitialized = false;
    },

    // Mithril lifecycle hooks
    oninit(vnode) {
        // Handle window resize
        this._resizeHandler = () => this.resizeCanvas();
        window.addEventListener('resize', this._resizeHandler);
    },

    oncreate(vnode) {
        this.setupCanvas(vnode.dom);

        // Apply initial theme if provided
        if (vnode.attrs.theme) {
            this.updateTheme(vnode.attrs.theme);
        }
    },

    onupdate(vnode) {
        // Update theme when changed
        if (vnode.attrs.theme) {
            this.updateTheme(vnode.attrs.theme);
        }
    },

    onremove(vnode) {
        window.removeEventListener('resize', this._resizeHandler);
        this.cleanup();
    },

    view(vnode) {
        const { theme, class: className } = vnode.attrs;

        return m('canvas.hypno-canvas', {
            class: className || 'absolute inset-0 z-5 pointer-events-none',
            style: {
                mixBlendMode: 'screen'
            }
        });
    }
};

// Factory function to create independent instances
function createHypnoCanvas() {
    return {
        ...HypnoCanvas,
        particles: [],
        canvas: null,
        ctx: null,
        animationFrame: null,
        isInitialized: false,
        particleColor: { r: 150, g: 180, b: 255 }
    };
}

// Export
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { HypnoCanvas, createHypnoCanvas };
}
if (typeof window !== 'undefined') {
    window.Magic8 = window.Magic8 || {};
    window.Magic8.HypnoCanvas = HypnoCanvas;
    window.Magic8.createHypnoCanvas = createHypnoCanvas;
}
