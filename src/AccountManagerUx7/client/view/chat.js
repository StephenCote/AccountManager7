
(function () {

  am7model.forms.chatRequest = {
    label: "Chat Request",
    commands: {
      cmdPeek: {
        label: 'Peek',
        icon: 'man',
        action: 'doPeek'
      }
    },
    fields: {
      prompt: {
        layout: 'full',
        format: 'list'
      },
      chat: {
        layout: 'full',
        format: 'list'
      },

      session: {
        layout: 'full'
      }
    }
  };
  /// Phase 13d item 15 (OI-65): Two view construction approaches exist:
  /// 1) am7view (generic) — uses 'list' field type, simpler structure, preferred for new forms
  /// 2) Object component view (legacy) — uses 'select' field type, supports complex nested forms
  /// The chatRequest forms below use the object component view because the chatSettings dialog
  /// requires picker fields and nested config resolution that am7view doesn't fully support.
  /// New forms should prefer am7view unless picker/nested config features are needed.
  am7model.forms.newChatRequest = {
    label: "Chat Request",
    fields: {
      promptConfig: {
        layout: 'third',
        format: 'picker',
        field: {
          format: 'picker',
          pickerType: "olio.llm.promptConfig",
          pickerProperty: {
            selected: "{object}",
            entity: "promptConfig"
          },
          label: "Prompt Config"
        }
      },
      chatConfig: {
        layout: 'third',
        format: 'picker',
        field: {
          format: 'picker',
          pickerType: "olio.llm.chatConfig",
          pickerProperty: {
            selected: "{object}",
            entity: "chatConfig"
          },
          label: "Chat Config"
        }
      },
      name: {
        label: "Chat Name",
        layout: 'third'
      },
      setting: {
        label: "Setting",
        layout: 'full'
      }
    }
  };

  function newChatControl() {

    let inst;
    const chat = {};
    let fullMode = false;
    let showFooter = false;
    let pagination = page.pagination();
    let listView;
    let treeView;
    let altView;
    let aPCfg;
    let aCCfg;
    // Phase 12: aSess removed — ConversationManager owns session list
    let audioText = "";
    let chatCfg = newChatConfig();

    let camera = false;
    let audio = false;

    let hideThoughts = true;
    // Phase 13f item 25: MCP debug toggle (OI-70)
    let mcpDebug = false;
    let editIndex = -1;
    let editMode = false;

    let profile = false;

    // Phase 10 (OI-40): Register policyEvent display handler
    if (window.LLMConnector) {
        LLMConnector.onPolicyEvent(function(evt) {
            let msg = "Policy violation detected";
            if (evt && evt.data) {
                try {
                    let details = typeof evt.data === "string" ? JSON.parse(evt.data) : evt.data;
                    if (details.details) msg = details.details;
                } catch(e) {}
            }
            page.toast("warn", msg, 5000);
        });
    }

    // Phase 13f (OI-71, OI-72): Register memoryEvent display handler
    if (window.LLMConnector) {
        LLMConnector.onPolicyEvent; // already registered above
        let _memHandler = function(evt) {
            if (!evt) return;
            let detail = "";
            if (evt.data) {
                try {
                    let parsed = typeof evt.data === "string" ? JSON.parse(evt.data) : evt.data;
                    detail = parsed.data || "";
                } catch(e) { detail = evt.data; }
            }
            if (evt.type === "extracted") {
                page.toast("info", "Memory created: " + detail, 3000);
            } else if (evt.type === "recalled") {
                console.log("[Chat] Recalled " + detail + " memories");
            } else if (evt.type === "keyframe") {
                console.log("[Chat] Keyframe created: " + detail);
            }
            // Refresh MemoryPanel when memories change
            if (window.MemoryPanel && chatCfg.chat) {
                MemoryPanel.refresh();
            }
        };
        // Wire to LLMConnector's existing handleMemoryEvent dispatch
        LLMConnector._chatMemoryHandler = _memHandler;
        let _origHandler = LLMConnector.handleMemoryEvent;
        LLMConnector.handleMemoryEvent = function(data) {
            _origHandler.call(LLMConnector, data);
            _memHandler(data);
        };
    }

    // Image token state
    let resolvingImages = {};
    let showTagSelector = false;
    let selectedImageTags = [];

    // Patch image tokens on the server request, then reload history to stay in sync
    // replacements: array of {from: unresolvedTokenStr, to: resolvedTokenStr}
    async function patchChatImageToken(replacements) {
        if (!inst || !replacements || !replacements.length) return;
        try {
            let sessionType = inst.api.sessionType();
            let session = inst.api.session();
            if (!sessionType || !session || !session.id) {
                console.warn("patchChatImageToken: No session available for server patch");
                return;
            }
            // Load the full request from the server
            let q = am7view.viewQuery(am7model.newInstance(sessionType));
            q.field("id", session.id);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length > 0) {
                let req = qr.results[0];
                if (req.messages) {
                    let patched = false;
                    // Search server messages for unresolved tokens and replace them
                    for (let i = req.messages.length - 1; i >= 0; i--) {
                        let serverMsg = req.messages[i];
                        if (!serverMsg.content) continue;
                        for (let r of replacements) {
                            if (serverMsg.content.indexOf(r.from) !== -1) {
                                serverMsg.content = serverMsg.content.replace(r.from, r.to);
                                patched = true;
                            }
                        }
                    }
                    if (patched) {
                        await page.patchObject(req);
                        // Reload history from server so local state matches
                        // Skip during streaming — server reload would overwrite locally-building content
                        if (!chatCfg.streaming) {
                            let h = await getHistory();
                            if (h) {
                                chatCfg.history = h;
                            }
                        }
                    }
                }
            }
        } catch (e) {
            console.error("patchChatImageToken server error:", e);
        }
    }

    // Resolve all image tokens in a message (chat.js version)
    // Processes one token at a time, patching after each to keep positions and server state in sync
    async function resolveChatImages(msgIndex) {
        if (!window.am7imageTokens) return;
        // Skip resolution during streaming — async patch/reload would overwrite streaming content
        if (chatCfg.streaming) return;
        let maxIterations = 10;
        let iteration = 0;

        while (iteration < maxIterations) {
            iteration++;

            // Re-read message content on each iteration so positions reflect any prior patches
            let msgs = chatCfg.history?.messages;
            if (!msgs || !msgs[msgIndex]) return;
            let msg = msgs[msgIndex];
            if (!msg.content) return;

            let character = msg.role === "user" ? chatCfg.user : chatCfg.system;
            if (!character) return;

            let tokens = window.am7imageTokens.parse(msg.content);

            // Find first unresolved token
            let unresolvedToken = null;
            for (let token of tokens) {
                if (!token.id || !window.am7imageTokens.cache[token.id]) {
                    unresolvedToken = token;
                    break;
                }
            }

            if (!unresolvedToken) break; // All resolved

            try {
                let resolved = await window.am7imageTokens.resolve(unresolvedToken, character);
                if (resolved && resolved.image) {
                    if (!unresolvedToken.id) {
                        let newToken = "${image." + resolved.image.objectId + "." + unresolvedToken.tags.join(",") + "}";
                        await patchChatImageToken([{from: unresolvedToken.match, to: newToken}]);
                    }
                } else {
                    // Resolution returned null – skip to avoid infinite loop
                    break;
                }
            } catch (e) {
                console.error("Error resolving image token:", unresolvedToken.match, e);
                break;
            }
        }
        m.redraw();
    }

    // Phase 10b: Delegate image token processing to ChatTokenRenderer
    function processImageTokensInContent(content, msgRole, msgIndex) {
        if (!window.ChatTokenRenderer) return content;
        let characters = { system: chatCfg.system, user: chatCfg.user };
        return ChatTokenRenderer.processImageTokens(content, msgRole, msgIndex, characters, { resolvingImages: resolvingImages }, function(idx) {
            return resolveChatImages(idx);
        });
    }

    function toggleImageTag(tag) {
        let idx = selectedImageTags.indexOf(tag);
        if (idx > -1) {
            selectedImageTags.splice(idx, 1);
        } else {
            selectedImageTags.push(tag);
        }
    }

    function insertImageToken() {
        if (!selectedImageTags.length) return;
        let token = "${image." + selectedImageTags.join(",") + "}";
        let input = document.querySelector("[name='chatmessage']");
        if (input) {
            input.value = (input.value ? input.value + " " : "") + token;
        }
        selectedImageTags = [];
        showTagSelector = false;
    }

    // ==========================================
    // Audio token inline processing
    // ==========================================

    // Phase 10b: Delegate audio token processing to ChatTokenRenderer
    function processAudioTokensInContent(content, msgRole, msgIndex) {
        if (!window.ChatTokenRenderer) return content;
        let characters = { system: chatCfg.system, user: chatCfg.user };
        let sessionId = inst ? inst.api.objectId() : 'nosess';
        return ChatTokenRenderer.processAudioTokens(content, msgRole, msgIndex, characters, sessionId);
    }

    function doClear() {
      clearEditMode();
      inst = undefined;
      chatCfg = newChatConfig();
      if (window.ContextPanel) ContextPanel.clear();
    }

    function pickSession(obj) {
      doClear();
      inst = am7model.prepareInstance(obj);
      window.dbgInst = inst;
      // Phase 10c: Load context panel for selected session
      if (window.ContextPanel && obj && obj.objectId) {
        ContextPanel.load(obj.objectId);
      }
      doPeek().then(function() {
        // Phase 13f: Load memories for the character pair (OI-69)
        if (window.MemoryPanel && chatCfg.chat) {
          MemoryPanel.loadForSession(chatCfg.chat);
        }
      }).catch(function(e) {
        console.warn("Failed to peek session", e);
      });
    }

    // Phase 13a item 3: Use AnalysisManager instead of dialog.chatInto (OI-56, OI-57)
    // When already on /chat, startAnalysis stores pending state then we immediately execute it
    async function chatInto() {
      if (!inst) {
        page.toast("warn", "Select or create a chat session before analyzing");
        return;
      }
      if (!window.AnalysisManager) {
        console.warn("AnalysisManager not loaded, analysis unavailable");
        return;
      }
      await AnalysisManager.startAnalysis(inst.entity, inst, aCCfg || []);
      // Already on /chat — oninit won't re-fire, so execute pending immediately
      if (AnalysisManager.getActiveAnalysis()) {
        await AnalysisManager.executePending();
      }
    }

    async function doCancel() {
      clearEditMode();
      chatCfg.pending = false;
      chatCfg.streaming = false;
      chatCfg.peek = false;
      chatCfg.history = { messages: [] };
      if(inst && inst.api.objectId()){
        let chatReq = {
          schema: inst.model.name,
          objectId: inst.api.objectId(),
          uid: page.uid()
        };

        return m.request({ method: 'POST', url: g_application_path + "/rest/chat/clear", withCredentials: true, body: chatReq }).then((r) => {
          m.redraw();
        });
      }
    }

    function newChatStream() {

      let cfg = {
        streamId: undefined,
        request: undefined,

        onchatcomplete: (id) => {
          console.log("Chat completed: " + id);
          if (page.chatStream && page.chatStream.streamId !== id) {
            console.warn("Mismatched stream identifiers");
          }
          clearChat();
          // If user interrupted with a message, re-send it now that the stream is stopped
          if (chatCfg.interruptMsg) {
            let msg = chatCfg.interruptMsg;
            chatCfg.interruptMsg = null;
            let inputEl = document.querySelector("[name='chatmessage']");
            if (inputEl) inputEl.value = msg;
            setTimeout(() => doChat(), 50);
          }
          m.redraw();
        },
        onchaterror: (id, msg) => {
          page.toast("error", "Chat error: " + msg, 5000);
          clearChat();
          // If interrupted, still re-send the user's message
          if (chatCfg.interruptMsg) {
            let imsg = chatCfg.interruptMsg;
            chatCfg.interruptMsg = null;
            let inputEl = document.querySelector("[name='chatmessage']");
            if (inputEl) inputEl.value = imsg;
            setTimeout(() => doChat(), 50);
          }
          m.redraw();
        },
        onchatstart: (id, req) => {
          // console.log("Start chat...");
          cfg.streamId = id;
          if (!chatCfg) {
            console.warn("Invalid chat configuration");
            return;
          }
          if (!chatCfg.history) chatCfg.history = {};
          if (!chatCfg.history.messages) chatCfg.history.messages = [];
          chatCfg.history.messages.push({ role: "assistant", content: "" });
        },
        onchatupdate: (id, msg) => {
          // console.log("Chat update: " + msg);
          if (!chatCfg.history || !chatCfg.history.messages || !chatCfg.history.messages.length) {
            console.error("Unexpected chat history — missing or empty");
            return;
          }
          let m1 = chatCfg.history.messages[chatCfg.history.messages.length - 1];
          if (m1.role != "assistant") {
            console.error("Expected assistant message to be the most recent");
            return;
          }

          m1.content += msg;
          m.redraw();
        }
      }

      function clearChat() {
        page.chatStream = undefined;
        chatCfg.streaming = false;
        chatCfg.pending = false;
      }

      return cfg;
    }

    function newChainStream() {
      let cfg = {
        steps: [],
        currentStep: 0,
        totalSteps: 0,
        isRunning: false,

        onchainStart: (data) => {
          cfg.isRunning = true;
          cfg.steps = [];
          cfg.currentStep = 0;
          m.redraw();
        },
        onstepStart: (data) => {
          if (data) {
            cfg.currentStep = data.stepNumber || 0;
            cfg.totalSteps = data.totalSteps || 0;
            cfg.steps.push({
              step: data.stepNumber,
              type: data.stepType || "TOOL",
              status: "executing",
              summary: data.stepSummary || "Executing..."
            });
          }
          m.redraw();
        },
        onstepComplete: (data) => {
          if (data && cfg.steps.length > 0) {
            let last = cfg.steps[cfg.steps.length - 1];
            last.status = "completed";
            last.summary = data.stepSummary || "Completed";
            cfg.totalSteps = data.totalSteps || cfg.totalSteps;
          }
          m.redraw();
        },
        onstepError: (data) => {
          if (data && cfg.steps.length > 0) {
            let last = cfg.steps[cfg.steps.length - 1];
            last.status = "error";
            last.summary = data.errorMessage || "Error";
          }
          m.redraw();
        },
        onchainComplete: (data) => {
          cfg.isRunning = false;
          if (data && data.stepSummary) {
            if (!chatCfg.history) chatCfg.history = {};
            if (!chatCfg.history.messages) chatCfg.history.messages = [];
            chatCfg.history.messages.push({ role: "assistant", content: data.stepSummary });
          }
          page.chainStream = undefined;
          m.redraw();
        },
        onstepsInserted: (data) => {
          if (data) {
            cfg.totalSteps = data.totalSteps || cfg.totalSteps;
          }
          m.redraw();
        },
        onchainMaxSteps: (data) => {
          cfg.isRunning = false;
          page.toast("warn", "Chain reached maximum step limit", 5000);
          page.chainStream = undefined;
          m.redraw();
        },
        onchainError: (data) => {
          cfg.isRunning = false;
          let msg = (typeof data === "string") ? data : (data?.errorMessage || "Chain error");
          page.toast("error", "Chain error: " + msg, 5000);
          page.chainStream = undefined;
          m.redraw();
        }
      };
      return cfg;
    }

    function getChainProgressView() {
      if (!page.chainStream || !page.chainStream.isRunning) return null;
      let cs = page.chainStream;
      let statusIcons = { "completed": "\u2713", "error": "\u2717", "executing": "\u25CB" };
      return m("div.chain-progress", [
        m("div.chain-progress-bar", [
          m("span", "Chain: Step " + cs.currentStep + " / " + cs.totalSteps),
          cs.isRunning ? m("span.chain-spinner", " \u23F3") : null
        ]),
        m("div.chain-steps", cs.steps.map(s =>
          m("div.chain-step." + s.status, [
            m("span.chain-step-icon", statusIcons[s.status] || "?"),
            m("span.chain-step-type", " [" + s.type + "] "),
            m("span.chain-step-summary", s.summary)
          ])
        ))
      ]);
    }

    function doChainSend() {
      let msg = document.querySelector("[name='chatmessage']").value;
      if (!msg || !msg.trim()) return;
      pushHistory();
      page.chainStream = newChainStream();
      page.chainStream.isRunning = true;
      let chainReq = { planQuery: msg };
      page.wss.send("chain", JSON.stringify(chainReq), undefined);
      m.redraw();
    }

    function doStop() {
      if (!chatCfg.streaming) {
        return;
      }
      let chatReq = {
        schema: inst.model.name,
        objectId: inst.api.objectId(),
        uid: page.uid(),
        message: "[stop]"
      };

      page.wss.send("chat", JSON.stringify(chatReq), undefined, inst.model.name);
    }

    function doChat() {
      clearEditMode();
      // Stream interrupt: user typed during streaming — stop stream, then re-send with prefix
      if (chatCfg.streaming) {
        let inputEl = document.querySelector("[name='chatmessage']");
        let interruptMsg = inputEl ? inputEl.value.trim() : "";
        if (!interruptMsg) {
          doStop();
          return;
        }
        chatCfg.interruptMsg = "[interrupting] " + interruptMsg;
        if (inputEl) inputEl.value = "";
        doStop();
        return;
      }
      if (chatCfg.pending) {
        console.warn("Chat is pending");
        return;
      }
      let msg = page.components.audio.recording() ? audioText : document.querySelector("[name='chatmessage']").value; ///e?.target?.value;
      if (!chatCfg.peek) {
        doPeek().then(() => {
          doChat();
        })
      }
      else {
        pushHistory();
        chatCfg.pending = true;
        let data = page.components.dnd.workingSet.map(r => { return JSON.stringify({ schema: r.schema, objectId: r.objectId }); });

        let chatReq = {
          schema: inst.model.name,
          objectId: inst.api.objectId(),
          uid: page.uid(),
          message: msg + (msg.length && faceProfile ? "\n(Metrics: " + JSON.stringify(faceProfile) + ")": ""),
          data
        };
        try {
          if (chatCfg?.chat.stream) {
            page.chatStream = newChatStream();
            chatCfg.streaming = true;
            page.wss.send("chat", JSON.stringify(chatReq), undefined, inst.model.name);
          }
          else {
            m.request({ method: 'POST', url: g_application_path + "/rest/chat/text", withCredentials: true, body: chatReq }).then((r) => {
              if (!chatCfg.history) chatCfg.history = {};
              chatCfg.history.messages = r?.messages || [];
              chatCfg.pending = false;
              // Audio components will automatically update when messages change
            });
          }
        }
        catch (e) {
          console.error("Error in chat", e);
          chatCfg.pending = false;
        }
      }
    }

    // Phase 12: deleteChat removed — ConversationManager handles session deletion (OI-52)

    /// Note: Chatmessage isn't defined in its own model, and should be to avoid all the goofy checks
    function pushHistory() {
      let msg = page.components.audio.recording() ? audioText : document.querySelector("[name='chatmessage']").value;
      if (!chatCfg.history) chatCfg.history = {};
      if (!chatCfg.history.messages) chatCfg.history.messages = [];
      chatCfg.history.messages.push({ role: "user", content: msg });
      if (page.components.audio.recording()) {
        audioText = "";
      }
      else {
        document.querySelector("[name='chatmessage']").value = "";
      }
      m.redraw();
    }

    function getHistory() {
      if (!inst) {
        return Promise.resolve(null);
      }
      // Phase 10: Delegate to LLMConnector
      return LLMConnector.getHistory({
        schema: inst.model.name,
        objectId: inst.api.objectId()
      });
    }

    function newChatConfig() {
      return {
        // UI state
        peek: false,          // true once configs + history have been fetched via doPeek
        pending: false,       // true while waiting for LLM response
        streaming: false,     // true during WebSocket streaming

        // Server-side objects (populated by doPeek)
        chat: null,           // olio.llm.chatConfig — full server chatConfig
        prompt: null,         // olio.llm.promptConfig — full server promptConfig

        // Characters (extracted from chatConfig for convenience)
        system: null,         // systemCharacter from chatConfig
        user: null,           // userCharacter from chatConfig

        // Message state
        history: null         // {messages: [...]} — conversation history from server
      };
    }


    function doPeek() {
      if (chatCfg.peek || !inst) {
        return Promise.resolve();
      }

      let c1 = inst.api.promptConfig();
      let c2 = inst.api.chatConfig();
      let p;
      if (c1 && c2 && (c1.name || c1.objectId) && (c2.name || c2.objectId)) {
        // Phase 10b: Prefer objectId-based lookup (10a endpoints), fall back to name
        let promptUrl = c1.objectId
            ? g_application_path + "/rest/chat/config/prompt/id/" + c1.objectId
            : g_application_path + "/rest/chat/config/prompt/" + encodeURIComponent(c1.name);
        let chatUrl = c2.objectId
            ? g_application_path + "/rest/chat/config/chat/id/" + c2.objectId
            : g_application_path + "/rest/chat/config/chat/" + encodeURIComponent(c2.name);

        chatCfg.peek = true;
        p = new Promise((res, rej) => {
          m.request({ method: 'GET', url: promptUrl, withCredentials: true })
            .then((c) => {
              chatCfg.prompt = c;

              m.request({ method: 'GET', url: chatUrl, withCredentials: true }).then((c3) => {
                chatCfg.chat = c3;
                chatCfg.system = c3.systemCharacter;
                chatCfg.user = c3.userCharacter;
                getHistory().then((h) => {
                  chatCfg.peek = true;
                  chatCfg.history = h;
                  m.redraw();
                  res();
                }).catch((e) => {
                  console.warn("Error in chat history", e);
                  chatCfg.peek = false;
                  rej(e);
                });
              }).catch((e) => {
                  console.warn("Error in chat config", e);
                  chatCfg.peek = false;
                  rej(e);
                });
            }).catch((e) => {
              console.warn("Error in prompt config", e);
              chatCfg.peek = false;
              rej(e);
            });
        });
      }
      else {
        console.warn("No prompt or chat config");
        p = Promise.resolve();
      }
      return p;
    }

    function getChatTopMenuView() {

      return [];
    }

    function getSplitContainerView() {
      let splitLeft = "";
      if (!fullMode) splitLeft = getSplitLeftContainerView();
      // , onselectstart: function (e) { e.preventDefault(); }
      return m("div", { class: "results-fixed" },
        m("div", { class: "splitcontainer" }, [
          splitLeft,
          getSplitRightContainerView()
        ])
      );
    }

    function getSplitRightContainerView() {
      let cls = "splitrightcontainer";
      if (fullMode) cls = "splitfullcontainer";
      return m("div", { class: cls }, [
        getResultsView()
      ]);
    }

    chat.callback = function () {

    };

    // Phase 10b: Sidebar delegates to ConversationManager component
    // Phase 10c: ContextPanel added below ConversationManager
    // Phase 12: Removed pre-10b fallback session list (OI-52)
    // Phase 13f item 23: MemoryPanel added between ConversationManager and ContextPanel
    function getSplitLeftContainerView() {
      let children = [];
      if (window.ConversationManager) {
        children.push(m(ConversationManager.SidebarView, { onNew: openChatSettings }));
      }
      if (window.MemoryPanel) {
        children.push(m(MemoryPanel.PanelView));
      }
      if (window.ContextPanel) {
        children.push(m(ContextPanel.PanelView));
      }
      return m("div", { class: "splitleftcontainer flex flex-col" }, children);
    }


    function openChatSettings(cinst) {
      page.components.dialog.chatSettings(cinst && cinst.model ? cinst : undefined, async function (e) {

        //let obj = await page.createObject(e);
        let chatReq = {
          schema: e[am7model.jsonModelKey],
          name: e.name,
          objectId: e.objectId,
          chatConfig: { objectId: e.chatConfig.objectId },
          promptConfig: { objectId: e.promptConfig.objectId },
          uid: page.uid()
        };
        let obj = await m.request({ method: 'POST', url: g_application_path + "/rest/chat/new", withCredentials: true, body: chatReq });
        inst = am7model.prepareInstance(obj);
        window.dbgInst = inst;
        doClear();
        await am7client.clearCache();
        await loadConfigList();
        doPeek();
      });
    }

    function toggleAudio() {
      // Stop all currently playing audio from both old and new systems
      if (page.components.audio) {
        page.components.audio.stopAudioSources();
        page.components.audio.stopAllAudioSourceControllers();
      }

      audio = !audio;
      m.redraw();
    }

    function sendToMagic8() {
      const magic8Context = {
        chatConfigId: chatCfg?.chat?.objectId,
        chatHistory: chatCfg?.history?.messages || [],
        systemCharacter: chatCfg?.system,
        userCharacter: chatCfg?.user,
        instanceId: inst?.api?.objectId()
      };
      // Phase 10c: Pass sessionId as route param; Magic8 can load context from server.
      // Keep sessionStorage as fallback for complex objects (history, characters)
      // that aren't in the REST context endpoint.
      sessionStorage.setItem('magic8Context', JSON.stringify(magic8Context));
      let routeParams = { context: 'chat' };
      if (inst && inst.api.objectId()) {
        routeParams.sessionId = inst.api.objectId();
      }
      m.route.set('/magic8', routeParams);
    }
    
    let faceProfile;
    function handleFaceMetricCapture(imageData) {
      if(imageData && imageData.results?.length){
        let id = imageData.results[0];
       faceProfile = {
          emotion: id.dominant_emotion,
          emotions: Object.fromEntries(
            Object.entries(id.emotion_scores).map(([key, value]) => {let vf = value; return [key, vf.toFixed(2)];})
          ),
          /*
          gender: id.dominant_gender,
          genders: Object.fromEntries(
            Object.entries(id.gender_scores).map(([key, value]) => {let vf = value; return [key, vf.toFixed(2)];})
          ),
          */
          race: id.dominant_race
          /*
          races: Object.fromEntries(
            Object.entries(id.race_scores).map(([key, value]) => {let vf = value; return [key, vf.toFixed(2)];})
          ),
          */
          //age: id.age
        };
      }
    }

    function toggleProfile() {
      profile = !profile;
    }
    function toggleCamera() {
      camera = !camera;
      if(camera){
        if(!page.components.camera.devices().length){
          page.components.camera.initializeAndFindDevices(handleFaceMetricCapture);
        }
        else{
          page.components.camera.startCapture(handleFaceMetricCapture);
        }
      }
      else{
        page.components.camera.stopCapture();
      }
    }

    function toggleThoughts() {
      hideThoughts = !hideThoughts;
    }

    function clearEditMode() {
      editMode = false;
      editIndex = -1;
    }

    /// Currently only editing the last assistant message
    /// The reasoning: The message history used for display is a truncated version of what's in the chat request
    /// Alternately, this could be a separate API, but that seems unnecessary at the moment
    async function editMessageHistory(idx, cnt) {

      let q = am7view.viewQuery(am7model.newInstance(inst.api.sessionType()));
      q.field("id", inst.api.session().id);
      let qr = await page.search(q);
      if (qr && qr.results.length > 0) {
        console.log("Patching session data", qr.results[0].objectId);
        let req = qr.results[0];

        /// change the contents of the last message 
        req.messages[req.messages.length - 1].content = cnt;
        let patch = await page.patchObject(req);
        clearEditMode();
        await am7client.clearCache();
        await doCancel();
        await doPeek();
      }
      else {
        console.error("Failed to find session data to edit", qr);
      }
      clearEditMode();
    }
    function toggleEditMode(idx) {
      if (editMode) {

        /// Put message back in the history
        ///
        editMessageHistory(idx, document.getElementById("editMessage").value);
      }
      else {
        editMode = true;
        editIndex = idx;
      }
    }

    function getResultsView() {
      if (!inst) {
        return "";
      }
      let c1g = "man";
      let c1l = "Nobody";
      let c2g = "man";
      let c2l = "Nobody";
      if (chatCfg.system) {
        c1l = chatCfg.system.name;
        if (chatCfg.system.gender == "female") {
          c1g = "woman";
        }
      }
      if (chatCfg.user) {
        c2l = chatCfg.user.name;
        if (chatCfg.user.gender == "female") {
          c2g = "woman";
        }
      }

      let c1i = m("span", { class: "material-icons-outlined" }, c1g);
      let c2i = m("span", { class: "material-icons-outlined" }, c2g);
      if (chatCfg.system?.profile?.portrait) {
        let pp = chatCfg.system.profile.portrait;
        let dataUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/96x96";
        // h-8 w-8
        c1i = m("img", { onclick: function () { page.imageView(pp); }, class: "mr-4 rounded-full", src: dataUrl });
      }

      if (chatCfg.user?.profile?.portrait) {
        let pp = chatCfg.user.profile.portrait;
        let dataUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/96x96";
        // h-8 w-8 
        c2i = m("img", { onclick: function () { page.imageView(pp); }, class: "ml-4 rounded-full", src: dataUrl });
      }

      let midx = -1;
      let aidx = 1;
      let amsg = chatCfg.history?.messages || [];
      let hasAutoPlayedThisSession = false;
      let msgs = amsg.map((msg) => {
        midx++;
        let align = "justify-start";
        let txt = "bg-gray-600 text-white";
        // console.log(msg);
        if (msg.role == "user") {
          align = "justify-end";
          txt = "dark:bg-gray-900 dark:text-white bg-gray-200 text-black";
        }
        let cnt = msg.content || "";
        let ectl = "";
        let ecls = "";
        let bectl = false;
        let lastMsg = (midx == (amsg.length - 1));
        /// Prefer server-side displayContent when available and hideThoughts is active
        let useServerDisplay = hideThoughts && !editMode && msg.displayContent;
        if (useServerDisplay) {
          cnt = msg.displayContent;
        }
        if (!useServerDisplay && hideThoughts && !editMode && window.LLMConnector) {
          cnt = LLMConnector.pruneOut(cnt, "--- CITATION", "END CITATIONS ---")
        }
        if (msg.role == "assistant") {
          bectl = (editMode && editIndex == midx);

          /// Only edit last message
          if (midx == amsg.length - 1) {
            ectl = m("span", { onclick: function () { toggleEditMode(midx); }, class: "material-icons-outlined text-slate-" + (bectl ? 200 : 700) }, "edit");
          }
          if (!useServerDisplay && hideThoughts && !editMode && window.LLMConnector) {
            cnt = LLMConnector.pruneToMark(cnt, "<|reserved_special_token");
            cnt = LLMConnector.pruneTag(cnt, "think");
            cnt = LLMConnector.pruneTag(cnt, "thought");
            cnt = LLMConnector.pruneOther(cnt);
          }

          if (bectl) {
            ecls = "w-full ";
            cnt = m("textarea", { id: "editMessage", class: "text-field textarea-field-full" }, cnt);
          }

        }
        if (!useServerDisplay && !bectl && hideThoughts && window.LLMConnector) {
          cnt = LLMConnector.pruneToMark(cnt, "(Metrics");
          cnt = LLMConnector.pruneToMark(cnt, "(Reminder");
          cnt = LLMConnector.pruneToMark(cnt, "(KeyFrame");
        }
        // Phase 13f item 25: MCP debug display (OI-70)
        if (!bectl && window.ChatTokenRenderer && ChatTokenRenderer.processMcpTokens) {
          cnt = ChatTokenRenderer.processMcpTokens(cnt, mcpDebug);
        }
        if (typeof cnt == "string") {
          // Process image and audio tokens before empty check — a message
          // that is only an image/audio token should show the placeholder
          cnt = processImageTokensInContent(cnt, msg.role, midx);
          cnt = processAudioTokensInContent(cnt, msg.role, midx);
        }
        if (!editMode && typeof cnt == "string" && cnt.trim().length == 0) {
          return "";
        }
        if (typeof cnt == "string") {
          //cnt = cnt.replace(/\r/,"").split("\n").map((l)=>{return m("p", l)});
          cnt = m.trust(marked.parse(cnt = page.components.emoji.markdownEmojis(cnt.replace(/\r/, ""))));
        }
        let aud = "";

        if (chatCfg.chat && audio && !chatCfg.streaming) {
          let profileId;
          if (msg.role == "assistant") {
            profileId = chatCfg?.system?.profile?.objectId;
          }
          else {
            profileId = chatCfg?.user?.profile?.objectId;
          }

          // Generate stable name based on chat objectId, role, and content hash
          let contentForHash = window.LLMConnector ? LLMConnector.pruneAll(msg.content) : (msg.content || "");
          let contentHash = page.components.audioComponents.simpleHash(contentForHash);
          let name = inst.api.objectId() + "-" + msg.role + "-" + contentHash;

          // Auto-play only the last message, and only once per session
          let shouldAutoPlay = lastMsg && !hasAutoPlayedThisSession;
          if (shouldAutoPlay) {
            hasAutoPlayedThisSession = true;
          }

          // Use new SimpleAudioPlayer component with stable key based on content
          aud = m(page.components.audioComponents.SimpleAudioPlayer, {
            key: name, // Use stable key based on content hash (same content = same component)
            id: name + "-" + aidx,
            name: name,
            profileId: profileId,
            content: contentForHash,
            autoLoad: true,  // Always load audio to create visualizer
            autoPlay: shouldAutoPlay,
            autoPlayDelay: 200,
            autoStopOthers: true,
            visualizerClass: "audio-container block w-full",
            height: 60,
            gradient: "prism",
            onDelete: () => {
              // Optional: Handle audio deletion if needed
              console.log("Delete audio:", name);
            }
          });

          aidx++;
        }
        return m("div", { class: "relative receive-chat flex " + align },
          [ectl, m("div", { class: ecls + "px-3 mb-1 " + txt + " py-1.5 text-sm max-w-[85%] border rounded-md font-light" },
            (audio ? aud : m("p", cnt))
            //,aud
          )]
        );
      });
      let flds = [m("div", { class: "flex justify-between" }, [
        m("div", { class: "flex items-center" }, [
          c1i,
          m("span", { class: "text-gray-400 text-sm pl-2" }, c1l)
        ]),
        m("div", { class: "flex items-center" }, [
          m("span", { class: "text-gray-400 text-sm pl-2" }, c2l),
          c2i
        ])
      ])
      ];
      let setting = inst.api.setting();
      let setLbl = "";
      if (setting) {
        setLbl = m("div", { class: "relative receive-chat flex justify-start" },
          m("div", { class: "px-5 mb-2 bg-gray-200 dark:bg-gray-900 dark:text-white text-black py-2 text-sm w-full border rounded-md font-light truncate", title: setting },
            "Setting: " + setting
          )
        );
      }
      
      let ret = [(profile ? m("div", { class: "bg-white dark:bg-black user-info-header px-3 py-1.5" }, flds) : ""), m("div", { id: "messages", class: "h-full w-full overflow-y-auto" }, [
        setLbl,
        msgs
      ])];

      return ret;
    }

    // Phase 12: Prune wrapper functions removed (OI-18) — callers use LLMConnector.* directly

    // Phase 13: Chat toolbar helper — material icon button with optional active/toggle state
    function chatIconBtn(icon, handler, active, title) {
      let cls = "inline-flex items-center justify-center w-10 h-10 rounded hover:bg-gray-200 dark:hover:bg-gray-700 text-gray-500 dark:text-gray-400 transition-colors";
      if (active) cls += " bg-gray-200 dark:bg-gray-700 text-gray-800 dark:text-white";
      return m("button", { class: cls, onclick: handler, title: title || "" },
        m("span", { class: "material-symbols-outlined" }, icon)
      );
    }

    function getChatBottomMenuView() {

      let pendBar = m("div", { class: "flex-1 px-2" },
        m("div", { class: "relative bg-gray-200 dark:bg-gray-700 rounded h-3" },
          m("div", { class: "absolute top-0 h-3 w-full rounded pending-blue" })
        )
      );

      let placeText = "Start typing...";
      if (!inst) {
        placeText = "Select or create a chat to begin...";
      }
      else if (chatCfg.streaming) {
        placeText = "Type to interrupt...";
      }
      else if (chatCfg.pending) {
        placeText = "Waiting ...";
      }
      let msgProps = { type: "text", name: "chatmessage", class: "text-field flex-1 min-w-0", placeholder: placeText, onkeydown: function (e) { if (e.which == 13) doChat(e); } };
      let input = m("input[" + (!inst || (chatCfg.pending && !chatCfg.streaming) ? "disabled='true'" : "") + "]", msgProps);

      // Tag selector row (shown when image button toggled)
      let tagSelectorRow = "";
      if (showTagSelector && window.am7imageTokens) {
          tagSelectorRow = m("div", { class: "px-3 py-1.5 border-b border-gray-300 dark:border-gray-600 flex flex-wrap gap-1 items-center max-h-20 overflow-y-auto" }, [
              window.am7imageTokens.tags.map(function(tag) {
                  let isSelected = selectedImageTags.indexOf(tag) > -1;
                  return m("button", {
                      class: "px-2 py-0.5 rounded-full text-xs " +
                          (isSelected ?
                              "bg-purple-600 text-white" :
                              "bg-gray-600 text-gray-200 hover:bg-gray-500"),
                      onclick: function() { toggleImageTag(tag); }
                  }, tag);
              }),
              selectedImageTags.length > 0 ?
                  m("button", {
                      class: "ml-2 px-3 py-0.5 rounded bg-purple-600 text-white text-xs hover:bg-purple-700",
                      onclick: function() { insertImageToken(); }
                  }, "Insert " + selectedImageTags.join(",") + " pic") : ""
          ]);
      }

      // Toolbar icons — left group (view controls)
      let leftTools = [
        chatIconBtn(fullMode ? "close_fullscreen" : "open_in_new", toggleFullMode, false, fullMode ? "Exit fullscreen" : "Fullscreen"),
        chatIconBtn("cancel", doCancel, false, "Clear/Cancel")
      ];

      // Toolbar icons — center-left group (media & features)
      let featureTools = [
        chatIconBtn(camera ? "photo_camera" : "no_photography", toggleCamera, camera, "Camera"),
        chatIconBtn(showTagSelector ? "image" : "add_photo_alternate", function() { showTagSelector = !showTagSelector; if (!showTagSelector) selectedImageTags = []; }, showTagSelector, "Image tags"),
        chatIconBtn(profile ? "account_circle" : "account_circle_off", toggleProfile, profile, "Profile"),
        chatIconBtn(audio ? "volume_up" : "volume_mute", toggleAudio, audio, "Audio"),
        chatIconBtn("counter_8", sendToMagic8, false, "Magic 8"),
        chatIconBtn("query_stats", chatInto, false, "Analyze"),
        chatIconBtn("visibility" + (hideThoughts ? "" : "_off"), toggleThoughts, !hideThoughts, "Thoughts"),
        chatIconBtn("bug_report", function() { mcpDebug = !mcpDebug; }, mcpDebug, "MCP Debug")
      ];

      // Input area — recording or text input or pending bar
      let inputArea;
      if (page.components.audio.recording()) {
        inputArea = m("div", { class: "flex-1 min-w-0 flex items-center" }, [
          page.components.audio.recordWithVisualizer(true, function (text) { audioText += text; console.log(text); }, function (contentType, b64) { handleAudioSave(contentType, b64); })
        ]);
      } else if (chatCfg.streaming) {
        // During streaming: show input with a thin progress bar above so user can interrupt
        inputArea = m("div", { class: "flex-1 min-w-0 flex flex-col" }, [
          m("div", { class: "relative bg-gray-200 dark:bg-gray-700 rounded h-1 mb-1" },
            m("div", { class: "absolute top-0 h-1 w-full rounded pending-blue" })
          ),
          input
        ]);
      } else if (chatCfg.pending) {
        inputArea = pendBar;
      } else {
        inputArea = input;
      }

      // Send controls — right group
      let sendTools = [
        page.components.audio.recordButton(),
        chatIconBtn("stop", doStop, false, "Stop"),
        chatIconBtn("chat", doChat, false, "Send")
      ];

      return m("div", { class: "border-t border-gray-200 dark:border-gray-700 bg-white dark:bg-black" }, [
        tagSelectorRow,
        // Toolbar row
        m("div", { class: "flex items-center gap-0.5 px-2 py-1" }, [
          // Left tools
          m("div", { class: "flex items-center gap-0.5 flex-shrink-0" }, leftTools),
          m("div", { class: "w-px h-5 bg-gray-300 dark:bg-gray-600 mx-1 flex-shrink-0" }),
          // Feature tools
          m("div", { class: "flex items-center gap-0.5 flex-shrink-0" }, featureTools),
          m("div", { class: "w-px h-5 bg-gray-300 dark:bg-gray-600 mx-1 flex-shrink-0" }),
          // Input area — takes remaining space
          inputArea,
          m("div", { class: "w-px h-5 bg-gray-300 dark:bg-gray-600 mx-1 flex-shrink-0" }),
          // Send controls
          m("div", { class: "flex items-center gap-0.5 flex-shrink-0" }, sendTools)
        ])
      ]);
    }
    
    async function handleAudioSave(mimeType, base64) {
      let name = inst.api.objectId() + " - " + (chatCfg?.history?.messages?.length || 1);
      console.log("Save:", name);
      let cdir = await page.makePath("auth.group", "data", "~/Data/Recordings");
      let sinst = am7model.newInstance("data.data");
      sinst.api.groupPath(cdir.path);
      sinst.api.organizationId(page.user.organizationId);
      sinst.api.contentType(mimeType);
      sinst.api.name(name);
      sinst.entity.dataBytesStore = base64;
    }

    function navKey(e) {
      switch (e.keyCode) {
        /// ESC
        case 27:
          if (fullMode) toggleFullMode();
          break;
      }
    }

    let lastCount = -1;
    function scrollToLast() {
      if (!inst) {
        return;
      }
      let oMsg = document.getElementById("messages");
      if (!oMsg) return;
      let msgs = oMsg.querySelectorAll(":scope > div");
      if (msgs.length > 1 && lastCount != msgs.length) {
        lastCount = msgs.length;
        msgs[lastCount - 1].scrollIntoView();

      }

    }



    // Phase 10b: loadConfigList delegates to ConversationManager
    // Phase 12: Removed fallback path and aSess tracking (OI-52)
    async function loadConfigList() {
      if (window.ConversationManager) {
        await ConversationManager.refresh();
        aPCfg = ConversationManager.getPromptConfigs() || [];
        aCCfg = ConversationManager.getChatConfigs() || [];
        if (aPCfg.length && inst && inst.api.promptConfig() == null) inst.api.promptConfig(aPCfg[0]);
        if (aCCfg.length && inst && inst.api.chatConfig() == null) inst.api.chatConfig(aCCfg[0]);
        if (!inst) ConversationManager.autoSelectFirst();
      } else {
        aPCfg = [];
        aCCfg = [];
      }
    }

    function getChatView(vnode) {

      if (aPCfg == undefined && aCCfg == undefined) {
        loadConfigList().then(() => {
          if (aCCfg && aCCfg.length > 0) {
            doPeek().catch(function(e) {
              console.warn("Failed to peek on load", e);
            });
          }
          m.redraw();
        });
      }


      return m("div", { class: "content-outer" }, [
        (fullMode ? "" : m(page.components.navigation, { hideBreadcrumb: true })),
        m("div", { class: "content-main" },
          m("div", { class: "list-results-container" },
            m("div", { class: "list-results" }, [
              (camera ? page.components.camera.videoView() : ""),
              getChatTopMenuView(),
              getSplitContainerView(),
              getChatBottomMenuView()
            ])
          )
        )
      ]);
    }

    function toggleFullMode() {
      fullMode = !fullMode;
      m.redraw();
    }

    chat.toggleFullMode = toggleFullMode
    chat.cancelView = function () {
      altView = undefined;
      fullMode = false;
      m.redraw();
    };
    chat.editItem = function (object) {
      if (!object) return;
      console.log("Edit", object);
      altView = {
        fullMode,
        view: page.views.object(),
        type: object[am7model.jsonModelKey],
        containerId: object.objectId
      };
      m.redraw();
    };
    chat.addNew = function (type, containerId, parentNew) {
      altView = {
        fullMode,
        view: page.views.object(),
        type,
        containerId,
        parentNew,
        new: true
      };
      m.redraw();
    };

    chat.view = {
      oninit: function (vnode) {
        let ctx = page.user.homeDirectory;
        origin = vnode.attrs.origin || ctx;

        // Phase 13a: Execute pending analysis if AnalysisManager has queued one
        if (window.AnalysisManager && AnalysisManager.getActiveAnalysis()) {
          AnalysisManager.executePending().catch(function(e) {
            console.warn("Failed to execute pending analysis", e);
          });
        }

        // Phase 10b: Wire ConversationManager callbacks
        if (window.ConversationManager) {
          ConversationManager.onSelect(function(session) {
            pickSession(session);
          });
          ConversationManager.onDelete(function() {
            doClear();
          });
        }

        document.documentElement.addEventListener("keydown", navKey);


      },
      oncreate: function (x) {
        page.navigable.setupPendingContextMenus();
      },
      onupdate: function (x) {
        page.navigable.setupPendingContextMenus();
        scrollToLast();

        // Audio components (SimpleAudioPlayer and Magic8Ball) manage themselves
        // No need to configure old audio system
      },
      onremove: function (x) {
        page.navigable.cleanupContextMenus();
        pagination.stop();
        document.documentElement.removeEventListener("keydown", navKey);

        // Stop all audio sources (both old and new systems)
        if (page.components.audio) {
          page.components.audio.stopBinauralSweep();
          page.components.audio.stopAudioSources();
          page.components.audio.stopAllAudioSourceControllers();
        }

        page.components.camera.stopCapture();
      },

      view: function (vnode) {
        let v = getChatView(vnode);
        return [v, page.components.dialog.loadDialog(), page.loadToast()];
      }
    };
    return chat;
  }
  page.views.chat = newChatControl;
}());
