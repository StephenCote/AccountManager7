(function () {

  function newListControl() {

    const listPage = {};
    let space;
    let entityName = "sig";
    let app;
    let wentBack = false;
    let carousel = false;
    let gridMode = 0;
    let fullMode = false;
    let maxMode = false;
    let info = true;
    let listType;
    let baseListType;
    let listContainerId;
    let navContainerId = null;
    let navFilter = null;
    let pickerMode = false;
    let embeddedMode = false;
    let embeddedController;
    let pickerHandler;
    let containerMode = false;
    let defaultRecordCount = 10;
    let defaultIconRecordCount = 40;
    let modType;
    let infiniteScroll = false;
    let showFooter = false;
    let showIconFooter = false;
    let navigateByParent = false;
    let systemList = false;
    let pagination = page.pagination();

    let dnd;

    function textField(sClass, id, fKeyHandler) {
      return m("input", { onkeydown: fKeyHandler, id: id, type: "text", class: sClass });
    }

    function addNew() {
      let pg = pagination.pages();
      if (embeddedMode && embeddedController && embeddedController.addNew) {
        console.info("Embedded add");
        embeddedController.addNew(pg.resultType, pg.containerId);
      }
      else {
        let path = "/" + (m.route.get().match(/plist/) ? "pnew" : "new") + "/" + pg.resultType + "/" + pg.containerId;
        console.info("Path", path);
        /// Note: Adding a key because cycling through the same view with different objects needs a full DOM rebuild to correctly disconnect the sync code
        ///
        m.route.set(path, { key: Date.now() });
      }
    }

    function getSelected() {
      let pages = pagination.pages();
      return pages.pageResults[pages.currentPage].filter((v) => { return (pages.pageState[v.objectId] && pages.pageState[v.objectId].checked); })
    }

    function getSelectedIndices() {
      let pages = pagination.pages();
      let aRes = pages.pageResults[pages.currentPage];
      let aSel = [];
      Object.keys(pages.pageState).forEach((k) => {
        if (pages.pageState[k].checked) {
          let idx = aRes.findIndex((v) => v.objectId === k);
          if (idx > -1) aSel.push(idx);
        }
      });
      return aSel;
    }

    function getType(o) {
      return (o.model ? o.model : listType);
    }

    function editItem(o) {
      if (!o || o == null) {
        console.error("Invalid object");
        return;
      }
      if (embeddedMode && embeddedController && embeddedController.addNew) {
        embeddedController.editItem(o);
      }
      else {
        m.route.set("/view/" + getType(o) + "/" + o.objectId);
      }
    }

    function editSelected() {
      let idx = getSelectedIndices();
      if (idx.length) {
        let pages = pagination.pages();
        editItem(pages.pageResults[pages.currentPage][idx[0]]);
      }
    }
    async function deleteSelected() {
      let contType = pagination.pages().containerType;
      let subType = pagination.pages().containerSubType;
      let bBucket = (subType && subType.match(/^(bucket|account|person)$/gi));
      let label = (bBucket ? "Remove" : "Delete");
      page.confirm(label + " selected objects?", async function () {
        let idx = getSelectedIndices();
        if (idx.length) {
          let pages = pagination.pages();
          let aP = [];
          for (let i = 0; i < idx.length; i++) {
            let obj = pages.pageResults[pages.currentPage][idx[i]];

            if (bBucket) {
              console.log(label, contType, listContainerId, getType(obj), obj.objectId);
              aP.push(page.member(contType, listContainerId, getType(obj), obj.objectId, false));
            }
            else {
              aP.push(page.deleteObject(getType(obj), obj.objectId));
            }
          }
          await Promise.all(aP);
          pagination.new();
          m.redraw();

          /*
          let obj = pages.pageResults[pages.currentPage][idx[0]];
          am7client.delete(getType(obj), obj.objectId, function(s, v){
            pagination.new();
            m.redraw();
          });
          */
        }
      });

    }

    function closeSelected() {
      carousel = false;
      pagination.pages().currentItem = undefined;
      //pagination.new();
      m.redraw();
    }

    function openSelected() {

      /// open first selected
      let idx = getSelectedIndices();
      if (idx.length) {
        carousel = true;
        pagination.pages().currentItem = idx[0];
        m.redraw();
      }

      /// Opens to multiple windows
      /// pagination.routeToResult();
    }
    function navigateUp() {
      let pages = pagination.pages();
      if (pages.container) {
        let type = listType;
        if (type) {
          type = modType.type || type;
        }
        let path;
        if (type === 'auth.group') {
          path = pages.container.path.substring(0, pages.container.path.lastIndexOf("/"));
          page.navigateToPath(type, modType, path).then((id) => {
            if (embeddedMode || pickerMode) {
              navContainerId = id;
              m.redraw();
            }
            else {
              page.listByType((containerMode ? baseListType : type), id);
            }
          });;
        }
        else if (am7model.isParent(modType) && navigateByParent) {
          let objectId = m.route.param("objectId");
          let q = am7view.viewQuery(am7model.newInstance(useType));
          q.field("objectId", objectId);

          am7client.search(q, function (qr) {
            let v;
            if (qr && qr.results.length) v = qr[0];
            //am7client.get(type, objectId, function(v){
            if (v != null) {
              /// this shouldn't be hit
              /// am7model.inherits(modType, "data.directory")
              if (v.parentId == 0 && am7model.isGroup(modType)) {
                console.warn("Navigate up from zero parent shouldn't be hit unless at the top - resetting out of parent mode");
                page.navigateToPath(type, modType, v.groupPath).then((id) => {
                  page.listByType((containerMode ? baseListType : type), id, false);
                });
                //navigateByParent = false;
                //page.listByType((containerMode ? baseListType : type), v.objectId, false);
              }
              else {
                am7client.get(type, v.parentId, function (v2) {
                  if (v2 != null) {
                    page.listByType((containerMode ? baseListType : type), v2.objectId, true);
                  }
                });
              }
            }
          });
        }
        else {
          console.error("Handle " + type);
        }
      }
    }

    async function navigateToPathId(grp) {
      let ltype = baseListType;

      if (embeddedMode || pickerMode) {
        let byParent = (am7model.isParent(modType) && type !== 'auth.group');
        navContainerId = grp.objectId;
        if (byParent) navigateByParent = true;
        m.redraw();
      }
      else {
        m.route.set("/list/" + (containerMode ? baseListType : ltype) + "/" + grp.objectId, { key: Date.now() });
      }
    }

    async function openSystemLibrary() {
      let grp = await page.systemLibrary(baseListType);
      navigateToPathId(grp);
    }

    async function openOlio(){
      let og = await page.findObject("auth.group", "DATA", "/Olio/Universes");
      navigateToPathId(og);
    }

    async function openFavorites() {
      let fav = await page.favorites();
      navigateToPathId(fav);
    }

    function navigateDown(sel) {
      let idx = getSelectedIndices();
      let type = listType;
      if (type) {
        type = modType.type || type;
      }
      let byParent = (am7model.isParent(modType) && type !== 'auth.group');
      if (sel && !sel.model) sel = null;
      if (sel || idx.length) {
        let pages = pagination.pages();
        let obj = sel || pages.pageResults[pages.currentPage][idx[0]];
        // containerMode || 
        if (embeddedMode || pickerMode) {
          //console.log("Nav down: " + obj.objectId);
          navContainerId = obj.objectId;
          if (byParent) navigateByParent = true;
          //pagination.new();
          m.redraw();
        }
        else {
          let ltype = obj.model || baseListType;
          m.route.set("/" + (byParent ? 'p' : '') + "list/" + (containerMode ? baseListType : ltype) + "/" + obj.objectId, { key: Date.now() });
        }
      }
    }

    function listSystemType() {
      systemList = !systemList;
      pagination.new();
      m.redraw();
    }

    function openItem(o) {
      let pages = pagination.pages();
      let aRes = pages.pageResults[pages.currentPage];
      let idx = aRes.findIndex((v) => v.objectId === o.objectId);
      if (idx > -1) {
        carousel = true;
        pages.currentItem = idx;
        m.redraw();
      }
      //openSelected();

      /// Opens to a window
      /// pagination.routeToResult(o);
    }

    function selectResult(o) {
      let state = pagination.state(o);
      state.checked = !state.checked;
      // console.log("REDRAW ON SELECT");
      m.redraw();
    }

    function toggleInfo() {
      info = !info;
      m.redraw();
    }

    function toggleContainer() {
      containerMode = !containerMode;
      pagination.new();
      m.redraw();
    }
    function toggleGrid(bOff) {
      if (typeof bOff == 'boolean' && !bOff) gridMode = 0;
      else gridMode++;
      if (gridMode > 2) gridMode = 0;
      let rc = defaultRecordCount;
      if (gridMode == 1) rc = defaultIconRecordCount;
      let pages = pagination.pages();
      m.route.set(pagination.url(pages.currentPage, rc), { key: Date.now() });
      //pagination.update(pages.containerType, pages.containerId, pages.filter, pages.startRecord, rc);
      //m.redraw();
    }

    function toggleCarousel() {
      carousel = !carousel;
      m.redraw();
    }

    function toggleCarouselFull(bForce) {
      if (typeof bForce == "boolean") {
        fullMode = bForce;
      }
      else {
        fullMode = !fullMode;
        if (embeddedController) {
          embeddedController.toggleFullMode();
        }
        else {
          m.redraw();
        }
      }
    }

    function toggleCarouselMax() {
      maxMode = !maxMode;
      m.redraw();
    }

    function displayGrid() {
      let results = [];
      let pages = pagination.pages();
      let showInfo = info;
      if (pages.pageResults[pages.currentPage]) results = pages.pageResults[pages.currentPage].map((p, i) => {

        let type = am7model.getModel(getType(p));
        let ico = "question_mark";
        if (type && type.icon) ico = type.icon;
        let icon;

        if (p.profile && p.profile.portrait && p.profile.portrait.contentType) {
          let pp = p.profile.portrait;
          let icoPath = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/" + (gridMode == 1 ? "256x256" : "512x512");
          if (gridMode == 2 && pp.contentType.match(/gif$/)) icoPath = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name;
          let icoCls = "image-grid-image carousel-item-img";
          icon = m("img", { class: icoCls, src: icoPath });
        }
        else if (p.contentType && p.contentType.match(/^image/)) {
          let icoPath = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name + "/" + (gridMode == 1 ? "256x256" : "512x512");
          if (gridMode == 2 && p.contentType.match(/gif$/)) icoPath = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name;
          // let icoCls = "carousel-item-img";
          // image-grid-image 
          let icoCls = "image-grid-image carousel-item-img";
          icon = m("img", { class: icoCls, src: icoPath });
        }
        else {
          //showInfo = true;
          icon = m("span", { class: "material-symbols-outlined material-icons-48" }, ico);
        }
        let cls = (gridMode == 1 ? "image-grid-item-tile" : "image-grid-item-1");
        if (pagination.state(p).checked) {
          cls += " image-grid-item-active";
        }
        let title = "";
        let footer = "";
        if (showInfo) {
          title = m("p", { class: "card-title" }, p.name);
          if (showIconFooter) footer = m("p", { class: "card-footer" }, 'footer');
        }
        // onclick : function(){ pages.currentItem = i; toggleCarousel();}
        //m("div", {class: "h-full"},

        let gridCellCls = "image-grid-item-content-32";
        if (gridMode == 2) gridCellCls = "image-grid-item-1";
        let attr = "";
        let props = {
          ondblclick: function () {
            if (containerMode) navigateDown(p);
            else openItem(p);
          },
          onclick: function () {
            selectResult(p);
          },
          class: cls
        };
        //if(dnd && dnd.dragStartHandler){
        attr = "[draggable]";
        props.ondragover = function (e) { dnd.doDragOver(e, p); };
        props.ondragend = function (e) { dnd.doDragEnd(e, p); };
        props.ondragstart = function (e) { dnd.doDragStart(e, p); };
        props.ondrop = function (e) { dnd.doDrop(e, p); };
        //}


        return m("div" + attr, props, [title, m("div", { class: gridCellCls }, icon), footer])
          //)
          ;
      });

      return m("div", { class: 'h-full overflow-hidden' }, m("div", { class: (gridMode == 1 ? 'image-grid-tile' : 'image-grid-5') }, results));
    }

    function displayList() {
      let results = [];
      let pages = pagination.pages();
      let rset;
      if (pages.pageResults[pages.currentPage]) {
        if (infiniteScroll) {
          rset = [].concat.apply([], pagination.pages().pageResults.slice(1, pages.currentPage + 1));
          //console.log("Working from " + pages.currentPage + " with " + rset.length);
          //rset = pages.pageResults[pages.currentPage];
        }
        else rset = pages.pageResults[pages.currentPage];
      }
      if (rset) results = rset.map((p) => {
        let type = am7model.getModel(getType(p).toLowerCase());
        let ico = "question_mark";
        if (type && type.icon) ico = type.icon;
        let icon;
        let icoCls;
        if (p.model == "MESSAGE") {
          if (p.spoolStatus == "ERROR") {
            icoCls = "text-red-600";
            ico = "quickreply";
          }
          else if (p.spoolStates == "ACKNOWLEGED_RECEIPT") {
            ico = "mark_chat_read";
          }
          else if (p.spoolStates == "TRANSMITTED" || p.spoolStatus == "SPOOLED") {
            icoCls = "text-green-600";
            ico = "mark_chat_unread";
          }

        }
        if (p.profile && p.profile.portrait && p.profile.portrait.contentType) {
          let icoPath = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.profile.portrait.groupPath + "/" + p.profile.portrait.name + "/48x48";
          icon = m("img", { height: 48, width: 48, src: icoPath });
        }
        else if (p.contentType && p.contentType.match(/^image/)) {
          let icoPath = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name + "/48x48";

          icon = m("img", { height: 48, width: 48, src: icoPath });
        }
        else {
          let icoCls = "material-symbols-outlined";
          let col = "";
          let sty = "";
          if (p.hex) {
            icoCls = "material-icons";
            col = " stroke-slate-50";
            sty = "color: " + p.hex + ";";
          }

          icon = m("span", { style: sty, class: icoCls + " material-icons-48" + (icoCls ? " " + icoCls : "") + col }, ico);
        }
        let cls = "result-item";
        let icoTxt = "check";
        let dcls;
        //if(dnd && dnd.dragDecorator){
        dcls = dnd.doDragDecorate(p);
        if (dcls) cls += " " + dcls;
        //}
        if (!dcls && pagination.state(p).checked) {
          cls += " result-item-active";
          icoTxt = "check_circle";
        }
        let useName = am7client.getAttributeValue(p, 'name', p.name) || (p.objectId);

        let supplData = "";
        if (p.contentType) supplData = p.contentType;
        else if (p.type) supplData = p.type;
        else if (p.description) supplData = p.description;
        else if (p.spoolStatus) supplData = p.spoolStatus;
        let attr = "";
        let dndProp = {};
        //if(dnd && dnd.dragStartHandler){
        attr = "[draggable]";
        dndProp = {
          ondragover: function (e) { dnd.doDragOver(e, p); },
          ondragend: function (e) { dnd.doDragEnd(e, p); },
          ondragstart: function (e) { dnd.doDragStart(e, p); },
          ondrop: function (e) { dnd.doDrop(e, p); }
        };

        //}

        return m("li" + attr, dndProp,
          [
            m("div", {
              rid: "item-" + p.objectId,
              onselectstart: function () { return false; },
              ondblclick: function () { if (containerMode) navigateDown(p); else openItem(p); },
              onclick: function () { selectResult(p) },
              class: cls
            }, [
              m("div", { class: "flex-polar" }, [
                m("div", { class: "label" }, useName),
                m("div", { class: "polar-label" }, [
                  m("span", { class: "tweak-box" }, [
                    /// placeholder: Favorites button
                    m("span", { class: "material-symbols-outlined material-icons-sm" }, "add")
                  ])
                ])
              ]),
              m("div", { class: "flex-polar-2" }, [
                m("div", { class: "meta-container" }, [
                  m("div", { class: "meta-item" }, [
                    icon,
                    m("span", { class: "ml-2" }, supplData),
                    m("span", { class: "ml-2" }, (p.modifiedDate ? " " + (new Date(p.modifiedDate)).toLocaleDateString("en-US", { weekday: 'short', year: 'numeric', month: 'long', day: 'numeric', hour: 'numeric', minute: 'numeric' }) : ""))
                  ])
                ]),
                m("div", { class: "annotation" }, [
                  m("span", { class: "material-symbols-outlined" }, icoTxt),
                  m("span", { class: "material-symbols-outlined" }, "favorite")
                ])
              ])
            ])
          ]);
      });

      return m("ul", { rid: 'resultList', onscroll: checkScrollPagination, class: "list-results-overflow" }, results);
    }

    function checkScrollPagination() {
      if (!infiniteScroll) return;
      let pages = pagination.pages();

      let list = document.querySelector('[rid=resultList]');
      let limit = (list.clientHeight + list.scrollTop) > (list.scrollHeight - 10);
      if (pages.currentPage != pages.pageCount && limit) {
        // console.log("peak ahead");
        list.removeEventListener('scroll', checkScrollPagination);
        pagination.next();
      }
    }

    function getListViewInner(type) {

      let infoButton = "";
      //let modType = am7model.getModel(type);

      //if(gridMode){
      infoButton = pagination.button("button" + (info ? " active" : ""), "info", "", toggleInfo);
      //}
      let selected = (getSelectedIndices().length > 0);
      let undef;

      //console.log("Type: " + type);
      let optButton;
      /// type === 'role' || type === 'permission'


      let cnt = pagination.pages().container;
      let buttons = [];
      let rs = page.context().roles;



      if (containerMode || type === 'auth.group' || am7model.isParent(modType)) {
        let disableUp = (type !== 'auth.group' && am7model.isParent(modType) && !navigateByParent)
        optButton = [pagination.button("button" + (disableUp ? " inactive" : ""), "north_west", "", navigateUp), pagination.button("button" + (!selected ? " inactive" : ""), "south_east", "", navigateDown)]
      }
      else if (!pickerMode) {
        optButton = pagination.button("button" + (carousel ? " active" : ""), "view_carousel", "", toggleCarousel);
      }

      if (pickerMode) {
        buttons.push(pagination.button("button", "check", "", function () { pickerHandler(getSelected()); }));
      }
      if (type.match(/^policy\.policy/) || rs.accountAdmin || (rs.roleReader && type.match(/^auth\.role$/gi)) || (rs.permissionReader && type.match(/^auth\.permission$/gi))) {
        buttons.push(pagination.button("button mr-4" + (systemList ? " active" : ""), "admin_panel_settings", "", listSystemType));
      }
      else if (am7model.system.library[type]) {
        if (cnt && cnt.path.match(/^\/Library/gi)) favSel = " bg-orange-200 active";
        buttons.push(pagination.button("button mr-4" + (systemList ? " active" : ""), "admin_panel_settings", "", openSystemLibrary));
      }
      //else{
      if (!pickerMode && !containerMode) {

        buttons.push(pagination.button("button mr-4", (fullMode ? "close_fullscreen" : "open_in_new"), "", toggleCarouselFull));
        if (!modType.systemNew) buttons.push(pagination.button("button", "add", "", addNew));
        buttons.push(pagination.button("button" + (!selected ? " inactive" : ""), "file_open", "", openSelected));
        buttons.push(pagination.button("button" + (!selected ? " inactive" : ""), "edit", "", editSelected));
        let bBucket = (pagination.pages().containerSubType && pagination.pages().containerSubType.match(/^(bucket|account|person)$/gi));

        buttons.push(pagination.button("button" + (!selected ? " inactive" : ""), (bBucket ? "playlist_remove" : "delete"), "", deleteSelected));
      }

      let oliSel = "";
      if (cnt && cnt.path.match(/^\/olio/gi)) oliSel = " bg-orange-200 active";
      buttons.push(pagination.button("button" + oliSel, "globe", "", openOlio));

      let favSel = "";
      if (cnt && cnt.name.match(/favorites/gi)) favSel = " bg-orange-200 active";
      buttons.push(pagination.button("button" + favSel, "favorite", "", openFavorites));

      buttons.push(optButton);
      buttons.push(pagination.button("button" + (gridMode > 0 ? " active" : ""), (gridMode <= 1 ? "apps" : "grid_view"), "", toggleGrid));
      if (!embeddedMode && (!containerMode || !type.match(/^auth\.group$/gi)) && modType.group) buttons.push(pagination.button("button" + (navigateByParent ? " inactive" : (containerMode ? " active" : "")), 'group_work', "", toggleContainer));
      buttons.push(infoButton);
      //}

      /// The authorizedsearch query has a few leftovers limiting it to groups, until that's corrected search won't work correctly for users, permissions, and roles (non-group types)
      if (am7model.isGroup(modType)) {
        buttons.push(textField("text-field", "listFilter", function (e) { if (e.which == 13) doFilter(); },));
        buttons.push(pagination.button("button", "search", null, doFilter));
      }
      let fdoh;
      let fdrh;
      //if(dnd && dnd.dragOverHandler){
      /// prevent default at the top
      fdoh = function (e) {
        e.preventDefault();
      };
      fdrh = dnd.doDrop;
      //}
      return m("div", { ondragover: fdoh, ondrop: fdrh, class: "list-results-container" }, [
        m("div", { class: "list-results" }, [
          m("div", { class: "result-nav-outer" }, [
            m("div", { class: "result-nav-inner" }, [
              m("div", { class: "result-nav tab-container" },
                buttons
              ),
              m("nav", { class: "result-nav" }, [
                pagination.pageButtons()
              ])
            ])
          ]),
          (gridMode > 0 ? displayGrid() : displayList()),
          (showFooter ? pagination.pageBarView() : "")
        ])
      ]);
    }

    function doFilter() {
      console.log(pagination.filter + "");
      if (embeddedMode || pickerMode) {
        navFilter = document.querySelector("[id=listFilter]").value;
        if (!navFilter.length) navFilter = null;
        m.redraw();
      }

      else pagination.filter(app);
    }

    function getListView() {
      let type = m.route.param("type");
      return m("div", { class: "content-outer" }, [
        (fullMode ? "" : m(page.components.navigation)),
        m("div", { class: "content-main" }, [
          getListViewInner(type)
        ])
      ]);
    }

    function displayIndicators() {
      let results = [];
      let pages = pagination.pages();
      if (pages.pageResults[pages.currentPage]) results = pages.pageResults[pages.currentPage].map((p, i) => {
        let cls = "material-symbols-outlined carousel-bullet";
        let ico = "radio_button_unchecked";
        if (i == pages.currentItem) {
          cls += " carousel-bullet-active";
          ico = "radio_button_checked";
        }
        return m("li", { onclick: function () { moveCarouselTo(i); }, class: "carousel-indicator" }, [
          m("span", { class: cls }, ico)
        ]);
      });

      return m("ul", { class: "carousel-indicators" }, [
        m("li", { onclick: function () { pagination.prev(embeddedMode || pickerMode); }, class: "carousel-indicator" }, [
          m("span", { class: "material-symbols-outlined carousel-bullet" }, "arrow_back")
        ]),
        results,
        m("li", { onclick: function () { pagination.next(embeddedMode || pickerMode); }, class: "carousel-indicator" }, [
          m("span", { class: "material-symbols-outlined carousel-bullet" }, "arrow_forward")
        ])
      ]);
    }
    function displayObjects() {

      let results = [];
      let pages = pagination.pages();
      let pr = pages.pageResults[pages.currentPage];
      /// let cls = 'carousel-item carousel-item-abs';
      if (pr) {
        results = pr.map((p, i) => {
          return m(page.components.object, { view: listPage, app, object: p, model: listType, active: (pages.currentItem == i), maxMode: maxMode, inner: true });
        });
      }
      return results;

    }

    function moveCarouselTo(i) {
      let pages = pagination.pages();
      wentBack = false;
      pages.currentItem = i;
      m.redraw();
    }

    function moveCarousel(i) {
      let pages = pagination.pages();
      let pr = pages.pageResults[pages.currentPage];

      wentBack = false;

      let idx = pages.currentItem + i;
      if (pr && idx >= pr.length) {
        if (pages.currentPage < pages.pageCount) pagination.next(embeddedMode || pickerMode);
      }
      else if (idx < 0) {
        if (pages.currentPage > 1) {
          wentBack = true;
          pagination.prev(embeddedMode || pickerMode);
        }
      }
      else {
        pages.currentItem = idx;
        // console.log("move carousel to " + idx);
        m.redraw();
      }
    }

    function getCarouselView() {

      /// set the current item value to the end of the array when paginating back while in carousel mode
      // console.log("Current item: " + pages.currentItem + " / " + pagination.requesting);

      return m("div", { class: "content-outer" }, [(fullMode ? "" : m(page.components.navigation)),
      m("div", { class: "content-main" }, [
        //            m("div", {class: "carousel"}, [
        //              m("div", {class : "carousel-inner"}, [
        getCarouselViewInner()
        //              ])
        //            ])
      ])
      ]);
    }

    function getCarouselViewInner() {
      let pages = pagination.pages();
      let pr = pages.pageResults[pages.currentPage];
      if (pr) {
        if (wentBack) {
          pages.currentItem = pr.length - 1;
          //wentBack = false;
        }
        if (pages.currentItem < 0 && pr.length) pages.currentItem = 0;
      }
      let uimarkers = [];
      if (!embeddedMode || fullMode) {
        uimarkers = [
          m("span", { onclick: toggleCarouselFull, class: "carousel-full" }, [m("span", { class: "material-symbols-outlined" }, (fullMode ? "close_fullscreen" : "open_in_new"))]),
          m("span", { onclick: toggleCarouselMax, class: "carousel-max" }, [m("span", { class: "material-symbols-outlined" }, (maxMode ? "photo_size_select_small" : "aspect_ratio"))]),
          m("span", { onclick: toggleInfo, class: "carousel-info" }, [m("span", { class: "material-symbols-outlined" + (info ? "" : "-outlined") }, (info ? "info" : "info"))]),
          m("span", { onclick: function () { editItem(pr[pages.currentItem]); }, class: "carousel-edit" }, [m("span", { class: "material-symbols-outlined" }, "edit")]),
          m("span", { onclick: toggleCarousel, class: "carousel-exit" }, [m("span", { class: "material-symbols-outlined" }, "close")]),
          m("span", { onclick: function () { moveCarousel(-1); }, class: "carousel-prev" }, [m("span", { class: "material-symbols-outlined" }, "arrow_back")]),
          m("span", { onclick: function () { moveCarousel(1); }, class: "carousel-next" }, [m("span", { class: "material-symbols-outlined" }, "arrow_forward")]),
        ];
      }
      return m("div", { class: "carousel" }, [
        m("div", { class: "carousel-inner" },
          [
            displayObjects(),
            uimarkers,
            displayIndicators()
          ]
        )
      ]);

    }

    function navListKey(e) {
      wentBack = false;
      //if(carousel || gridMode){
      switch (e.keyCode) {
        /// <
        case 37:
          //if((gridMode && !carousel) || e.shiftKey){
          if (!carousel || e.shiftKey) {
            wentBack = true;
            pagination.prev(embeddedMode || pickerMode);
          }
          else moveCarousel(-1);
          break;
        /// >
        case 39:
          //if((gridMode && !carousel) || e.shiftKey)
          if (!carousel || e.shiftKey) {
            pagination.next(embeddedMode || pickerMode);
          }
          else moveCarousel(1);
          break;
        /// ESC
        case 27:
          if (carousel) toggleCarousel();
          else if (gridMode > 0) toggleGrid(false);
          else if (fullMode) toggleCarouselFull();
          break;
      }

      // }
    }

    function initParams(vnode) {
      ;
      listType = vnode.attrs.type || m.route.param("type") || "data";
      //dnd = vnode.attrs.dnd;
      /*
      dnd.dragStartHandler = vnode.attrs.dragStartHandler;
      dnd.dragOverHandler = vnode.attrs.dragOverHandler;
      dnd.dragEndHandler = vnode.attrs.dragEndHandler;
      dnd.dropHandler = vnode.attrs.dropHandler;
      */

      modType = am7model.getModel(listType);
      if (!modType) {
        console.error("Missing modType for type " + listType);
      }
      else {
        navigateByParent = vnode.attrs.navigateByParent || (m.route.get().match(/^\/plist/) != null);
        if (navigateByParent && !am7model.isParent(modType)) {
          console.warn("Type is not clustered by parent");
          navigateByParent = false;
        }
        // console.log("Parent mode: " + navigateByParent);
      }
      //console.log('type=' + listType);
      //console.log(modType);
      baseListType = listType;
      if (modType.group && containerMode && !listType.match(/^auth\.group$/gi)) listType = 'auth.group';
      //console.log(listType);
      listContainerId = navContainerId || vnode.attrs.objectId || m.route.param("objectId");
      //console.log(listContainerId + " || " + navContainerId);
      if (!embeddedMode && !pickerMode) navContainerId = null;
    }

    function update(vnode) {
      initParams(vnode);
      let listFilter = navFilter || vnode.attrs.filter || m.route.param("filter");
      //console.log(listFilter, navFilter, vnode.attrs.filter, m.route.param("filter"));
      if (listFilter) listFilter = decodeURI(listFilter);

      let startRecord = vnode.attrs.startRecord || m.route.param("startRecord");
      let recordCount = vnode.attrs.recordCount || m.route.param("recordCount") || defaultRecordCount;
      pagination.update(listType, listContainerId, navigateByParent, listFilter, startRecord, recordCount, systemList);
    }

    listPage.closeView = function () {
      closeSelected();
    };

    listPage.pagination = function () {
      return pagination;
    };

    listPage.view = {
      oninit: function (x) {
        dnd = page.components.dnd;
        initParams(x);
        document.documentElement.addEventListener("keydown", navListKey);
      },
      oncreate: function (x) {
        fullMode = x.attrs.fullMode || fullMode;
        pickerMode = x.attrs.pickerMode;
        embeddedMode = x.attrs.embeddedMode;
        embeddedController = x.attrs.embeddedController;
        pickerHandler = x.attrs.pickerHandler;
        update(x);
      },
      onupdate: function (x) {
        update(x);
      },
      onremove: function (x) {
        navFilter = null;
        document.documentElement.removeEventListener("keydown", navListKey);
        pagination.stop();
        if (app) app.destroy();
      },

      view: function (vnode) {
        let v;
        if (vnode.attrs.pickerMode || (!carousel && vnode.attrs.embeddedMode)) v = getListViewInner(vnode.attrs.type);
        else if (carousel) {
          if (vnode.attrs.embeddedMode) v = getCarouselViewInner();
          else v = v = getCarouselView();
        }
        else v = getListView();
        if (vnode.attrs.pickerMode || vnode.attrs.embeddedMode) return v;
        return [v, page.loadDialog()];
      }
    };
    window.dbgList = listPage;
    return listPage;
  }

  page.views.list = newListControl;
}());
