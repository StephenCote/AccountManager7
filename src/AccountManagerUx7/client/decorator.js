(function () {

    function getFileTypeIcon(p, i) {
        let mtIco = "";
        if (p.contentType && p.name.indexOf(".") > 0) {
            let ext = p.name.substring(p.name.lastIndexOf(".") + 1, p.name.length).toLowerCase();
            mtIco = m("span", { class: "fontLabel" + (i && i > 0 ? "-" + i : "") + " fiv-cla fiv-icon-" + ext });
        }
        return mtIco;
    }

    function getThumbnail(ctl, p){
        let type = am7model.getModel(p[am7model.jsonModelKey]);
        let ico = "question_mark";
        if (type && type.icon) ico = type.icon;
        let gridMode = ctl.gridMode;
        let icon;
        if (p.profile && p.profile.portrait && p.profile.portrait.contentType) {
          let pp = p.profile.portrait;
          let icoPath = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name + "/" + (gridMode == 1 ? "256x256" : "512x512");
          if (gridMode == 2 && (pp.contentType.match(/gif$/) || pp.contentType.match(/webp$/))) icoPath = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + pp.groupPath + "/" + pp.name;
          let icoCls = "image-grid-image carousel-item-img";
          icon = m("img", { class: icoCls, src: icoPath });
        }
        else if (p.contentType && p.contentType.match(/^image/)) {
          let icoPath = g_application_path + "/thumbnail/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name + "/" + (gridMode == 1 ? "256x256" : "512x512");
          if(p.dataBytesStore && p.dataBytesStore.length){
            icoPath = "data:" + p.contentType + ";base64," + p.dataBytesStore;
          }
          if (gridMode == 2 && (p.contentType.match(/gif$/) || p.contentType.match(/webp$/))) icoPath = g_application_path + "/media/" + am7client.dotPath(am7client.currentOrganization) + "/data.data" + p.groupPath + "/" + p.name;
          let icoCls = "image-grid-image carousel-item-img";
          icon = m("img", { class: icoCls, src: icoPath });
        }
        else if(p.contentType && p.name.indexOf(".") > 0){
           let ext = p.name.substring(p.name.lastIndexOf(".") + 1, p.name.length);
            icon = m("span", { class: "fontLabel-" + (gridMode == 1 ? "6" : "10") + " fiv-cla fiv-icon-" + ext });
        }
        else {
          icon = m("span", { class: "material-symbols-outlined material-icons-48" }, ico);
        }
        return icon;
    }

    function getIcon(p) {
        let type = am7model.getModel(p[am7model.jsonModelKey]);
        let ico = "question_mark";
        if (type && type.icon) ico = type.icon;
        let icon;
        let icoCls = "";
        if (p[am7model.jsonModelKey] == "message.spool") {
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
            if (p.dataBytesStore && p.dataBytesStore.length) {
                icoPath = "data:" + p.contentType + ";base64," + p.dataBytesStore;
            }
            icon = m("img", { height: 48, width: 48, src: icoPath });
        }
        else if(p.contentType && p.name && p.name.indexOf(".") > 0){
            icon = getFileTypeIcon(p, 3);
        }
        else {
            icoCls = "material-symbols-outlined";
            let col = "";
            let sty = "";
            if (p.hex) {
                icoCls = "material-icons";
                col = " stroke-slate-50";
                sty = "color: " + p.hex + ";";
            }

            icon = m("span", { style: sty, class: icoCls + " material-icons-48" + (icoCls ? " " + icoCls : "") + col }, ico);
        }
        return icon;
    };



    function getFavoriteStyle(p) {
        let isFavcls = "";
        if (page.isFavorite(p)) {
            isFavcls = " material-symbols-outlined-red filled";
        }
        return isFavcls
    }

    function getPolarListItem(ctl, p) {
        let icon = am7decorator.icon(p);
        let cls = "result-item";
        let dcls;
        let dnd = page.components.dnd;
        let pagination = ctl.pagination;
        dcls = dnd.doDragDecorate(p);
        if (dcls) cls += " " + dcls;

        if (!dcls && pagination.state(p).checked) {
            cls += " result-item-active";
        }
        let useName = am7client.getAttributeValue(p, 'name', p.name) || (p.objectId);

        let supplData = "";
        if (p.contentType) supplData = p.contentType;
        else if (p.type) supplData = p.type;
        else if (p.description) supplData = p.description;
        else if (p.spoolStatus) supplData = p.spoolStatus;
        let attr = "";
        let dndProp = page.components.dnd.dragProps(p);
        attr = "[draggable]";
        let isFavcls = getFavoriteStyle(p);
        let mtIco = am7decorator.fileIcon(p);
        return m("li" + attr, dndProp,
            [
                m("div", {
                    rid: "item-" + p.objectId,
                    onselectstart: function () { return false; },
                    ondblclick: function () { if (ctl.containerMode) ctl.down(p); else ctl.open(p); },
                    onclick: function () { ctl.select(p) },
                    class: cls
                }, [
                    m("div", { class: "flex-polar" }, [
                        m("div", { class: "label" }, [useName]),
                        m("div", { class: "label" }, ["Tags"]),
                        m("div", { class: "polar-label" }, [
                            m("span", { class: "tweak-box" }, [
                                mtIco
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
                            m("span", {
                                class: "cursor-pointer material-symbols-outlined" + isFavcls, onclick: function (e) {
                                    e.preventDefault();
                                    page.favorite(p).then((b) => {
                                        m.redraw();
                                    });
                                    return false;
                                }
                            }, "favorite")
                        ])
                    ])
                ])
            ]);
    }

    function getPolarListView(ctl, rset){
      let results = (rset || []).map((p) => {
        return getPolarListItem(ctl, p);
      });
      return m("ul", { rid: 'resultList', onscroll: ctl.onscroll, class: "list-results-overflow" }, results);
    }

    let defaultHeaderMap = [
        "_rowNum",
        "_icon",
        "id",
        "name",
        "modifiedDate",
        // "_thumbnail",
        "_tags",
        "_favorite"
    ];
    
    function getHeaders(type, map){
        return (map || defaultHeaderMap).filter((h) => am7model.hasField(type, h) || h.match(/^_/));
    }

    function getHeadersView(ctl, map){
        let type = ctl.listType;
        let mod = am7model.getModel(type);
        let pages = ctl.pagination.pages();
        let headers = getHeaders(type, map).map((h) => {
            let fld = am7model.getModelField(type, h);
            let lbl = fld?.label || h;
            if(h.match(/^_/)){
                lbl = "";
                if(h == "_tags"){
                    lbl = "tags";
                }
            }
            let ico = "";
            if (h == "_icon" && mod.icon) {
                ico = m("span", { class: "material-icons-cm material-symbols-outlined" }, mod.icon);
            }
            else if(h == "_rowNum"){
                ico = m("span", { class: "material-icons-cm material-symbols-outlined" }, "tag");
            }
            let cw = getCellStyle(ctl, h);
            let sortButton = "";
            //if(pages.sort == h){
            if(!h.match(/^_/)){
                let sico = "swap_vert";
                let icoCls = "text-red-200";
                if(pages.sort == h){
                    sico = (pages.order == "ascending" ? "arrow_upward" : "arrow_downward");
                    icoCls = "text-blue-700";
                }
                sortButton = m("span", { class: "mr-2 material-icons-cm material-symbols-outlined " + icoCls, onclick: function(){
                    if(pages.sort == h){
                        pages.order = (pages.order == "ascending" ? "descending" : "ascending");
                    }
                    else{
                        pages.sort = h;
                        pages.order = "ascending";
                    }
                }}, sico);
            }
            return m("th", {class: cw}, [ico, sortButton, lbl]);
        });
        return m("thead", m("tr", {class: "tabular-header"}, headers));
    }

    function getTabularRow(ctl, p, idx, map) {
        let isFavcls = getFavoriteStyle(p);
        let dcls = page.components.dnd.doDragDecorate(p);
        let cls = "tabular-row";
        if(ctl.pagination.state(p).checked){
            cls += " tabular-row-active";
        }
        if (dcls) cls += " " + dcls;
        let props = Object.assign(page.components.dnd.dragProps(p), {
            class: cls,
            onselectstart: function () { return false; },
            ondblclick: function () { if (ctl.containerMode) ctl.down(p); else ctl.open(p); },
            onclick: function () { ctl.select(p) }
        });
        let attr = "[draggable]";
        return m("tr" + attr, props, getHeaders(ctl.listType, map).map((h) => {
            let cw = getCellStyle(ctl, h);
            if(h == "_thumbnail") {
                return m("td", { class: {class: cw}, }, [getThumbnail(ctl, p)]);
            }
            else if(h == "_icon") {
                return m("td", { class: cw}, [getIcon(p)]);
            }
            else if(h == "_tags"){

                let tags = (p.tags || []).map((t) => {
                    return [m("span", {
                        class: "cursor-pointer material-symbols-outlined", onclick: function (e) {
                            e.preventDefault();

                            return false;
                        }
                    }, "label"), t.name];
                });
                return m("td", { class: cw}, tags);
            }
            else if(h == "_favorite"){
                return m("td", {class: cw},  m("span", {
                    class: "cursor-pointer material-symbols-outlined" + isFavcls, onclick: function (e) {
                        e.preventDefault();
                        page.favorite(p).then((b) => {
                            m.redraw();
                        });
                        return false;
                    }
                }, "favorite"));
            }
            else if(h == "_rowNum"){
                return m("td", {class: cw}, (parseInt(ctl.pagination.pages().startRecord) + 1) + idx);
            };
            

            return m("td",{class: cw}, getFormattedValue(ctl, p, h));
        }));
    
    }
    
    function getCellStyle(ctl, h){
        let w = "";
        if(h == "_icon" || h == "id" || h == "_rowNum"){
            w = "w-14";
        }
        else if(h == "_favorite"){
            w = "w-8"
        }

        if(h == "id" || h == "_rowNum"){
            w += " align-right";
        }
        else{
            w += " align-center";
        }
        return w;
    }

    function getFormattedValue(ctl, p, h){
        if(h.match(/^_/)){
            console.warn("Unhandled special column: " + h);
            return "";
        }
        let fld = am7model.getModelField(p[am7model.jsonModelKey], h);
        if(!fld){
            console.warn("Couldn't find field", h);
            return p[h];
        }

        if(fld.type == "timestamp"){
            //  { weekday: 'short', year: 'numeric', month: 'long', day: 'numeric', hour: 'numeric', minute: 'numeric' }
            return (p[h] ? " " + (new Date(p[h])).toLocaleDateString("en-US", {year: 'numeric', month: 'long', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }) : "");
        }

        return p[h];
    }

    function getTabularView(ctl, rset){
        let results = (rset || []).map((p, i) => {
          return getTabularRow(ctl, p, i);
        });
        if(results.length == 0){
            return "";
        }

        let table = m("table", { class: "tabular-results-table" }, [ getHeadersView(ctl), m("tbody", results)]);


        return m("div", { rid: 'resultList', onscroll: ctl.onscroll, class: "tabular-results-overflow" },
            table
        );
      }

    function getGridListItem(ctl, p) {
        let type = am7model.getModel(p[am7model.jsonModelKey]);
        let ico = "question_mark";
        let gridMode = ctl.gridMode;
        let showInfo = ctl.info;
        if (type && type.icon) ico = type.icon;
        let icon = getThumbnail(ctl, p);

        let cls = (gridMode == 1 ? "image-grid-item-tile" : "image-grid-item-1");
        if (ctl.pagination.state(p).checked) {
          cls += " image-grid-item-active";
        }
        let title = "";
        let footer = "";
        if (showInfo) {
          title = m("p", { class: "card-title" }, p.name);
          if (ctl.showIconFooter) footer = m("p", { class: "card-footer" }, 'footer');
        }

        let gridCellCls = "image-grid-item-content-32";
        if (gridMode == 2) gridCellCls = "image-grid-item-1";
        let attr = "";
        let props = Object.assign(page.components.dnd.dragProps(p),{
          ondblclick: function () {
            if (ctl.containerMode) ctl.down(p);
            else ctl.open(p);
          },
          onclick: function () {
            ctl.select(p);
          },
          class: cls
        });
        attr = "[draggable]";

        return m("div" + attr, props, [title, m("div", { class: gridCellCls }, icon), footer]);
    }

    function displayIndicators(ctl) {
        let results = [];
        let pages = ctl.pagination.pages();
        if (pages.pageResults[pages.currentPage]) results = pages.pageResults[pages.currentPage].map((p, i) => {
          let cls = "material-symbols-outlined carousel-bullet";
          let ico = "radio_button_unchecked";
          if (i == pages.currentItem) {
            cls += " carousel-bullet-active";
            ico = "radio_button_checked";
          }
          return m("li", { onclick: function () { ctl.moveTo(i); }, class: "carousel-indicator" }, [
            m("span", { class: cls }, ico)
          ]);
        });
  
        return m("ul", { class: "carousel-indicators" }, [
          m("li", { onclick: function () { pagination.prev(ctl.embeddedMode || ctl.pickerMode); }, class: "carousel-indicator" }, [
            m("span", { class: "material-symbols-outlined carousel-bullet" }, "arrow_back")
          ]),
          results,
          m("li", { onclick: function () { pagination.next(ctl.embeddedMode || ctl.pickerMode); }, class: "carousel-indicator" }, [
            m("span", { class: "material-symbols-outlined carousel-bullet" }, "arrow_forward")
          ])
        ]);
      }

      function displayObjects(ctl) {
  
        let results = [];
        let pages = ctl.pagination.pages();
        let pr = pages.pageResults[pages.currentPage];
        /// let cls = 'carousel-item carousel-item-abs';
        if (pr) {
          results = pr.map((p, i) => {
            return m(page.components.object, { view: ctl.listPage, app:undefined, object: p, model: ctl.listType, active: (pages.currentItem == i), maxMode: ctl.maxMode, inner: true });
          });
        }
        return results;
  
      }

    function getGridListView(ctl) {
        let results = [];
        let gridMode = ctl.gridMode;
        let pages = ctl.pagination.pages();
        if (pages.pageResults[pages.currentPage]) results = pages.pageResults[pages.currentPage].map((p, i) => {
         return getGridListItem(ctl, p);
        });
  
        return m("div", { class: 'h-full overflow-hidden' }, m("div", { class: (gridMode == 1 ? 'image-grid-tile' : 'image-grid-5') }, results));
    }
    function getCarouselItem(ctl) {
        let pages = ctl.pagination.pages();
        let pr = ctl.results();
        let uimarkers = [];
        if (!ctl.embeddedMode || ctl.fullMode) {
            uimarkers = [
            m("span", { onclick: ctl.toggleCarouselFull, class: "carousel-full" }, [m("span", { class: "material-symbols-outlined" }, (ctl.fullMode ? "close_fullscreen" : "open_in_new"))]),
            m("span", { onclick: ctl.toggleCarouselMax, class: "carousel-max" }, [m("span", { class: "material-symbols-outlined" }, (ctl.maxMode ? "photo_size_select_small" : "aspect_ratio"))]),
            m("span", { onclick: ctl.toggleInfo, class: "carousel-info" }, [m("span", { class: "material-symbols-outlined" + (ctl.info ? "" : "-outlined") }, (ctl.info ? "info" : "info"))]),
            m("span", { onclick: function () { ctl.edit(pr[pages.currentItem]); }, class: "carousel-edit" }, [m("span", { class: "material-symbols-outlined" }, "edit")]),
            m("span", { onclick: ctl.toggleCarousel, class: "carousel-exit" }, [m("span", { class: "material-symbols-outlined" }, "close")]),
            m("span", { onclick: function () { ctl.move(-1); }, class: "carousel-prev" }, [m("span", { class: "material-symbols-outlined" }, "arrow_back")]),
            m("span", { onclick: function () { ctl.move(1); }, class: "carousel-next" }, [m("span", { class: "material-symbols-outlined" }, "arrow_forward")]),
            ];
        }
        return m("div", { class: "carousel" }, [
            m("div", { class: "carousel-inner" },
            [
                displayObjects(ctl),
                uimarkers,
                displayIndicators(ctl)
            ]
            )
        ]);

    }

    function getCarouselView(ctl) {

        /// set the current item value to the end of the array when paginating back while in carousel mode
        // console.log("Current item: " + pages.currentItem + " / " + pagination.requesting);
        return m("div", { class: "content-outer" }, [(ctl.fullMode ? "" : m(page.components.navigation)),
        m("div", { class: "content-main" }, [
          getCarouselItem(ctl)
        ])
        ]);
      }
    

    let am7dec = {
        icon: getIcon,
        fileIcon: getFileTypeIcon,
        tabularView: getTabularView,
        polarListItem: getPolarListItem,
        polarListView: getPolarListView,
        gridListView: getGridListView,
        gridListItem: getGridListItem,
        carouselView: getCarouselView,
        carouselItem: getCarouselItem
    };

    if (typeof module != "undefined") {
        module.am7decorator = am7dec;
    } else {
        window.am7decorator = am7dec;
    }
}());