
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
  /// TODO: There are two different methods for constructing the object view: 1) the newer generic am7view, and 2) the legacy though still more complex object component view
  /// So some form fields are not interoperable, like 'list' for am7view must be 'select' for the object component view.
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
    let aSess;
    let audioText = "";
    let chatCfg = newChatConfig();

    let camera = false;
    let audio = false;

    let hideThoughts = true;
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
                        let h = await getHistory();
                        if (h) {
                            chatCfg.history = h;
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
      doPeek().catch(function(e) {
        console.warn("Failed to peek session", e);
      });
    }

    async function chatInto() {
      page.components.dialog.chatInto(inst.entity, inst, aCCfg)
    }

    async function doCancel() {
      clearEditMode();
      chatCfg.pending = false;
      chatCfg.peek = false;
      chatCfg.history = [];
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
          if (page.chatStream && !page.chatStream.streamId == id) {
            console.warn("Mismatched stream identifiers");
          }
          clearChat();
          m.redraw();
        },
        onchaterror: (id, msg) => {
          page.toast("error", "Chat error: " + msg, 5000);
          clearChat();
          m.redraw();
        },
        onchatstart: (id, req) => {
          // console.log("Start chat...");
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
          if (!chatCfg.history.messages.length) {
            console.error("Unexpected chat history");
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
      if (chatCfg.pending) {
        console.warn("Chat is pending");
        return;
      }
      if (chatCfg.streaming) {
        console.warn("Chat is streaming - TODO - interrupt");
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

    async function deleteChat(s) {
      am7chat.deleteChat(s, false, async function () {
        doClear();
        aSess = undefined;
        await loadConfigList();
        m.redraw();
      });
    }

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
        peek: false,
        history: undefined,
        pending: false
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
    function getSplitLeftContainerView() {
      if (window.ConversationManager) {
        let children = [m(ConversationManager.SidebarView, { onNew: openChatSettings })];
        if (window.ContextPanel) {
          children.push(m(ContextPanel.PanelView));
        }
        return m("div", { class: "splitleftcontainer flex flex-col" }, children);
      }
      // Fallback: inline session list (pre-10b compat)
      let vsess = aSess || [];
      return m("div", {
        class: "splitleftcontainer",
        ondragover: function (e) {
          e.preventDefault();
        }
      }, [
        vsess.map((s, i) => {
          let bNew = s.objectId == undefined;
          let bDel = "";
          if (bNew) {
            bDel = m("button", { class: "menu-button content-end mr-2", onclick: openChatSettings }, m("span", { class: "material-symbols-outlined material-icons-24" }, "add"));
          }
          else {
            bDel = m("button", { class: "menu-button content-end mr-2", onclick: function (e) { e.preventDefault(); deleteChat(s); return false; } }, m("span", { class: "material-symbols-outlined material-icons-24" }, "delete_outline"));
          }
          return m("button", {
            class: "flyout-button" + (inst && s.name == inst.api.name() ? " active" : ""), onclick: function () {
              pickSession(s);
            }
          }, [bDel, s.name]);
        })
      ]);
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
        aSess = undefined;
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
        let lastMsg = (midx == (chatCfg.history.messages.length - 1));
        /// Prefer server-side displayContent when available and hideThoughts is active
        let useServerDisplay = hideThoughts && !editMode && msg.displayContent;
        if (useServerDisplay) {
          cnt = msg.displayContent;
        }
        if (!useServerDisplay && hideThoughts && !editMode) {
          cnt = pruneOut(cnt, "--- CITATION", "END CITATIONS ---")
          // cnt = pruneTag(cnt, "citation");
        }
        if (msg.role == "assistant") {
          bectl = (editMode && editIndex == midx);

          /// Only edit last message
          if (midx == chatCfg.history.messages.length - 1) {
            ectl = m("span", { onclick: function () { toggleEditMode(midx); }, class: "material-icons-outlined text-slate-" + (bectl ? 200 : 700) }, "edit");
          }
          if (!useServerDisplay && hideThoughts && !editMode) {
            cnt = pruneToMark(cnt, "<|reserved_special_token");
            cnt = pruneTag(cnt, "think");
            cnt = pruneTag(cnt, "thought");
            cnt = pruneOther(cnt);
          }

          if (bectl) {
            ecls = "w-full ";
            cnt = m("textarea", { id: "editMessage", class: "text-field textarea-field-full" }, cnt);
          }

        }
        if (!useServerDisplay && !bectl && hideThoughts) {
          cnt = pruneToMark(cnt, "(Metrics");
          cnt = pruneToMark(cnt, "(Reminder");
          cnt = pruneToMark(cnt, "(KeyFrame");
        }
        if (!editMode && cnt.trim().length == 0) {
          return "";
        }

        if (typeof cnt == "string") {
          // Process image and audio tokens before markdown parsing
          cnt = processImageTokensInContent(cnt, msg.role, midx);
          cnt = processAudioTokensInContent(cnt, msg.role, midx);
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
          let contentForHash = pruneAll(msg.content);
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
          [ectl, m("div", { class: ecls + "px-5 mb-2 " + txt + " py-2 text-base max-w-[80%] border rounded-md font-light" },
            (audio ? aud : m("p", cnt))
            //,aud
          )]
        );
      });
      let flds = [m("div", { class: "flex justify-between" }, [
        m("div", { class: "flex items-center" }, [
          c1i,
          m("span", { class: "text-gray-400 text-base pl-4" }, c1l)
        ]),
        m("div", { class: "flex items-center" }, [

          m("span", { class: "text-gray-400 text-base pl-4" }, c2l),
          c2i
        ])
      ])
      ];
      let setting = inst.api.setting();
      let setLbl = "";
      if (setting) {
        setLbl = m("div", { class: "relative receive-chat flex justify-start"},
          setLbl = m("div", { class:  "px-5 mb-2 bg-gray-200 dark:bg-gray-900 dark:text-white text-black py-2 text-base w-full border rounded-md font-light" },
             m("p", "Setting: " + setting)

          )
        );
      }
      
      let ret = [(profile ? m("div", { class: "bg-white dark:bg-black user-info-header px-5 py-3" }, flds) : ""), m("div", { id: "messages", class: "h-full w-full overflow-y-auto" }, [
        setLbl,
        msgs
      ])];

      return ret;
    }

    // Phase 10: Prune functions delegate to LLMConnector shared implementations
    function pruneAll(cnt) { return LLMConnector.pruneAll(cnt); }
    function pruneOther(cnt) { return LLMConnector.pruneOther(cnt); }
    function pruneToMark(cnt, mark) { return LLMConnector.pruneToMark(cnt, mark); }
    function pruneOut(cnt, start, end) { return LLMConnector.pruneOut(cnt, start, end); }
    function pruneTag(cnt, tag) { return LLMConnector.pruneTag(cnt, tag); }

    function getChatBottomMenuView() {
      // if(!showFooter) return "";

      let pendBar = m("div", { class: "w-[80%] p-4 flex flex-col" },
        m("div", { class: "relative bg-gray-200 rounded" },
          m("div", { class: "absolute top-0 h-4 w-full rounded pending-blue" })
        )
      );

      let placeText = "Start typing...";
      if (!inst) {
        placeText = "Select or create a chat to begin...";
      }
      else if (chatCfg.pending) {
        placeText = "Waiting ...";
      }
      let msgProps = { type: "text", name: "chatmessage", class: "text-field w-[80%]", placeholder: placeText, onkeydown: function (e) { if (e.which == 13) doChat(e); } };
      let input = m("input[" + (!inst || chatCfg.pending ? "disabled='true'" : "") + "]", msgProps);

      // Tag selector row (shown when image button toggled)
      let tagSelectorRow = "";
      if (showTagSelector && window.am7imageTokens) {
          tagSelectorRow = m("div", { class: "px-3 py-2 border-b border-gray-600 flex flex-wrap gap-1 items-center max-h-24 overflow-y-auto" }, [
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

      return m("div", { class: "result-nav-outer" },
        m("div", { class: "results-fixed" }, [
          tagSelectorRow,
          m("div", { class: "splitcontainer" }, [
            m("div", { class: "splitleftcontainer" },
              m("button", { class: "flyout-button", onclick: openChatSettings },
                [m("span", { class: "material-symbols-outlined material-icons-24" }, "add"), "New Chat"]
              )
            ),
            m("div", { class: "splitrightcontainer result-nav-inner" },

              m("div", { class: "tab-container result-nav w-full" }, [
                page.iconButton("button",  (fullMode ? "close_fullscreen" : "open_in_new"), "", toggleFullMode),
                page.iconButton("button",  "cancel", "", doCancel),
                // (camera ? " animate-pulse" : "")
                page.iconButton("button",  (camera ? "photo_camera" : "no_photography"), "", toggleCamera),
                page.iconButton("button",  (showTagSelector ? "image" : "add_photo_alternate"), "", function() { showTagSelector = !showTagSelector; if (!showTagSelector) selectedImageTags = []; }),
                page.iconButton("button",  (profile ? "account_circle" : "account_circle_off"), "", toggleProfile),
                page.iconButton("button",  (audio ? "volume_up" : "volume_mute"), "", toggleAudio),
                page.iconButton("button",  "counter_8", "", sendToMagic8),
                page.iconButton("button",  "query_stats", "", chatInto),
                page.iconButton("button",  "visibility" + (hideThoughts ? "" : "_off"), "", toggleThoughts),
                page.components.audio.recordButton(),
                (page.components.audio.recording() ? page.components.audio.recordWithVisualizer(true, function (text) { audioText += text; console.log(text); }, function (contentType, b64) { handleAudioSave(contentType, b64); }) : (chatCfg.pending ? pendBar : input)),
                page.iconButton("button",  "stop", "", doStop),
                page.iconButton("button",  "chat", "", doChat)
              ])
            )
          ]),
        ])
      );
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
    async function loadConfigList() {
      if (window.ConversationManager) {
        await ConversationManager.refresh();
        aPCfg = ConversationManager.getPromptConfigs() || [];
        aCCfg = ConversationManager.getChatConfigs() || [];
        aSess = ConversationManager.getSessions() || [];
        if (aPCfg.length && inst && inst.api.promptConfig() == null) inst.api.promptConfig(aPCfg[0]);
        if (aCCfg.length && inst && inst.api.chatConfig() == null) inst.api.chatConfig(aCCfg[0]);
        if (!inst) ConversationManager.autoSelectFirst();
      } else {
        // Fallback: direct load (pre-10b compat)
        await am7client.clearCache(undefined, true);
        let dir = await page.findObject("auth.group", "DATA", "~/Chat");
        if (aPCfg == undefined) {
          aPCfg = await am7client.list("olio.llm.promptConfig", dir.objectId, null, 0, 0);
          if (aPCfg && aPCfg.length && inst && inst.api.promptConfig() == null) inst.api.promptConfig(aPCfg[0]);
        }
        if (aCCfg == undefined) {
          aCCfg = await am7client.list("olio.llm.chatConfig", dir.objectId, null, 0, 0);
          if (aCCfg && aCCfg.length && inst && inst.api.chatConfig() == null) inst.api.chatConfig(aCCfg[0]);
        }
        let dir2 = await page.findObject("auth.group", "DATA", "~/ChatRequests");
        if (aSess == undefined) {
          aSess = await am7client.list("olio.llm.chatRequest", dir2.objectId, null, 0, 0);
          if (aSess && aSess.length && !inst) pickSession(aSess[0]);
        }
      }
    }

    function getChatView(vnode) {

      if (aPCfg == undefined && aCCfg == undefined) {
        loadConfigList().then(() => {
          if (aCCfg.length > 0) {
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
        let bPopSet = false;
        origin = vnode.attrs.origin || ctx;

        if (window.remoteEntity) {
          inst = am7model.prepareInstance(remoteEntity, am7model.forms.chatSettings);
          bPopSet = true;
          delete window.remoteEntity;
        }

        // Phase 10b: Wire ConversationManager callbacks
        if (window.ConversationManager) {
          ConversationManager.onSelect(function(session) {
            pickSession(session);
          });
          ConversationManager.onDelete(function() {
            doClear();
            aSess = undefined;
          });
        }

        document.documentElement.addEventListener("keydown", navKey);

        if (bPopSet) {
          openChatSettings(inst);
        }


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
