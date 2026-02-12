/**
 * SessionDirector - LLM-powered session orchestration
 * Periodically gathers session state (biometric data, labels, voice text)
 * and sends it to an LLM with a user-defined session command.
 * Receives structured JSON directives to adjust audio, visuals, labels, and image generation.
 * Uses the "Open Chat" chatConfig template from ~/Chat for LLM connection settings.
 */
(function() {

    class SessionDirector {
        static PROMPT_TEMPLATE_PATH = 'media/prompts/magic8DirectorPrompt.json';
        static _cachedTemplate = null;

        constructor() {
            // LLM infrastructure
            this.chatRequest = null;
            this.chatConfig = null;
            this.promptConfig = null;

            // Session config
            this.command = '';
            this.intervalMs = 60000;
            this.intervalId = null;
            this.isRunning = false;
            this.isPaused = false;

            // State provider callback - set by Magic8App
            this.stateProvider = null;

            // Tracking
            this.lastDirective = null;
            this.lastError = null;
            this.callCount = 0;
            this.consecutiveErrors = 0;

            // Mood ring mode
            this.moodRingMode = false;

            // Callbacks
            this.onDirective = null;
            this.onError = null;
            this.onStatusChange = null;
            this.onTickDebug = null; // (info) => {} — fired each tick with {tickNum, stateMessage, rawContent, directive}
        }

        /**
         * Initialize the LLM connection following the cardGame.js pattern
         * @param {string} command - User's session intent/theme text
         * @param {number} [intervalMs=60000] - Polling interval in ms
         * @param {string} [sessionName] - Session name for unique conversation history
         * @param {Object} [options] - Additional options
         * @param {string} [options.imageTags] - Comma-separated tags available for image search
         */
        async initialize(command, intervalMs, sessionName, options) {
            this.command = command || '';
            this.intervalMs = intervalMs || 60000;
            this.sessionName = sessionName || '';
            this.imageTags = options?.imageTags || '';

            try {
                this._setStatus('initializing');

                // 1. Find ~/Chat directory
                const chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
                if (!chatDir) {
                    throw new Error("Chat directory ~/Chat not found");
                }

                // 2. Find "Open Chat" config template
                const chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 0);
                const templateCfg = chatConfigs.find(c => c.name === "Open Chat");
                if (!templateCfg) {
                    throw new Error("Chat config 'Open Chat' not found in ~/Chat");
                }

                // 3. Load full template for LLM settings
                const fullTemplate = await am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);
                if (!fullTemplate) {
                    throw new Error("Failed to load 'Open Chat' template");
                }

                // 4. Create or update prompt config with current command
                const systemPrompt = await this._buildSystemPrompt(command);
                this.promptConfig = await this._ensurePromptConfig(chatDir, systemPrompt);
                if (!this.promptConfig) {
                    throw new Error("Failed to create prompt config");
                }

                // 5. Find or create chat config cloned from template
                this.chatConfig = await this._ensureChatConfig(chatDir, fullTemplate);
                if (!this.chatConfig) {
                    throw new Error("Failed to create chat config");
                }

                // 6. Create chat request
                const requestName = this.sessionName
                    ? 'Magic8 Director - ' + this.sessionName
                    : 'Magic8 Director';
                this.chatRequest = await am7chat.getChatRequest(requestName, this.chatConfig, this.promptConfig);
                if (!this.chatRequest) {
                    throw new Error("Failed to create chat request");
                }

                this._setStatus('ready');
                console.log('SessionDirector: Initialized successfully');
            } catch (err) {
                this.lastError = err.message;
                this._setStatus('error');
                console.error('SessionDirector: Initialization failed:', err);
                if (this.onError) this.onError(err);
            }
        }

        /**
         * Find or create the prompt config, updating system lines if it already exists
         * @param {Object} chatDir - ~/Chat group object
         * @param {Array<string>} systemPrompt - System prompt lines
         * @returns {Object|null} Prompt config object
         * @private
         */
        async _ensurePromptConfig(chatDir, systemPrompt) {
            const promptName = this.sessionName
                ? "Magic8 Director - " + this.sessionName
                : "Magic8 Director";
            let q = am7view.viewQuery(am7model.newInstance("olio.llm.promptConfig"));
            q.field("groupId", chatDir.id);
            q.field("name", promptName);
            q.cache(false);
            let qr = await page.search(q);

            if (qr && qr.results && qr.results.length > 0) {
                // Prompt exists - update system lines to reflect current command
                let existing = qr.results[0];
                existing.system = systemPrompt;
                await page.patchObject(existing);
                console.log('SessionDirector: Updated prompt config system lines');
                return existing;
            }

            // Create new prompt config
            let icfg = am7model.newInstance("olio.llm.promptConfig");
            icfg.api.groupId(chatDir.id);
            icfg.api.groupPath(chatDir.path);
            icfg.api.name(promptName);
            icfg.entity.system = systemPrompt;
            await page.createObject(icfg.entity);

            // Re-fetch to get the created object
            qr = await page.search(q);
            return (qr && qr.results && qr.results.length > 0) ? qr.results[0] : null;
        }

        /**
         * Find or create a diagnostic-specific prompt config.
         * Uses separate "Magic8 Diagnostics" name to avoid modifying live session prompt.
         * @param {Object} chatDir - ~/Chat group object
         * @param {Array<string>} systemPrompt - System prompt lines
         * @returns {Object|null} Prompt config object
         * @private
         */
        async _ensureDiagnosticPromptConfig(chatDir, systemPrompt) {
            const promptName = "Magic8 Diagnostics";
            let q = am7view.viewQuery(am7model.newInstance("olio.llm.promptConfig"));
            q.field("groupId", chatDir.id);
            q.field("name", promptName);
            q.cache(false);
            let qr = await page.search(q);

            if (qr && qr.results && qr.results.length > 0) {
                let existing = qr.results[0];
                existing.system = systemPrompt;
                await page.patchObject(existing);
                return existing;
            }

            let icfg = am7model.newInstance("olio.llm.promptConfig");
            icfg.api.groupId(chatDir.id);
            icfg.api.groupPath(chatDir.path);
            icfg.api.name(promptName);
            icfg.entity.system = systemPrompt;
            await page.createObject(icfg.entity);
            qr = await page.search(q);
            return (qr && qr.results && qr.results.length > 0) ? qr.results[0] : null;
        }

        /**
         * Find or create chat config from template
         * @param {Object} chatDir - ~/Chat group object
         * @param {Object} fullTemplate - Full "Open Chat" template object
         * @returns {Object|null} Chat config object
         * @private
         */
        async _ensureChatConfig(chatDir, fullTemplate) {
            const chatConfigName = "Magic8 Director";
            let q = am7view.viewQuery("olio.llm.chatConfig");
            q.field("groupId", chatDir.id);
            q.field("name", chatConfigName);
            q.cache(false);
            let qr = await page.search(q);

            if (qr && qr.results && qr.results.length > 0) {
                let chatCfg = qr.results[0];
                // Sync LLM settings from template
                if (fullTemplate && (
                    chatCfg.serverUrl !== fullTemplate.serverUrl ||
                    chatCfg.serviceType !== fullTemplate.serviceType ||
                    chatCfg.model !== fullTemplate.model
                )) {
                    chatCfg.serverUrl = fullTemplate.serverUrl;
                    chatCfg.serviceType = fullTemplate.serviceType;
                    chatCfg.model = fullTemplate.model;
                    chatCfg.apiVersion = fullTemplate.apiVersion;
                    // Remove apiKey before patching - EncryptFieldProvider errors on null values
                    delete chatCfg.apiKey;
                    await page.patchObject(chatCfg);
                    console.log('SessionDirector: Updated chat config LLM settings');
                }
                return chatCfg;
            }

            // Build new chatConfig from template
            const t = fullTemplate;
            const newChatCfg = {
                schema: "olio.llm.chatConfig",
                groupId: chatDir.id,
                groupPath: chatDir.path,
                name: chatConfigName,
                model: t.model,
                serverUrl: t.serverUrl,
                serviceType: t.serviceType,
                apiVersion: t.apiVersion,
                rating: t.rating,
                setting: t.setting,
                assist: t.assist,
                stream: t.stream,
                prune: t.prune,
                useNLP: t.useNLP,
                messageTrim: 4,
                remindEvery: t.remindEvery,
                keyframeEvery: t.keyframeEvery
            };
            if (t.chatOptions) {
                const co = { schema: "olio.llm.chatOptions" };
                const optKeys = ["max_tokens", "min_p", "num_ctx", "num_gpu", "repeat_last_n", "repeat_penalty", "temperature", "top_k", "top_p", "typical_p", "frequency_penalty", "presence_penalty", "seed"];
                for (const k of optKeys) {
                    if (t.chatOptions[k] !== undefined) co[k] = t.chatOptions[k];
                }
                newChatCfg.chatOptions = co;
            }
            return await page.createObject(newChatCfg);
        }

        /**
         * Find or create a diagnostic-specific chat config from template.
         * Uses separate "Magic8 Diagnostics" name with higher temperature for test diversity.
         * @param {Object} chatDir - ~/Chat group object
         * @param {Object} fullTemplate - Full "Open Chat" template object
         * @returns {Object|null} Chat config object
         * @private
         */
        async _ensureDiagnosticChatConfig(chatDir, fullTemplate) {
            const chatConfigName = "Magic8 Diagnostics";
            let q = am7view.viewQuery("olio.llm.chatConfig");
            q.field("groupId", chatDir.id);
            q.field("name", chatConfigName);
            q.cache(false);
            let qr = await page.search(q);

            if (qr && qr.results && qr.results.length > 0) {
                let chatCfg = qr.results[0];
                // Always sync LLM connection from template
                chatCfg.serverUrl = fullTemplate.serverUrl;
                chatCfg.serviceType = fullTemplate.serviceType;
                chatCfg.model = fullTemplate.model;
                chatCfg.apiVersion = fullTemplate.apiVersion;
                // Remove apiKey before patching - EncryptFieldProvider errors on null values
                delete chatCfg.apiKey;
                // Preserve existing chatOptions schema metadata, only update temperature
                if (chatCfg.chatOptions) {
                    chatCfg.chatOptions.temperature = 0.8;
                } else {
                    chatCfg.chatOptions = { schema: "olio.llm.chatOptions", temperature: 0.8 };
                }
                await page.patchObject(chatCfg);
                return chatCfg;
            }

            const t = fullTemplate;
            const newChatCfg = {
                schema: "olio.llm.chatConfig",
                groupId: chatDir.id,
                groupPath: chatDir.path,
                name: chatConfigName,
                model: t.model,
                serverUrl: t.serverUrl,
                serviceType: t.serviceType,
                apiVersion: t.apiVersion,
                rating: t.rating,
                setting: t.setting,
                assist: t.assist,
                stream: t.stream,
                prune: t.prune,
                useNLP: t.useNLP,
                messageTrim: 4,
                remindEvery: t.remindEvery,
                keyframeEvery: t.keyframeEvery
            };
            // Copy chatOptions from template but set higher temperature for test diversity
            const co = { schema: "olio.llm.chatOptions" };
            if (t.chatOptions) {
                const optKeys = ["max_tokens", "min_p", "num_ctx", "num_gpu", "repeat_last_n", "repeat_penalty", "temperature", "top_k", "top_p", "typical_p", "frequency_penalty", "presence_penalty", "seed"];
                for (const k of optKeys) {
                    if (t.chatOptions[k] !== undefined) co[k] = t.chatOptions[k];
                }
            }
            co.temperature = 0.8;
            newChatCfg.chatOptions = co;
            return await page.createObject(newChatCfg);
        }

        /**
         * Load the prompt template JSON from the static file.
         * Caches after first successful load.
         * @returns {Promise<Object|null>} Template object or null
         * @private
         */
        async _loadPromptTemplate() {
            if (SessionDirector._cachedTemplate) return SessionDirector._cachedTemplate;
            try {
                const resp = await m.request({
                    method: 'GET',
                    url: SessionDirector.PROMPT_TEMPLATE_PATH
                });
                if (resp && Array.isArray(resp.lines)) {
                    SessionDirector._cachedTemplate = resp;
                    console.log('SessionDirector: Loaded prompt template v' + (resp.version || '?'));
                    return resp;
                }
            } catch (err) {
                console.warn('SessionDirector: Failed to load prompt template, using fallback:', err.message || err);
            }
            return null;
        }

        /**
         * Replace ${token} placeholders in a string
         * @param {string} str - String with ${token} placeholders
         * @param {Object} tokens - Key-value map of token replacements
         * @returns {string}
         * @private
         */
        _resolveTokens(str, tokens) {
            return str.replace(/\$\{(\w+)\}/g, (match, key) => {
                return tokens.hasOwnProperty(key) ? tokens[key] : match;
            });
        }

        /**
         * Build the system prompt for the director LLM.
         * Loads from external JSON template if available, falls back to hardcoded.
         * @param {string} command - User's session intent
         * @returns {Promise<Array<string>>} System prompt lines
         * @private
         */
        async _buildSystemPrompt(command) {
            const tokens = {
                command: command || '',
                imageTags: ''
            };

            // Resolve image tags for token replacement
            if (this.imageTags) {
                const tags = this.imageTags.split(',').map(t => t.trim()).filter(t => t);
                if (tags.length > 0) {
                    tokens.imageTags = tags.join(', ');
                }
            }

            // Try loading from external template
            const template = await this._loadPromptTemplate();
            if (template) {
                let lines = template.lines.map(line => this._resolveTokens(line, tokens));

                // Append image tags section if tags are configured
                if (tokens.imageTags && Array.isArray(template.imageTagsAppendix)) {
                    const tagLines = template.imageTagsAppendix.map(line => this._resolveTokens(line, tokens));
                    lines = lines.concat(tagLines);
                }

                return lines;
            }

            // Fallback: hardcoded prompt (in case template file is unavailable)
            console.warn('SessionDirector: Using hardcoded prompt fallback');
            return this._buildSystemPromptFallback(command);
        }

        /**
         * Hardcoded fallback system prompt (used if template JSON is unavailable)
         * @param {string} command
         * @returns {Array<string>}
         * @private
         */
        _buildSystemPromptFallback(command) {
            const lines = [
                "You are the Session Director for Magic8, an immersive audiovisual experience.",
                "SESSION INTENT: " + command,
                "You receive periodic state snapshots and must return a JSON object with directives to adjust the session.",
                "You MUST always respond with a valid JSON object containing at least one directive field.",
                "",
                "Directive fields (include what you want to change):",
                "  audio: { preset: \"goddessEnergy\"|\"heartOpening\"|\"loveFrequency\"|\"deepHealing\"|\"lettingGo\"|\"transformation\"|\"creativeFlow\"|\"thirdEye\"|\"crownConnection\"|\"regeneration\"|\"deepHypnosis\"|\"lucidDream\"",
                "           OR band: \"delta\"|\"theta\"|\"alphaTheta\"|\"alpha\"|\"beta\"|\"gamma\" with optional troughBand, endBand, secondTransition, thirdTransition,",
                "              baseFreq: carrier Hz (174|285|396|417|432|528|639|741|852|963),",
                "           isochronicEnabled: true/false, isochronicBand: \"delta\"|\"theta\"|\"alphaTheta\"|\"alpha\"|\"beta\"|\"gamma\",",
                "           volume: 0.0-1.0 }",
                "  visuals: { effect: \"particles\" or \"spiral\" or \"mandala\" or \"tunnel\" or \"hypnoDisc\", transitionDuration: 2000 }",
                "  labels: { add: [\"1-3 word phrase\"], remove: [\"exact label text\"] }",
                "  generateImage: { description: \"scene description\", style: \"art style keyword\", imageAction: \"what the subject is doing\", denoisingStrength: 0.3-0.9, cfg: 3-15, tags: \"tag1+tag2+tag3\" }",
                "  opacity: { labels: 0.15-1.0, images: 0.15-1.0, visualizer: 0.15-1.0, visuals: 0.15-1.0 }",
                "  voiceLine: \"short sentence to be spoken next via text-to-speech\"",
                "  suggestMood: { emotion: \"happy\"|\"sad\"|\"angry\"|\"fear\"|\"surprise\"|\"disgust\"|\"neutral\", gender: \"Man\"|\"Woman\", genderPct: 0-100 }",
                "  progressPct: 0-100",
                "  commentary: \"your reasoning (not displayed to user)\"",
                "",
                "Guidelines:",
                "- Evolve gradually based on biometric emotion, elapsed time, and session intent",
                "- Labels must be 1-3 words only. Keep them poetic and aligned with session intent.",
                "- Respond with ONLY the JSON object, no markdown fences, no explanation text"
            ];

            if (this.imageTags) {
                const tags = this.imageTags.split(',').map(t => t.trim()).filter(t => t);
                if (tags.length > 0) {
                    lines.push("");
                    lines.push("Available image tags: " + tags.join(', '));
                }
            }

            return lines;
        }

        /**
         * Build a trimmed system prompt for mood ring mode
         * Only requests suggestMood + commentary (no labels, voice, images, audio, opacity)
         * @param {string} command - Session intent
         * @returns {Array<string>}
         * @private
         */
        _buildMoodRingPrompt(command) {
            return [
                "You are the Mood Ring advisor for Magic8, an immersive audiovisual experience.",
                "SESSION INTENT: " + command,
                "You receive periodic biometric state snapshots and must return a JSON object.",
                "You MUST always respond with a valid JSON object.",
                "",
                "Directive fields:",
                "  suggestMood: { emotion: \"happy\"|\"sad\"|\"angry\"|\"fear\"|\"surprise\"|\"disgust\"|\"neutral\", gender: \"Man\"|\"Woman\" }",
                "  commentary: \"your reasoning (not displayed to user)\"",
                "",
                "Your role: observe the user's detected emotion and suggest what mood they might be feeling or transitioning toward.",
                "Consider the session intent, elapsed time, and emotion history to provide insightful mood suggestions.",
                "The suggested mood drives a visual mood ring color effect.",
                "",
                "Example: {\"suggestMood\":{\"emotion\":\"happy\",\"gender\":\"Woman\"},\"commentary\":\"user shows warmth\"}",
                "",
                "- Respond with ONLY the JSON object, no markdown fences, no explanation text"
            ];
        }

        /**
         * Format state for mood ring mode — only biometric data + emotion transition + elapsed time
         * @param {Object} state - Session state
         * @returns {string}
         * @private
         */
        _formatMoodRingState(state) {
            const lines = ['Mood Ring State:'];

            if (state.biometric) {
                const b = state.biometric;
                lines.push(`Biometric: emotion=${b.emotion || 'unknown'}, age=${b.age || 'unknown'}, gender=${b.gender || 'unknown'}`);
            } else {
                lines.push('Biometric: no face data');
            }

            if (state.emotionTransition) {
                const t = state.emotionTransition;
                lines.push(`Emotion Shift: ${t.from} → ${t.to} (${t.secsAgo}s ago)`);
            }

            if (state.elapsedMinutes != null) {
                lines.push(`Elapsed: ${Math.round(state.elapsedMinutes)} min`);
            }

            return lines.join('\n');
        }

        /**
         * Start the director polling loop
         */
        start() {
            if (this.isRunning) return;
            if (!this.chatRequest) {
                console.warn('SessionDirector: Cannot start - not initialized');
                return;
            }

            this.isRunning = true;
            this.isPaused = false;
            this._setStatus('running');
            console.log('SessionDirector: Started with interval', this.intervalMs, 'ms');

            // Run first tick immediately
            this._tick();

            // Set up polling interval
            this.intervalId = setInterval(() => this._tick(), this.intervalMs);
        }

        /**
         * Stop the director
         */
        stop() {
            this.isRunning = false;
            this.isPaused = false;
            if (this.intervalId) {
                clearInterval(this.intervalId);
                this.intervalId = null;
            }
            this._setStatus('stopped');
            console.log('SessionDirector: Stopped');
        }

        /**
         * Pause the director (keeps interval but skips ticks)
         */
        pause() {
            if (!this.isRunning) return;
            this.isPaused = true;
            this._setStatus('paused');
        }

        /**
         * Resume the director from pause
         */
        resume() {
            if (!this.isRunning) return;
            this.isPaused = false;
            this._setStatus('running');
        }

        /**
         * Perform one director tick - gather state, call LLM, apply directive
         * @private
         */
        async _tick() {
            if (!this.isRunning || this.isPaused) return;

            try {
                // Gather current session state
                const state = this.stateProvider ? this.stateProvider() : {};

                // Format the user message with state data
                const userMessage = this._formatStateMessage(state);

                const tickNum = this.callCount + 1;
                console.log('SessionDirector: Tick #' + tickNum + ', sending state to LLM');

                // Call the LLM - m.request can reject with null/undefined on server errors
                let response;
                try {
                    response = await am7chat.chat(this.chatRequest, userMessage);
                } catch (reqErr) {
                    const detail = reqErr?.message || reqErr?.statusText || (reqErr == null ? 'server returned empty error (check LLM server connectivity)' : String(reqErr));
                    throw new Error('LLM request failed: ' + detail);
                }

                if (!response) {
                    console.warn('SessionDirector: Null response from LLM');
                    return;
                }

                this.callCount++;
                this.consecutiveErrors = 0;

                // Extract assistant content from response
                const content = this._extractContent(response);
                if (!content) {
                    console.warn('SessionDirector: Empty response from LLM');
                    return;
                }

                console.log('SessionDirector: LLM response:', content);

                // Parse the JSON directive
                const directive = this._parseDirective(content);
                if (directive) {
                    this.lastDirective = directive;
                    if (this.onDirective) {
                        this.onDirective(directive);
                    }
                }

                // Emit tick debug info
                if (this.onTickDebug) {
                    this.onTickDebug({
                        tickNum: tickNum,
                        stateMessage: userMessage,
                        rawContent: content,
                        directive: directive
                    });
                }
            } catch (err) {
                this.consecutiveErrors++;
                this.lastError = err.message || String(err);
                // Only log every error the first 3 times, then every 5th to reduce spam
                if (this.consecutiveErrors <= 3 || this.consecutiveErrors % 5 === 0) {
                    console.error('SessionDirector: Tick error (' + this.consecutiveErrors + ' consecutive):', err.message || err);
                }
                if (this.onError) this.onError(err);
            }
        }

        /**
         * Format session state into a user message for the LLM
         * @param {Object} state - Current session state
         * @returns {string} Formatted state message
         * @private
         */
        _formatStateMessage(state) {
            const lines = ['State Update:'];

            // Biometric data
            if (state.biometric) {
                const b = state.biometric;
                lines.push(`Biometric: emotion=${b.emotion || 'unknown'}, age=${b.age || 'unknown'}, gender=${b.gender || 'unknown'}`);
            } else {
                lines.push('Biometric: no face data');
            }

            // Emotion transition (recent change within 30s)
            if (state.emotionTransition) {
                const t = state.emotionTransition;
                lines.push(`Emotion Shift: ${t.from} → ${t.to} (${t.secsAgo}s ago)`);
            }

            // Session labels
            if (state.labels && state.labels.length > 0) {
                lines.push('Session Labels: [' + state.labels.join(', ') + ']');
            } else {
                lines.push('Session Labels: none');
            }

            // Recent voice lines
            if (state.recentVoiceLines && state.recentVoiceLines.length > 0) {
                lines.push('Recent Voice: "' + state.recentVoiceLines.join('" | "') + '"');
            }

            // Injected voice lines (so the LLM knows what it already said)
            if (state.injectedVoiceLines && state.injectedVoiceLines.length > 0) {
                lines.push('Your Injected Lines: "' + state.injectedVoiceLines.join('" | "') + '"');
            }

            // Current text
            if (state.currentText) {
                lines.push('Current Text: "' + state.currentText + '"');
            }

            // Audio state — prefer band labels over raw Hz
            if (state.audio) {
                const a = state.audio;
                if (a.bandLabel) {
                    lines.push(`Current Audio: band=${a.bandLabel}, carrier=${a.baseFreq || '?'} Hz, isochronic=${a.isochronicEnabled ? 'on' : 'off'}`);
                } else {
                    lines.push(`Current Audio: sweep ${a.sweepStart || '?'}→${a.sweepTrough || '?'}→${a.sweepEnd || '?'} Hz, isochronic=${a.isochronicEnabled ? 'on' : 'off'}`);
                }
            }

            // Visual state
            if (state.visuals) {
                const v = state.visuals;
                lines.push(`Current Visual: ${v.mode || 'single'} mode, effects=[${(v.effects || []).join(',')}]`);
            }

            // SD image generation config
            if (state.sdConfig) {
                const sd = state.sdConfig;
                lines.push(`Current SD: style=${sd.style || '?'}, denoising=${sd.denoisingStrength || '?'}, cfg=${sd.cfg || '?'}, desc="${(sd.description || '').substring(0, 60)}"`);
            }

            // Layer opacity
            if (state.layerOpacity) {
                const o = state.layerOpacity;
                lines.push(`Layer Opacity: labels=${o.labels}, images=${o.images}, visualizer=${o.visualizer}, visuals=${o.visuals}`);
            }

            // User response from interactive mode
            if (state.userResponse) {
                lines.push('User Response: "' + state.userResponse + '"');
            }

            // Interactive mode indicator with escalating prompts
            if (state.interactiveEnabled) {
                const ticks = state.ticksSinceAskUser || 0;
                if (ticks >= 4) {
                    lines.push('Interactive: ENABLED — You MUST include askUser in this response. Ask the user a short question about their experience. Example: {"askUser":{"question":"How are you feeling right now?"}}');
                } else if (ticks >= 2) {
                    lines.push('Interactive: ENABLED — Include an askUser directive to check in with the user. Pair it with a matching voiceLine.');
                } else {
                    lines.push('Interactive: ENABLED');
                }
            }

            // Mood ring status
            if (state.moodRingEnabled) {
                lines.push('Mood Ring: ACTIVE — include suggestMood in your response');
            }

            // Elapsed time
            if (state.elapsedMinutes != null) {
                lines.push('Elapsed: ' + Math.round(state.elapsedMinutes) + ' min');
            }

            return lines.join('\n');
        }

        /**
         * Extract assistant content from LLM response
         * @param {Object} response - Chat response object
         * @returns {string|null} Content string
         * @private
         */
        _extractContent(response) {
            if (!response) return null;

            // Handle different response shapes
            if (typeof response === 'string') return response;

            // olio.llm.chatResponse — extract last assistant message content
            if (response.messages && Array.isArray(response.messages)) {
                for (let i = response.messages.length - 1; i >= 0; i--) {
                    if (response.messages[i].role === 'assistant' && response.messages[i].content) {
                        return response.messages[i].content;
                    }
                }
            }

            if (response.message) return response.message;
            if (response.content) return response.content;
            if (response.choices && response.choices[0]) {
                const choice = response.choices[0];
                return choice.message?.content || choice.content || choice.text;
            }
            if (response.results && response.results.length > 0) {
                return response.results[0].content || response.results[0].message;
            }

            return JSON.stringify(response);
        }

        /**
         * Parse a JSON directive from LLM response content
         * Handles markdown fences, extraneous text, and validation
         * @param {string} content - Raw LLM response
         * @returns {Object|null} Parsed directive or null
         * @private
         */
        _parseDirective(content) {
            if (!content) return null;

            let jsonStr = content.trim();

            // Strip markdown code fences
            jsonStr = jsonStr.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '');

            // Find the first { to last } block
            const firstBrace = jsonStr.indexOf('{');
            if (firstBrace === -1) {
                console.warn('SessionDirector: No JSON object found in response');
                return null;
            }

            const lastBrace = jsonStr.lastIndexOf('}');
            if (lastBrace !== -1 && lastBrace > firstBrace) {
                jsonStr = jsonStr.substring(firstBrace, lastBrace + 1);
            } else {
                jsonStr = jsonStr.substring(firstBrace);
            }

            // Repair unbalanced braces/brackets (truncated LLM responses)
            jsonStr = this._repairTruncatedJson(jsonStr);

            // Try strict JSON first, then lenient JS-object parsing
            let directive;
            try {
                directive = JSON.parse(jsonStr);
            } catch (strictErr) {
                // Local LLMs often return JS-like objects: unquoted keys, single-quoted strings, trailing commas
                try {
                    let fixed = jsonStr;

                    // Step 0: Strip JS-style comments
                    fixed = fixed.replace(/\/\/[^\n]*/g, '');
                    fixed = fixed.replace(/\/\*[\s\S]*?\*\//g, '');

                    // Step 1: Protect existing double-quoted strings from regex modification
                    const preserved = [];
                    fixed = fixed.replace(/"(?:[^"\\]|\\.)*"/g, (match) => {
                        preserved.push(match);
                        return '"__P' + (preserved.length - 1) + '__"';
                    });

                    // Step 2: Quote unquoted keys (word followed by colon, after { , or [)
                    fixed = fixed.replace(/([{,\[]\s*)([a-zA-Z_]\w*)\s*:/g, '$1"$2":');

                    // Step 2.5: Quote bare identifier values (word after colon, not true/false/null)
                    fixed = fixed.replace(/:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*([,}\]])/g, (match, val, term) => {
                        if (val === 'true' || val === 'false' || val === 'null') return match;
                        return ': "' + val + '"' + term;
                    });
                    // Also handle bare identifiers inside arrays: [word, word]
                    fixed = fixed.replace(/([[,]\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=[,\]])/g, (match, prefix, val) => {
                        if (val === 'true' || val === 'false' || val === 'null') return match;
                        return prefix + '"' + val + '"';
                    });

                    // Step 3: Convert single-quoted strings to double-quoted
                    fixed = fixed.replace(/'([^']*?)'/g, '"$1"');

                    // Step 4: Remove trailing commas before } or ]
                    fixed = fixed.replace(/,\s*([}\]])/g, '$1');

                    // Step 5: Remove leading commas after { or [
                    fixed = fixed.replace(/([{\[]\s*),/g, '$1');

                    // Step 6: Restore preserved double-quoted strings
                    fixed = fixed.replace(/"__P(\d+)__"/g, (_, i) => preserved[parseInt(i)]);

                    // Step 7: Repair any remaining unbalanced braces
                    fixed = this._repairTruncatedJson(fixed);

                    directive = JSON.parse(fixed);
                    console.log('SessionDirector: Parsed directive using lenient JS-object mode');
                } catch (lenientErr) {
                    console.warn('SessionDirector: Failed to parse directive (strict & lenient):', strictErr.message);
                    return null;
                }
            }

            try {

                // Validate expected fields - ignore unknown
                const valid = {};

                if (directive.audio && typeof directive.audio === 'object') {
                    valid.audio = {};
                    // Named preset
                    if (typeof directive.audio.preset === 'string') valid.audio.preset = directive.audio.preset;
                    // Named band with optional transitions
                    const validBands = ['delta', 'theta', 'alphaTheta', 'alpha', 'beta', 'gamma'];
                    if (typeof directive.audio.band === 'string' && validBands.includes(directive.audio.band)) {
                        valid.audio.band = directive.audio.band;
                    }
                    if (typeof directive.audio.troughBand === 'string' && validBands.includes(directive.audio.troughBand)) {
                        valid.audio.troughBand = directive.audio.troughBand;
                    }
                    if (typeof directive.audio.endBand === 'string' && validBands.includes(directive.audio.endBand)) {
                        valid.audio.endBand = directive.audio.endBand;
                    }
                    if (typeof directive.audio.secondTransition === 'string' && validBands.includes(directive.audio.secondTransition)) {
                        valid.audio.secondTransition = directive.audio.secondTransition;
                    }
                    if (typeof directive.audio.thirdTransition === 'string' && validBands.includes(directive.audio.thirdTransition)) {
                        valid.audio.thirdTransition = directive.audio.thirdTransition;
                    }
                    if (typeof directive.audio.baseFreq === 'number') valid.audio.baseFreq = directive.audio.baseFreq;
                    // Legacy raw Hz sweep (backwards compatible)
                    if (typeof directive.audio.sweepStart === 'number') valid.audio.sweepStart = directive.audio.sweepStart;
                    if (typeof directive.audio.sweepTrough === 'number') valid.audio.sweepTrough = directive.audio.sweepTrough;
                    if (typeof directive.audio.sweepEnd === 'number') valid.audio.sweepEnd = directive.audio.sweepEnd;
                    // Isochronic
                    if (typeof directive.audio.isochronicEnabled === 'boolean') valid.audio.isochronicEnabled = directive.audio.isochronicEnabled;
                    if (typeof directive.audio.isochronicRate === 'number') valid.audio.isochronicRate = directive.audio.isochronicRate;
                    if (typeof directive.audio.isochronicBand === 'string' && validBands.includes(directive.audio.isochronicBand)) {
                        valid.audio.isochronicBand = directive.audio.isochronicBand;
                    }
                    if (typeof directive.audio.volume === 'number') valid.audio.volume = directive.audio.volume;
                }

                if (directive.visuals && typeof directive.visuals === 'object') {
                    valid.visuals = {};
                    if (typeof directive.visuals.effect === 'string') valid.visuals.effect = directive.visuals.effect;
                    if (typeof directive.visuals.transitionDuration === 'number') valid.visuals.transitionDuration = directive.visuals.transitionDuration;
                }

                if (directive.labels && typeof directive.labels === 'object') {
                    valid.labels = {};
                    if (Array.isArray(directive.labels.add)) {
                        valid.labels.add = directive.labels.add.filter(l => typeof l === 'string');
                    }
                    if (Array.isArray(directive.labels.remove)) {
                        valid.labels.remove = directive.labels.remove.filter(l => typeof l === 'string');
                    }
                }

                if (directive.generateImage && typeof directive.generateImage === 'object') {
                    valid.generateImage = {};
                    if (typeof directive.generateImage.description === 'string') valid.generateImage.description = directive.generateImage.description;
                    if (typeof directive.generateImage.style === 'string') valid.generateImage.style = directive.generateImage.style;
                    if (typeof directive.generateImage.imageAction === 'string') valid.generateImage.imageAction = directive.generateImage.imageAction;
                    if (typeof directive.generateImage.denoisingStrength === 'number') valid.generateImage.denoisingStrength = directive.generateImage.denoisingStrength;
                    if (typeof directive.generateImage.cfg === 'number') valid.generateImage.cfg = directive.generateImage.cfg;
                    // Tags as "tag1+tag2" string or ["tag1","tag2"] array
                    if (typeof directive.generateImage.tags === 'string') {
                        valid.generateImage.tags = directive.generateImage.tags.split('+').map(t => t.trim()).filter(t => t);
                    } else if (Array.isArray(directive.generateImage.tags)) {
                        valid.generateImage.tags = directive.generateImage.tags.filter(t => typeof t === 'string');
                    }
                    // Only keep if at least one field was set
                    if (Object.keys(valid.generateImage).length === 0) delete valid.generateImage;
                }

                if (directive.opacity && typeof directive.opacity === 'object') {
                    valid.opacity = {};
                    for (const layer of ['labels', 'images', 'visualizer', 'visuals']) {
                        if (typeof directive.opacity[layer] === 'number') {
                            valid.opacity[layer] = Math.max(0.15, Math.min(1, directive.opacity[layer]));
                        }
                    }
                    if (Object.keys(valid.opacity).length === 0) delete valid.opacity;
                }

                if (typeof directive.voiceLine === 'string' && directive.voiceLine.trim().length > 0) {
                    valid.voiceLine = directive.voiceLine.trim();
                }

                if (typeof directive.commentary === 'string') {
                    valid.commentary = directive.commentary;
                }

                if (typeof directive.progressPct === 'number') {
                    valid.progressPct = Math.max(0, Math.min(100, Math.round(directive.progressPct)));
                }

                // Validate suggestMood
                if (directive.suggestMood && typeof directive.suggestMood === 'object') {
                    const validEmotions = ['neutral', 'happy', 'sad', 'angry', 'fear', 'surprise', 'disgust'];
                    const validGenders = ['Man', 'Woman'];
                    valid.suggestMood = {};
                    if (typeof directive.suggestMood.emotion === 'string' && validEmotions.includes(directive.suggestMood.emotion)) {
                        valid.suggestMood.emotion = directive.suggestMood.emotion;
                    }
                    if (typeof directive.suggestMood.gender === 'string' && validGenders.includes(directive.suggestMood.gender)) {
                        valid.suggestMood.gender = directive.suggestMood.gender;
                    }
                    if (typeof directive.suggestMood.genderPct === 'number') {
                        valid.suggestMood.genderPct = Math.max(0, Math.min(100, Math.round(directive.suggestMood.genderPct)));
                    }
                    if (Object.keys(valid.suggestMood).length === 0) delete valid.suggestMood;
                }

                // Validate askUser
                if (directive.askUser && typeof directive.askUser === 'object') {
                    if (typeof directive.askUser.question === 'string' && directive.askUser.question.trim().length > 0) {
                        valid.askUser = {
                            question: directive.askUser.question.trim()
                        };
                    }
                }

                return valid;
            } catch (err) {
                console.warn('SessionDirector: Failed to parse JSON directive:', err.message);
                return null;
            }
        }

        /**
         * Repair truncated JSON by closing unbalanced braces/brackets and
         * trimming any trailing incomplete key-value pair
         * @param {string} json - Potentially truncated JSON string
         * @returns {string} Repaired JSON string
         * @private
         */
        _repairTruncatedJson(json) {
            // Remove trailing incomplete value (after last comma or colon outside strings)
            json = json.replace(/,\s*[a-zA-Z_"'][^{}[\]]*$/, '');
            json = json.replace(/:\s*$/, ': null');
            // Remove naked trailing comma (truncated mid-object/array)
            json = json.replace(/,\s*$/, '');

            let openBraces = 0, openBrackets = 0;
            let inString = false, escape = false, stringChar = '';
            for (const ch of json) {
                if (escape) { escape = false; continue; }
                if (ch === '\\') { escape = true; continue; }
                if (!inString && (ch === '"' || ch === "'")) { inString = true; stringChar = ch; continue; }
                if (inString && ch === stringChar) { inString = false; continue; }
                if (inString) continue;
                if (ch === '{') openBraces++;
                else if (ch === '}') openBraces--;
                else if (ch === '[') openBrackets++;
                else if (ch === ']') openBrackets--;
            }

            // Close unclosed strings (if truncated mid-string)
            if (inString) json += stringChar;

            if (openBrackets > 0 || openBraces > 0) {
                for (let i = 0; i < openBrackets; i++) json += ']';
                for (let i = 0; i < openBraces; i++) json += '}';
                // Clean up trailing commas before the newly added closing braces/brackets
                json = json.replace(/,\s*([}\]])/g, '$1');
                console.log('SessionDirector: Repaired truncated JSON (added ' + openBraces + ' braces, ' + openBrackets + ' brackets)');
            }

            return json;
        }

        /**
         * Verify that a directive was applied correctly by comparing against post-application state
         * @param {Object} directive - The directive that was applied
         * @param {Object} postState - State gathered after application
         * @returns {Array<{field: string, pass: boolean, detail: string}>}
         * @private
         */
        _verifyDirectiveApplication(directive, postState) {
            const checks = [];

            if (directive.audio && postState.audio) {
                for (const key of ['sweepStart', 'sweepTrough', 'sweepEnd']) {
                    if (directive.audio[key] != null) {
                        const match = postState.audio[key] === directive.audio[key];
                        checks.push({
                            field: 'audio.' + key,
                            pass: match,
                            detail: match
                                ? key + '=' + postState.audio[key]
                                : 'expected ' + directive.audio[key] + ', got ' + postState.audio[key]
                        });
                    }
                }
                if (directive.audio.isochronicEnabled != null) {
                    const match = postState.audio.isochronicEnabled === directive.audio.isochronicEnabled;
                    checks.push({
                        field: 'audio.isochronicEnabled',
                        pass: match,
                        detail: match
                            ? 'isochronic=' + postState.audio.isochronicEnabled
                            : 'expected ' + directive.audio.isochronicEnabled + ', got ' + postState.audio.isochronicEnabled
                    });
                }
            }

            if (directive.visuals?.effect && postState.visuals) {
                const match = postState.visuals.effects && postState.visuals.effects.includes(directive.visuals.effect);
                checks.push({
                    field: 'visuals.effect',
                    pass: match,
                    detail: match
                        ? 'effect=' + directive.visuals.effect
                        : 'expected ' + directive.visuals.effect + ', got ' + JSON.stringify(postState.visuals.effects)
                });
            }

            if (directive.labels?.add?.length > 0 && postState.labels) {
                for (const label of directive.labels.add) {
                    const match = postState.labels.includes(label);
                    checks.push({
                        field: 'labels.add',
                        pass: match,
                        detail: match ? '"' + label + '" present' : '"' + label + '" not found in state'
                    });
                }
            }

            if (directive.labels?.remove?.length > 0 && postState.labels) {
                for (const label of directive.labels.remove) {
                    const match = !postState.labels.includes(label);
                    checks.push({
                        field: 'labels.remove',
                        pass: match,
                        detail: match ? '"' + label + '" removed' : '"' + label + '" still present'
                    });
                }
            }

            return checks;
        }

        /**
         * Run diagnostics - verify infrastructure, then multi-pass behavioral tests
         * with varying biometric states to verify the LLM adapts its directives.
         * Uses dedicated "Magic8 Diagnostics" configs to avoid modifying live session state.
         * @param {string} [command] - Optional command override for testing
         * @param {Function} [onDirective] - Called with each parsed directive for live application
         * @param {Function} [onLog] - Called with each log entry for live display
         * @returns {Promise<Array<{name: string, pass: boolean, detail: string, directive?: Object}>>}
         */
        async runDiagnostics(command, onDirective, onLog) {
            const results = [];
            const testCommand = command || this.command || 'Diagnostic test session';

            const log = (name, pass, detail, directive) => {
                const entry = { name, pass, detail: detail || '' };
                if (directive) entry.directive = directive;
                results.push(entry);
                console.log(`SessionDirector [TEST] ${pass ? 'PASS' : 'FAIL'}: ${name}${detail ? ' - ' + detail : ''}`);
                if (onLog) onLog(entry);
            };

            // === INFRASTRUCTURE TESTS ===

            // Test 1: ~/Chat directory
            let chatDir;
            try {
                chatDir = await page.findObject("auth.group", "DATA", "~/Chat");
                if (chatDir && chatDir.objectId) {
                    log('Find ~/Chat directory', true, chatDir.objectId);
                } else {
                    log('Find ~/Chat directory', false, 'Not found');
                    return results;
                }
            } catch (err) {
                log('Find ~/Chat directory', false, err.message);
                return results;
            }

            // Test 2: "Open Chat" template
            let templateCfg, fullTemplate;
            try {
                const chatConfigs = await am7client.list("olio.llm.chatConfig", chatDir.objectId, null, 0, 0);
                templateCfg = chatConfigs.find(c => c.name === "Open Chat");
                if (templateCfg) {
                    log('Find "Open Chat" template', true, templateCfg.objectId);
                } else {
                    const names = chatConfigs.map(c => c.name).join(', ');
                    log('Find "Open Chat" template', false, 'Available: ' + (names || 'none'));
                    return results;
                }
            } catch (err) {
                log('Find "Open Chat" template', false, err.message);
                return results;
            }

            // Test 3: Load full template
            try {
                fullTemplate = await am7client.getFull("olio.llm.chatConfig", templateCfg.objectId);
                if (fullTemplate && fullTemplate.serverUrl) {
                    log('Load template details', true,
                        `model=${fullTemplate.model}, service=${fullTemplate.serviceType}, server=${fullTemplate.serverUrl}`);
                } else {
                    log('Load template details', false, 'Template loaded but missing serverUrl');
                    return results;
                }
            } catch (err) {
                log('Load template details', false, err.message);
                return results;
            }

            // Test 4: Diagnostic prompt config (separate from live session)
            let promptConfig;
            try {
                const systemPrompt = await this._buildSystemPrompt(testCommand);
                promptConfig = await this._ensureDiagnosticPromptConfig(chatDir, systemPrompt);
                if (promptConfig && promptConfig.objectId) {
                    log('Diagnostic prompt config', true,
                        `objectId=${promptConfig.objectId}, system lines=${promptConfig.system?.length || '?'}`);
                } else {
                    log('Diagnostic prompt config', false, 'Returned null');
                    return results;
                }
            } catch (err) {
                log('Diagnostic prompt config', false, err.message);
                return results;
            }

            // Test 5: Diagnostic chat config (separate, temperature=0.8)
            let chatConfig;
            try {
                chatConfig = await this._ensureDiagnosticChatConfig(chatDir, fullTemplate);
                if (chatConfig && chatConfig.objectId) {
                    const temp = chatConfig.chatOptions?.temperature;
                    log('Diagnostic chat config', true,
                        `objectId=${chatConfig.objectId}, model=${chatConfig.model}, temp=${temp || 'default'}`);
                } else {
                    log('Diagnostic chat config', false, 'Returned null');
                    return results;
                }
            } catch (err) {
                log('Diagnostic chat config', false, err.message);
                return results;
            }

            // Test 6: Diagnostic chat request
            let chatRequest;
            try {
                chatRequest = await am7chat.getChatRequest("Magic8 Diagnostics", chatConfig, promptConfig);
                if (chatRequest) {
                    log('Diagnostic chat request', true,
                        `objectId=${chatRequest.objectId || 'inline'}`);
                } else {
                    log('Diagnostic chat request', false, 'Returned null');
                    return results;
                }
            } catch (err) {
                log('Diagnostic chat request', false, err.message);
                return results;
            }

            // === BEHAVIORAL TESTS - multiple passes with varying biometric states ===

            const testScenarios = [
                {
                    name: 'Pass 1: Neutral baseline',
                    state: {
                        biometric: { emotion: 'neutral', age: 30, gender: 'unknown' },
                        labels: [],
                        recentVoiceLines: [],
                        currentText: '',
                        audio: { sweepStart: 8, sweepTrough: 2, sweepEnd: 8, isochronicEnabled: false },
                        visuals: { mode: 'single', effects: ['particles'] },
                        elapsedMinutes: 0
                    }
                },
                {
                    name: 'Pass 2: Happy + early session',
                    state: {
                        biometric: { emotion: 'happy', age: 28, gender: 'female' },
                        labels: [], // will be filled from pass 1
                        recentVoiceLines: ['relax and breathe deeply'],
                        currentText: 'let go of tension',
                        audio: { sweepStart: 8, sweepTrough: 2, sweepEnd: 8, isochronicEnabled: false },
                        visuals: { mode: 'single', effects: ['particles'] },
                        elapsedMinutes: 2
                    }
                },
                {
                    name: 'Pass 3: Sad + mid-session',
                    state: {
                        biometric: { emotion: 'sad', age: 35, gender: 'male' },
                        labels: [],
                        recentVoiceLines: ['feel the weight lifting', 'you are safe here'],
                        currentText: 'embrace the stillness',
                        audio: { sweepStart: 6, sweepTrough: 3, sweepEnd: 7, isochronicEnabled: true },
                        visuals: { mode: 'single', effects: ['spiral'] },
                        elapsedMinutes: 5
                    }
                },
                {
                    name: 'Pass 4: Fear + late session',
                    state: {
                        biometric: { emotion: 'fear', age: 42, gender: 'female' },
                        labels: [],
                        recentVoiceLines: ['nothing can harm you', 'breathe into the feeling'],
                        currentText: 'you are protected',
                        audio: { sweepStart: 4, sweepTrough: 2, sweepEnd: 5, isochronicEnabled: true },
                        visuals: { mode: 'single', effects: ['mandala'] },
                        elapsedMinutes: 12
                    }
                },
                {
                    name: 'Pass 5: Interactive + user response',
                    state: {
                        biometric: { emotion: 'happy', age: 30, gender: 'female' },
                        labels: [],
                        recentVoiceLines: [],
                        currentText: '',
                        audio: { sweepStart: 8, sweepTrough: 4, sweepEnd: 8, isochronicEnabled: false },
                        visuals: { mode: 'single', effects: ['particles'] },
                        interactiveEnabled: true,
                        ticksSinceAskUser: 5,
                        userResponse: 'I feel calm and centered',
                        elapsedMinutes: 8
                    }
                }
            ];

            const directives = [];
            let accumulatedLabels = [];

            for (let i = 0; i < testScenarios.length; i++) {
                const scenario = testScenarios[i];

                // Carry forward labels from previous directives
                if (i > 0) {
                    scenario.state.labels = [...accumulatedLabels];
                }
                // Carry forward audio/visual changes from previous directives
                if (i > 0 && directives[i - 1]) {
                    const prev = directives[i - 1];
                    if (prev.audio) {
                        if (prev.audio.sweepStart != null) scenario.state.audio.sweepStart = prev.audio.sweepStart;
                        if (prev.audio.sweepTrough != null) scenario.state.audio.sweepTrough = prev.audio.sweepTrough;
                        if (prev.audio.sweepEnd != null) scenario.state.audio.sweepEnd = prev.audio.sweepEnd;
                        if (prev.audio.isochronicEnabled != null) scenario.state.audio.isochronicEnabled = prev.audio.isochronicEnabled;
                    }
                    if (prev.visuals?.effect) {
                        scenario.state.visuals.effects = [prev.visuals.effect];
                    }
                }

                try {
                    const msg = this._formatStateMessage(scenario.state);
                    console.log('SessionDirector [TEST] Sending:', msg);
                    const response = await am7chat.chat(chatRequest, msg);
                    const content = this._extractContent(response);
                    console.log('SessionDirector [TEST] Raw response:', content);

                    if (!content) {
                        log(scenario.name, false, 'Empty response from LLM');
                        directives.push(null);
                        continue;
                    }

                    const directive = this._parseDirective(content);
                    directives.push(directive);

                    if (directive) {
                        // Track accumulated labels
                        if (directive.labels?.add) {
                            for (const l of directive.labels.add) {
                                if (!accumulatedLabels.includes(l)) accumulatedLabels.push(l);
                            }
                            if (accumulatedLabels.length > 10) accumulatedLabels = accumulatedLabels.slice(-10);
                        }
                        if (directive.labels?.remove) {
                            accumulatedLabels = accumulatedLabels.filter(l => !directive.labels.remove.includes(l));
                        }

                        // Build detail summary
                        const parts = [];
                        if (directive.audio) parts.push('audio:' + Object.keys(directive.audio).join(','));
                        if (directive.visuals) parts.push('visual:' + (directive.visuals.effect || 'unchanged'));
                        if (directive.labels?.add?.length) parts.push('+' + directive.labels.add.length + ' labels');
                        if (directive.labels?.remove?.length) parts.push('-' + directive.labels.remove.length + ' labels');
                        if (directive.generateImage) parts.push('img:yes');
                        if (directive.askUser) parts.push('askUser:"' + directive.askUser.question.substring(0, 30) + '"');
                        if (directive.commentary) parts.push('note:' + directive.commentary.substring(0, 40));

                        log(scenario.name, true, parts.join(' | ') || 'empty directive', directive);

                        // Notify caller for live application
                        if (onDirective) onDirective(directive, i, scenario.name);

                        // Verify directive was applied to actual session state
                        if (onDirective && this.stateProvider) {
                            await new Promise(r => setTimeout(r, 200));
                            const postState = this.stateProvider();
                            const verifications = this._verifyDirectiveApplication(directive, postState);
                            for (const v of verifications) {
                                log(scenario.name + ' verify: ' + v.field, v.pass, v.detail);
                            }
                        }
                    } else {
                        log(scenario.name, false, 'Could not parse: ' + content.substring(0, 80));
                    }
                } catch (err) {
                    log(scenario.name, false, err.message);
                    directives.push(null);
                }
            }

            // === ADAPTATION ANALYSIS ===

            const validDirectives = directives.filter(d => d != null);
            if (validDirectives.length >= 2) {
                // Check if directives actually changed between passes
                let changes = 0;
                for (let i = 1; i < validDirectives.length; i++) {
                    const prev = JSON.stringify(validDirectives[i - 1]);
                    const curr = JSON.stringify(validDirectives[i]);
                    if (prev !== curr) changes++;
                }

                if (changes > 0) {
                    log('Adaptation check', true,
                        changes + '/' + (validDirectives.length - 1) + ' passes produced different directives');
                } else {
                    log('Adaptation check', false,
                        'All passes returned identical directives - LLM may not be responding to state changes');
                }

                // Check if emotion shift caused any label/visual/audio change
                const hasLabelActivity = validDirectives.some(d => d.labels?.add?.length || d.labels?.remove?.length);
                const hasAudioChange = validDirectives.some(d => d.audio && Object.keys(d.audio).length > 0);
                const hasVisualChange = validDirectives.some(d => d.visuals?.effect);

                const hasAskUser = validDirectives.some(d => d.askUser);

                log('Coverage: labels', hasLabelActivity, hasLabelActivity ? 'LLM generated labels' : 'No labels across all passes');
                log('Coverage: audio', hasAudioChange, hasAudioChange ? 'LLM adjusted audio' : 'No audio changes across all passes');
                log('Coverage: visuals', hasVisualChange, hasVisualChange ? 'LLM changed visuals' : 'No visual changes across all passes');
                log('Coverage: askUser', hasAskUser, hasAskUser ? 'LLM used askUser directive' : 'No askUser across all passes (may be expected if interactive scenario was not reached)');
            } else if (validDirectives.length === 1) {
                log('Adaptation check', true, 'Only 1 valid directive - cannot compare adaptation');
            } else {
                log('Adaptation check', false, 'No valid directives returned');
            }

            return results;
        }

        /**
         * Emit status change
         * @param {string} status
         * @private
         */
        _setStatus(status) {
            if (this.onStatusChange) {
                this.onStatusChange(status);
            }
        }

        /**
         * Clean up all resources
         */
        dispose() {
            this.stop();
            this.chatRequest = null;
            this.chatConfig = null;
            this.promptConfig = null;
            this.stateProvider = null;
            this.onDirective = null;
            this.onError = null;
            this.onStatusChange = null;
            this.onTickDebug = null;
            this.lastDirective = null;
            this.lastError = null;
        }
    }

    // Export
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = SessionDirector;
    }
    if (typeof window !== 'undefined') {
        window.Magic8 = window.Magic8 || {};
        window.Magic8.SessionDirector = SessionDirector;
    }

}());
