(function () {

  function newPaginationControl() {
    let requesting = false;

    let entity = {
      listFilter: "",
      sort: "name",
      order: "ascending"
    };

    function newPagination(containerType) {
      return {
        navigateByParent: false,
        listSystem: false,
        startRecord: 0,
        recordCount: 10,
        displayCount: 0,
        totalCount: 0,
        counted: false,
        currentPage: -1,
        currentItem: 0,
        pageCount: 0,
        pageResults: [],
        pageState: {},
        resultType: null,
        filter: null,
        containerType: (containerType || "auth.group"),
        containerSubType: null,
        containerId: null,
        container: null,
        sort: entity.sort,
        order: entity.order,
        id: page.uid()
      };
    }
    let pages = newPagination();

    async function getSearchQuery() {

      let q = am7client.newQuery(pages.resultType);
      q.entity.request = getRequestFields(pages.resultType);
      if(pages.filter != null){
        let qf = q.field("name", pages.filter);
        qf.comparator = "like";
      }

      if(!am7model.hasField(pages.resultType, "name") && entity.sort == "name"){
        entity.sort = "id";
        pages.sort = "id";
      }

      q.range(pages.startRecord, pages.recordCount);
      q.sort(pages.sort);
      q.order(pages.order);
      q.field("organizationId", page.user.organizationId);

      if(am7model.hasField(pages.resultType, "tags")){ 
        q.entity.request.push("tags");
      }

      if(!pages.containerId){
        am7client.search(q, handleList);
      }
      else{
        let gq = am7view.viewQuery(am7model.newInstance(pages.containerType));
        gq.field("objectId", pages.containerId  );
        let g = await page.search(gq);
        let id = 0;
        if(g && g.results){
          id = g.results[0].id;
        }
        /// auth.group is a special case where it is a parent/child hierarchy, so the parentId is used
        /// some models like data.note can be hierarchical by group and parent
        if(pages.resultType){
          if(am7model.isGroup(pages.resultType) && am7model.hasField(pages.resultType, "groupId")){
            q.field("groupId", id);
          }
          else if(am7model.isParent(pages.resultType)){
            q.field("parentId", id);
          }
        }
      }

      return q;
    }

    function handleCount(v) {
      pages.pageResults = [];
      pages.counted = true;
      pages.totalCount = v || 0;

      requesting = false;
      m.redraw();
    }
    function handleList(v) {
      if(v && v[am7model.jsonModelKey] == "io.queryResult"){
        v = v.results;
      }
      pages.pageResults[pages.currentPage] = v;
      am7model.updateListModel(v);
      requesting = false;
      m.redraw();
    }

    function listRouter(url) {
      m.route.set(url);
    }
    function navNext(bRedrawOnly) {
      if (pages.currentPage < pages.pageCount) {
        if (bRedrawOnly) {
          pages.currentPage++;
          m.redraw();
        }
        else listRouter(getPageUrl(pages.currentPage + 1));
      }
    }
    function navPrev(bRedrawOnly) {
      if (pages.currentPage > 1) {
        if (bRedrawOnly) {
          pages.currentPage--;
          m.redraw();
        }
        else listRouter(getPageUrl(pages.currentPage - 1));
      }
    }

    function updatePage(iPage) {
      if (requesting) return;
      if (iPage <= 0 || iPage > pages.pageCount) {
        // console.debug("Invalid page: " + iPage + " / " + pages.pageCount);
        return;
      }

      pages.currentPage = iPage;
      pages.currentItem = -1;
      if (pages.pageResults[iPage]) {
        m.redraw();
      }

      else {
        /// filter is used for count and list through the search API, which allows for scoping to the current group and recurse through child group structures
        ///
        requesting = true;

        if (pages.containerSubType != null && pages.containerSubType.match(/^(user|account|person|bucket)$/gi)) {
          am7client.members(pages.containerType, pages.containerId, pages.resultType, pages.startRecord, pages.recordCount, function (v) {
            handleList(v);
          });
        }
        else if (pages.listSystem && pages.resultType.match(/^(policy\.policy|auth\.role|auth\.permission)$/gi)) {
          let ttype =  pages.resultType.substring(pages.resultType.lastIndexOf(".") + 1,  pages.resultType.length);

          let uType = ttype.substring(0, 1).toUpperCase() + ttype.slice(1);
          let fType = uType + "s";
          if (uType.match(/y$/)) fType = uType.slice(0, uType.length - 1) + "ies";
          let v;
          if((v = page.application["system" + fType])){
            let i = parseInt(pages.startRecord);
            let c = parseInt(pages.recordCount);
            let modList = v.slice(i, i + c);
            handleList(modList);
          }

        }
        else if (pages.resultType.match(/^request$/)) {
          console.warn("REFACTOR LIST REQUESTS");
          // am7client.listRequests("USER", page.user.objectId, pages.startRecord, pages.recordCount, handleList);
        }
        else {
          getSearchQuery().then((q) => {
            am7client.search(q, handleList);
          });
        }
      }
    }

    function getRequestFields(type) {
      let flds = am7model.queryFields(type);
      window.am7decorator.map().forEach(m => {
        if(!m.match(/^_/) && !flds.includes(m) && am7model.hasField(type, m)){
          flds.push(m);
        }
      });
      return flds;

    }

    function stopPaginating() {
      pagination.paginating = false;
      pages = newPagination();
    }

    function updateList(type, containerId, navigateByParent, filter, startRecord, recordCount, bSystem) {
      if (requesting) return;
      pagination.paginating = true;

      entity.listFilter = filter || "";
      let sortChange = (entity.sort != pages.sort || entity.order != pages.order);

      if (
        sortChange
        ||
        ((pages.filter == null && entity.listFilter.length) || (pages.filter != null && pages.filter != entity.listFilter))
        ||
        (pages.containerId != null && pages.containerId != containerId)
        ||
        (!isNaN(recordCount) && recordCount > 0 && pages.recordCount != recordCount)
      ) {
        /// TODO: Need to clean all of this up - the pagination state object needs a better relationship with the entity containing the filter and any search/query configuration
        /// A lot of this is due to the original API being driven by listing by type and membership, and it's morphed into the query/search format which is more flexible (albeit still somewhat limited by type)
        ///
        let sort = pages.sort;
        let order = pages.order;
        pages = newPagination();
        entity.sort = sort;
        entity.order = order;
        pages.sort = sort;
        pages.order = order;
        pages.recordCount = (!isNaN(recordCount) && recordCount > 0 ? recordCount : pages.recordCount);
        pages.filter = (entity.listFilter.length ? entity.listFilter : null);

      }
      pages.navigateByParent = navigateByParent;
      pages.listSystem = bSystem;
      if (type.match(/^(system\.user|auth\.permission|auth\.role|auth\.request|auth\.control)$/gi) || navigateByParent) pages.containerType = type;
      pages.resultType = type;
      pages.containerId = containerId;

      /// Need to get the container to understand the subType, such as GROUP/DATA vs GROUP/BUCKET
      if (containerId && pages.container == null) {
        requesting = true;
        page.openObject(pages.containerType, pages.containerId).then(v => {
          pages.container = v;
          requesting = false;
          if (v != null) {
            // console.log("Redraw to load container object");
            pages.container = v;
            if (pages.containerType.match(/^auth\.group$/gi)) {
              pages.containerSubType = pages.container.type;
            }
            m.redraw();
          }
          else {
            console.error("Error loading container: " + pages.containerType + " / " + pages.containerId);
          }
        });
      }
      else if (!pages.counted) {
        requesting = true;
        if (pages.containerSubType != null && pages.containerSubType.match(/^(system\.user|identity\.account|identity\.person|bucket)$/gi)) {
          am7client.countMembers(pages.containerType, pages.containerId, type, handleCount);
        }
        else if (bSystem && type.match(/^(policy\.policy|auth\.role|auth\.permission)$/gi)) {
          let ttype = type.substring(type.lastIndexOf(".") + 1, type.length);
          let uType = ttype.substring(0, 1).toUpperCase() + ttype.slice(1);
          let fType = uType + "s";
          if (uType.match(/y$/)) fType = uType.slice(0, uType.length - 1) + "ies";
          if(page.application["system" + fType]){
            handleCount(page.application["system" + fType].length);
          }
        }
        else if (type.match(/^request$/gi)) {
          console.warn("REFACTOR COUNT REQUESTS");
          /*
          am7client.countRequests("USER", page.user.objectId, function (v) {
            handleCount(v);
          });
          */
        }
        else {

          //let req = getSearchQuery();
          //am7client.searchCount(req, handleCount);
          /// Set record count to 0 because searchCount operates differently than the regular count, counting authorized identifiers outside of a view versus counting rows
          ///
          getSearchQuery().then((req) => {
            req.recordCount = 0;
            page.count(req).then((v) => {
              handleCount(v);
            });
          });

        }

      }
      else {
        if (pages.totalCount > 0) pages.pageCount = Math.ceil(pages.totalCount / pages.recordCount);
        if (isNaN(startRecord)) startRecord = 0;
        if (isNaN(recordCount)) recordCount = pages.recordCount;
        pages.startRecord = startRecord;
        pages.recordCount = recordCount;

        let usePage = 0;
        if (recordCount > 0) {
          usePage = 1;
          if (startRecord > 1) usePage = Math.ceil(startRecord / pages.recordCount) + 1;
          if (usePage != pages.currentPage) {
            updatePage(usePage);
          }
        }

      }
    }
    function doFilter(sFilt, bRedrawOnly) {
      let url = page.getRawUrl() + "?startRecord=0";
      pages = newPagination();
      entity.listFilter = sFilt || "";
      console.log("Filter: ", entity);
      if (entity.listFilter != null && entity.listFilter.length) {
        url += "&filter=" + encodeURI(entity.listFilter);
        pages.filter = entity.listFilter;
      }
      if(bRedrawOnly){
        m.redraw();
      }
      else{
        listRouter(url);
      }
    }

    function getPageUrl(iPage, iCount) {
      if (isNaN(iPage)) iPage = pages.currentPage;
      if (isNaN(iCount)) iCount = pages.recordCount;
      let url = page.getRawUrl();
      let filter = "";
      if (pages.filter != null) filter = "&filter=" + encodeURI(pages.filter);
      url += "?startRecord=" + ((iPage - 1) * iCount) + filter + "&recordCount=" + iCount;
      let stem = page.uriStem();

      return url + (stem.length ? "&" + stem : "");
    }
    function paginationControlBar() {
      return m("div", { class: "result-nav-outer" }, [
        m("div", { class: "result-nav-inner" }, [
          m("div", { class: "count-label" }, "Showing " + pages.currentPage + " of " + pages.pageCount + " (" + pages.totalCount + ")"),
          m("nav", { class: "result-nav" }, [
            pageButtons()
          ])
        ])
      ]);
    }
    function pageButtons(bRedrawOnly) {

      let pageButtons = [];

      pageButtons.push(paginationButton("previous button", "keyboard_double_arrow_left", "", function () { listRouter(getPageUrl(1)); }));
      pageButtons.push(paginationButton("button", "chevron_left", "", function () { navPrev(); }));

      let bMore = (pages.pageCount > 7)
      let left = pages.currentPage - 3;

      if (left < 1) left = 1;

      let right = pages.currentPage + 3;
      if (right < 7) right = 7;
      right = Math.min(pages.pageCount, right);

      if (pages.currentPage > 3 && (right - left) < 7) {
        left = Math.max(right - 6, 1);
      }
      let maxI = Math.max(right, 7);
      for (let i = left; i <= maxI; i++) {
        let style = "page";
        let fHandler;
        if (i > pages.pageCount) {
          pageButtons.push(paginationButton("page", null, ""));
          continue;
        }
        if (pages.currentPage == i) style = "page-current";
        else fHandler = function () {
          listRouter(getPageUrl(i));
        };
        pageButtons.push(paginationButton(style, null, i, fHandler));

      };
      pageButtons.push(paginationButton("button", "chevron_right", "", function () { navNext(); }));
      pageButtons.push(paginationButton("next button", "keyboard_double_arrow_right", "", function () { listRouter(getPageUrl(pages.pageCount)); }));
      return pageButtons;
    }

    function getPageState(o) {
      if (!pages.pageState[o.objectId]) {
        pages.pageState[o.objectId] = { checked: false };
      }
      return pages.pageState[o.objectId];
    }

    function paginationButton(sClass, sIco, sLabel, fHandler) {
      return page.iconButton(sClass, sIco, sLabel, fHandler);
    }

    let pagination = {
      requesting: requesting,
      entity: entity,
      paginating: false,
      new: function () {
        pages = newPagination();
        return pages;
      },
      pages: function () { return pages; },
      pageBarView: paginationControlBar,
      update: updateList,
      searchQuery: getSearchQuery,
      handleCount: handleCount,
      handleList: handleList,
      next: navNext,
      prev: navPrev,
      filter: doFilter,
      //changePage: updatePage,
      url: getPageUrl,
      pageButtons: pageButtons,
      state: getPageState,
      button: paginationButton,
      stop: stopPaginating

    };
    return pagination;
  }
  page.pagination = newPaginationControl;
}())