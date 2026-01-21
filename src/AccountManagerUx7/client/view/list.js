(function () {

  function newListControl() {

    const listPage = {};
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
    let pickerCancel;
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
    let taggingInProgress = false;
    let taggingAbort = false;

    function textField(sClass, id, plc, fKeyHandler) {
      return m("input", { placeholder: plc, onkeydown: fKeyHandler, id: id, type: "text", class: sClass });
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
      return (o[am7model.jsonModelKey] ? o[am7model.jsonModelKey] : listType);
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
      page.components.dialog.confirm(label + " selected objects?", async function () {
        let idx = getSelectedIndices();
        if (idx.length) {
          let pages = pagination.pages();
          let aP = [];
          for (let i = 0; i < idx.length; i++) {
            let obj = pages.pageResults[pages.currentPage][idx[i]];

            if (bBucket) {
              aP.push(page.member(contType, listContainerId, getType(obj), obj.objectId, false).then((r) => { if(r) page.toast("success", "Removed member"); else page.toast("error", "Failed to remove member"); }));
            }
            else {
              aP.push(page.deleteObject(getType(obj), obj.objectId).then((r) => { if(r) page.toast("success", "Deleted object"); else page.toast("error", "Failed to delete object"); }));
            }
          }
          await Promise.all(aP);
          pagination.new();
          m.redraw();
        }
      });

    }

    async function applyTagsToList() {
      // If already in progress, abort
      if (taggingInProgress) {
        taggingAbort = true;
        page.toast("warn", "Aborting tag operation...");
        m.redraw();
        return;
      }

      let pages = pagination.pages();
      if (!pages.containerId || !pages.container) {
        page.toast("error", "No container selected");
        return;
      }

      let groupId = pages.container.id;
      if (!groupId) {
        page.toast("error", "Container does not have a valid group ID");
        return;
      }

      // Build query to find data in the group, then filter for images client-side
      let q = am7client.newQuery("data.data");
      q.field("groupId", groupId);
      q.entity.request = ["id", "objectId", "name", "contentType"];
      q.range(0, 500);

      page.toast("info", "Searching for images...");
      let qr = await page.search(q);

      if (!qr || !qr.results || !qr.results.length) {
        page.toast("warn", "No data found in this group");
        return;
      }

      // Filter for images only
      let images = qr.results.filter(r => r.contentType && r.contentType.match(/^image\//i));
      if (!images.length) {
        page.toast("warn", "No images found in this group");
        return;
      }
      taggingInProgress = true;
      taggingAbort = false;
      m.redraw();

      let successCount = 0;
      let errorCount = 0;

      page.toast("info", "Tagging " + images.length + " images...", 10000);

      for (let i = 0; i < images.length; i++) {
        if (taggingAbort) {
          page.toast("warn", "Tag operation aborted at " + i + " of " + images.length);
          break;
        }

        let img = images[i];
        try {
          let tags = await page.applyImageTags(img.objectId);
          if (tags) {
            successCount++;
          }
        }
        catch (e) {
          console.error("Failed to tag image: " + img.name, e);
          errorCount++;
        }

        // Update progress every 10 images
        if ((i + 1) % 10 === 0) {
          page.toast("info", "Tagged " + (i + 1) + " of " + images.length + " images...", 5000);
          m.redraw();
        }
      }

      taggingInProgress = false;
      taggingAbort = false;

      if (successCount > 0 || errorCount > 0) {
        page.toast("success", "Completed: " + successCount + " tagged" + (errorCount > 0 ? ", " + errorCount + " errors" : ""));
      }

      m.redraw();
    }

    function closeSelected() {
      carousel = false;
      pagination.pages().currentItem = undefined;
      //pagination.new();
      m.redraw();
    }

    function openSelected() {

      /// open first selected
      page.components.pdf.clear();
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
          });
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

    async function openPath(path) {
      let grp = await page.findObject("auth.group", "DATA", path);
      if(!grp){
        page.toast("error", "Path not found: " + path);
        return;
      }
      navigateToPathId(grp);
    }


    function navigateDown(sel) {
      let idx = getSelectedIndices();
      let type = listType;
      if (type) {
        type = modType.type || type;
      }
      let byParent = (am7model.isParent(modType) && type !== 'auth.group');
      if (sel && !sel[am7model.jsonModelKey]) sel = null;
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
          let ltype = obj[am7model.jsonModelKey] || baseListType;
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
      if (embeddedMode || pickerMode) {
        // Reset pagination and start fresh with new record count
        pagination.new();
        pagination.update(listType, listContainerId, navigateByParent, navFilter, 0, rc, systemList);
        m.redraw();
      }
      else {
        m.route.set(pagination.url(1, rc), { key: Date.now() });
      }
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
      return am7decorator.gridListView(getListController());
    }

    function getListController(){
      return {
        containerMode,
        listPage,
        showIconFooter,
        embeddedMode,
        pickerMode,
        results: getCurrentResults,
        gridMode,
        info,
        pagination,
        fullMode,
        maxMode,
        listType,
        edit: editItem,
        move: moveCarousel,
        moveTo: moveCarouselTo,
        select: selectResult,
        open: openItem,
        down: navigateDown,
        onscroll: checkScrollPagination,
        toggleCarousel,
        toggleCarouselFull,
        toggleCarouselMax,
        toggleInfo
      };
    }
 
    function displayList() {
      let results = [];
      let rset;
      let pages = pagination.pages();
      page.checkFavorites();

      if (pages.pageResults[pages.currentPage]) {
        if (infiniteScroll) {
          rset = [].concat.apply([], pagination.pages().pageResults.slice(1, pages.currentPage + 1));
        }
        else rset = pages.pageResults[pages.currentPage];
      }

      //lreturn am7decorator.polarListView(getListController(), rset);
      return am7decorator.tabularView(getListController(), rset);
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

    function getOptionButton(type){
      let optButton;
      let selected = (getSelectedIndices().length > 0);
      if (containerMode || type === 'auth.group' || am7model.isParent(modType)) {
        let disableUp = (type !== 'auth.group' && am7model.isParent(modType) && !navigateByParent)
        optButton = [pagination.button("button" + (disableUp ? " inactive" : ""), "north_west", "", navigateUp), pagination.button("button" + (!selected ? " inactive" : ""), "south_east", "", navigateDown)]
      }
      else if (!pickerMode) {
        optButton = pagination.button("button" + (carousel ? " active" : ""), "view_carousel", "", toggleCarousel);
      }
      return optButton;
    }

    function getAdminButtons(type){
      let rs = page.context().roles;
      let buttons = [];
      let cnt = pagination.pages().container;
      let favSel = "";
      if (type.match(/^policy\.policy/) || rs.accountAdmin || (rs.roleReader && type.match(/^auth\.role$/gi)) || (rs.permissionReader && type.match(/^auth\.permission$/gi))) {
        buttons.push(pagination.button("button mr-4" + (systemList ? " active" : ""), "admin_panel_settings", "", listSystemType));
      }
      else if (am7model.system.library[type]) {
        if (cnt && cnt.path.match(/^\/Library/gi)) favSel = " bg-orange-200 active";
        buttons.push(pagination.button("button mr-4" + (systemList ? " active" + favSel : ""), "admin_panel_settings", "", openSystemLibrary));
      }
      return buttons;
    }

    function getPickerButton(){
      let buttons = [];
      if (pickerMode) {
        buttons.push(pagination.button("button", "check", "", function () { pickerHandler(getSelected()); }));
        if (pickerCancel) {
          buttons.push(pagination.button("button", "close", "", function () { pickerCancel(); }));
        }
      }
      return buttons;
    }

    function getActionButtons(type){
      let buttons = [];
      if (!pickerMode && !containerMode) {
        let selected = (getSelectedIndices().length > 0);
        buttons.push(pagination.button("button mr-4", (fullMode ? "close_fullscreen" : "open_in_new"), "", toggleCarouselFull));
        if (!modType.systemNew) buttons.push(pagination.button("button", "add", "", addNew));
        if(type && type == "olio.charPerson"){
          buttons.push(pagination.button("button", "steppers", "", am7model.forms.commands.characterWizard));
        }
        buttons.push(pagination.button("button" + (!selected ? " inactive" : ""), "file_open", "", openSelected));
        buttons.push(pagination.button("button" + (!selected ? " inactive" : ""), "edit", "", editSelected));
        let bBucket = (pagination.pages().containerSubType && pagination.pages().containerSubType.match(/^(bucket|account|person)$/gi));
        buttons.push(pagination.button("button" + (!selected ? " inactive" : ""), (bBucket ? "playlist_remove" : "delete"), "", deleteSelected));
        if(type && type == "data.data"){
          buttons.push(pagination.button("button" + (taggingInProgress ? " active" : ""), "sell", "", applyTagsToList));
        }
      }

      return buttons;
    }

    function getPageToggleButtons(type){
      let buttons = [];
      buttons.push(pagination.button("button" + (gridMode > 0 ? " active" : ""), (gridMode <= 1 ? "apps" : "grid_view"), "", toggleGrid));
      if (!embeddedMode && (!containerMode || !type.match(/^auth\.group$/gi)) && modType.group) buttons.push(pagination.button("button" + (navigateByParent ? " inactive" : (containerMode ? " active" : "")), 'group_work', "", toggleContainer));
      buttons.push( pagination.button("button" + (info ? " active" : ""), "info", "", toggleInfo));
      return buttons;
    }

    function getGroupSearchButtons(){
      let buttons = [];
      if (am7model.isGroup(modType)) {
        let plc = "";
        let cnt = pagination.pages().container;
        if(cnt && cnt.path) plc = cnt.path;
        buttons.push(textField("text-field", "listFilter", plc, function (e) { if (e.which == 13) doFilter(); },));
        buttons.push(pagination.button("button", "search", null, doFilter));
      }
      return buttons;
    }

    function getOlioButtons(){
      let buttons = [];
      let oliSel = "";
      let cnt = pagination.pages().container;
      if (cnt && cnt.path.match(/^\/olio/gi)) oliSel = " bg-orange-200 active";
      buttons.push(pagination.button("button" + oliSel, "globe", "", openOlio));
      return buttons;
    }

    function getFavoriteButtons(){
      let buttons = [];
      let cnt = pagination.pages().container;
      let favSel = "";
      if (cnt && cnt.name.match(/favorites/gi)) favSel = " bg-orange-200 active";
      buttons.push(pagination.button("button" + favSel, "favorite", "", openFavorites));
      return buttons;
    }

    function getActionButtonBar(type){
      let buttons = [];
      buttons.push(getPickerButton());
      buttons.push(getAdminButtons(type));
      buttons.push(getActionButtons(type));
      buttons.push(getOlioButtons());
      buttons.push(getFavoriteButtons());
      buttons.push(getOptionButton(type));
      buttons.push(getPageToggleButtons(type));
      buttons.push(getGroupSearchButtons());
      return buttons;
    }

    function getListViewInner(type) {

      let buttons = getActionButtonBar(type);

      let fdoh = function (e) {
        e.preventDefault();
      };
      let fdrh = dnd.doDrop;

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
      navFilter = document.querySelector("[id=listFilter]").value;
      if (!navFilter.length) navFilter = null;

      else if(navFilter.indexOf("..") > -1 || navFilter.indexOf("~") > -1 || navFilter.indexOf("/") > -1){
        let npath = page.normalizePath(navFilter, pagination.pages().container);
        if(npath){
          openPath(npath);
        }
        return;
      }

      let red = false;
      if (embeddedMode || pickerMode) {
        //m.redraw();
        red = true;
      }

      else pagination.filter(navFilter, red);
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

   

    function moveCarouselTo(i) {
      let pages = pagination.pages();
      wentBack = false;
      pages.currentItem = i;
      m.redraw();
    }

    function moveCarousel(i) {
      let pages = pagination.pages();
      let pr = pages.pageResults[pages.currentPage];
      page.components.pdf.clear();
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
        m.redraw();
      }
    }



    function getCurrentResults(){
      let pages = pagination.pages();
      let pr = pages.pageResults[pages.currentPage];
      if (pr) {
        if (wentBack) {
          pages.currentItem = pr.length - 1;
        }
        if (pages.currentItem < 0 && pr.length) pages.currentItem = 0;
      }
      return pr;
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
      }
      baseListType = listType;
      if (modType.group && containerMode && !listType.match(/^auth\.group$/gi)) listType = 'auth.group';
      listContainerId = navContainerId || vnode.attrs.objectId || m.route.param("objectId");
      if (!embeddedMode && !pickerMode) navContainerId = null;
    }

    function update(vnode) {
      initParams(vnode);
      let listFilter = navFilter || vnode.attrs.filter || m.route.param("filter");
      if (listFilter) listFilter = decodeURI(listFilter);

      let pages = pagination.pages();
      // In embedded/picker mode, skip update if pagination has results to avoid resetting state
      if ((embeddedMode || pickerMode) && pages.counted && pages.currentPage > 0) {
        return;
      }
      let currentDefaultRecordCount = (gridMode == 1) ? defaultIconRecordCount : defaultRecordCount;
      let startRecord = vnode.attrs.startRecord || m.route.param("startRecord");
      let recordCount = vnode.attrs.recordCount || m.route.param("recordCount") || currentDefaultRecordCount;
      pagination.update(listType, listContainerId, navigateByParent, listFilter, startRecord, recordCount, systemList);

      if(carousel){
        let pr = getCurrentResults();
        let cient = pr ? pr[pagination.pages().currentItem] : undefined;
        if(cient && page.context().contextObjects[cient.objectId]){
          if(page.components.pdf.viewer(cient.objectId)){
            page.components.pdf.viewers[cient.objectId].init(1.0);
          }
        }
      }
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
        pickerCancel = x.attrs.pickerCancel;
        pagination.setEmbeddedMode(embeddedMode || pickerMode);
        update(x);
      },
      onupdate: function (x) {
        update(x);
      },
      onremove: function (x) {
        page.components.pdf.clear();
        navFilter = null;
        document.documentElement.removeEventListener("keydown", navListKey);
        pagination.stop();
        if (app) app.destroy();
      },

      view: function (vnode) {
        let v;
        if (vnode.attrs.pickerMode || (!carousel && vnode.attrs.embeddedMode)) v = getListViewInner(vnode.attrs.type);
        else if (carousel) {
          if (vnode.attrs.embeddedMode) v = am7decorator.carouselItem(getListController());
          else v = v = am7decorator.carouselView(getListController());
        }
        else v = getListView();
        if (vnode.attrs.pickerMode || vnode.attrs.embeddedMode) return v;
        return [v, page.components.dialog.loadDialog(), page.loadToast()];
      }
    };
    window.dbgList = listPage;
    return listPage;
  }

  page.views.list = newListControl;
}());
