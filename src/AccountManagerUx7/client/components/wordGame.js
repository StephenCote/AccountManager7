// Main Game Component

// Word Game
(function () {
    let entity = {};
    let fullMode = false;
    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

    // *** MODIFICATION START ***
    // Array of common "glue" words for poetry.
    const commonWords = [
        'a', 'an', 'the', 'this', 'that', 'these', 'those', 'my', 'your', 'its', 'our', 'their', 'some', 'any', 'every', 'each', 'no',
        'of', 'in', 'on', 'for', 'to', 'from', 'with', 'by', 'at', 'as', 'like', 'into', 'unto', 'over', 'under', 'above', 'below', 'beneath', 'beside', 'between', 'beyond', 'through', 'against', 'without', 'within', 'before', 'after', 'since', 'until',
        'I', 'me', 'you', 'he', 'him', 'she', 'her', 'it', 'we', 'us', 'they', 'them', 'who', 'what', 'where', 'when', 'why', 'how',
        'and', 'but', 'or', 'nor', 'so', 'yet', 'for', 'if', 'then', 'than', 'while', 'because', 'though',
        'is', 'am', 'are', 'was', 'were', 'be', 'being', 'been', 'has', 'have', 'had', 'do', 'does', 'did', 'will', 'would', 'shall', 'should', 'can', 'could', 'may', 'might', 'must'
    ];

    // Function to shuffle and pick a new set of common words.
    function randomizeCommonWords() {
        const shuffled = commonWords.sort(() => 0.5 - Math.random());
        data.activeCommonWords = shuffled.slice(0, 8); // Pick 8 random words
    }
    // *** MODIFICATION END ***


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

    function newGame() {

    }

    function getFooter() {
        return [
            m("button", { class: "flyout-button", onclick: newGame },
                [m("span", { class: "material-symbols-outlined material-icons-24" }, "add"), "New"]
            )
        ];
    }

    function handleGridDragStart(e, word, rowIndex, colIndex) {
        e.stopPropagation();
        const payload = {
            word: word,
            source: 'board',
            rowIndex: rowIndex,
            colIndex: colIndex
        };
        e.dataTransfer.setData("text/plain", JSON.stringify(payload));
    }

    function handleDrop(e, targetRowIndex, targetColIndex) {
        e.preventDefault();
        e.stopPropagation();
        const payload = JSON.parse(e.dataTransfer.getData("text/plain"));

        if (data.board[targetRowIndex][targetColIndex]) {
            return;
        }

        data.board[targetRowIndex][targetColIndex] = payload.word;

        if (payload.source === 'left') {
            data.left.splice(payload.index, 1);
        } else if (payload.source === 'right') {
            data.right.splice(payload.index, 1);
        } else if (payload.source === 'board') {
            data.board[payload.rowIndex][payload.colIndex] = null;
        }
        // *** MODIFICATION START ***
        // No removal logic is needed for 'common' source, as they are a static pool.

        // Re-randomize the common words list after any successful drop.
        randomizeCommonWords();
        // *** MODIFICATION END ***

        m.redraw();
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
                            m("div", { class: "flex flex-col w-3/5 p-2 overflow-auto mx-auto" }, [
                                m("h2.text-center", "Word Battle"),
                                // *** MODIFICATION START ***
                                // Container for the randomly selected common words.
                                m("div", { class: "menu-buttons-spaced my-2 justify-center" },
                                    data.activeCommonWords.map(word =>
                                        m("button[draggable]", {
                                            class: "menu-button",
                                            ondragstart: (e) => {
                                                const payload = {
                                                    // Ensure the word object has a 'name' property to match other words.
                                                    word: { name: word },
                                                    source: 'common'
                                                };
                                                e.dataTransfer.setData("text/plain", JSON.stringify(payload));
                                            }
                                        }, word)
                                    )
                                ),
                                // *** MODIFICATION END ***
                                m("table.w-full.border-collapse.mt-2.table-fixed", [
                                    m("tbody",
                                        data.board.map((row, rowIndex) =>
                                            m("tr", { key: rowIndex },
                                                row.map((slot, colIndex) =>
                                                    m("td.border.border-gray-500.h-12.p-1",
                                                        {
                                                            ondragover: (e) => e.preventDefault(),
                                                            ondrop: (e) => handleDrop(e, rowIndex, colIndex)
                                                        },
                                                        slot ? m("div.bg-blue-200.text-blue-800.rounded.p-1.text-center.text-sm.cursor-move", {
                                                            draggable: true,
                                                            title: slot.definition,
                                                            ondragstart: (e) => handleGridDragStart(e, slot, rowIndex, colIndex)
                                                        }, slot.name)
                                                            : m("div.w-full.h-full")
                                                    )
                                                )
                                            )
                                        )
                                    )
                                ]),
                                m("button", {class: "flyout-button text-center"}, [m("span",{class: "material-symbols-outlined material-icons-24"}, "delete"), "Trash"])
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
    function keyControl(e) {



    }
    function start() {
        tryStart();
    }
    function stop() {
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
        board: Array(8).fill(null).map(() => Array(10).fill(null)),
        // *** MODIFICATION START ***
        // This array holds the currently displayed common words.
        activeCommonWords: [],
        // *** MODIFICATION END ***
        left: [],
        right: []
    };

    function leftWords() {
        return data.left.map((word, index) => m("button[draggable]", {
            class: "flyout-button",
            title: word.definition,
            ondragstart: (e) => {
                const payload = {
                    word: word,
                    source: 'left',
                    index: index
                };
                e.dataTransfer.setData("text/plain", JSON.stringify(payload));
            }
        }, word.name));
    }

    function rightWords() {
        return data.right.map((word, index) => m("button[draggable]", {
            class: "flyout-button",
            title: word.definition,
            ondragstart: (e) => {
                const payload = {
                    word: word,
                    source: 'right',
                    index: index
                };
                e.dataTransfer.setData("text/plain", JSON.stringify(payload));
            }
        }, word.name));
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

    async function prepareWords() {
        /// gridPath + "/Words"
        console.log("Prepare words ...");
        let grp = await page.findObject("auth.group", "data", "/Library/Dictionary");
        let q3 = am7client.newQuery("data.wordNet");
        q3.field("groupId", grp.id);
        q3.range(0, 20);
        q3.entity.request = ["name", "groupId", "organizationId", "type", "definition"];
        q3.entity.sortField = "random()";
        let l3 = await page.search(q3);
        if (!l3 || !l3.results) {
            page.toast("error", "No word results");
            return;
        }
        let res = l3.results.length / 2;
        data.left = l3.results.slice(0, res);
        data.right = l3.results.slice(res);
        //console.log(grp.id, l3);
    }

    let isSetup = false;
    async function setup() {
        console.log("Setup");
        // *** MODIFICATION START ***
        // Initialize the first set of common words when the game starts.
        randomizeCommonWords();
        // *** MODIFICATION END ***
        prepareWords();
    }

    let wordGame = {
        keyHandler: keyControl,
        scoreCard: getScoreCard,

        oninit: function () {
            setup();
        },
        view: function () {
            return modelPanel()
        }
    };
    page.components.wordGame = wordGame;
}());
