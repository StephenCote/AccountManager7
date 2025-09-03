// Main Game Component

// Word Game
(function () {
    let entity = {};
    let fullMode = false;
    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

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


    const MapView = {
        view: function () {
            return m(".grid.grid-cols-10.gap-1.p-4",
                Array(100).fill(0).map(() => m(".w-6.h-6.bg-gray-600.rounded"))
            );
        }
    };

    function getOuterView() {

        return m("div", { class: "content-outer" }, [
            (!entity || fullMode ? "" : m(page.components.navigation)),
            m("div", { class: "content-main" }, [
                modelPanel()
            ])
        ]);

    }



    let icon = "gamepad";

    function newGame(){

    }

    function getFooter() {
        return [
            m("button", { class: "flyout-button", onclick: newGame },
                [m("span", { class: "material-symbols-outlined material-icons-24" }, "add"), "New"]
            )
        ];
    }

    function modelPanel() {
        return [
            m("div", { class: "flex-grow overflow-hidden flex bg-white border border-gray-200 dark:bg-black dark:border-gray-700 dark:text-gray-200" }, [
                m("div", { class: "flex flex-col w-full overflow-hidden p-0" }, [
                    m("div", { class: "flex-1 overflow-hidden" }, [
                        m("div", { class: "max-h-full h-full flex" }, [
                            m("div", { class: "flex flex-col w-1/4 p-4 overflow-y-auto mx-auto" }, [
                                m("h3", "Player 1"),
                                leftWords()
                                
                            ]),
                            m("div", { class: "flex flex-col w-1/2 p-4 overflow-y-auto mx-auto overflow-hidden" }, [
                                m("h2", "Word Battle")
                            ]),
                            m("div", { class: "flex flex-col w-1/4 p-4 overflow-y-auto mx-auto" }, [
                                m("h3", "Player 2"),
                                rightWords()
                            ])
                        ])
                    ]),
                    m("div", { class: "bg-white px-1 py-1 flex items-center justify-between border-t border-gray-200 dark:border-gray-700 dark:bg-black" }, [
                        getFooter()
                    ])
                ])

            ])
        ];

    }
 function keyControl(e){
    
        var i = e.keyCode;
        /* Move a piece left */
        if(i==37 || i==52 || i==100)
            move(-1);
    
        /* Move a piece right */
        if(i==39 || i==54 || i==102)
            move(1);
    
        /* Drop a piece */
        if(i == 38) down(1);
        if(i==40 || i==50 || i==98)
            down();

        /* Rotate left */
        if(i==44 || i==55 || i==188 || i==101)
            rotatePiece(-1);
    
        /* Rotate right */
        if(i==46 || i==57 || i==190)
            rotatePiece(1);
    
        /* Reset */
        if(i==82 || i==114)
            clearAllNodes();
    
        /* Start */
        if(i==83 || i==115)
            tryStart();
    
    }
        function start(){
        tryStart();
    }
    function stop(){
        endGame();
    }

    let data = {
        player1: {
            name: "Player 1",
            score: 0,
            words: []
        },
        player2: {
            name: "Player 2",
            score: 0,
            words: []
        },

        left: [],
        right: []
    };

    function leftWords(){
        return data.left.map(word => m("button[draggable]", { class: "flyout-button" }, word.name));
    }
    function rightWords(){
        return data.right.map(word => m("button[draggable]", { class: "flyout-button" }, word.name));
    }

    
    function getScoreCard() {
        return m("div", { class: "result-nav-outer" }, [
            m("div", { class: "result-nav-inner" }, [
                page.iconButton("button mr-4", "start", "", start),
                m("div", { class: "count-label mr-4" }, "Level " + (data ? data.level : 1)),
                m("div", { class: "count-label mr-4" }, "Score " + (data ? data.score : 0)),
                page.iconButton("button", "stop", "", stop)
            ])
        ]);
    }

    async function prepareWords(){
        /// gridPath + "/Words"
        console.log("Prepare words ...");
        let grp = await page.findObject("auth.group", "data", "/Library/Dictionary");
        let q3 = am7client.newQuery("data.wordNet");
        q3.field("groupId", grp.id);
        q3.range(0, 20);
        q3.entity.request = ["name", "groupId", "organizationId", "type", "definition"];
        q3.entity.sortField = "random()";
        let l3 = await page.search(q3);
        if(!l3 || !l3.results){
            page.toast("error", "No word results");
            return;
        }
        let res = l3.results.length / 2;
        data.left = l3.results.slice(0, res);
        data.right = l3.results.slice(res);
        //console.log(grp.id, l3);
    }

    let isSetup = false;
    async function setup(){
        console.log("Setup");
        prepareWords();
    }

    let wordGame = {
        keyHandler: keyControl,
        scoreCard: getScoreCard,

        oninit: function () {
            setup();
        },
        view: function () {
            //return [getOuterView(), page.components.dialog.loadDialog(), page.loadToast()];
            return modelPanel()
        }
    };
    page.components.wordGame = wordGame;
}());
