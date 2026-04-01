import m from 'mithril';
import { am7model } from '../core/model.js';
import { am7view } from '../core/view.js';
import { am7client } from '../core/am7client.js';
import { page } from '../core/pageClient.js';

function newPaginationControl() {
  let requesting = false;
  let embeddedMode = false;
  let containerCache = {};   // objectId → numeric id cache

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
    if (!pages.resultType) return null;
    let q = am7client.newQuery(pages.resultType);
    q.entity.request = getRequestFields(pages.resultType);

    if (pages.filter != null) {
      let qf = q.field("name", pages.filter);
      qf.comparator = "like";
    }

    if (!am7model.hasField(pages.resultType, "name") && entity.sort == "name") {
      entity.sort = "id";
      pages.sort = "id";
    }

    q.range(pages.startRecord, pages.recordCount);
    q.sort(pages.sort);
    q.order(pages.order);
    q.field("organizationId", page.user.organizationId);

    if (am7model.hasField(pages.resultType, "tags")) {
      q.entity.request.push("tags");
    }

    let needsContainer = (am7model.isGroup(pages.resultType) && am7model.hasField(pages.resultType, "groupId"))
        || am7model.isParent(pages.resultType);

    if (pages.containerId) {
      let id = containerCache[pages.containerId];
      if (!id) {
        let gq = am7view.viewQuery(am7model.newInstance(pages.containerType));
        gq.field("objectId", pages.containerId);
        let g = await page.search(gq);
        if (g && g.results && g.results.length) {
          id = g.results[0].id;
          containerCache[pages.containerId] = id;
        }
      }
      if (id) {
        if (am7model.isGroup(pages.resultType) && am7model.hasField(pages.resultType, "groupId")) {
          q.field("groupId", id);
        }
        else if (am7model.isParent(pages.resultType)) {
          q.field("parentId", id);
        }
      }
    } else if (needsContainer) {
      console.warn("[pagination] Type " + pages.resultType + " requires a container but none provided — returning empty results");
      return null;
    }

    return q;
  }

  function handleCount(v) {
    pages.pageResults = [];
    pages.counted = true;
    pages.totalCount = v || 0;

    requesting = false;
    // No m.redraw() here — let the chain complete to handleList() for a single redraw
  }

  function handleList(v) {
    if (v && v[am7model.jsonModelKey] == "io.queryResult") {
      v = v.results;
    }
    pages.pageResults[pages.currentPage] = v;
    am7model.updateListModel(v);
    requesting = false;
    m.redraw();
  }

  function listRouter(url, iPage) {
    if (embeddedMode) {
      updatePage(iPage);
    }
    else {
      m.route.set(url);
    }
  }

  function navNext(bRedrawOnly) {
    if (pages.currentPage < pages.pageCount) {
      if (bRedrawOnly || embeddedMode) {
        updatePage(pages.currentPage + 1);
      }
      else listRouter(getPageUrl(pages.currentPage + 1), pages.currentPage + 1);
    }
  }

  function navPrev(bRedrawOnly) {
    if (pages.currentPage > 1) {
      if (bRedrawOnly || embeddedMode) {
        updatePage(pages.currentPage - 1);
      }
      else listRouter(getPageUrl(pages.currentPage - 1), pages.currentPage - 1);
    }
  }

  function updatePage(iPage) {
    if (requesting) return;
    if (!pages.resultType) return;
    if (iPage <= 0 || iPage > pages.pageCount) {
      return;
    }

    pages.currentPage = iPage;
    pages.startRecord = (iPage - 1) * pages.recordCount;
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
        let ttype = pages.resultType.substring(pages.resultType.lastIndexOf(".") + 1, pages.resultType.length);
        let uType = ttype.substring(0, 1).toUpperCase() + ttype.slice(1);
        let fType = uType + "s";
        if (uType.match(/y$/)) fType = uType.slice(0, uType.length - 1) + "ies";
        let v;
        if ((v = page.application["system" + fType])) {
          let i = parseInt(pages.startRecord);
          let c = parseInt(pages.recordCount);
          let modList = v.slice(i, i + c);
          handleList(modList);
        }
      }
      else if (pages.resultType.match(/^request$/)) {
        console.warn("REFACTOR LIST REQUESTS");
      }
      else {
        getSearchQuery().then((q) => {
          if (!q) { requesting = false; handleList([]); return; }
          am7client.search(q, handleList);
        });
      }
    }
  }

  let _columnProvider = null;

  function getRequestFields(type) {
    let flds = am7model.queryFields(type);
    /// Pull additional fields from the decorator's type-specific header map
    let dec = page.components.decorator;
    let decoratorFields = (dec && typeof dec.getHeaders === 'function')
      ? dec.getHeaders(type)
      : (dec && typeof dec.map === 'function' ? dec.map() : []);
    decoratorFields.forEach(field => {
      if (!field.match(/^_/) && !flds.includes(field) && am7model.hasField(type, field)) {
        flds.push(field);
      }
    });
    // Include custom columns from list view column picker
    if (_columnProvider) {
      let extraCols = _columnProvider(type);
      if (extraCols) {
        extraCols.forEach(col => {
          if (!flds.includes(col) && am7model.hasField(type, col)) {
            flds.push(col);
          }
        });
      }
    }
    return flds;
  }

  function stopPaginating() {
    pagination.paginating = false;
    pages = newPagination();
    containerCache = {};
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
          containerCache[pages.containerId] = v.id;
          if (pages.containerType.match(/^auth\.group$/gi)) {
            pages.containerSubType = pages.container.type;
          }
          // Chain into count step immediately
          doCount(type, bSystem, startRecord, recordCount);
        }
        else {
          console.error("Error loading container: " + pages.containerType + " / " + pages.containerId);
        }
      });
    }
    else if (!pages.counted) {
      doCount(type, bSystem, startRecord, recordCount);
    }
    else {
      doSearchPage(startRecord, recordCount);
    }
  }

  function doCount(type, bSystem, startRecord, recordCount) {
    if (requesting) return;
    requesting = true;
    if (pages.containerSubType != null && pages.containerSubType.match(/^(system\.user|identity\.account|identity\.person|bucket)$/gi)) {
      am7client.countMembers(pages.containerType, pages.containerId, type, function (v) {
        handleCount(v);
        doSearchPage(startRecord, recordCount);
      });
    }
    else if (bSystem && type.match(/^(policy\.policy|auth\.role|auth\.permission)$/gi)) {
      let ttype = type.substring(type.lastIndexOf(".") + 1, type.length);
      let uType = ttype.substring(0, 1).toUpperCase() + ttype.slice(1);
      let fType = uType + "s";
      if (uType.match(/y$/)) fType = uType.slice(0, uType.length - 1) + "ies";
      if (page.application["system" + fType]) {
        handleCount(page.application["system" + fType].length);
        doSearchPage(startRecord, recordCount);
      }
    }
    else if (type.match(/^request$/gi)) {
      console.warn("REFACTOR COUNT REQUESTS");
    }
    else {
      getSearchQuery().then((req) => {
        if (!req) { requesting = false; handleCount(0); m.redraw(); return; }
        req.recordCount = 0;
        page.count(req).then((v) => {
          handleCount(v);
          doSearchPage(startRecord, recordCount);
        });
      });
    }
  }

  function doSearchPage(startRecord, recordCount) {
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

  function doFilter(sFilt, bRedrawOnly) {
    let url = page.getRawUrl() + "?startRecord=0";
    pages = newPagination();
    entity.listFilter = sFilt || "";
    console.log("Filter: ", entity);
    if (entity.listFilter != null && entity.listFilter.length) {
      url += "&filter=" + encodeURI(entity.listFilter);
      pages.filter = entity.listFilter;
    }
    if (bRedrawOnly) {
      m.redraw();
    }
    else {
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
    let stem = (typeof page.uriStem === 'function') ? page.uriStem() : "";

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
    let btns = [];

    btns.push(paginationButton("previous button", "keyboard_double_arrow_left", "", function () { listRouter(getPageUrl(1), 1); }));
    btns.push(paginationButton("button", "chevron_left", "", function () { navPrev(); }));

    let bMore = (pages.pageCount > 7);
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
        btns.push(paginationButton("page", null, ""));
        continue;
      }
      if (pages.currentPage == i) style = "page-current";
      else fHandler = function () {
        listRouter(getPageUrl(i), i);
      };
      btns.push(paginationButton(style, null, i, fHandler));
    }

    btns.push(paginationButton("button", "chevron_right", "", function () { navNext(); }));
    btns.push(paginationButton("next button", "keyboard_double_arrow_right", "", function () { listRouter(getPageUrl(pages.pageCount), pages.pageCount); }));

    return btns;
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
      requesting = false;
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
    url: getPageUrl,
    pageButtons: pageButtons,
    state: getPageState,
    button: paginationButton,
    stop: stopPaginating,
    setEmbeddedMode: function (bEmbed) { embeddedMode = bEmbed; },
    setColumnProvider: function (fn) { _columnProvider = fn; }
  };

  return pagination;
}

export { newPaginationControl };
export default newPaginationControl;
