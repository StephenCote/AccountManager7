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
    
    // --- UTILITY FUNCTIONS ---
    
    function getWordColorClass(word) {
        if (!word) return '';
        if (word.isCustom) return 'bg-gray-200 text-gray-800';
        
        if (word.isCommon) {
            if (word.player === 1) return 'bg-blue-200 text-blue-800';
            if (word.player === 2) return 'bg-green-200 text-green-800';
            return 'bg-white text-black border border-gray-400';
        }

        switch (word.type) {
            case 'n': return 'bg-red-200 text-red-800';         // Noun
            case 'v': return 'bg-yellow-200 text-yellow-800';  // Verb
            case 's': return 'bg-purple-200 text-purple-800';    // Adjective
            case 'a': return 'bg-purple-200 text-purple-800';    // Adjective (alternate code)
            case 'r': return 'bg-indigo-200 text-indigo-800';   // Adverb
            default:
                if (word.player === 1) return 'bg-blue-200 text-blue-800';
                if (word.player === 2) return 'bg-green-200 text-green-800';
                return 'bg-gray-300 text-gray-900';
        }
    }

    function calculateTurnScore() {
        if (data.timeLeft > 24) return 5;
        if (data.timeLeft > 18) return 4;
        if (data.timeLeft > 12) return 3;
        if (data.timeLeft > 6) return 2;
        return 1;
    }


    // --- GAME LOGIC AND STATE MANAGEMENT ---

    function getInitialData() {
        return {
            turn: 1, // 1 or 2
            turnPoints: 5,
            currentRound: 1,
            actionsThisRound: 0,
            commonWordUsedThisTurn: false, // Prevents using more than one common word per turn
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
    }

    let data = getInitialData();
    
    function resetGame() {
        clearInterval(data.timerId);
        page.toast("info", "Game has been reset.");
        data = getInitialData();
        setup();
    }

    function togglePause() {
        data.isPaused = !data.isPaused;
        page.toast("info", `Timer ${data.isPaused ? 'paused' : 'resumed'}.`);
        m.redraw();
    }
    
    function exportBoardState() {
        const exportedData = {
            gameState: {
                currentRound: data.currentRound,
                actionsRemainingInRound: 20 - data.actionsThisRound,
                scores: {
                    player1: data.player1.score,
                    player2: data.player2.score,
                },
            },
            playerHands: {
                player1: data.left.map(w => ({ name: w.name, type: w.type || (w.isCustom ? 'custom' : 'unknown') })),
                player2: data.right.map(w => ({ name: w.name, type: w.type || (w.isCustom ? 'custom' : 'unknown') })),
            },
            board: []
        };

        data.board.forEach((row, rIdx) => {
            row.forEach((cell, cIdx) => {
                if (cell) {
                    exportedData.board.push({
                        row: rIdx,
                        col: cIdx,
                        word: cell.name,
                        type: cell.type || (cell.isCommon ? 'common' : 'custom'),
                        player: cell.player
                    });
                }
            });
        });

        const jsonString = JSON.stringify(exportedData, null, 2);
        console.log("Exported Board State:", jsonString);

        try {
            const textArea = document.createElement("textarea");
            textArea.value = jsonString;
            textArea.style.position = "fixed";
            textArea.style.left = "-9999px";
            document.body.appendChild(textArea);
            textArea.focus();
            textArea.select();
            document.execCommand('copy');
            document.body.removeChild(textArea);
            page.toast("success", "Board state copied to clipboard!");
        } catch (err) {
            page.toast("error", "Could not copy. See browser console for data.");
            console.error("Clipboard write failed:", err);
        }
    }


    function startTimer() {
        clearInterval(data.timerId);
        data.timeLeft = 30;
        data.timerId = setInterval(() => {
            if (data.isPaused) return;

            data.timeLeft--;
            if (data.timeLeft <= 0) {
                page.toast("info", `Player ${data.turn} ran out of time!`);
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
        data.commonWordUsedThisTurn = false; // Reset for the new turn
        startTimer();
        
        if (data.turn === 2 && data.player2.autopilot) {
            setTimeout(runAutopilot, 1500); // Give a slight delay for the AI move
        }
    }
    
    function spendTurnPoints(points) {
        data.turnPoints -= points;
        if(data.turnPoints <= 0) {
            data.actionsThisRound++;
            if (data.actionsThisRound >= 20) {
                page.toast("success", `Round ${data.currentRound} has ended!`);
                data.currentRound++;
                data.actionsThisRound = 0;
            }
            setTimeout(switchTurn, 200); // Short delay before switching turns
        }
    }

    function handleRefreshWords(playerNum) {
        let player = playerNum === 1 ? data.player1 : data.player2;
        let words = playerNum === 1 ? data.left : data.right;
        
        if (words.length > 5) {
             player.score--;
        }
        if (words.length >= 6) {
             player.score -= 5;
        }
        page.toast("info", `Player ${playerNum} refreshed their word list.`);
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
        const wordName = player.customWord.trim();
        if (wordName) {
            const isDuplicate = data.left.some(w => w.name.toLowerCase() === wordName.toLowerCase()) || 
                                data.right.some(w => w.name.toLowerCase() === wordName.toLowerCase());
            
            if (isDuplicate) {
                page.toast("error", "Cannot add a duplicate word.");
                player.customWord = ''; // Clear input anyway
                return;
            }

            const newWord = {
                name: wordName,
                isCustom: true,
                definition: "A custom word."
            };
            if (playerNum === 1) data.left.unshift(newWord);
            else data.right.unshift(newWord);
            page.toast("info", `Player ${playerNum} added the custom word '${wordName}'.`);
            player.customWord = '';
            m.redraw();
        }
    }
    
    function runAutopilot() {
        if (data.turn !== 2 || !data.player2.autopilot) return;

        const choice = Math.random();

        // Find an empty spot on the board
        let emptyCells = [];
        data.board.forEach((row, rIdx) => {
            row.forEach((cell, cIdx) => {
                if (!cell) emptyCells.push({r: rIdx, c: cIdx});
            });
        });
        
        // 70% chance to place a dictionary word
        if (choice < 0.7 && data.right.length > 0 && emptyCells.length > 0) {
            const wordToPlace = data.right[Math.floor(Math.random() * data.right.length)];
            const wordIndex = data.right.indexOf(wordToPlace);
            const targetCell = emptyCells[Math.floor(Math.random() * emptyCells.length)];
            
            const wordWithPlayer = { ...wordToPlace, player: 2 };
            data.board[targetCell.r][targetCell.c] = wordWithPlayer;
            data.right.splice(wordIndex, 1);
            
            const points = calculateTurnScore();
            data.player2.score += points;
            page.toast("info", `Autopilot placed '${wordWithPlayer.name}' for ${points} points.`);

            spendTurnPoints(5);
            if (data.right.length < 5) prepareWords(2, true);
        } 
        // 20% chance to place a common word
        else if (choice < 0.9 && !data.commonWordUsedThisTurn && emptyCells.length > 0) {
            const wordToPlace = data.activeCommonWords[Math.floor(Math.random() * data.activeCommonWords.length)];
            const targetCell = emptyCells[Math.floor(Math.random() * emptyCells.length)];
            
            const wordWithPlayer = { name: wordToPlace, isCommon: true, player: 2 };
            data.board[targetCell.r][targetCell.c] = wordWithPlayer;
            data.commonWordUsedThisTurn = true;
            page.toast("info", `Autopilot placed common word '${wordWithPlayer.name}'.`);
        } 
        // 10% chance to pass, or default action
        else {
             page.toast("info", "Autopilot passed its turn.");
             switchTurn();
        }
        m.redraw();
    }
    
    function shiftWords(direction) {
        const newBoard = Array(8).fill(null).map(() => Array(10).fill(null));
        const rows = data.board.length;
        const cols = data.board[0].length;

        if (direction === 'up' || direction === 'down') {
            for (let c = 0; c < cols; c++) {
                const colWords = [];
                for (let r = 0; r < rows; r++) {
                    if (data.board[r][c]) {
                        colWords.push(data.board[r][c]);
                    }
                }
                if (direction === 'up') {
                    colWords.forEach((word, i) => newBoard[i][c] = word);
                } else { // down
                    colWords.forEach((word, i) => newBoard[rows - colWords.length + i][c] = word);
                }
            }
        } else { // left or right
            for (let r = 0; r < rows; r++) {
                const rowWords = [];
                for (let c = 0; c < cols; c++) {
                    if (data.board[r][c]) {
                        rowWords.push(data.board[r][c]);
                    }
                }
                if (direction === 'left') {
                    rowWords.forEach((word, i) => newBoard[r][i] = word);
                } else { // right
                    rowWords.forEach((word, i) => newBoard[r][cols - rowWords.length + i] = word);
                }
            }
        }
        data.board = newBoard;
        page.toast("info", `Player ${data.turn} shifted the board ${direction}.`);
        spendTurnPoints(5); // Shifting words costs the turn
        m.redraw();
    }


    // --- DRAG AND DROP HANDLERS ---
    
    function findEmptyAdjacent(row, col) {
        const directions = [
            [0, 1], [0, -1], [1, 0], [-1, 0], 
            [1, 1], [1, -1], [-1, 1], [-1, -1]
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
        
        if (payload.source === 'common' && data.commonWordUsedThisTurn) {
            return;
        }

        if ((payload.source === 'left' && data.turn !== 1) || (payload.source === 'right' && data.turn !== 2)) {
            return;
        }
        if(payload.source === 'board' && payload.word.player !== data.turn) {
            return;
        }

        const wordWithPlayer = { ...payload.word, player: data.turn };

        if (data.board[targetRowIndex][targetColIndex]) {
            const bumpedWord = data.board[targetRowIndex][targetColIndex];
            const emptySpot = findEmptyAdjacent(targetRowIndex, targetColIndex);
            
            if (emptySpot) {
                data.board[emptySpot.r][emptySpot.c] = bumpedWord;
                page.toast("info", `Player ${bumpedWord.player}'s word '${bumpedWord.name}' was bumped.`);
            } else {
                if (!bumpedWord.isCommon && !bumpedWord.isCustom) {
                    if (bumpedWord.player === 1) data.player1.score -= 5;
                    else data.player2.score -= 5;
                    page.toast("info", `Player ${bumpedWord.player}'s word '${bumpedWord.name}' was destroyed! (-5 points)`);
                } else {
                    page.toast("info", `Player ${bumpedWord.player}'s word '${bumpedWord.name}' was destroyed!`);
                }
            }
        }
        
        data.board[targetRowIndex][targetColIndex] = wordWithPlayer;

        if (payload.source !== 'board') {
            page.toast("info", `Player ${data.turn} placed the word '${wordWithPlayer.name}'.`);
        }

        if (payload.source === 'left') {
            data.left.splice(payload.index, 1);
            if(data.left.length < 5) prepareWords(1, true);
        } else if (payload.source === 'right') {
            data.right.splice(payload.index, 1);
            if(data.right.length < 5) prepareWords(2, true);
        } else if (payload.source === 'board') {
            data.board[payload.rowIndex][payload.colIndex] = null;
        } else if (payload.source === 'common') {
            data.commonWordUsedThisTurn = true;
        }

        if (payload.source !== 'common' && payload.source !== 'board') {
            const points = calculateTurnScore();
            if (data.turn === 1) data.player1.score += points;
            else data.player2.score += points;
            page.toast("success", `Player ${data.turn} scored ${points} points!`);
            spendTurnPoints(5);
        }
        
        randomizeCommonWords();
        m.redraw();
    }
    
    function handleTrashDrop(e) {
        e.preventDefault();
        const payload = JSON.parse(e.dataTransfer.getData("text/plain"));

        if (payload.source === 'board' && payload.word.player === data.turn) {
            if (!payload.word.isCommon && !payload.word.isCustom) {
                 if(data.turn === 1) data.player1.score -= 5;
                 else data.player2.score -= 5;
            }
            page.toast("info", `Player ${data.turn} trashed the word '${payload.word.name}'.`);
            data.board[payload.rowIndex][payload.colIndex] = null;
            spendTurnPoints(5);
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
                                onblur: () => addCustomWord(1),
                                disabled: data.turn !== 1
                            }),
                            m("button", { class: "menu-button", onclick: () => handleRefreshWords(1), disabled: data.turn !== 1 }, [m("span", {class:"material-symbols-outlined material-icons-24"},"refresh"), "Refresh Words"]),
                            leftWords()
                        ]),
                        
                        // Center Game Board Panel
                        m("div", { class: "flex flex-col w-3/5 p-2 overflow-auto mx-auto" }, [
                            m("h2", { class: "text-center text-2xl font-bold" }, "Word Battle"),
                            m("div", {class: "text-center font-semibold text-lg"}, `Round: ${data.currentRound} | Actions Left: ${20 - data.actionsThisRound}`),
                            m("div", {class: "text-center font-semibold text-lg"}, `Turn: Player ${data.turn} | Time: ${data.timeLeft}s | Turn Actions: ${data.turnPoints}`),
                            m("div", { class: "menu-buttons-spaced my-2 justify-center" },
                                data.activeCommonWords.map(word =>
                                    m("button", {
                                        draggable: !data.commonWordUsedThisTurn,
                                        class: `menu-button ${getWordColorClass({isCommon: true})} ${data.commonWordUsedThisTurn ? 'opacity-50 cursor-not-allowed' : ''}`,
                                        ondragstart: (e) => {
                                            if (data.commonWordUsedThisTurn) {
                                                e.preventDefault();
                                                return;
                                            }
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
                                                        class: `p-1 rounded text-center text-sm cursor-move ${getWordColorClass(slot)}`,
                                                        draggable: slot.player === data.turn,
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
                            m("div", { class: "flex justify-center items-center mt-2" }, [
                                m("button", { class: "menu-button", onclick: resetGame }, m("span", { class: "material-symbols-outlined" }, "restart_alt")),
                                m("button", { class: "menu-button", onclick: togglePause }, m("span", { class: "material-symbols-outlined" }, data.isPaused ? "play_arrow" : "pause")),
                                m("button", { class: "menu-button", onclick: exportBoardState }, m("span", { class: "material-symbols-outlined" }, "download")),
                                m("button", { class: "menu-button", onclick: () => shiftWords('up'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_upward")),
                                m("button", { class: "menu-button", onclick: () => shiftWords('down'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_downward")),
                                m("button", { class: "menu-button", onclick: () => shiftWords('left'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_back")),
                                m("button", { class: "menu-button", onclick: () => shiftWords('right'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_forward")),
                                m("button", {
                                    class: "flyout-button text-center ml-4",
                                    ondragover: (e) => e.preventDefault(),
                                    ondrop: handleTrashDrop
                                }, [m("span", { class: "material-symbols-outlined material-icons-24" }, "delete"), "Trash"])
                            ])
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
                                onblur: () => addCustomWord(2),
                                disabled: data.turn !== 2
                            }),
                            m("button", { class: "menu-button", onclick: () => handleRefreshWords(2), disabled: data.turn !== 2 }, [m("span", {class:"material-symbols-outlined material-icons-24"},"refresh"), "Refresh Words"]),
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
                    m("button", {class: "menu-button", onclick: () => {
                        page.toast("info", `Player ${data.turn} passed their turn.`);
                        switchTurn();
                    } }, "Pass Turn")
                ])
            ])
        ]);
    }

    function leftWords() {
        return data.left.map((word, index) => m("button", {
            draggable: data.turn === 1,
            class: `flyout-button ${getWordColorClass(word)}`,
            title: word.definition,
            ondragstart: (e) => {
                const payload = { word: word, source: 'left', index: index };
                e.dataTransfer.setData("text/plain", JSON.stringify(payload));
            }
        }, word.name));
    }

    function rightWords() {
        return data.right.map((word, index) => m("button", {
            draggable: data.turn === 2,
            class: `flyout-button ${getWordColorClass(word)}`,
            title: word.definition,
            ondragstart: (e) => {
                const payload = { word: word, source: 'right', index: index };
                e.dataTransfer.setData("text/plain", JSON.stringify(payload));
            }
        }, word.name));
    }
    
    // --- DATA FETCHING AND SETUP ---

    async function prepareWords(playerNum = null, isAutoRefresh = false) {
        console.log("Preparing words by type...");
        let grp = await page.findObject("auth.group", "data", "/Library/Dictionary");

        let wordTargetCount = 8;
        let wordCount = wordTargetCount;
        if (playerNum) {
            const currentWords = playerNum === 1 ? data.left : data.right;
            wordCount = Math.max(0, wordTargetCount - currentWords.length);
        }
        if (wordCount <= 0 && isAutoRefresh) return;
        if (wordCount <= 0 && !isAutoRefresh) wordCount = wordTargetCount;

        const wordsPerType = Math.ceil(wordCount / 4);
        const wordTypes = { 'v': 'verbs', 'n': 'nouns', 's': 'adjectives', 'r': 'adverbs' };
        let wordDict = { nouns: [], verbs: [], adjectives: [], adverbs: [] };

        for (const typeCode in wordTypes) {
            let q = am7client.newQuery("data.wordNet");
            q.field("groupId", grp.id);
            q.field("type", typeCode);
            q.cache(false);
            q.range(0, 30); // Fetch a larger pool to pick from
            q.entity.request = ["name", "groupId", "organizationId", "type", "definition"];
            q.entity.sortField = "random()";
            let l = await page.search(q);
            if (l && l.results) {
                wordDict[wordTypes[typeCode]] = l.results;
            }
        }

        const allExistingNames = new Set([...data.left.map(w => w.name.toLowerCase()), ...data.right.map(w => w.name.toLowerCase())]);

        function getNewPlayerWords() {
            let newWords = [];
            for (const typeName in wordDict) {
                const availableWords = wordDict[typeName].filter(w => !allExistingNames.has(w.name.toLowerCase()));
                const picked = availableWords.slice(0, wordsPerType);
                newWords.push(...picked);
                picked.forEach(w => allExistingNames.add(w.name.toLowerCase()));
            }
            return newWords.sort(() => 0.5 - Math.random());
        }

        const assignWords = (playerList) => {
            const newWords = getNewPlayerWords();
            if (isAutoRefresh || playerNum) {
                return playerList.concat(newWords);
            } else {
                return newWords;
            }
        };

        if (playerNum === 1) {
            data.left = assignWords(data.left);
        } else if (playerNum === 2) {
            data.right = assignWords(data.right);
        } else {
            data.left = assignWords([]);
            data.right = assignWords([]);
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

