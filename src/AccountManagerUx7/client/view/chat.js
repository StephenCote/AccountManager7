
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
    
    let audio = false;
    let audioMagic8 = false;
  
    let hideThoughts = true;
    let editIndex = -1;
    let editMode = false;

    let profile = false;

    function doClear() {
      
      clearEditMode();
      page.components.audio.unconfigureAudio(audio);
      page.components.audio.clearMagic8(audioMagic8);
      chatCfg = newChatConfig();
    }

    function pickSession(obj) {

      doClear();
      inst = am7model.prepareInstance(obj);
      window.dbgInst = inst;
      doPeek();
    }

    async function chatInto() {
      page.components.dialog.chatInto(undefined, inst, aCCfg)
    }

    async function doCancel() {
      clearEditMode();
      chatCfg.pending = false;
      chatCfg.peek = false;
      chatCfg.history = [];
      let chatReq = {
        schema: inst.model.name,
        objectId: inst.api.objectId(),
        uid: page.uid()
      };

      return m.request({ method: 'POST', url: g_application_path + "/rest/chat/clear", withCredentials: true, body: chatReq }).then((r) => {
        m.redraw();
      });
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
        if (msg && msg.length && (audio || audioMagic8)) {
          // Generate user audio prior to submitting the chat request;
          let name = inst.api.objectId() + " - " + (chatCfg.history.messages.length - 1);
          if (!page.components.audio.hasAudioMap(name)) {
            console.info("Priming comment audio ... " + name);
            let profileId = chatCfg?.user?.profile?.objectId;
            let vprops = { "text": msg, "speed": 1.2, voiceProfileId: profileId };
            if (!vprops.voiceProfileId) {
              vprops.engine = "piper";
              vprops.speaker = "en_GB-alba-medium";
            }
            console.log("Priming", name);
            m.request({ method: 'POST', url: g_application_path + "/rest/voice/" + name, withCredentials: true, body: vprops }).then((r) => {
              console.info("Comment audio primed");
              page.components.audio.clearMagic8();
            });
          }
        }
        chatCfg.pending = true;
        let data = page.components.dnd.workingSet.map(r => { return JSON.stringify({ schema: r.schema, objectId: r.objectId }); });

        let chatReq = {
          schema: inst.model.name,
          objectId: inst.api.objectId(),
          uid: page.uid(),
          message: msg,
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
              page.components.audio.clearMagic8();
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
      page.components.dialog.confirm("Delete chat " + s + "?", async function () {
        if (s.session) {
          let q = am7client.newQuery(s.sessionType);
          q.field("id", s.session.id);
          try {
            let qr = await page.search(q);
            if (qr && qr.results && qr.results.length > 0) {
              page.toast("info", "Deleting session data - " + qr.results[0].objectId);
              await page.deleteObject(s.sessionType, qr.results[0].objectId);
            }
            else {
              page.toast("error", "Failed to find session data to delete", 5000);
              console.error("Failed to delete session data", qr);
            }
          }
          catch (e) {
            page.toast("error", "Error deleting session data", 5000);
            console.error("Error deleting session data", e);
          }

        }
        else {
          console.warn("No session data found");
        }
        // console.log("Deleting request", s.objectId);
        await page.deleteObject(s[am7model.jsonModelKey], s.objectId);

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
        /*
        chat: undefined,
        prompt: undefined,
        system: undefined,
        user: undefined,
        */
        peek: false,
        history: undefined,
        pending: false
      };
    }


    function doPeek() {
      //doClear();
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
              m.request({ method: 'GET', url: g_application_path + "/rest/chat/config/chat/" + c2.name, withCredentials: true }).then((c2) => {
                chatCfg.chat = c2;
                chatCfg.system = c2.systemCharacter;
                chatCfg.user = c2.userCharacter;
                getHistory().then((h) => {
                  chatCfg.peek = true;
                  chatCfg.history = h;
                  m.redraw();
                  res();
                }).catch((e) => {
                  console.warn("Error in chat history", e);
                  chatCfg.peek = false;
                  // rej(e);
                });
              });
            });
        });
      }
      else {
        console.warn("No prompt or chat config");

      }
      return p;
    }

    function getChatTopMenuView() {

      return [];
    }

    function getSplitContainerView() {
      let splitLeft = "";
      if (!fullMode) splitLeft = getSplitLeftContainerView();
      return m("div", { class: "results-fixed", onselectstart: function (e) { e.preventDefault(); } },
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


    function openChatSettings() {
      page.components.dialog.chatSettings(async function (e) {

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
        await loadConfigList();
        doPeek();
      });
    }

    function toggleAudio() {
      
      if(audio){
        audio = false;
        if(!audioMagic8){
          page.components.audio.unconfigureAudio();
        }
        audioMagic8 = true;
      }
      else if(audioMagic8){
        page.components.audio.unconfigureAudio();
        audio = false;
        audioMagic8 = false;
      }
      else{
        if(!audio){
          page.components.audio.unconfigureAudio();
        }
        audio = true;
        audioMagic8 = false;
      }
    }

    function toggleProfile() {
      profile = !profile;
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
      if (audioMagic8) {
        return page.components.audio.getMagic8View(chatCfg, profile);
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
      let msgs = (chatCfg?.history?.messages || []).map((msg) => {
        midx++;
        let align = "justify-start";
        let txt = "bg-gray-600 text-white";
        // console.log(msg);
        if (msg.role == "user") {
          align = "justify-end";
          txt = "bg-gray-200 text-black";
        }
        let cnt = msg.content || "";
        let ectl = "";
        let ecls = "";
        let bectl = false;
        let lastMsg = (midx == (chatCfg.history.messages.length - 1));
        if (hideThoughts && !editMode) {
          cnt = pruneTag(cnt, "citation");
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
          cnt = pruneToMark(cnt, "(Reminder");
          cnt = pruneToMark(cnt, "(KeyFrame");
        }
        if (!editMode && cnt.trim().length == 0) {
          return "";
        }

        if (typeof cnt == "string") {
          //cnt = cnt.replace(/\r/,"").split("\n").map((l)=>{return m("p", l)});
          cnt = m.trust(marked.parse(cnt = page.components.emoji.markdownEmojis(cnt.replace(/\r/, ""))));
        }
        let aud = "";
        //if(lastMsg && chatCfg.chat && audio && msg.role == "assistant"){

        if (chatCfg.chat && audio && !chatCfg.streaming) {
          //let name = chatCfg.chat.objectId + " - " + midx;
          let name = inst.api.objectId() + " - " + midx;
          let profileId;
          if (msg.role == "assistant") {
            profileId = chatCfg?.system?.profile?.objectId;
          }
          else {
            profileId = chatCfg?.user?.profile?.objectId;
          }
          aud = page.components.audio.createAudioVisualizer(name, aidx, profileId, lastMsg, pruneAll(msg.content));
          if (!aud || typeof aud == "string") {
            aud = "";
          }
          else {
            aidx++;
          }

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

      let ret = [(profile ? m("div", { class: "bg-white user-info-header px-5 py-3" }, flds) : ""), m("div", { id: "messages", class: "h-full w-full overflow-y-auto" }, [

        msgs
      ])];

      return ret;
    }

    function pruneAll(cnt) {
      cnt = pruneToMark(cnt, "<|reserved_special_token");
      cnt = pruneTag(cnt, "think");
      cnt = pruneTag(cnt, "thought");
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

      return m("div", { class: "result-nav-outer" },
        m("div", { class: "results-fixed" },
          m("div", { class: "splitcontainer" }, [
            m("div", { class: "splitleftcontainer" },
              m("button", { class: "flyout-button", onclick: openChatSettings },
                [m("span", { class: "material-symbols-outlined material-icons-24" }, "add"), "New Chat"]
              )
            ),
            m("div", { class: "splitrightcontainer result-nav-inner" },

              m("div", { class: "tab-container result-nav w-full" }, [
                m("button", { class: "button", onclick: toggleFullMode }, m("span", { class: "material-symbols-outlined material-icons-24" }, (fullMode ? "close_fullscreen" : "open_in_new"))),
                m("button", { class: "button", onclick: doCancel }, m("span", { class: "material-symbols-outlined material-icons-24" }, "cancel")),
                m("button", { class: "button", onclick: toggleProfile }, m("span", { class: "material-symbols-outlined material-icons-24" }, (profile ? "account_circle" : "account_circle_off"))),
                m("button", { class: "button", onclick: toggleAudio }, m("span", { class: "material-symbols-outlined material-icons-24" }, (audio ? "volume_up" : (audioMagic8 ? "counter_8" : "volume_mute")))),
                m("button", { class: "button", onclick: chatInto }, m("span", { class: "material-symbols-outlined material-icons-24" }, "query_stats")),
                m("button", { class: "button", onclick: toggleThoughts }, m("span", { class: "material-symbols-outlined material-icons-24" }, "visibility" + (hideThoughts ? "" : "_off"))),
                page.components.audio.recordButton(),
                (page.components.audio.recording() ? page.components.audio.recordWithVisualizer(true, function (text) { audioText += text; console.log(text); }, function (contentType, b64) { handleAudioSave(contentType, b64); }) : (chatCfg.pending ? pendBar : input)),
                m("button", { class: "button", onclick: doStop }, m("span", { class: "material-symbols-outlined material-icons-24" }, "stop")),
                m("button", { class: "button", onclick: doChat }, m("span", { class: "material-symbols-outlined material-icons-24" }, "chat"))
              ])
            )
          ]),
        )
      );
      /*
      return m("div", {class: "result-nav-outer"}, 
        m("div", {class: "result-nav-inner"}, [
          m("div", {class: "result-nav"}, "..."),
          m("div", {class: "result-nav"}, "Bottom")
        ])
      );
      */
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

      // }
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
      am7client.clearCache(undefined, true);
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
          window.dbgInst = inst;
          bPopSet = true;
          delete window.remoteEntity;
        }

        let cfg = page.context().contextObjects["chatConfig"];
        if (cfg) {
          // && !inst.api.chatConfig() && !inst.api.promptConfig()
          console.warn("TODO: Refactor sending in chat config ref");
          /*
          console.log(cfg["olio.llm.chatConfig"].name);
          inst.api.chatConfig(chatCfg.chatConfig);
          inst.api.promptConfig(chatCfg.promptConfig);
          */
        }
        document.documentElement.addEventListener("keydown", navKey);

        if (bPopSet) {
          openChatSettings();

        }


      },
      oncreate: function (x) {
        page.navigable.setupPendingContextMenus();
      },
      onupdate: function (x) {
        page.navigable.setupPendingContextMenus();
        scrollToLast();
        page.components.audio.configureAudio(audio);
        page.components.audio.configureMagic8(inst, chatCfg, audioMagic8, pruneAll);
      },
      onremove: function (x) {
        page.navigable.cleanupContextMenus();
        pagination.stop();
        document.documentElement.removeEventListener("keydown", navKey);
        page.components.audio.unconfigureAudio(false);
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
