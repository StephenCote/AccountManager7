// Main Game Component

// Character Card Component
(function(){
    let entity = {};
    let fullMode = false;
    let playerState;
    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";
    let npcs = [];
    let pois = [];

    am7model.models.push(
    {
        name: "playerStates", icon: "gamepad", fields: [
            {
                name: "state",
                label: "State",
                type: 'list',
                limit: []
            }
        ]
    });

am7model.forms.playerStates = {
    label: "Player States",
    fields: {
      state: {
        layout: 'full',
        format: 'select'
        /*,
        field: {
          format: 'picker',
          pickerType: "olio.playerState",
          pickerProperty: {
            selected: "{object}",
            entity: "state"
          },
          label: "State"
        }
*/
      }
    }
  };

  am7model.forms.playerState = {
    label: "Player State",
    fields: {
     name: {
        layout: 'third'
      },
      character: {
        layout: 'third',
        format: 'picker',
        field: {
          format: 'picker',
          pickerType: "olio.charPerson",
          pickerProperty: {
            selected: "{object}",
            entity: "character",
            path: gridPath + "/Population"
          },
          label: "Character"
        }
      }
    }
  };

let popped = false;
async function repopPlayerState(){
    popped = false;
    popPlayerState(true);
}
async function popPlayerState(bRepop){
    /// || (!bRepop && playerState)
    if(popped){
        return;
        
    }
    popped = true;
    let entity = am7model.newPrimitive("olio.playerState");
    entity.name = "New Game - " + Date.now();
    let cinst = am7model.prepareInstance(entity, am7model.forms.playerState);


    let sentity = am7model.newPrimitive("playerStates");
    let sinst = am7model.prepareInstance(sentity, am7model.forms.playerStates);


    let cfg = {
        label: "New Player State",
        entityType: "olio.playerState",
        size: 75,
        data: {entity, inst: cinst},
        confirm: async function (data) {
            console.log(data);
            if(!data || !data.entity.character){
                page.toast("error", "Character was not selected");
                return;
            }
            let cdat = await page.createObject(data.entity);
            if(!cdat){
                page.toast("error", "Failed to save player state");
            }
            else{
                page.toast("success", "Saved player state");
            }
            page.components.dialog.endDialog();
        },
        cancel: async function (data) {
            page.components.dialog.endDialog();
        }
    };
    let dir = await page.makePath("auth.group", "DATA", "~/Player States");
    let aps = (dir ? await am7client.list("olio.playerState", dir.objectId, null, 0, 0) : []);
    if(aps.length && !bRepop){
        workWithState(aps[0]);
        return;
    }
    am7model.getModelField("playerStates", "state").limit  =  ["New", ...aps.map((c) => { return c.name; })];
    sinst.api.state("New");
    let bNew = false;
    let scfg = {
        label: "Pick Player State",
        entityType: "playerStates",
        size: 75,
        data: {entity: sentity, inst: sinst},
        confirm: async function (data) {
            if(!data || !data.entity.state){
                page.toast("error", "State was not selected");
                return;
            }
            page.components.dialog.endDialog();

            let st = data.entity.state;

            if(st.toLowerCase() == "new"){
                page.components.dialog.setDialog(cfg);
                return;
            }
            let as = aps.filter(a => a.name.toLowerCase() == st.toLowerCase());
            if(as.length){
                workWithState(as[0]);
            }
            else{
                page.toast("error", "Failed to identify state");
            }
        },
        cancel: async function (data) {
            page.components.dialog.endDialog();
        }
    };

    if(aps.length){
        workWithState(aps[0]);
        //page.components.dialog.setDialog(scfg);
    }   
    else{
        page.components.dialog.setDialog(cfg);
    }
    
    
}

async function workWithState(o){
    //console.log("Work with", o);
    playerState = await am7client.getFull("olio.playerState", o.objectId);
    am7model.applyModelNames(playerState);
    console.log(playerState);
    /// Need to get full state information for all characters, events, etc

}

//let g = await page.findObject("auth.group", "data", "/Olio/Universes/My Grid Universe/Worlds/My Grid World/Population")





// Map View Component
const MapView = {
    view: function() {
        return m(".grid.grid-cols-10.gap-1.p-4",
            Array(100).fill(0).map(() => m(".w-6.h-6.bg-gray-600.rounded"))
        );
    }
};

        function getOuterView(){

            return m("div",{class : "content-outer"},[
                (!entity || fullMode ? "" : m(page.components.navigation)),
                m("div",{class : "content-main"},[
                    modelPanel()
            ])
           ]);
            
        }
        function getInnerView(){
            console.log(playerState);
            return m("div", {class: "flex flex-col h-screen"}, [
                m("div", {class: "flex flex-1"}, [
                    // 3D Crawl View (Top-Left)
                    m("div", {class: "flex-1 border-r border-b border-gray-600 flex flex-col"}, [
                        m("h2", {class: "text-lg p-2 bg-gray-700"}, "View"),
                        m("div", {class: "flex-1 flex items-center justify-center bg-black"}, [
                            m("p", {class: "text-gray-400"}, "3D Viewport")
                        ]),
                        m(".flex.justify-center.p-4.bg-gray-700", [
                            m("button.bg-gray-600.hover:bg-gray-500.text-white.font-bold.py-2.px-4.rounded.mx-2", "Forward"),
                            m("button.bg-gray-600.hover:bg-gray-500.text-white.font-bold.py-2.px-4.rounded.mx-2", "Left"),
                            m("button.bg-gray-600.hover:bg-gray-500.text-white.font-bold.py-2.px-4.rounded.mx-2", "Right")
                        ])
                    ]),
                    // Map View (Top-Right)
                    m("div", {class: ".w-1/3.border-b.border-gray-600.flex.flex-col"}, [
                        m("h2.text-lg.p-2.bg-gray-700", "Map"),
                        m(MapView)
                    ])
                ]),

                // Card Stacks (Bottom)
                m("div", {class: "h-1/3 border-t border-gray-600 flex"}, [
                    // Player Card Stack
                    m("div", {class: "flex-1 border-r border-gray-600 flex flex-col"}, [
                        m("h2.text-lg.p-2.bg-gray-700", "Player & Companions"),
                        m(CardStack, {
                            characters: [playerState?.character, playerState?.companion],
                            isPlayerStack: true
                        })
                    ]),
                    // NPC Card Stack
                    m(".flex-1.flex.flex-col", [
                        m("h2.text-lg.p-2.bg-gray-700", "NPCs, Hostiles, & POIs"),
                        m("p", {
                            characters: [...npcs, ...pois]
                        })
                    ])
                ])
        
            ]);
        }

        let status = "Status";
        let data;
        let label = "Label";
        let infoLabel = "Info";
        let infoStatus = "I Status";
        let icon = "gamepad";

    function modelInfo(){
        if(!data) return "";
        let ino = data.info;

        if(ino.step === 'setup'){
            let aR = [];
            if(ino.per){
                aR.push(m("li",m("div", {onclick: function(){ chooseInfo(ino.per);}, class: 'result-item' + (ino.selected && ino.selected.objectId == ino.per.objectId ? " result-item-active" : "")},  ino.per.name + " (" + ino.per.gender + ") (" + getAge(ino.per) + " years) (" + AM6Client.getAttributeValue(ino.per, "alignment") + ") (" + AM6Client.getAttributeValue(ino.per, "trade") + ")")));
            }
            if(ino.pers) ino.pers.forEach((p)=>{
                aR.push(m("li",m("div", {onclick: function(){ chooseInfo(p);}, class: 'result-item'  + (ino.selected && ino.selected.objectId == p.objectId ? " result-item-active" : "")}, p.name + " (" + p.gender + ")" + (AM6Client.getAttributeValue(p, "deceased") === 'true' ? " (dead)" : "") + " (" + getAge(p) + " years) (" + AM6Client.getAttributeValue(p, "alignment") + ") (" + AM6Client.getAttributeValue(p, "trade") + ")")));
            });
            return m("ul", {"class" : "list-results-overflow"}, aR);
        }
        return "";
    }
    function modelGame(){
        return "";
    }

// Card Stack Component
let CardStack = {
    view: function(vnode) {

        const characters = vnode.attrs.characters || [];
        const isPlayerStack = vnode.attrs.isPlayerStack;
        //console.log(characters);
        return m("div", {class: "flex-1 overflow-y-auto"},
            characters.filter(char => char != undefined).map(char => m(CharacterCard, {
                character: char,
                isPlayer: isPlayerStack
            }))
        );
    }
};

let CharacterCard = {
    view: function(vnode) {
        const character = vnode.attrs.character;
        const isPlayer = vnode.attrs.isPlayer;

        return m("div", {class: 'p-2 m-2 rounded-lg shadow-md flex items-center ' + isPlayer ? 'bg-blue-800' : 'bg-gray-700'}, [
            
            m("div", {class: 'mr-4'}, [
                am7decorator.icon(character)
                // Placeholder for character icon
                /*
                m("svg", {class: "w-12 h-12 text-gray-400"}, {
                    fill: "currentColor",
                    viewBox: "0 0 20 20"
                }, [
                    m("path", {
                        d: "M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z",
                        "clip-rule": "evenodd",
                        "fill-rule": "evenodd"
                    })
                ])
                */
            ]),
            
            m("div", [
                m("h3.font-bold", character?.name),
                m("p.text-sm", 'Health: ${character?.statistics?.health}')
            ])
                
        ]);
    }
};

function getFooter(){
    return [
        m("button", { class: "flyout-button", onclick: repopPlayerState },
            [m("span", { class: "material-symbols-outlined material-icons-24" }, "add"), "New"]
        )
    ];
}

  function modelPanel(){
        return [
            m("div", {class: "flex-grow overflow-hidden flex bg-white border border-gray-200 dark:bg-black dark:border-gray-700 dark:text-gray-200"}, [
                m("div", {class: "flex flex-col w-full overflow-hidden p-0"}, [
                    m("div", {class: "flex-1 overflow-hidden"}, [
                        m("div", {class: "max-h-full h-full flex"}, [
                            m("div", {class: "flex flex-col w-1/4 p-4 overflow-y-auto mx-auto"},[
                                "left",
                                getInnerView()
                            ]),
                            m("div", {class: "flex flex-col w-1/2 p-4 overflow-y-auto mx-auto overflow-hidden"}, [
                                "center"
                            ]),
                            m("div", {class: "flex flex-col w-1/4 p-4 overflow-y-auto mx-auto"}, [
                                "right"
                            ])
                        ])
                    ]),
                    m("div", {class: "bg-white px-1 py-1 flex items-center justify-between border-t border-gray-200 dark:border-gray-700 dark:bg-black"}, [
                        getFooter()
                    ])
                ])

            ])
        ];
    /*
        return [m("div",{class: "results-overflow grid grid-cols-2 gap-4"},
                    m("div",{class: "panel-card"},
                        m("p", {"class" : "card-title"}, [
                            m("span", {class : "material-icons material-icons-cm mr-2"}, icon),
                            label + status
                        ]),
                        m("div", {"class": "card-contents"}, modelGame())
                    ),
                    m("div",{class: "panel-card"},
                    m("p", {"class" : "card-title"}, [
                        m("span", {class : "material-icons material-icons-cm mr-2"}, icon),
                        infoLabel + infoStatus
                    ]),
                    m("div", {"class": "card-contents"}, modelInfo())
                )

            ),
            m("div",{class: "result-nav-outer"},[
                m("div",{class: "result-nav-inner"},[
                    m("div",{class: "result-nav tab-container"},[
                        page.iconButton("button", "west", "", moveWest),
                        page.iconButton("button", "north", "", moveNorth),
                        page.iconButton("button", "south", "", moveSouth),
                        page.iconButton("button", "east", "", moveEast),
                        page.iconButton("button", "hourglass_empty", "", wait)
                    ])
                ])
            ]),
        ];
        */
    }
    function wait(){

    }
    function moveEast(){

    }
    function moveWest(){

    }
    function moveNorth(){

    }
    function moveSouth(){

    }

let cardGame = {
    oninit: function(){
        popPlayerState();
    },
    view: function() {
        return [getOuterView(), page.components.dialog.loadDialog(), page.loadToast()];
    }
};
page.views.cardGame = cardGame;
}());
