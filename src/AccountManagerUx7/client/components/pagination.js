(function(){

    function newPaginationControl(){
      let requesting = false;

      let entity = {
          listFilter : ""
      };

      function newPagination(containerType){
          return {
              navigateByParent : false,
              listSystem: false,
              startRecord : 0,
              recordCount : 10,
              displayCount : 0,
              totalCount : 0,
              counted : false,
              currentPage : -1,
              currentItem : 0,
              pageCount : 0,
              pageResults : [],
              pageState : {},
              resultType : null,
              filter : null,
              containerType : (containerType || "auth.group"),
              containerSubType : null,
              containerId : null,
              container : null,
              id : page.uid()
          };
      }
      let pages = newPagination();

      function getSearchQuery(){

          let q = am7client.newQuery(pages.resultType);
          q.entity.request = getRequestFields(pages.resultType);
          let qf = q.field("name", pages.filter);
          qf.comparator = "like";
          q.recordCount = pages.recordCount;
          q.startRecord = pages.startRecord;
          q.sortField = "name";
          q.order = "asc";
          return q;
      }
      
      function handleMemberList(v){
        let aL = v || [];
        pages.pageResults = [];
        pages.counted = true;
        requesting = false;
        pages.totalCount = aL.length;
        pages.pageCount = Math.ceil(pages.totalCount / pages.recordCount);
        for(let i = 1; i <= pages.pageCount; i++){
          let iStart = (i-1) * pages.recordCount;
          pages.pageResults[i] = aL.slice(iStart, iStart + pages.recordCount);
        }
        m.redraw();
      }

      function handleCount(v){
          console.log("Count: " + v);
          pages.pageResults = [];
          pages.counted = true;
          pages.totalCount = v || 0;

          requesting = false;
          m.redraw();
      }
      function handleList(v){

          pages.pageResults[pages.currentPage] = v;
          /// In AM7, the 'model' property may be condensed to occur in only the first result  if the remaining results are the same
          ///
          if(v.length){
            let m = v[0].model;
            for(let i = 1; i < v.length; i++){
              if(!v[i].model) v[i].model = m;
            }
          }
          requesting = false;
          m.redraw();
      }

      function listRouter(url){
          m.route.set(url);
        }
      function navNext(bRedrawOnly){
          if(pages.currentPage < pages.pageCount){
              if(bRedrawOnly){
                pages.currentPage++;
                m.redraw();
              }
              else listRouter(getPageUrl(pages.currentPage + 1));
          }
      }
      function navPrev(bRedrawOnly){
          if(pages.currentPage > 1){
              if(bRedrawOnly){
                pages.currentPage--;
                m.redraw();
              }
              else listRouter(getPageUrl(pages.currentPage - 1));
          }
      }

      function updatePage(iPage){
          if(requesting) return;
          if(iPage <= 0 || iPage > pages.pageCount){
            console.debug("Invalid page: " + iPage + " / " + pages.pageCount);
            return;
          }
          pages.currentPage = iPage;
          pages.currentItem = -1;
          if(pages.pageResults[iPage]){
            //console.log("Redraw to change to received page: " + iPage);
            m.redraw();
          }

          else{
            /// filter is used for count and list through the search API, which allows for scoping to the current group and recurse through child group structures
            ///
            requesting = true;
            let sFields = getRequestFields(pages.resultType);
            //console.log(pages.resultType + " - " + pages.listSystem);
            if(pages.containerSubType != null && pages.containerSubType.match(/^(user|account|person|bucket)$/gi)){
              am7client.members(pages.containerType, pages.containerId,  pages.resultType, pages.startRecord, pages.recordCount, function(v){
                console.log(v);
                handleList(v);
              });
            }
            else if(pages.listSystem && pages.resultType.match(/^(policy\.policy|auth\.role|auth\.permission)$/gi)){
                let uType = pages.resultType.substring(0, 1) + pages.resultType.slice(1);
                let fType = uType + "s";
                if(uType.match(/y$/)) fType = uType.slice(0, uType.length - 1) + "ies";
                console.log("List " + uType + " / " + fType);
                am7client["system" + fType](function(v){
                  let i = parseInt(pages.startRecord);
                  let c = parseInt(pages.recordCount);
                  let modList = v.slice(i, i + c);
                  handleList(modList);
                });
            }
            else if(pages.resultType.match(/^request$/)){
              console.log("List Req");
              am7client.listRequests("USER", page.user.objectId, pages.startRecord, pages.recordCount, handleList);
            }
            else if(pages.filter == null){
              //console.log("List in parent: " + pages.navigateByParent);
              am7client[pages.navigateByParent ? "listInParent" : "list"](pages.resultType, pages.containerId, sFields, pages.startRecord, pages.recordCount, handleList);
            }
            else{
              //console.log("Do search ...");
              am7client.search(getSearchQuery(), handleList);
            }
          }
      }

      /// TODO: Replace this with the model 'query' guidance
      ///
      function getRequestFields(type){
        return am7model.queryFields(type);
      }

      function stopPaginating(){
        pagination.paginating = false;
        pages = newPagination();
      }

      function updateList(type, containerId, navigateByParent, filter, startRecord, recordCount, bSystem){
          if(requesting) return;
          pagination.paginating = true;

          //let type = m.route.param("type");
          //let containerId = m.route.param("objectId");

          entity.listFilter = filter || ""; //decodeURI(m.route.param("filter") || "");
          if(
            ((pages.filter == null && entity.listFilter.length) || (pages.filter != null && pages.filter != entity.listFilter))
            ||
            (pages.containerId != null && pages.containerId != containerId)
            ||
            (!isNaN(recordCount) && recordCount > 0 && pages.recordCount != recordCount)
          ){
            // console.log("Reset pagination");
            pages = newPagination();
            pages.recordCount = (!isNaN(recordCount) && recordCount > 0 ? recordCount : pages.recordCount);
            pages.filter = (entity.listFilter.length ? entity.listFilter : null);
          }
          pages.navigateByParent = navigateByParent;
          pages.listSystem = bSystem;
          if(type.match(/^(system\.user|auth\.permission|auth\.role|auth\.request|auth\.control)$/gi) || navigateByParent) pages.containerType = type;
          pages.resultType = type;
          pages.containerId = containerId;
          
          /// Need to get the container to understand the subType, such as GROUP/DATA vs GROUP/BUCKET
          if(containerId && pages.container == null){
            requesting = true;
            page.openObject(pages.containerType, pages.containerId).then( v=> {
              pages.container = v;
              requesting = false;
              if(v != null){
                // console.log("Redraw to load container object");
                pages.container = v;
                if(pages.containerType.match(/^auth\.group$/gi)){
                  pages.containerSubType = pages.container.type;
                }
                m.redraw();
              }
              else{
                console.error("Error loading container: " + pages.containerType + " / " + pages.containerId);
              }
            });
          }
          else if(!pages.counted){
            //console.log("COUNTING...");
            requesting = true;
            /// Memberships are not currently paginated, so the whole set needs to be loaded and then paginated in memory
            /// the participationList/Count API should be used here instead
            ///
            console.log(pages.containerType, pages.containerSubType, pages.containerId, pages.resultType);
            if(pages.containerSubType != null && pages.containerSubType.match(/^(system\.user|identity\.account|identity\.person|bucket)$/gi)){
              //console.log("Obtain bucket members...");
              //am7client.members(pages.containerType, pages.containerId, type, 0, startRecord || 0, pages.recordCount, handleMemberList);
              am7client.countMembers(pages.containerType, pages.containerId, type, handleCount);
            }
            else if(bSystem && type.match(/^(policy\.policy|auth\.role|auth\.permission)$/gi)){
              let uType = type.substring(0, 1) + type.slice(1);
              let fType = uType + "s";
              if(uType.match(/y$/)) fType = uType.slice(0, uType.length - 1) + "ies";

              am7client["system" + fType](function(v){
                handleCount(v.length);
              });
            }
            else if(type.match(/^request$/gi)){

              am7client.countRequests("USER", page.user.objectId, function(v){
                handleCount(v);
              });
            }
            else if(pages.filter == null){
              //console.log("Count in parent: " + navigateByParent);
              am7client[navigateByParent ? "countInParent" : "count"](type, containerId, handleCount);
            }
            else{
              let req = getSearchQuery();
              /// Set record count to 0 because searchCount operates differently than the regular count, counting authorized identifiers outside of a view versus counting rows
              ///
              req.recordCount = 0;
    
              am7client.searchCount(req, handleCount);
            }
    
          }
          else{
            if(pages.totalCount > 0) pages.pageCount = Math.ceil(pages.totalCount / pages.recordCount);
    
            //let startRecord = parseInt(m.route.param("startRecord"));
    
            //let recordCount = parseInt(m.route.param("recordCount"));
            if(isNaN(startRecord)) startRecord = 0;
            if(isNaN(recordCount)) recordCount = pages.recordCount;
            pages.startRecord = startRecord;
            pages.recordCount = recordCount;
    
            let usePage = 0;
            if(recordCount > 0){
              usePage = 1;
              if(startRecord > 1) usePage = Math.ceil(startRecord / pages.recordCount) + 1;
              if(usePage != pages.currentPage){
                // console.log("UPDATING PAGE LIST");
                updatePage(usePage);
              }
            }
    
          }
      }
      function doFilter(app){
          let url = page.getRawUrl() + "?startRecord=0";
          pages = newPagination();
          if(entity.listFilter != null && entity.listFilter.length)
          {
              url += "&filter=" + encodeURI(entity.listFilter);
              pages.filter = entity.listFilter;
          }
          listRouter(url);
      }
    
      function getPageUrl(iPage, iCount){
          if(isNaN(iPage)) iPage = pages.currentPage;
          if(isNaN(iCount)) iCount = pages.recordCount;
          let url = page.getRawUrl();
          let filter = "";
          if(pages.filter != null) filter = "&filter=" + encodeURI(pages.filter);
          url += "?startRecord=" + ((iPage-1) * iCount) + filter + "&recordCount=" + iCount;
          let stem = page.uriStem();
          
          return url + (stem.length ? "&" + stem : "");
      } 
      function paginationControlBar(){
          return m("div",{class: "result-nav-outer"},[
              m("div",{class: "result-nav-inner"},[
                  m("div",{class: "count-label"},"Showing " + pages.currentPage + " of " + pages.pageCount + " (" + pages.totalCount + ")"),
                  m("nav",{class: "result-nav"},[
                    pageButtons()
                  ])
              ])
          ]);
      }
      function pageButtons(bRedrawOnly){
        
          let pageButtons = [];

          pageButtons.push(paginationButton("previous button","keyboard_double_arrow_left","", function(){ listRouter(getPageUrl(1));}));
          pageButtons.push(paginationButton("button","chevron_left","", function(){navPrev();}));

          let bMore = (pages.pageCount > 7)
          let left = pages.currentPage - 3;
    
          if(left < 1) left = 1;
          
          let right = pages.currentPage + 3;
          if(right < 7) right = 7;
          right = Math.min(pages.pageCount,right);
    
          if(pages.currentPage > 3 && (right-left) < 7) {
            left = Math.max(right - 6,1);
          }
          let maxI = Math.max(right,7);
          for(let i = left; i <= maxI; i++){
            let style = "page";
            let fHandler;
            if(i > pages.pageCount){
              pageButtons.push(paginationButton("page",null, ""));
              continue;
            }
            if(pages.currentPage == i) style = "page-current";
            else fHandler = function(){ 
              listRouter(getPageUrl(i));
            };
            pageButtons.push(paginationButton(style, null, i, fHandler));
            
          };
          pageButtons.push(paginationButton("button","chevron_right","", function(){navNext();}));
          pageButtons.push(paginationButton("next button","keyboard_double_arrow_right","", function(){ listRouter(getPageUrl(pages.pageCount));}));
          return pageButtons;
      }
      
      function getPageState(o){
          if(!pages.pageState[o.objectId]){
            pages.pageState[o.objectId] = {checked:false};
          }
          return pages.pageState[o.objectId];
      }
      
      function paginationButton(sClass, sIco, sLabel, fHandler){
          return page.iconButton(sClass, sIco, sLabel, fHandler);
      }

      let pagination = {
          requesting: requesting,
          entity : entity,
          paginating : false,
          new : function(){
              pages = newPagination();
              return pages;
          },
          pages : function(){ return pages; },
          pageBarView : paginationControlBar,
          update : updateList,
          searchQuery : getSearchQuery,
          handleCount : handleCount,
          handleList : handleList,
          next : navNext,
          prev : navPrev,
          filter : doFilter,
          changePage : updatePage,
          url : getPageUrl,
          pageButtons : pageButtons,
          state : getPageState,
          button : paginationButton,
          //routeToResult : routeToResultObject,
          stop : stopPaginating

      };
      return pagination;
    }
    page.pagination = newPaginationControl; //pagination;
}())