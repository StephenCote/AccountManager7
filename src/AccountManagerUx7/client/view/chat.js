(function () {
  /*
  am7model.getModel("olio.llm.chatRequest").fields.push({
      name: "sesssions",
      label: 'Sessions',
      virtual: true,
      ephemeral: true,
      type: "string",
      baseType: "string",
      format: "list"
  });
  */
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

    let chatCfg = newChatConfig();


    function doClear(){
      clearEditMode();
      chatCfg = newChatConfig();
    }
    

    // window.dbgCfg = chatCfg;
    let inst;
    // = am7model.newInstance("olio.llm.chatRequest", am7model.forms.chatRequest);
    
    function pickSession(obj){
      doClear();
      inst = am7model.prepareInstance(obj);
      window.dbgInst = inst;
      doPeek();
    }

    function setSession(obj){

    }

    async function chatInto(){
      page.components.dialog.chatInto(undefined, undefined, aCCfg)
    }

    async function doCancel() {
      clearEditMode();
      chatCfg.pending = false;
      chatCfg.history = [];
      return m.request({ method: 'POST', url: g_application_path + "/rest/chat/clear", withCredentials: true, body: chatReq }).then((r) => {
        m.redraw();
      });
    }

    function doChat() {
      clearEditMode();
      if (chatCfg.pending) {
        console.warn("Chat is pending");
        return;
      }
      let msg = document.querySelector("[name='chatmessage']").value; ///e?.target?.value;
      if (!chatCfg.peek) {
        doPeek().then(() => {
          doChat();
        })
      }
      else {
        pushHistory();
        chatCfg.pending = true;
        let data = page.components.dnd.workingSet.map(r => {return JSON.stringify({schema:r.schema, objectId:r.objectId});});

        let chatReq = {
          schema: inst.model.name,
          objectId: inst.api.objectId(),
          // chatConfig: {inst.api.chatConfig()?.objectId},
          // promptConfig: {inst.api.promptConfig()?.objectId},
          uid: page.uid(),
          message: msg,
          data
        };

        m.request({ method: 'POST', url: g_application_path + "/rest/chat/text", withCredentials: true, body: chatReq }).then((r) => {
          if (!chatCfg.history) chatCfg.history = {};
          chatCfg.history.messages = r?.messages || [];
          chatCfg.pending = false;
        });
      }

    }
    
    async function deleteChat(s){
      page.components.dialog.confirm("Delete chat " + s + "?", async function(){
        if(s.session){
          let q = am7client.newQuery(s.sessionType);
          q.field("id", s.session.id);    
          let qr = await page.search(q);
          if(qr && qr.results.length > 0){
            // console.log("Deleting session data", qr.results[0].objectId);
            await page.deleteObject(s.sessionType, qr.results[0].objectId);
          }
          else{
            console.error("Failed to find session data to delete", qr);
          }
        }
        // console.log("Deleting request", s.objectId);
        await page.deleteObject(s[am7model.jsonModelKey], s.objectId);
        
        aSess = undefined;
        await loadConfigList();
        doClear();
        m.redraw();

      });
    }

    function pushHistory() {
      let msg = document.querySelector("[name='chatmessage']").value;
      if (!chatCfg.history) chatCfg.history = {};
      if (!chatCfg.history.messages) chatCfg.history.messages = [];
      chatCfg.history.messages.push({ role: "user", content: msg });
      document.querySelector("[name='chatmessage']").value = "";
      m.redraw();
    }

    function getHistory() {
      // console.log(chatCfg);
      if(!inst){
        return;
      }

      let chatReq = {
        schema: inst.model.name,
        objectId: inst.api.objectId(),
        uid: page.uid()
      };
      return m.request({ method: 'POST', url: g_application_path + "/rest/chat/history", withCredentials: true, body: chatReq });
    }

    function newChatConfig(){
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
      if(chatCfg.peek || !inst){
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
                });
              });
            });
        });
      }
      else{
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
          if(bNew){
            bDel = m("button", {class: "menu-button content-end mr-2", onclick: openChatSettings}, m("span", {class: "material-symbols-outlined material-icons-24"}, "add"));
            //m("button", {class: "menu-button material-symbols-outlined material-icons-24 mr-2"},"add");
          }
          else{
            bDel = m("button", {class: "menu-button content-end mr-2", onclick: function(e){ e.preventDefault(); deleteChat(s); return false;}}, m("span", {class: "material-symbols-outlined material-icons-24"}, "delete_outline"));
          }
          return m("button", { class: "flyout-button" + (inst && s.name == inst.api.name() ? " active": ""), onclick: function () {
            pickSession(s);
          } }, [bDel, s.name]);
        })
           
      ]);
    }


    function openChatSettings(){
      page.components.dialog.chatSettings(async function(e){

        //let obj = await page.createObject(e);
        let chatReq = {
          schema: e[am7model.jsonModelKey],
          name: e.name,
          objectId: e.objectId,
          chatConfig: {objectId: e.chatConfig.objectId},
          promptConfig: {objectId: e.promptConfig.objectId},
          uid: page.uid()
        };

        let obj = await m.request({ method: 'POST', url: g_application_path + "/rest/chat/new", withCredentials: true, body: chatReq });

        inst = am7model.prepareInstance(obj);
        window.dbgInst = inst;
        aSess = undefined;
        await loadConfigList();
        doPeek();
        /*

        inst.api.name(esess);
        inst.api.chatConfig(e.chat);
        inst.api.promptConfig(e.prompt);
        //am7model.getModelField("chatSettings", "sessions").limit = ["New Chat", inst.api.session()].concat(aSess.map((c) => { return c.name; }));
        //pickSession();
        doPeek();
        */
      });
    }

    let hideThoughts = true;
    let editIndex = -1;
    let editMode = false;
    function toggleThoughts() {
      hideThoughts = !hideThoughts;
    }
    function clearEditMode() {
      editMode = false;
      editIndex = -1;
    }
    /// Currently only editing the last assistant message
    /// The reasoning: The message history returned from the chat API is not the entire history, and doesn't include any system prompt
    /// Alternately, this could be a separate API, but that seems unnecessary at the moment
    async function editMessageHistory(idx, cnt) {
      logger.warn("REVISE EDIT");
      /*
      let pg = await page.findObject("auth.group", "data", "~/Chat")
      if (pg) {
        let name = page.user.name + "-" + inst.api.chat() + "-" + inst.api.prompt() + "-" + inst.api.session();
        let hist = await page.searchByName("data.data", pg.id, name);
        let ocnt = chatCfg.history.messages[idx];
        window.dbgCnt = ocnt;
        if (hist) {
          let histx = JSON.parse(Base64.decode(hist.dataBytesStore));
          if (histx && histx.messages.length > 0) {
            let om = histx.messages[histx.messages.length - 1];

            om.content = cnt;
            hist.dataBytesStore = Base64.encode(JSON.stringify(histx));
            let histy = {
              schema: hist[am7model.jsonModelKey],
              id: hist.id,
              compressionType: "none",
              ownerId: hist.ownerId,
              groupId: hist.groupId,
              organizationId: hist.organizationId,
              dataBytesStore: hist.dataBytesStore
            };
            let patch = await page.patchObject(histy);
            await am7client.clearCache();
            await doCancel();
            await doPeek();
            clearEditMode();
          }
          else {
            console.error("Failed to parse history, or history contained no messages");
          }
          window.dbgHist = hist;
        }
        else {
          console.error("Failed to find history " + name);
        }
      }
      else {
        console.error("Failed to find chat group");
      }
      clearEditMode();
      */
    }
    function toggleEditMode(idx) {
      if (editMode) {

        /// Need to put message back in the history
        /// The data object containing the history isn't cited here, so it needs to be retrieved, modified, and updated
        ///
        //clearEditMode();
        editMessageHistory(idx, document.getElementById("editMessage").value);
      }
      else {
        console.log("Edit", idx);
        editMode = true;
        editIndex = idx;
      }
    }

    function getResultsView() {
      if(!inst){
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
        if(hideThoughts && !editMode){
          let tdx1 = cnt.toLowerCase().indexOf("<citation");
          let check = 0;
          let maxCheck = 20;
          while (tdx1 > -1) {
            if (check++ >= maxCheck) {
              console.error("Break on loop!");
              break;
            }

            let tdx2 = cnt.toLowerCase().indexOf("</citation>");
            if (tdx1 > -1 && tdx2 > -1 && tdx2 > tdx1) {
              cnt = cnt.substring(0, tdx1) + cnt.substring(tdx2 + 11, cnt.length);
            }
            tdx1 = cnt.toLowerCase().indexOf("<citation");
          }
        }
        if (msg.role == "assistant") {
          bectl = (editMode && editIndex == midx);
          /// Only edit last message
          if (midx == chatCfg.history.messages.length - 1) {
            ectl = m("span", { onclick: function () { toggleEditMode(midx); }, class: "material-icons-outlined text-slate-" + (bectl ? 200 : 700) }, "edit");
          }
          if (hideThoughts && !editMode) {
            let rdx = cnt.indexOf("<|reserved_special_token");
            if (rdx > -1) {
              cnt = cnt.substring(0, rdx);
            }
            let tdx1 = cnt.toLowerCase().indexOf("<thought>");
            let maxCheck = 20;
            let check = 0;
            while (tdx1 > -1) {
              if (check++ >= maxCheck) {
                console.error("Break on loop!");
                break;
              }

              let tdx2 = cnt.toLowerCase().indexOf("</thought>");
              if (tdx1 > -1 && tdx2 > -1 && tdx2 > tdx1) {
                cnt = cnt.substring(0, tdx1) + cnt.substring(tdx2 + 10, cnt.length);
              }
              tdx1 = cnt.toLowerCase().indexOf("<thought>");
            }



          }

          if (bectl) {
            //cnt = m("input", {id: "editMessage", value:cnt, class: "text-field w-[80%]"});
            ecls = "w-full ";
            cnt = m("textarea", { id: "editMessage", class: "text-field textarea-field-full" }, cnt);
          }

        }
        if (!bectl && hideThoughts) {
          let idx = cnt.indexOf("(Reminder");
          if (idx > -1) {
            cnt = cnt.substring(0, idx);
          }
          idx = cnt.indexOf("(KeyFrame");
          if (idx > -1) {
            cnt = cnt.substring(0, idx);
          }

        }
        if(!editMode && cnt.replace(/^\s*/).replace(/\s*$/).length == 0){
          return "";
        }

        if(typeof cnt == "string"){
          cnt = cnt.replace(/\r/,"").split("\n").map((l)=>{return m("p", l)});
        }

        return m("div", { class: "relative receive-chat flex " + align },
          [ectl, m("div", { class: ecls + "px-5 mb-2 " + txt + " py-2 text-base max-w-[80%] border rounded-md font-light" },
            //m("i", {class: "material-icons text-violet-400 -top-4 absolute"}, "arrow_upward_alt"),
            m("p", cnt)
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
          //m("i", {class: "material-icons text-violet-300"}, "chat")
        ])
      ])
      ];
      //flds.push(...msgs);
      /*
      let ret = m("div", {class: "list-results-container"},
        m("div", {class: "list-results"}, [
          m("div", {class: "result-nav-outer"}, m("div", {class: "bg-white user-info-header px-5 py-3"}, flds)),
          m("div", {id: "messages", class: "list-results-overflow"}, msgs)
        ])
        );
      */

      let ret = m("div", { id: "messages", class: "h-full w-full overflow-y-auto" }, [
        m("div", { class: "bg-white user-info-header px-5 py-3" }, flds),
        msgs
      ]);

      /*
      m("div", {class: "relative receive-chat flex justify-start"},
        m("div", {class: "px-5 mb-2 bg-violet-400 text-white py-2 text-sm max-w-[80%] rounded font-light"},
          m("i", {class: "material-icons text-violet-400 -top-4 absolute"}, "arrow_upward_alt"),
          m("p", "Here is the text")
        )
      ),
      m("div", {class: "relative receive-chat flex justify-end"},
        m("div", {class: "px-5 mb-2 bg-violet-200 text-slate-500 py-2 text-sm max-w-[80%] rounded font-light"},
        m("i", {class: "material-icons text-violet-200 -bottom-2 right-0 absolute"}, "arrow_downward_alt"),
          m("p", "Here is the text")
        )
      ),
      */

      return ret;
    }

    function getChatBottomMenuView() {
      // if(!showFooter) return "";

    let pendBar = m("div", {class: "w-[80%] p-4 flex flex-col"},
        m("div", {class: "relative bg-gray-200 rounded"},
          m("div", {class: "absolute top-0 h-4 w-full rounded pending-blue"})
        )
      );
      let placeText = "Start typing...";
      if(!inst){
        placeText = "Select or create a chat to begin...";
      }
      else if(chatCfg.pending){
        placeText = "Waiting ...";
      }
      let input = m("input[" + (!inst || chatCfg.pending ? "disabled='true'" : "") + "]", { type: "text", name: "chatmessage", class: "text-field w-[80%]", placeholder: placeText, onkeydown: function (e) { if (e.which == 13) doChat(e); } });

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
                m("button", { class: "button", onclick: chatInto }, m("span", { class: "material-symbols-outlined material-icons-24" }, "query_stats")),
                m("button", { class: "button", onclick: toggleThoughts }, m("span", { class: "material-symbols-outlined material-icons-24" }, "visibility" + (hideThoughts ? "" : "_off"))),
                //m("input[" + (chatCfg.pending ? "disabled='true'" : "") + "]", { type: "text", name: "chatmessage", class: "text-field w-[80%]", placeholder: "Message", onkeydown: function (e) { if (e.which == 13) doChat(e); } }),
                (chatCfg.pending ? pendBar : input),
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
      if(!inst){
        return;
      }
      let msgs = document.getElementById("messages").querySelectorAll(":scope > div");
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
        if(aPCfg && aPCfg.length && inst && inst.api.promptConfig() == null) inst.api.promptConfig(aPCfg[0]);
      }
      if (aCCfg == undefined) {
        aCCfg = await am7client.list("olio.llm.chatConfig", dir.objectId, null, 0, 0);
        if(aCCfg && aCCfg.length && inst && inst.api.chatConfig() == null) inst.api.chatConfig(aCCfg[0]);

      }
      let dir2 = await page.findObject("auth.group", "DATA", "~/ChatRequests");
      if (aSess == undefined) {
        aSess = await am7client.list("olio.llm.chatRequest", dir2.objectId, null, 0, 0);
        if(aSess && aSess.length && !inst) pickSession(aSess[0]);
      }

    }

    function getChatView(vnode) {

      if (aPCfg == undefined && aCCfg == undefined) {
        loadConfigList().then(() => {
          // am7model.getModelField("chatSettings", "prompt").limit = aPCfg.map((c) => { return c.name; });
          // am7model.getModelField("chatSettings", "chat").limit = aCCfg.map((c) => { return c.name; });
          // am7model.getModelField("chatSettings", "sessions").limit = [inst.api.session()].concat(aSess.map((c) => { return c.name; }));
          if(aCCfg.length > 0){
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

        if(window.remoteEntity){
          inst = am7model.prepareInstance(remoteEntity, am7model.forms.chatSettings);
          window.dbgInst = inst;
          bPopSet = true;
          delete window.remoteEntity;
        }

        let cfg = page.context().contextObjects["chatConfig"];
        if (cfg ) {
          // && !inst.api.chatConfig() && !inst.api.promptConfig()
          console.warn("TODO: Refactor sending in chat config ref");
          /*
          console.log(cfg["olio.llm.chatConfig"].name);
          inst.api.chatConfig(chatCfg.chatConfig);
          inst.api.promptConfig(chatCfg.promptConfig);
          */
        }
        document.documentElement.addEventListener("keydown", navKey);

        if(bPopSet){
          openChatSettings();

        }


      },
      oncreate: function (x) {
        page.navigable.setupPendingContextMenus();
      },
      onupdate: function (x) {
        page.navigable.setupPendingContextMenus();
        scrollToLast();
      },
      onremove: function (x) {
        page.navigable.cleanupContextMenus();
        pagination.stop();
        document.documentElement.removeEventListener("keydown", navKey);
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
