// Main Game Component

// Word Game
(function () {
    let entity = {};
    let fullMode = false;
    let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

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
        data.activeCommonWords = shuffled.slice(0, 10); // Pick 10 random words
    }
    
    // --- GAME LOGIC AND STATE MANAGEMENT ---

    let data = {
        turn: 1, // 1 or 2
        turnPoints: 5,
        timerId: null,
        timeLeft: 30,
        isPaused: false,
        player1: {
            name: "Player 1",
            score: 0,
            customWord: '',
            customWordTimeout: null,
        },
        player2: {
            name: "Player 2",
            score: 0,
            customWord: '',
            customWordTimeout: null,
            autopilot: false,
        },
        board: Array(8).fill(null).map(() => Array(10).fill(null)),
        activeCommonWords: [],
        left: [], // Player 1's words
        right: [] // Player 2's words
    };
    
    function startTimer() {
        clearInterval(data.timerId);
        data.timeLeft = 30;
        data.timerId = setInterval(() => {
            if (data.isPaused) return;

            data.timeLeft--;
            if (data.timeLeft <= 0) {
                if (data.turn === 1) data.player1.score--;
                else data.player2.score--;
                switchTurn();
            }
            m.redraw();
        }, 1000);
    }

    function switchTurn() {
        data.turn = data.turn === 1 ? 2 : 1;
        data.turnPoints = 5;
        startTimer();
        
        if (data.turn === 2 && data.player2.autopilot) {
            setTimeout(runAutopilot, 1500); // Give a slight delay for the AI move
        }
    }
    
    function spendTurnPoints(points) {
        data.turnPoints -= points;
        if(data.turnPoints <= 0) {
            setTimeout(switchTurn, 200); // Short delay before switching turns
        }
    }

    function handleRefreshWords(playerNum) {
        let player = playerNum === 1 ? data.player1 : data.player2;
        let words = playerNum === 1 ? data.left : data.right;
        
        // Scoring penalties for refreshing
        if (words.length > 5) {
             player.score--;
        }
        if (words.length >= 6) {
             player.score -= 5;
        }

        prepareWords(playerNum);
    }
    
    function handleCustomWord(playerNum, value) {
        let player = playerNum === 1 ? data.player1 : data.player2;
        player.customWord = value;
        
        clearTimeout(player.customWordTimeout);
        player.customWordTimeout = setTimeout(() => {
            addCustomWord(playerNum);
        }, 5000);
    }

    function addCustomWord(playerNum) {
        let player = playerNum === 1 ? data.player1 : data.player2;
        if (player.customWord.trim()) {
            const newWord = {
                name: player.customWord.trim(),
                isCustom: true,
                definition: "A custom word."
            };
            if (playerNum === 1) data.left.push(newWord);
            else data.right.push(newWord);
            player.customWord = '';
            m.redraw();
        }
    }
    
    function runAutopilot() {
        if (data.turn !== 2 || !data.player2.autopilot || data.right.length === 0) {
            if (data.right.length === 0) switchTurn(); // Pass if no words
            return;
        }

        const wordToPlace = data.right[Math.floor(Math.random() * data.right.length)];
        const wordIndex = data.right.indexOf(wordToPlace);

        // Find an empty spot
        let emptyCells = [];
        data.board.forEach((row, rIdx) => {
            row.forEach((cell, cIdx) => {
                if (!cell) emptyCells.push({r: rIdx, c: cIdx});
            });
        });

        if (emptyCells.length > 0) {
            const targetCell = emptyCells[Math.floor(Math.random() * emptyCells.length)];
            
            // Simulate drop
            const wordWithPlayer = { ...wordToPlace, player: 2 };
            data.board[targetCell.r][targetCell.c] = wordWithPlayer;
            data.right.splice(wordIndex, 1);
            data.player2.score += 5;
            spendTurnPoints(5);
        } else {
             switchTurn(); // No space, pass turn
        }
        m.redraw();
    }


    // --- DRAG AND DROP HANDLERS ---
    
    function findEmptyAdjacent(row, col) {
        const directions = [
            [0, 1], [0, -1], [1, 0], [-1, 0], // Orthogonal
            [1, 1], [1, -1], [-1, 1], [-1, -1] // Diagonal
        ];
        for (const [dr, dc] of directions) {
            const newRow = row + dr;
            const newCol = col + dc;
            if (newRow >= 0 && newRow < data.board.length && newCol >= 0 && newCol < data.board[0].length && !data.board[newRow][newCol]) {
                return { r: newRow, c: newCol };
            }
        }
        return null;
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
        const wordWithPlayer = { ...payload.word, player: data.turn };

        // Handle occupied cell (bumping)
        if (data.board[targetRowIndex][targetColIndex]) {
            const bumpedWord = data.board[targetRowIndex][targetColIndex];
            const emptySpot = findEmptyAdjacent(targetRowIndex, targetColIndex);
            
            if (emptySpot) {
                data.board[emptySpot.r][emptySpot.c] = bumpedWord;
            } else {
                // Word is destroyed
                if (!bumpedWord.isCommon && !bumpedWord.isCustom) {
                    if (bumpedWord.player === 1) data.player1.score -= 5;
                    else data.player2.score -= 5;
                }
            }
        }
        
        data.board[targetRowIndex][targetColIndex] = wordWithPlayer;

        // Remove word from original source
        if (payload.source === 'left') {
            data.left.splice(payload.index, 1);
        } else if (payload.source === 'right') {
            data.right.splice(payload.index, 1);
        } else if (payload.source === 'board') {
            data.board[payload.rowIndex][payload.colIndex] = null;
        }

        // Add score for placing a dictionary word and spend points
        if (payload.source !== 'common' && payload.source !== 'board') {
            if (data.turn === 1) data.player1.score += 5;
            else data.player2.score += 5;
            spendTurnPoints(5);
        }
        
        randomizeCommonWords();
        m.redraw();
    }
    
    function handleTrashDrop(e) {
        e.preventDefault();
        const payload = JSON.parse(e.dataTransfer.getData("text/plain"));

        // Can only trash words from the board placed by the current player
        if (payload.source === 'board' && payload.word.player === data.turn) {
            // No penalty for common or custom words
            if (!payload.word.isCommon && !payload.word.isCustom) {
                 if(data.turn === 1) data.player1.score -= 5;
                 else data.player2.score -= 5;
            }
            data.board[payload.rowIndex][payload.colIndex] = null;
            spendTurnPoints(5); // Trashing costs the turn
            m.redraw();
        }
    }


    // --- VIEW COMPONENTS ---

    function modelPanel() {
        return m("div", { class: "flex-grow overflow-hidden flex bg-white border border-gray-200 dark:bg-black dark:border-gray-700 dark:text-gray-200" }, [
            m("div", { class: "flex flex-col w-full overflow-hidden p-0" }, [
                m("div", { class: "flex-1 overflow-hidden" }, [
                    m("div", { class: "max-h-full h-full flex" }, [
                        // Player 1 Panel
                        m("div", { class: `flex flex-col w-1/4 p-4 overflow-y-auto mx-auto ${data.turn === 1 ? 'bg-blue-100 dark:bg-blue-900' : ''}` }, [
                            m("div", {class: "flex justify-between items-center"}, [
                                m("h3", {class: "text-lg font-bold"}, "Player 1"),
                                m("div", {class: "font-bold text-xl p-2 bg-gray-200 dark:bg-gray-700 rounded"}, data.player1.score)
                            ]),
                            m("input", {
                                class: "w-full p-1 my-2 border rounded",
                                placeholder: "Add custom word...",
                                value: data.player1.customWord,
                                oninput: (e) => handleCustomWord(1, e.target.value),
                                onblur: () => addCustomWord(1)
                            }),
                            m("button", { class: "menu-button", onclick: () => handleRefreshWords(1) }, [m("span", {class:"material-symbols-outlined material-icons-24"},"refresh"), "Refresh Words"]),
                            leftWords()
                        ]),
                        
                        // Center Game Board Panel
                        m("div", { class: "flex flex-col w-3/5 p-2 overflow-auto mx-auto" }, [
                            m("h2", { class: "text-center text-2xl font-bold" }, "Word Battle"),
                            m("div", {class: "text-center font-semibold text-lg"}, `Turn: Player ${data.turn} | Time Left: ${data.timeLeft}s | Actions: ${data.turnPoints}`),
                            m("div", { class: "menu-buttons-spaced my-2 justify-center" },
                                data.activeCommonWords.map(word =>
                                    m("button", {
                                        draggable: true,
                                        class: "menu-button",
                                        ondragstart: (e) => {
                                            const payload = {
                                                word: { name: word, isCommon: true },
                                                source: 'common'
                                            };
                                            e.dataTransfer.setData("text/plain", JSON.stringify(payload));
                                        }
                                    }, word)
                                )
                            ),
                            m("table", { class: "w-full border-collapse mt-2 table-fixed" }, [
                                m("tbody",
                                    data.board.map((row, rowIndex) =>
                                        m("tr", { key: rowIndex },
                                            row.map((slot, colIndex) =>
                                                m("td", {
                                                    class: "border border-gray-500 h-12 p-1",
                                                    ondragover: (e) => e.preventDefault(),
                                                    ondrop: (e) => handleDrop(e, rowIndex, colIndex)
                                                },
                                                    slot ? m("div", {
                                                        class: `p-1 rounded text-center text-sm cursor-move ${slot.player === 1 ? 'bg-blue-200 text-blue-800' : 'bg-green-200 text-green-800'}`,
                                                        draggable: true,
                                                        title: slot.definition,
                                                        ondragstart: (e) => handleGridDragStart(e, slot, rowIndex, colIndex)
                                                    }, slot.name)
                                                        : m("div", { class: "w-full h-full" })
                                                )
                                            )
                                        )
                                    )
                                )
                            ]),
                            m("button", { 
                                class: "flyout-button text-center mt-2",
                                ondragover: (e) => e.preventDefault(),
                                ondrop: handleTrashDrop
                            }, [m("span", { class: "material-symbols-outlined material-icons-24" }, "delete"), "Trash"])
                        ]),
                        
                        // Player 2 Panel
                        m("div", { class: `flex flex-col w-1/4 p-4 overflow-y-auto mx-auto ${data.turn === 2 ? 'bg-green-100 dark:bg-green-900' : ''}` }, [
                             m("div", {class: "flex justify-between items-center"}, [
                                m("h3", {class: "text-lg font-bold"}, "Player 2"),
                                m("div", {class: "font-bold text-xl p-2 bg-gray-200 dark:bg-gray-700 rounded"}, data.player2.score)
                            ]),
                             m("input", {
                                class: "w-full p-1 my-2 border rounded",
                                placeholder: "Add custom word...",
                                value: data.player2.customWord,
                                oninput: (e) => handleCustomWord(2, e.target.value),
                                onblur: () => addCustomWord(2)
                            }),
                            m("button", { class: "menu-button", onclick: () => handleRefreshWords(2) }, [m("span", {class:"material-symbols-outlined material-icons-24"},"refresh"), "Refresh Words"]),
                            m("label", {class: "flex items-center my-2"}, [
                                m("input", {
                                    type: "checkbox",
                                    checked: data.player2.autopilot,
                                    onchange: (e) => { data.player2.autopilot = e.target.checked; if(data.turn === 2 && data.player2.autopilot) runAutopilot(); }
                                }),
                                m("span", {class: "ml-2"}, "Autopilot")
                            ]),
                            rightWords()
                        ])
                    ])
                ]),
                m("div", { class: "bg-white px-4 py-2 flex items-center justify-center border-t border-gray-200 dark:border-gray-700 dark:bg-black" }, [
                    m("button", {class: "menu-button", onclick: switchTurn }, "Pass Turn")
                ])
            ])
        ]);
    }

    function leftWords() {
        return data.left.map((word, index) => m("button", {
            draggable: true,
            class: "flyout-button",
            title: word.definition,
            ondragstart: (e) => {
                const payload = { word: word, source: 'left', index: index };
                e.dataTransfer.setData("text/plain", JSON.stringify(payload));
            }
        }, word.name));
    }

    function rightWords() {
        return data.right.map((word, index) => m("button", {
            draggable: true,
            class: "flyout-button",
            title: word.definition,
            ondragstart: (e) => {
                const payload = { word: word, source: 'right', index: index };
                e.dataTransfer.setData("text/plain", JSON.stringify(payload));
            }
        }, word.name));
    }
    
    // --- DATA FETCHING AND SETUP ---

    async function prepareWords(playerNum = null) {
        console.log("Prepare words ...");
        let grp = await page.findObject("auth.group", "data", "/Library/Dictionary");
        let q3 = am7client.newQuery("data.wordNet");
        q3.field("groupId", grp.id);
        
        let wordCount = 10;
        if(playerNum === 1) wordCount = 5 - data.left.length;
        if(playerNum === 2) wordCount = 5 - data.right.length;
        if(wordCount < 0) wordCount = 0;

        q3.range(0, playerNum ? wordCount : 20);
        q3.entity.request = ["name", "groupId", "organizationId", "type", "definition"];
        q3.entity.sortField = "random()";
        let l3 = await page.search(q3);
        if (!l3 || !l3.results) {
            page.toast("error", "No word results");
            return;
        }
        
        if (playerNum === 1) {
            data.left = data.left.concat(l3.results);
        } else if (playerNum === 2) {
            data.right = data.right.concat(l3.results);
        } else {
             let res = l3.results.length / 2;
             data.left = l3.results.slice(0, res);
             data.right = l3.results.slice(res);
        }
        m.redraw();
    }

    async function setup() {
        console.log("Setup");
        randomizeCommonWords();
        await prepareWords();
        startTimer();
    }

    let wordGame = {
        scoreCard: () => "",
        oninit: function () {
            setup();
        },
        onremove: function() {
            clearInterval(data.timerId); // Cleanup timer on component removal
        },
        view: function () {
            return modelPanel()
        }
    };
    page.components.wordGame = wordGame;
}());

