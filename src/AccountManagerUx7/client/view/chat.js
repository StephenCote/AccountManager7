(function(){

 

    function newChatControl(){
  
        const chat = {};
        let fullMode = false;
        let showFooter = false;
        let pagination = page.pagination();
        let listView;
        let treeView;
        let altView;

        if(am7model.getModel("chatSettings") == null){
          am7model.models.push({
            name: "chatSettings",
            icon: "chat",
            label: "Settings",
            fields: [
              {
                "name": "chat",
                "label": "Chat Config",
                "type": "string",
                "default": "ad2.chat"
                },
                {
                  "name": "prompt",
                  "label": "Prompt Config",
                  "type": "string",
                  "default": "alg.prompt"
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
            label : "Settings",
            commands : {
              peek : {
                label : 'Peek',
                icon : 'man',
                action : 'peek'
            },
              chat : {
                  label : 'Chat',
                  icon : 'chat',
                  action : 'chat'
              }
          },
            fields: {
              prompt: {
                layout: 'full'
              },
              chat: {
                layout: 'full'
              },
              session: {
                layout: 'full'
              },
            }
        };
      }

    let chatCfg = {
      chat: undefined,
      prompt: undefined,
      system: undefined,
      user: undefined,
      peek: false,
      history: undefined,
      pending: false
    };
    window.dbgCfg = chatCfg;
    let inst = am7model.newInstance("chatSettings", am7model.forms.chatSettings);
    inst.api.session(sessionName());
    window.dbgInst = inst;
    inst.action("peek", doPeek);
    inst.action("chat", doChat);

    function sessionName(){
      let a = ["turtle","bunny","kitty","puppy","duckling","pony", "fishy"];
      let b = ["fluffy","cute","ornery", "obnoxious", "scratchy", "licky", "cuddly"];
      let c = ["little", "tiny", "enormous", "big"];
      
      return b[parseInt(Math.random() * b.length)] + " " + c[parseInt(Math.random() * c.length)] + " " + a[parseInt(Math.random() * a.length)];

    }

    function doCancel(){
      chatCfg.pending = false;
      chatCfg.history = [];
      m.request({method: 'POST', url: g_application_path + "/rest/chat/clear", withCredentials: true, body: {chatConfig:chatCfg?.chat?.objectId, promptConfig:chatCfg?.prompt?.objectId, sessionName: inst.api.session(), uid: page.uid()}}).then((r)=> {
        m.redraw();
      });
    }
    function doChat(){
      // console.log("Chatting ...");
      if(chatCfg.pending){
        console.warn("Chat is pending");
        return;
      }
      let msg = document.querySelector("[name='chatmessage']").value; ///e?.target?.value;
      //if(msg && msg.length){
        if(!chatCfg.peek){
          doPeek().then(()=>{
            doChat();
          })
        }
        else{
          pushHistory();
          chatCfg.pending = true;
          m.request({method: 'POST', url: g_application_path + "/rest/chat/text", withCredentials: true, body: {chatConfig:chatCfg.chat.objectId, promptConfig:chatCfg.prompt.objectId, sessionName: inst.api.session(), uid: page.uid(),message: msg}}).then((r)=> {
            if(!chatCfg.history) chatCfg.history = {};
            // console.log(r);
            chatCfg.history.messages = r?.messages || [];
            chatCfg.pending = false;
          });
        }
      //}
    }
    function pushHistory(){
      let msg = document.querySelector("[name='chatmessage']").value;
      if(!chatCfg.history) chatCfg.history = {};
      if(!chatCfg.history.messages) chatCfg.history.messages = [];
      chatCfg.history.messages.push({role: "user", content: msg});
      document.querySelector("[name='chatmessage']").value = "";
      m.redraw();
    }
    function getHistory(){
      console.log("History ...");
      return m.request({method: 'POST', url:g_application_path + "/rest/chat/history", withCredentials: true, body: {chatConfig:chatCfg.chat.objectId, promptConfig:chatCfg.prompt.objectId, sessionName: inst.api.session(), uid: page.uid()}});
    }
    
    function doPeek(){
      console.log("Peeking ...");
      chatCfg.history = undefined;
      chatCfg.system = undefined;
      chatCfg.user = undefined;
      chatCfg.peek = false;
      let c1 = inst.api.prompt();
      let c2 = inst.api.chat();
      let p;
      if(c1.length && c2.length){
        p = new Promise((res, rej)=>{
          m.request({method: 'GET', url: g_application_path + "/rest/chat/config/prompt/" + c1, withCredentials: true})
          .then((c)=>{
            chatCfg.prompt = c;
            m.request({method: 'GET', url: g_application_path + "/rest/chat/config/chat/" + c2, withCredentials: true}).then((c2)=>{
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
        function getChatTopMenuView(){

         return [];
        }

        function getSplitContainerView(){
          let splitLeft = "";
          if(!fullMode) splitLeft = getSplitLeftContainerView();
          return m("div", {class: "results-fixed", onselectstart: function(e){ e.preventDefault();}},
            m("div", {class: "splitcontainer"}, [
              splitLeft,
              getSplitRightContainerView()
            ])
          );
        }

        function getSplitRightContainerView(){
          let cls = "splitrightcontainer";
          if(fullMode) cls = "splitfullcontainer";
          return m("div", {class: cls}, [
              getResultsView()     
          ]);
        }

        chat.callback = function(){

        };

        function getSplitLeftContainerView(){

          return m("div", {
            class: "splitleftcontainer",
            ondragover: function(e){
              e.preventDefault();
            }
          }, [
            am7view.form(inst)
          ]);
        }
        function getResultsView(){
          let c1g = "man";
          let c1l = "Nobody";
          let c2g = "man";
          let c2l = "Nobody";
          if(chatCfg.system){
            c1l = chatCfg.system.name;
            if(chatCfg.system.gender == "female"){
              c1g = "woman";
            }
          }
          if(chatCfg.user){
            c2l = chatCfg.user.name;
            if(chatCfg.user.gender == "female"){
              c2g = "woman";
            }
          }

          let c1i = m("span", {class: "material-icons-outlined"}, c1g);
          let c2i = m("span", {class: "material-icons-outlined"}, c2g);
          if(chatCfg.system?.profile?.portrait){
              let pp = chatCfg.system.profile.portrait;
              let dataUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/96x96";
              // h-8 w-8 
              c1i = m("img",{class : "mr-4 rounded-full", src  : dataUrl});
          }

          if(chatCfg.user?.profile?.portrait){
            let pp = chatCfg.user.profile.portrait;
            let dataUrl = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/96x96";
            // h-8 w-8 
            c2i = m("img",{class : "ml-4 rounded-full", src  : dataUrl});
        }
          

          let msgs = (chatCfg?.history?.messages || []).map((msg) => {
            let align = "justify-start";
            let txt = "bg-gray-600 text-white";
            // console.log(msg);
            if(msg.role == "user"){
              align = "justify-end";
              txt = "bg-gray-200 text-black";
            }
            let cnt = msg.content;
            let idx = cnt.indexOf("(Reminder");
            if(idx > -1){
              cnt = cnt.substring(0, idx);
            }
            return  m("div", {class: "relative receive-chat flex " + align},
              m("div", {class: "px-5 mb-2 " + txt + " py-2 text-base max-w-[80%] border rounded-md font-light"},
                //m("i", {class: "material-icons text-violet-400 -top-4 absolute"}, "arrow_upward_alt"),
                m("p", cnt)
              )
            );
          });
          let flds = [ m("div", {class: "flex justify-between"}, [
            m("div", {class: "flex items-center"}, [
              c1i,
              m("span", {class: "text-gray-400 text-base pl-4"}, c1l)
            ]),
            m("div",{class: "flex items-center"}, [
              
              m("span", {class: "text-gray-400 text-base pl-4"}, c2l),
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
          
          let ret = m("div", {id: "messages", class: "h-full overflow-y-auto"}, [
            m("div", {class: "bg-white user-info-header px-5 py-3"}, flds),
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

        function getChatBottomMenuView(){
          // if(!showFooter) return "";
          return m("div", {class: "result-nav-outer"}, 
            m("div", {class: "results-fixed"},
            m("div", {class: "splitcontainer"}, [
              m("div", {class: "splitleftcontainer"}, "..."),
              m("div", {class: "splitrightcontainer result-nav-inner"},

                m("div",{class: "tab-container result-nav w-full"},[
                m("button", {class: "button", onclick: doCancel},m("span", {class: "material-symbols-outlined material-icons-24"}, "cancel")),
                m("input[" + (chatCfg.pending ? "disabled='true'":"") + "]", {type: "text", name: "chatmessage", class: "text-field w-[80%]", placeholder: "Message", onkeydown : function(e){ if (e.which == 13) doChat(e);}}),
                m("button", {class: "button", onclick: doChat},m("span", {class: "material-symbols-outlined material-icons-24"}, "chat"))
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

        let lastCount = -1;
        function scrollToLast(){
          
          let msgs = document.getElementById("messages").querySelectorAll(":scope > div");
          if(msgs.length > 1 && lastCount != msgs.length){
            lastCount = msgs.length;
            msgs[lastCount - 1].scrollIntoView();

          }

        }
      
        function getChatView(vnode){
            return m("div",{class : "content-outer"},[
              (fullMode ? "" : m(page.components.navigation, {hideBreadcrumb: true})),
              m("div",{class : "content-main"},
                m("div", {class: "list-results-container"},
                  m("div", {class: "list-results"}, [
                    getChatTopMenuView(),
                    getSplitContainerView(),
                    getChatBottomMenuView()
                  ])
                )
              )
            ]);
        }
        chat.toggleFullMode = function(){
          fullMode = !fullMode;
          m.redraw();
        }
        chat.cancelView = function(){
          altView = undefined;
          fullMode = false;
          m.redraw();
        };
        chat.editItem = function(object){
          if(!object) return;
          console.log("Edit", object);
          altView = {
            fullMode,
            view: page.views.object(),
            type: object.model,
            containerId: object.objectId
          };
          m.redraw();
        };
        chat.addNew = function(type, containerId, parentNew){
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
            oninit : function(vnode){
                let ctx = page.user.homeDirectory;
                origin = vnode.attrs.origin || ctx;
                let cfg = page.context().contextObjects["chatConfig"];
                if(cfg && !inst.api.chat() && !inst.api.prompt()){
                  console.log(cfg["olio.llm.chatConfig"].name);
                  //chatCfg.chat = cfg["olio.llm.chatConfig"];
                  //chatCfg.prompt = cfg["olio.llm.promptConfig"];
                  inst.api.chat(chatCfg.chat.name);
                  inst.api.prompt(chatCfg.prompt.name);
                }

            },
            oncreate : function (x) {
              page.navigable.setupPendingContextMenus();
            },
            onupdate : function(x){
              page.navigable.setupPendingContextMenus();
              scrollToLast();
            },
            onremove : function(x){
              page.navigable.cleanupContextMenus();
              pagination.stop();
            },
  
            view: function (vnode) {
              let v = getChatView(vnode);
                return [v, page.loadDialog()];
            }
        };
        return chat;
    }
      page.views.chat = newChatControl;
  }());
  