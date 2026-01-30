
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

    // Image token state
    let resolvingImages = {};
    let showTagSelector = false;
    let selectedImageTags = [];

    // Patch a chat message to replace tag-only tokens with id+tag tokens (chat.js version)
    async function patchChatImageToken(msgIndex, newContent) {
        // Always update local state first so the UI reflects the change immediately
        // This prevents re-generation on redraw even if server patch fails
        if (chatCfg.history?.messages?.[msgIndex]) {
            chatCfg.history.messages[msgIndex].content = newContent;
        }

        // Then try to persist to server
        if (!inst) return;
        try {
            let sessionType = inst.api.sessionType();
            let session = inst.api.session();
            if (!sessionType || !session || !session.id) {
                console.warn("patchChatImageToken: No session available for server patch");
                return;
            }
            let q = am7view.viewQuery(am7model.newInstance(sessionType));
            q.field("id", session.id);
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length > 0) {
                let req = qr.results[0];
                if (req.messages && req.messages[msgIndex]) {
                    req.messages[msgIndex].content = newContent;
                    await page.patchObject(req);
                }
            }
        } catch (e) {
            console.error("patchChatImageToken server error (local state updated):", e);
        }
    }

    // Resolve all image tokens in a message (chat.js version)
    async function resolveChatImages(msgIndex) {
        let msgs = chatCfg.history?.messages;
        if (!msgs || !msgs[msgIndex]) return;
        if (!window.am7imageTokens) return;
        let msg = msgs[msgIndex];
        if (!msg.content) return;

        let tokens = window.am7imageTokens.parse(msg.content);
        if (!tokens.length) return;

        let character = msg.role === "user" ? chatCfg.user : chatCfg.system;
        if (!character) return;

        let newContent = msg.content;
        let changed = false;

        for (let token of tokens) {
            if (token.id && window.am7imageTokens.cache[token.id]) continue;

            let resolved = await window.am7imageTokens.resolve(token, character);
            if (resolved && resolved.image) {
                if (!token.id) {
                    let newToken = "${image." + resolved.image.objectId + "." + token.tags.join(",") + "}";
                    newContent = newContent.replace(token.match, newToken);
                    changed = true;
                }
            }
        }

        if (changed) {
            await patchChatImageToken(msgIndex, newContent);
        }
        m.redraw();
    }

    // Process content string: replace image tokens with HTML for resolved images or placeholders
    function processImageTokensInContent(content, msgRole, msgIndex) {
        if (!content || !window.am7imageTokens) return content;
        let tokens = window.am7imageTokens.parse(content);
        if (!tokens.length) return content;

        // Trigger resolution for unresolved/uncached tokens
        if (!resolvingImages[msgIndex]) {
            let needsResolution = tokens.some(t => !t.id || !window.am7imageTokens.cache[t.id]);
            if (needsResolution) {
                resolvingImages[msgIndex] = true;
                resolveChatImages(msgIndex).then(function() {
                    resolvingImages[msgIndex] = false;
                });
            }
        }

        // Replace tokens with HTML
        let result = content;
        for (let i = tokens.length - 1; i >= 0; i--) {
            let token = tokens[i];
            let html;
            if (token.id && window.am7imageTokens.cache[token.id]) {
                let cached = window.am7imageTokens.cache[token.id];
                html = '<img src="' + cached.url + '" class="max-w-[256px] rounded-lg my-2 cursor-pointer" onclick="page.imageView(window.am7imageTokens.cache[\'' + token.id + '\'].image)" />';
            } else {
                html = '<span class="inline-flex items-center gap-1 px-2 py-1 rounded bg-gray-500/30 text-xs my-1"><span class="material-symbols-outlined text-xs">photo_camera</span>' + token.tags.join(", ") + '...</span>';
            }
            result = result.substring(0, token.start) + html + result.substring(token.end);
        }
        return result;
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

    function doClear() {
      clearEditMode();
      inst = undefined;
      chatCfg = newChatConfig();
    }

    function pickSession(obj) {
      doClear();
      inst = am7model.prepareInstance(obj);
      window.dbgInst = inst;
      doPeek();
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
        aSess = undefined;
        await loadConfigList();
        doClear();
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
        return;
      }

      let chatReq = {
        schema: inst.model.name,
        objectId: inst.api.objectId(),
        uid: page.uid()
      };
      return m.request({ method: 'POST', url: g_application_path + "/rest/chat/history", withCredentials: true, body: chatReq });
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
      if (c1 && c2) {
        /// Looking up the prompt and chat config to get the full objects
        ///
        chatCfg.peek = true;
        p = new Promise((res, rej) => {
          m.request({ method: 'GET', url: g_application_path + "/rest/chat/config/prompt/" + c1.name, withCredentials: true })
            .then((c) => {
              chatCfg.prompt = c;

              m.request({ method: 'GET', url: g_application_path + "/rest/chat/config/chat/" + c2.name, withCredentials: true }).then((c3) => {
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
                });
              }).catch((e) => {
                  console.warn("Error in chat config", e);
                  chatCfg.peek = false;
                });;
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

    function getSplitLeftContainerView() {
      /*
      inst.formField("prompt").hide = sess;
      inst.formField("chat").hide = sess;
      inst.formField("session").hide = sess;
      */
      let vsess = aSess || [];

      return m("div", {
        class: "splitleftcontainer",
        ondragover: function (e) {
          e.preventDefault();
        }
      }, [
        // am7view.form(inst),
        //         m("button", { class: "flyout-button", onclick: openChatSettings }, [m("span", { class: "material-symbols-outlined material-icons-24" }, "add"), "New Chat"]),
        vsess.map((s, i) => {
          let bNew = s.objectId == undefined;
          let bDel = "";
          if (bNew) {
            bDel = m("button", { class: "menu-button content-end mr-2", onclick: openChatSettings }, m("span", { class: "material-symbols-outlined material-icons-24" }, "add"));
            //m("button", {class: "menu-button material-symbols-outlined material-icons-24 mr-2"},"add");
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
      sessionStorage.setItem('magic8Context', JSON.stringify(magic8Context));
      m.route.set('/magic8', { context: 'chat' });
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
        if (hideThoughts && !editMode) {
          cnt = pruneOut(cnt, "--- CITATION", "END CITATIONS ---")
          // cnt = pruneTag(cnt, "citation");
        }
        if (msg.role == "assistant") {
          bectl = (editMode && editIndex == midx);

          /// Only edit last message
          if (midx == chatCfg.history.messages.length - 1) {
            ectl = m("span", { onclick: function () { toggleEditMode(midx); }, class: "material-icons-outlined text-slate-" + (bectl ? 200 : 700) }, "edit");
          }
          if (hideThoughts && !editMode) {
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
        if (!bectl && hideThoughts) {
          cnt = pruneToMark(cnt, "(Metrics");
          cnt = pruneToMark(cnt, "(Reminder");
          cnt = pruneToMark(cnt, "(KeyFrame");
        }
        if (!editMode && cnt.trim().length == 0) {
          return "";
        }

        if (typeof cnt == "string") {
          // Process image tokens before markdown parsing
          cnt = processImageTokensInContent(cnt, msg.role, midx);
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

    function pruneAll(cnt) {
      cnt = pruneToMark(cnt, "<|reserved_special_token");
      cnt = pruneTag(cnt, "think");
      cnt = pruneTag(cnt, "thought");
      cnt = pruneToMark(cnt, "(Metrics");
      cnt = pruneToMark(cnt, "(Reminder");
      cnt = pruneToMark(cnt, "(KeyFrame");
      cnt = pruneOther(cnt);
      return cnt;
    }

    function pruneOther(cnt) {
      cnt = cnt.replace(/\[interrupted\]/g, "");
      return cnt;
    }

    function pruneToMark(cnt, mark) {
      let idx = cnt.indexOf(mark);
      if (idx > -1) {
        cnt = cnt.substring(0, idx);
      }
      return cnt;
    }

    function pruneOut(cnt, start, end){
      let idx1 = cnt.indexOf(start);
      let idx2 = cnt.indexOf(end);
      if(idx1 > -1 && idx2 > -1 && idx2 > idx1){
        cnt = cnt.substring(0, idx1) + cnt.substring(idx2 + end.length, cnt.length);  
      }
      return cnt;
    }

    function pruneTag(cnt, tag) {
      let tdx1 = cnt.toLowerCase().indexOf("<" + tag + ">");
      let maxCheck = 20;
      let check = 0;
      while (tdx1 > -1) {
        if (check++ >= maxCheck) {
          console.error("Break on loop!");
          break;
        }

        let tdx2 = cnt.toLowerCase().indexOf("</" + tag + ">");
        if (tdx1 > -1 && tdx2 > -1 && tdx2 > tdx1) {
          cnt = cnt.substring(0, tdx1) + cnt.substring(tdx2 + tag.length + 3, cnt.length);
        }
        tdx1 = cnt.toLowerCase().indexOf("<" + tag + ">");
      }
      return cnt;
    }

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



    async function loadConfigList() {
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

    function getChatView(vnode) {

      if (aPCfg == undefined && aCCfg == undefined) {
        loadConfigList().then(() => {
          if (aCCfg.length > 0) {
            doPeek();
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
