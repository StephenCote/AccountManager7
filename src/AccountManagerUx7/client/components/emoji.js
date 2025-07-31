(function () {

    let emojiLoadReq;
    let emojiDict;
    let emojiReq;
    let emojiDesc;

    let emojiCat;
    let emojiSubCat;
    let emojiResults = [];

    function searchEmojis(e) {
        let search = e.target.value.trim().toLowerCase();
        emojiCat = undefined;
        if(search.length == 0){
            emojiResults = [];
            m.redraw();
            return;
        }
        findEmojis(search).then((results) => {
            emojiResults = results.map((o) => {
                return emojiMenuButton(o);
            });
            if (emojiResults.length == 0) {
                emojiResults = [m("div", { class: "context-menu-item" }, "No emojis found")];
            }
            m.redraw();
        });
    }

    function emojiMenuButton(o){
        return m("button", {
            class: "context-menu-item text-left",
            onclick: function () {
                page.navigable.menu("#emojiContextMenu");
                navigator.clipboard.writeText(o.emoji);
                page.toast("info", o.emoji + " copied to clipboard", 2000);
            }
        }, [
            m("span", { class: "emoji" }, o.emoji),
            m("span", { class: "ml-2" }, o.name)
        ]);
    }

    function emojiCategories(){
        let ret = [];
        if(!emojiLoadReq && !emojiDict){
            emojiLoadReq = true;
            loadEmojiDict().then(() => {
                m.redraw();
            });
            return ret;
        };
        if(emojiCat){
            ret.push(m("button", {class: "context-menu-item text-left", onclick: function () { emojiCat = undefined; emojiSubCat = undefined; m.redraw(); }}, [
                m("span", { class: "ml-2" }, emojiCat)
            ]));
            if(emojiSubCat){
                ret.push(m("button", {class: "context-menu-item text-left", onclick: function () { emojiSubCat = undefined; m.redraw(); }}, [
                    m("span", { class: "ml-2" }, emojiSubCat)
                ]));
            }
        }
        
        if(emojiDict){
            let ejs = (emojiCat ? emojiDict[emojiCat] : emojiDict);
            if(emojiSubCat){
                ejs = ejs[emojiSubCat];
            }
            for(let e in ejs){
                let itm = e;
                if(emojiSubCat){
                    ret.push(emojiMenuButton(ejs[e]));
                }
                else{

                    ret.push(m("button", {
                        class: "context-menu-item text-left",
                        onclick: function () {
                            //page.navigable.menu(".context-menu-48");
                            if(emojiCat){
                                emojiSubCat = e;
                            }
                            else{
                                emojiCat = e;
                                emojiSubCat = undefined;
                            }
                            
                            m.redraw();
                        }
                    }, [
                        m("span", { class: "ml-2" }, e)
                    ]));
                }
            }
        }
        return ret;
    }

    function emojiContextButton() {
        let ico = m("span", { class: "material-symbols-outlined" }, "add_reaction");
        return m("button", { id: "emojiContextMenuButton", class: "context-menu-button" }, [ico]);
    }
    


    function renderEmojiButton() {
        return m("div", { class: "context-menu-container" }, [
            emojiContextButton(),
            m("div", { id: "emojiContextMenu", class: "transition transition-0 context-menu-48" }, [
                m("input", {type: "text", class: "ml-0 mr-0 context-menu-input text-field", placeholder: "Search emojis", oninput: searchEmojis}),
                (emojiResults && emojiResults.length > 0 ? emojiResults : emojiCategories())
            ])
        ])
    }

    function markdownEmojis(text) {
      if(!emojiDict){
        loadEmojiDict().then(() => {
            m.redraw();
        });
        return text;
      }

      let ea = Array.from(new Set(extractEmojis(text)));
      for(let i = 0; i < ea.length; i++){
        let desc = findEmojiDesc(ea[i]);
        let txt = desc?.alt || desc?.description;
        if(!txt) txt = findEmojiName(ea[i]);

        let lbl = "[" + ea[i] + "](## \"" + txt + "\")"; 
        text = text.replaceAll(ea[i], lbl);
      }
      return text;
    }

    async function loadEmojiDict(){
        if(!emojiDict){
            if(!emojiReq){
                emojiReq = true;
                let edd = await m.request({ method: 'GET', url: "/common/emojiDesc.json", withCredentials: true });
                if(edd && edd.length > 0){
                    emojiDesc = edd;
                }
                let ed = await m.request({ method: 'GET', url: "/common/emojis.categories.json", withCredentials: true });
                if(ed && ed.emojis){
                    emojiDict = ed.emojis;
                }

            }
        }

    }
    function findEmojiDesc(emoji) {
        let desc;
        if(emojiDesc){
            let ad = emojiDesc.filter(e => e.emoji === emoji);
            if(ad && ad.length > 0){
                desc = ad[0];
            };
        }
        return desc;
    }

    function findEmojiName(targetEmoji) {
        for (const categoryName in emojiDict) {
            const subCategories = emojiDict[categoryName];
            for (const subCategoryName in subCategories) {
                const emojiList = subCategories[subCategoryName];
                const foundEmoji = emojiList.find(emoji => emoji.emoji === targetEmoji.trim());
                if (foundEmoji) {
                    return foundEmoji.name;
                }
            }
        }
        return;
    }

    async function findEmojis(targetEmoji) {
        await loadEmojiDict();
        let be = extractEmojis(targetEmoji).length > 0;
        let rea = !be ? "emoji" : "name";
        let ar = [];
        let et = targetEmoji.trim();
        for (const categoryName in emojiDict) {
            const subCategories = emojiDict[categoryName];
            for (const subCategoryName in subCategories) {
                const emojiList = subCategories[subCategoryName];
                const foundEmoji = emojiList.find(emoji => (be && emoji.emoji === et) || (!be && emoji.name.toLowerCase().indexOf(et.toLowerCase()) > -1));
                if (foundEmoji) {
                    //return foundEmoji[rea];
                    ar.push({
                        name: foundEmoji.name,
                        emoji: foundEmoji.emoji,
                        category: categoryName,
                        subCategory: subCategoryName
                    });
                }
            }
        }
          
        return ar;
    }

    function extractEmojis(text) {
        if (!text || typeof text !== 'string') {
            return [];
        }
        const emojiRegex = /\p{Emoji_Presentation}|\p{Extended_Pictographic}/gu;
        const matches = text.match(emojiRegex);
        return matches || [];
    }

    async function exportEmojis(delim) {
        await loadEmojiDict();
        // Define the header row for the TSV file
        const headers = [
            'Category',
            'Subcategory',
            'Emoji',
            'Name',
            'Keywords',
            'Unicode'
        ];
        let tsvContent = headers.join(delim) + '\n';

        // Iterate over each category in the JSON data
        for(let category in emojiDict){
            //const categoryName = category.name || 'N/A';
            for(let subCategory in emojiDict[category]){
            // Iterate over each emoji within the category
                let ems = emojiDict[category][subCategory];

                if (Array.isArray(ems)) {
                    ems.forEach(emoji => {
                        const emojiChar = emoji.emoji || '';
                        const name = emoji.name || 'N/A';
                        // Join keywords with a comma, or provide 'N/A' if none exist
                        const keywords = Array.isArray(emoji.keywords) ? emoji.keywords.join(', ') : 'N/A';
                        const unicode = emoji.unicode || 'N/A';

                        // Create a row array and join with tabs
                        const row = [
                            category,
                            subCategory,
                            emojiChar,
                            name,
                            keywords,
                            unicode
                        ];
                        tsvContent += row.join('\t') + '\n';
                    });
                }
            }
        };

        return tsvContent;
    }

    let emoji = {
        menuButton: renderEmojiButton,
        markdownEmojis,
        findEmojis,
        findEmojiDesc,
        emojis: () => emojiDict,
        emojisDesc: () => emojiDesc,
        loadEmojiDict,
        exportEmojis,
        component: {
            
            oncreate: function (x) {
                page.navigable.addContextMenu("#emojiContextMenu", "#emojiContextMenuButton");
            },
            oninit: function(){
                page.navigable.setupPendingContextMenus();
            },
            onupdate: function(){
                page.navigable.setupPendingContextMenus();
            },
            view: function (x) {
                return renderEmojiButton();
            }

        }
    };

    page.components.emoji = emoji;

}());
