(function () {



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

    if (am7model.getModel("chatSettings") == null) {
      am7model.models.push({
        name: "chatSettings",
        icon: "chat",
        label: "Settings",
        fields: [
          {
            "name": "chat",
            "label": "Chat Config",
            "type": "string",
            "baseType": "string"
          },
          {
            "name": "prompt",
            "label": "Prompt Config",
            "type": "string",
            "baseType": "string"
          },
          {
            "name": "sessions",
            "label": "Sessions",
            "type": "string",
            "baseType": "string"
          },
          {
            "name": "session",
            "label": "Session Name",
            "type": "string",
            "default": "Def Sess"

          }
        ]
      });

      am7model.forms.chatSettings = {
        label: "Settings",
        commands: {
          cmdPeek: {
            label: 'Peek',
            icon: 'man',
            action: 'doPeek'
          },
          cmdChat: {
            label: 'Chat',
            icon: 'chat',
            action: 'doChat'
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
          sessions: {
            layout: 'full',
            format: 'list'
          },
          session: {
            layout: 'full'
          },
        }
      };
    }


    // window.dbgCfg = chatCfg;
    let inst = am7model.newInstance("chatSettings", am7model.forms.chatSettings);
    inst.api.session(sessionName());
    inst.api.sessions(inst.api.session());
    // window.dbgInst = inst;
    inst.action("doPeek", doPeek);
    inst.action("doChat", doChat);

    function pickSession(i, n){
      doClear();
      let key = inst.api.sessions();
      if(key.length > 1){
        let pairs = key.split("-");
        if(pairs.length == 4){
          inst.api.chat(pairs[1]);
          inst.api.prompt(pairs[2]);
          inst.api.session(pairs[3]);
          doPeek();
        }
        else if(pairs.length == 1){
          inst.api.session(pairs[0]);
        }
        else{
          console.warn("Unexpected key format: " + key);
        }
      }
      else{
        console.warn("Invalid key");
      }
    }
    inst.action("chat", doPeek);
    inst.action("sessions", pickSession);

    function sessionName() {
      let a = ["turtle", "bunny", "kitty", "puppy", "duckling", "pony", "fishy"];
      let b = ["fluffy", "cute", "ornery", "obnoxious", "scratchy", "licky", "cuddly"];
      let c = ["little", "tiny", "enormous", "big"];

      return b[parseInt(Math.random() * b.length)] + " " + c[parseInt(Math.random() * c.length)] + " " + a[parseInt(Math.random() * a.length)];

    }

    async function doCancel() {
      clearEditMode();
      chatCfg.pending = false;
      chatCfg.history = [];
      return m.request({ method: 'POST', url: g_application_path + "/rest/chat/clear", withCredentials: true, body: { chatConfig: chatCfg?.chat?.objectId, promptConfig: chatCfg?.prompt?.objectId, sessionName: inst.api.session(), uid: page.uid() } }).then((r) => {
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
        m.request({ method: 'POST', url: g_application_path + "/rest/chat/text", withCredentials: true, body: { chatConfig: chatCfg.chat.objectId, promptConfig: chatCfg.prompt.objectId, sessionName: inst.api.session(), uid: page.uid(), message: msg } }).then((r) => {
          if (!chatCfg.history) chatCfg.history = {};
          chatCfg.history.messages = r?.messages || [];
          chatCfg.pending = false;
        });
      }

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
      return m.request({ method: 'POST', url: g_application_path + "/rest/chat/history", withCredentials: true, body: { chatConfig: chatCfg.chat.objectId, promptConfig: chatCfg.prompt.objectId, sessionName: inst.api.session(), uid: page.uid() } });
    }

    function newChatConfig(){
      return {
        chat: undefined,
        prompt: undefined,
        system: undefined,
        user: undefined,
        peek: false,
        history: undefined,
        pending: false
      };
    }


    function doPeek() {
      doClear();
      let c1 = inst.api.prompt();
      let c2 = inst.api.chat();
      let p;
      if (c1.length && c2.length) {
        p = new Promise((res, rej) => {
          m.request({ method: 'GET', url: g_application_path + "/rest/chat/config/prompt/" + c1, withCredentials: true })
            .then((c) => {
              chatCfg.prompt = c;
              m.request({ method: 'GET', url: g_application_path + "/rest/chat/config/chat/" + c2, withCredentials: true }).then((c2) => {
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
        /*
        p = Promise.all([
          m.request({method: 'GET', url: g_application_path + "/rest/chat/character/" + c1, withCredentials: true}).then((c)=>{chatCfg.system = c}),
          m.request({method: 'GET', url: g_application_path + "/rest/chat/character/" + c2, withCredentials: true}).then((c)=>{chatCfg.user = c})
        ]).then(()=>{
          chatCfg.peek = true;
          m.redraw();
        });
        */
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
      let sess = inst.api.sessions().split("-").length == 4;
      inst.formField("prompt").hide = sess;
      inst.formField("chat").hide = sess;
      inst.formField("session").hide = sess;

      return m("div", {
        class: "splitleftcontainer",
        ondragover: function (e) {
          e.preventDefault();
        }
      }, [
        am7view.form(inst)
      ]);
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
            //let om = histx.messages.filter(m => m.content == ocnt.content);
            //if(om.length){
            //  om[0].content = cnt;
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
            /*
            }
            else{
              console.error("Failed to find matching history");
            }
            */
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
      return m("div", { class: "result-nav-outer" },
        m("div", { class: "results-fixed" },
          m("div", { class: "splitcontainer" }, [
            m("div", { class: "splitleftcontainer" }, "..."),
            m("div", { class: "splitrightcontainer result-nav-inner" },

              m("div", { class: "tab-container result-nav w-full" }, [
                m("button", { class: "button", onclick: toggleFullMode }, m("span", { class: "material-symbols-outlined material-icons-24" }, (fullMode ? "close_fullscreen" : "open_in_new"))),
                m("button", { class: "button", onclick: doCancel }, m("span", { class: "material-symbols-outlined material-icons-24" }, "cancel")),
                m("button", { class: "button", onclick: toggleThoughts }, m("span", { class: "material-symbols-outlined material-icons-24" }, "visibility" + (hideThoughts ? "" : "_off"))),
                m("input[" + (chatCfg.pending ? "disabled='true'" : "") + "]", { type: "text", name: "chatmessage", class: "text-field w-[80%]", placeholder: "Message", onkeydown: function (e) { if (e.which == 13) doChat(e); } }),
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

      let msgs = document.getElementById("messages").querySelectorAll(":scope > div");
      if (msgs.length > 1 && lastCount != msgs.length) {
        lastCount = msgs.length;
        msgs[lastCount - 1].scrollIntoView();

      }

    }
    
    async function loadConfigList() {
      let dir = await page.findObject("auth.group", "DATA", "~/Chat");
      if (aPCfg == undefined) {
        aPCfg = await am7client.list("olio.llm.promptConfig", dir.objectId, null, 0, 0);
        if(aPCfg && aPCfg.length && inst.api.prompt() == null) inst.api.prompt(aPCfg[0].name);
      }
      if (aCCfg == undefined) {
        aCCfg = await am7client.list("olio.llm.chatConfig", dir.objectId, null, 0, 0);
        if(aCCfg && aCCfg.length && inst.api.chat() == null) inst.api.chat(aCCfg[0].name);

      }
      if (aSess == undefined) {
        aSess = await am7client.list("data.data", dir.objectId, null, 0, 0);
        if(aSess && aSess.length && inst.api.sessions().length == 1) inst.api.sessions(inst.api.sessions().concat(aSess.map((s)=>{return s.name})));
      }

    }

    function getChatView(vnode) {

      if (aPCfg == undefined && aCCfg == undefined) {
        loadConfigList().then(() => {
          am7model.getModelField("chatSettings", "prompt").limit = aPCfg.map((c) => { return c.name; });
          am7model.getModelField("chatSettings", "chat").limit = aCCfg.map((c) => { return c.name; });
          am7model.getModelField("chatSettings", "sessions").limit = [inst.api.session()].concat(aSess.map((c) => { return c.name; }));
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
        origin = vnode.attrs.origin || ctx;
        let cfg = page.context().contextObjects["chatConfig"];
        if (cfg && !inst.api.chat() && !inst.api.prompt()) {
          console.log(cfg["olio.llm.chatConfig"].name);
          //chatCfg.chat = cfg["olio.llm.chatConfig"];
          //chatCfg.prompt = cfg["olio.llm.promptConfig"];
          inst.api.chat(chatCfg.chat.name);
          inst.api.prompt(chatCfg.prompt.name);
        }
        document.documentElement.addEventListener("keydown", navKey);

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
        return [v, page.loadDialog(), page.loadToast()];
      }
    };
    return chat;
  }
  page.views.chat = newChatControl;
}());
