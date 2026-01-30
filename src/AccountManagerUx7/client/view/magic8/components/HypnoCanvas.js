/**
 * HypnoCanvas - Multi-effect visual animation canvas with theme-responsive colors
 * Supports particles, spiral, mandala, tunnel effects with transitions
 * Mithril.js component for the visual effect layer
 */
const HypnoCanvas = {
    PARTICLE_COUNT: 100,
    particles: [],
    canvas: null,
    ctx: null,
    animationFrame: null,
    isInitialized: false,
    frame: 0,

    // Effect configuration
    effectMode: 'particles',
    effects: ['particles'],
    visuals: null,

    // Transition state
    prevEffectMode: null,
    transitionStart: 0,
    transitionDuration: 2000,

    // Auto-cycle state
    cycleIndex: 0,
    cycleTimer: null,

    // Effect-specific settings
    spiralSpeed: 0.01,
    mandalaLayers: 4,
    mandalaPetals: 12,
    tunnelRings: 12,

    /**
     * Particle color from theme (updated dynamically)
     */
    particleColor: { r: 150, g: 180, b: 255 },

    /**
     * Accent color from theme for non-particle effects
     */
    accentColor: { r: 150, g: 180, b: 255 },

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

        this.resizeCanvas();
        this._initParticles();

        this.isInitialized = true;
        this.frame = 0;
        this.animate();
    },

    /**
     * Initialize or reinitialize particles
     * @private
     */
    _initParticles() {
        const count = this.visuals?.particleCount || this.PARTICLE_COUNT;
        this.particles = [];
        for (let i = 0; i < count; i++) {
            this.particles.push(this.createParticle());
        }
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
     * Update colors from theme
     * @param {Object} theme - Theme object with particle/accent color
     */
    updateTheme(theme) {
        if (theme && theme.particle) {
            this.particleColor = {
                r: theme.particle[0],
                g: theme.particle[1],
                b: theme.particle[2]
            };
        } else if (theme && theme.accent) {
            this.particleColor = {
                r: theme.accent[0],
                g: theme.accent[1],
                b: theme.accent[2]
            };
        }
        if (theme && theme.accent) {
            this.accentColor = {
                r: theme.accent[0],
                g: theme.accent[1],
                b: theme.accent[2]
            };
        }
    },

    /**
     * Apply visuals configuration from session config
     * @param {Object} visuals - Visual effects config
     */
    applyVisuals(visuals) {
        if (!visuals) return;
        this.visuals = visuals;

        if (visuals.effects?.length) {
            this.effects = visuals.effects;
        }
        if (visuals.spiralSpeed) this.spiralSpeed = visuals.spiralSpeed;
        if (visuals.mandalaLayers) this.mandalaLayers = visuals.mandalaLayers;
        if (visuals.tunnelRings) this.tunnelRings = visuals.tunnelRings;
        if (visuals.transitionDuration) this.transitionDuration = visuals.transitionDuration;

        // Set initial mode
        if (visuals.mode === 'combined') {
            this.effectMode = 'combined';
        } else if (this.effects.length > 0) {
            this.effectMode = this.effects[0];
        }

        // Reinitialize particles if count changed
        if (visuals.particleCount && visuals.particleCount !== this.particles.length) {
            this._initParticles();
        }

        // Start auto-cycling if configured
        this._stopCycling();
        if (visuals.mode === 'cycle' && this.effects.length > 1) {
            this._startCycling(visuals.transitionInterval || 30000);
        }
    },

    /**
     * Start auto-cycling through effects
     * @param {number} interval - Milliseconds between transitions
     * @private
     */
    _startCycling(interval) {
        this.cycleIndex = 0;
        this.cycleTimer = setInterval(() => {
            this.cycleIndex = (this.cycleIndex + 1) % this.effects.length;
            this.transitionTo(this.effects[this.cycleIndex]);
        }, interval);
    },

    /**
     * Stop auto-cycling
     * @private
     */
    _stopCycling() {
        if (this.cycleTimer) {
            clearInterval(this.cycleTimer);
            this.cycleTimer = null;
        }
    },

    /**
     * Transition to a new effect mode with cross-fade
     * @param {string} newMode - Target effect mode
     * @param {number} [duration] - Transition duration in ms
     */
    transitionTo(newMode, duration) {
        if (newMode === this.effectMode) return;
        this.prevEffectMode = this.effectMode;
        this.effectMode = newMode;
        this.transitionStart = Date.now();
        if (duration != null) this.transitionDuration = duration;
    },

    /**
     * Draw a specific effect by name
     * @param {string} mode - Effect mode name
     * @private
     */
    _drawEffect(mode) {
        const ctx = this.ctx;
        const w = this.canvas.width;
        const h = this.canvas.height;

        switch (mode) {
            case 'particles':
                this._drawParticles(ctx, w, h);
                break;
            case 'spiral':
                this._drawSpiral(ctx, w, h);
                break;
            case 'mandala':
                this._drawMandala(ctx, w, h);
                break;
            case 'tunnel':
                this._drawTunnel(ctx, w, h);
                break;
        }
    },

    /**
     * Draw particle effect (glowing orbs)
     * @private
     */
    _drawParticles(ctx, w, h) {
        this.particles.forEach(p => {
            p.x += p.vx;
            p.y += p.vy;

            if (p.x < 0 || p.x > w) p.vx *= -1;
            if (p.y < 0 || p.y > h) p.vy *= -1;

            p.pulsePhase += p.pulseSpeed;
            const pulseFactor = 0.5 + 0.5 * Math.sin(p.pulsePhase);

            p.alpha -= 0.001;
            if (p.alpha <= 0) {
                Object.assign(p, this.createParticle());
            }

            const alpha = p.alpha * pulseFactor;
            const { r, g, b } = this.particleColor;

            // Outer glow
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.radius * 2, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha * 0.3})`;
            ctx.fill();

            // Inner particle
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha})`;
            ctx.fill();
        });
    },

    /**
     * Draw rotating spiral effect (theme-aware colors)
     * @private
     */
    _drawSpiral(ctx, w, h) {
        const centerX = w / 2;
        const centerY = h / 2;
        const speed = this.spiralSpeed;
        const pulse = 1 + 0.1 * Math.sin(this.frame * 0.005);
        const { r, g, b } = this.accentColor;

        const brightness = (r + g + b) / 3;
        const useHueCycle = brightness < 50;

        ctx.beginPath();
        if (useHueCycle) {
            const hue = (this.frame * 0.5) % 360;
            ctx.strokeStyle = `hsla(${hue}, 100%, 80%, 0.8)`;
            ctx.shadowColor = `hsl(${hue}, 100%, 80%)`;
        } else {
            ctx.strokeStyle = `rgba(${r}, ${g}, ${b}, 0.8)`;
            ctx.shadowColor = `rgb(${r}, ${g}, ${b})`;
        }
        ctx.shadowBlur = 10;
        ctx.lineWidth = 2;

        let angle = 0;
        ctx.moveTo(centerX, centerY);

        const maxPoints = Math.min(2000, Math.max(w, h) * 2);
        for (let i = 0; i < maxPoints; i++) {
            const radius = i * 0.1 * pulse;
            angle += 0.02;
            const x = centerX + radius * Math.cos(angle + this.frame * speed);
            const y = centerY + radius * Math.sin(angle + this.frame * speed);
            ctx.lineTo(x, y);
        }

        ctx.stroke();
        ctx.shadowBlur = 0;
    },

    /**
     * Draw rotating mandala pattern (theme-aware colors)
     * @private
     */
    _drawMandala(ctx, w, h) {
        const centerX = w / 2;
        const centerY = h / 2;
        const layers = this.mandalaLayers;
        const petals = this.mandalaPetals;
        const { r, g, b } = this.accentColor;

        const pulse = 1 + 0.08 * Math.sin(this.frame * 0.02);
        const rotation = this.frame * 0.002;
        const brightness = (r + g + b) / 3;
        const useHueCycle = brightness < 50;

        ctx.save();
        ctx.translate(centerX, centerY);
        ctx.scale(pulse, pulse);
        ctx.rotate(rotation);

        for (let layer = 0; layer < layers; layer++) {
            const radius = 60 + layer * 30;
            const petalLength = 30 + layer * 10;
            const petalWidth = 12;

            for (let i = 0; i < petals; i++) {
                const angle = (i * 2 * Math.PI) / petals;
                const x = radius * Math.cos(angle);
                const y = radius * Math.sin(angle);

                ctx.save();
                ctx.translate(x, y);
                ctx.rotate(angle + rotation);

                ctx.beginPath();
                ctx.ellipse(0, 0, petalWidth, petalLength, 0, 0, 2 * Math.PI);

                if (useHueCycle) {
                    const hue = ((this.frame * 0.6) + i * 30) % 360;
                    ctx.strokeStyle = `hsla(${hue}, 100%, 75%, 0.8)`;
                    ctx.shadowColor = ctx.strokeStyle;
                } else {
                    const shift = (i * 30 + layer * 45) % 360;
                    const lr = Math.min(255, r + Math.sin(shift * Math.PI / 180) * 60);
                    const lg = Math.min(255, g + Math.cos(shift * Math.PI / 180) * 60);
                    const lb = Math.min(255, b + Math.sin((shift + 120) * Math.PI / 180) * 60);
                    ctx.strokeStyle = `rgba(${lr|0}, ${lg|0}, ${lb|0}, 0.8)`;
                    ctx.shadowColor = ctx.strokeStyle;
                }

                ctx.shadowBlur = 8;
                ctx.lineWidth = 2;
                ctx.stroke();

                ctx.restore();
            }
        }

        ctx.restore();
        ctx.shadowBlur = 0;
    },

    /**
     * Draw hypnotic tunnel effect (concentric rings zooming outward)
     * @private
     */
    _drawTunnel(ctx, w, h) {
        const centerX = w / 2;
        const centerY = h / 2;
        const maxRadius = Math.max(w, h) * 0.7;
        const rings = this.tunnelRings;
        const { r, g, b } = this.accentColor;

        const brightness = (r + g + b) / 3;
        const useHueCycle = brightness < 50;
        const rotation = this.frame * 0.003;

        ctx.save();
        ctx.translate(centerX, centerY);
        ctx.rotate(rotation);

        for (let i = 0; i < rings; i++) {
            const phase = ((this.frame * 0.5 + i * (maxRadius / rings)) % maxRadius) / maxRadius;
            const radius = phase * maxRadius;
            const alpha = 1 - phase;

            if (alpha <= 0.05) continue;

            ctx.beginPath();
            ctx.arc(0, 0, radius, 0, Math.PI * 2);

            if (useHueCycle) {
                const hue = (this.frame * 0.3 + i * 25) % 360;
                ctx.strokeStyle = `hsla(${hue}, 100%, 70%, ${alpha * 0.7})`;
                ctx.shadowColor = `hsla(${hue}, 100%, 70%, ${alpha * 0.3})`;
            } else {
                ctx.strokeStyle = `rgba(${r}, ${g}, ${b}, ${alpha * 0.7})`;
                ctx.shadowColor = `rgba(${r}, ${g}, ${b}, ${alpha * 0.3})`;
            }

            ctx.shadowBlur = 6;
            ctx.lineWidth = 2 + (1 - phase) * 2;
            ctx.stroke();
        }

        ctx.restore();
        ctx.shadowBlur = 0;
    },

    /**
     * Animation loop — handles single, combined, and transition modes
     */
    animate() {
        if (!this.ctx || !this.canvas) return;

        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        this.frame++;

        // Check for active cross-fade transition
        const isTransitioning = this.prevEffectMode &&
            (Date.now() - this.transitionStart) < this.transitionDuration;

        if (isTransitioning) {
            const progress = (Date.now() - this.transitionStart) / this.transitionDuration;

            // Draw previous effect fading out
            this.ctx.save();
            this.ctx.globalAlpha = 1 - progress;
            this._drawEffect(this.prevEffectMode);
            this.ctx.restore();

            // Draw new effect fading in
            this.ctx.save();
            this.ctx.globalAlpha = progress;
            this._drawEffect(this.effectMode);
            this.ctx.restore();

            if (progress >= 1) {
                this.prevEffectMode = null;
            }
        } else if (this.effectMode === 'combined' && this.effects.length > 0) {
            // Combined mode: layer all active effects
            const alpha = 1 / this.effects.length;
            for (const effect of this.effects) {
                this.ctx.save();
                this.ctx.globalAlpha = alpha;
                this._drawEffect(effect);
                this.ctx.restore();
            }
        } else {
            this._drawEffect(this.effectMode);
        }

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
        this._stopCycling();
        this.particles = [];
        this.canvas = null;
        this.ctx = null;
        this.isInitialized = false;
        this.frame = 0;
        this.prevEffectMode = null;
    },

    // Mithril lifecycle hooks
    oninit(vnode) {
        this._resizeHandler = () => this.resizeCanvas();
        window.addEventListener('resize', this._resizeHandler);
    },

    oncreate(vnode) {
        this.setupCanvas(vnode.dom);

        if (vnode.attrs.theme) {
            this.updateTheme(vnode.attrs.theme);
        }
        if (vnode.attrs.visuals) {
            this.applyVisuals(vnode.attrs.visuals);
        }
    },

    onupdate(vnode) {
        if (vnode.attrs.theme) {
            this.updateTheme(vnode.attrs.theme);
        }
        if (vnode.attrs.visuals && vnode.attrs.visuals !== this.visuals) {
            this.applyVisuals(vnode.attrs.visuals);
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
        frame: 0,
        effectMode: 'particles',
        effects: ['particles'],
        visuals: null,
        prevEffectMode: null,
        cycleIndex: 0,
        cycleTimer: null,
        particleColor: { r: 150, g: 180, b: 255 },
        accentColor: { r: 150, g: 180, b: 255 }
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
