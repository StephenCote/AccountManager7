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
        let classes = '';

        if (word.isLocked) {
             classes += word.owner === 1 ? 'shadow-[0_0_8px_#60a5fa] ' : 'shadow-[0_0_8px_#4ade80] ';
        }

        if (word.isCustom) return classes + 'bg-gray-200 text-gray-800';
        
        if (word.isCommon) {
            if (word.player === 1) return classes + 'bg-blue-200 text-blue-800';
            if (word.player === 2) return classes + 'bg-green-200 text-green-800';
            return classes + 'bg-white text-black border border-gray-400';
        }

        switch (word.type) {
            case 'n': return classes + 'bg-red-200 text-red-800';         // Noun
            case 'v': return classes + 'bg-yellow-200 text-yellow-800';  // Verb
            case 's': return classes + 'bg-purple-200 text-purple-800';    // Adjective
            case 'a': return classes + 'bg-purple-200 text-purple-800';    // Adjective (alternate code)
            case 'r': return classes + 'bg-indigo-200 text-indigo-800';   // Adverb
            default:
                if (word.player === 1) return classes + 'bg-blue-200 text-blue-800';
                if (word.player === 2) return classes + 'bg-green-200 text-green-800';
                return classes + 'bg-gray-300 text-gray-900';
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
            isGameStarted: false,
            turn: 1, // 1 or 2
            turnPoints: 5,
            currentRound: 1,
            actionsThisRound: 0,
            commonWordUsedThisTurn: false,
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
            left: [], 
            right: [],
            foundCombinations: []
        };
    }

    let data = getInitialData();
    
    async function analyzeAndLockPhrases() {
        scanForCombinations();
        const phrasesToAnalyze = data.foundCombinations
            .filter(combo => !combo.isLocked)
            .map(combo => combo.phrase);

        if (phrasesToAnalyze.length === 0) return;

        try {
            let resp = await m.request({
                method: 'POST',
                url: g_application_path + '/rest/word/analyze',
                withCredentials: true,
                headers: { 'Content-Type': 'application/json' },
                body: { phrases: phrasesToAnalyze }
            });

            if (resp && resp.scores) {
                resp.scores.forEach(scoredPhrase => {
                    if (scoredPhrase.score > 7.5) {
                        const comboToLock = data.foundCombinations.find(c => c.phrase === scoredPhrase.phrase);
                        if (comboToLock) {
                            // Determine ownership
                            const p1Words = comboToLock.words.filter(w => w.player === 1).length;
                            const p2Words = comboToLock.words.filter(w => w.player === 2).length;
                            const owner = p1Words > p2Words ? 1 : (p2Words > p1Words ? 2 : data.turn);
                            
                            // Mark words on board as locked
                            const phraseId = Date.now() + Math.random();
                            comboToLock.words.forEach(wordInfo => {
                                const boardWord = data.board[wordInfo.row][wordInfo.col];
                                if (boardWord && boardWord.name === wordInfo.word) {
                                    boardWord.isLocked = true;
                                    boardWord.phraseId = phraseId;
                                    boardWord.owner = owner;
                                }
                            });
                            page.toast("success", `Phrase "${scoredPhrase.phrase}" locked by Player ${owner}!`);
                        }
                    }
                });
            }
        } catch (e) {
            console.error("Error analyzing phrases:", e);
            page.toast("error", "Could not analyze phrases.");
        }
    }


    function scanForCombinations() {
        const combinations = [];
        data.board.forEach((row, rIdx) => {
            let currentCombination = [];
            row.forEach((cell, cIdx) => {
                if (cell) {
                    currentCombination.push({
                        word: cell.name,
                        player: cell.player,
                        row: rIdx,
                        col: cIdx,
                        isLocked: !!cell.isLocked // Pass lock status
                    });
                } else {
                    if (currentCombination.length >= 2) {
                        combinations.push({
                            phrase: currentCombination.map(c => c.word).join(' '),
                            row: rIdx,
                            words: currentCombination,
                            isLocked: currentCombination.every(c => c.isLocked)
                        });
                    }
                    currentCombination = [];
                }
            });
            if (currentCombination.length >= 2) {
                combinations.push({
                    phrase: currentCombination.map(c => c.word).join(' '),
                    row: rIdx,
                    words: currentCombination,
                    isLocked: currentCombination.every(c => c.isLocked)
                });
            }
        });
        data.foundCombinations = combinations;
    }

    async function startGame() {
        data.isGameStarted = true;
        await prepareWords();
        startTimer();
    }

    function resetGame() {
        clearInterval(data.timerId);
        page.toast("info", "Game has been reset.");
        data = getInitialData();
        setup();
    }

    function togglePause() {
        if (!data.isGameStarted) return;
        data.isPaused = !data.isPaused;
        page.toast("info", `Timer ${data.isPaused ? 'paused' : 'resumed'}.`);
        m.redraw();
    }
    
    function exportBoardState() {
        scanForCombinations(); // Ensure combinations are up-to-date
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
            board: [],
            wordCombinations: data.foundCombinations
        };

        data.board.forEach((row, rIdx) => {
            row.forEach((cell, cIdx) => {
                if (cell) {
                    exportedData.board.push({
                        row: rIdx,
                        col: cIdx,
                        word: cell.name,
                        type: cell.type || (cell.isCommon ? 'common' : 'custom'),
                        player: cell.player,
                        isLocked: !!cell.isLocked,
                        owner: cell.owner || null
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
        data.commonWordUsedThisTurn = false;
        startTimer();
        
        if (data.turn === 2 && data.player2.autopilot) {
            setTimeout(runAutopilot, 1500);
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
            setTimeout(switchTurn, 200);
        }
    }

    function handleRefreshWords(playerNum) {
        let player = playerNum === 1 ? data.player1 : data.player2;
        let words = playerNum === 1 ? data.left : data.right;
        
        if (words.length > 5) player.score--;
        if (words.length >= 6) player.score -= 5;
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
                player.customWord = '';
                return;
            }

            const newWord = { name: wordName, isCustom: true, definition: "A custom word." };
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

        let emptyCells = [];
        data.board.forEach((row, rIdx) => row.forEach((cell, cIdx) => { if (!cell) emptyCells.push({r: rIdx, c: cIdx}); }));
        
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
            analyzeAndLockPhrases();
            spendTurnPoints(5);
            if (data.right.length < 5) prepareWords(2, true);
        } 
        else if (choice < 0.9 && !data.commonWordUsedThisTurn && emptyCells.length > 0) {
            const wordToPlace = data.activeCommonWords[Math.floor(Math.random() * data.activeCommonWords.length)];
            const targetCell = emptyCells[Math.floor(Math.random() * emptyCells.length)];
            
            const wordWithPlayer = { name: wordToPlace, isCommon: true, player: 2 };
            data.board[targetCell.r][targetCell.c] = wordWithPlayer;
            data.commonWordUsedThisTurn = true;
            page.toast("info", `Autopilot placed common word '${wordWithPlayer.name}'.`);
            analyzeAndLockPhrases();
            setTimeout(switchTurn, 200); // End the turn after placing a common word
        } 
        else {
             page.toast("info", "Autopilot passed its turn.");
             switchTurn();
        }
        m.redraw();
    }
    
    function shiftWords(direction) {
        const tempBoard = JSON.parse(JSON.stringify(data.board)); // Deep copy
        const rows = data.board.length;
        const cols = data.board[0].length;

        // Group locked words by phraseId
        const phrases = {};
        for (let r = 0; r < rows; r++) {
            for (let c = 0; c < cols; c++) {
                const cell = tempBoard[r][c];
                if (cell && cell.isLocked) {
                    if (!phrases[cell.phraseId]) {
                        phrases[cell.phraseId] = [];
                    }
                    phrases[cell.phraseId].push({ ...cell, r, c });
                }
            }
        }

        const canMovePhrase = (phrase, dr, dc) => {
            for (const word of phrase) {
                const newR = word.r + dr;
                const newC = word.c + dc;
                if (newR < 0 || newR >= rows || newC < 0 || newC >= cols) return false; // Out of bounds
                const targetCell = tempBoard[newR][newC];
                if (targetCell && targetCell.phraseId !== word.phraseId) return false; // Collision
            }
            return true;
        };

        const movePhrase = (phrase, dr, dc) => {
            phrase.forEach(word => (tempBoard[word.r][word.c] = null));
            phrase.forEach(word => {
                word.r += dr;
                word.c += dc;
                tempBoard[word.r][word.c] = word;
            });
        };

        const unlockedWords = [];
        for (let r = 0; r < rows; r++) {
            for (let c = 0; c < cols; c++) {
                if (tempBoard[r][c] && !tempBoard[r][c].isLocked) {
                    unlockedWords.push(tempBoard[r][c]);
                    tempBoard[r][c] = null;
                }
            }
        }

        // Simplified logic for unlocked words for now
        // This part needs a more robust implementation to handle block shifting correctly
        if (direction === 'up') unlockedWords.sort((a,b) => a.r - b.r);
        if (direction === 'down') unlockedWords.sort((a,b) => b.r - a.r);
        if (direction === 'left') unlockedWords.sort((a,b) => a.c - b.c);
        if (direction === 'right') unlockedWords.sort((a,b) => b.c - a.c);
        
        unlockedWords.forEach(word => {
             // A proper implementation would find the first available slot in the shift direction
        });


        page.toast("info", `Player ${data.turn} shifted the board ${direction}.`);
        data.board = tempBoard;
        analyzeAndLockPhrases();
        spendTurnPoints(5);
        m.redraw();
    }


    // --- DRAG AND DROP HANDLERS ---
    
    function findEmptyAdjacent(row, col) {
        const directions = [ [0, 1], [0, -1], [1, 0], [-1, 0], [1, 1], [1, -1], [-1, 1], [-1, -1] ];
        for (const [dr, dc] of directions) {
            const newRow = row + dr, newCol = col + dc;
            if (newRow >= 0 && newRow < 8 && newCol >= 0 && newCol < 10 && !data.board[newRow][newCol]) {
                return { r: newRow, c: newCol };
            }
        }
        return null;
    }

    function handleGridDragStart(e, word, rowIndex, colIndex) {
        e.stopPropagation();
        const payload = { word: word, source: 'board', rowIndex: rowIndex, colIndex: colIndex };
        e.dataTransfer.setData("text/plain", JSON.stringify(payload));
    }

    function handleDrop(e, targetRowIndex, targetColIndex) {
        e.preventDefault();
        e.stopPropagation();
        const payload = JSON.parse(e.dataTransfer.getData("text/plain"));
        
        const targetCell = data.board[targetRowIndex][targetColIndex];
        if (targetCell && targetCell.isLocked) {
             page.toast("error", "Cannot drop a word on a locked phrase.");
             return;
        }

        if (payload.source === 'common' && data.commonWordUsedThisTurn) return;
        if ((payload.source === 'left' && data.turn !== 1) || (payload.source === 'right' && data.turn !== 2)) return;
        if(payload.source === 'board' && payload.word.player !== data.turn) return;

        const wordWithPlayer = { ...payload.word, player: data.turn };

        if (targetCell) {
            const bumpedWord = targetCell;
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

        if (payload.source !== 'board') page.toast("info", `Player ${data.turn} placed the word '${wordWithPlayer.name}'.`);

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
        
        analyzeAndLockPhrases();
        randomizeCommonWords();
        m.redraw();
    }
    
    function handleTrashDrop(e) {
        e.preventDefault();
        const payload = JSON.parse(e.dataTransfer.getData("text/plain"));

        if (payload.source === 'board' && payload.word.player === data.turn) {
            const wordToTrash = payload.word;
            if (wordToTrash.isLocked) {
                // If a locked word is trashed, unlock the entire phrase
                data.board.forEach(row => row.forEach(cell => {
                    if (cell && cell.phraseId === wordToTrash.phraseId) {
                        cell.isLocked = false;
                        cell.phraseId = null;
                        cell.owner = null;
                    }
                }));
                 page.toast("info", "A locked phrase was broken!");
            }

            if (!wordToTrash.isCommon && !wordToTrash.isCustom) {
                 if(data.turn === 1) data.player1.score -= 5;
                 else data.player2.score -= 5;
            }
            page.toast("info", `Player ${data.turn} trashed the word '${wordToTrash.name}'.`);
            data.board[payload.rowIndex][payload.colIndex] = null;
            analyzeAndLockPhrases();
            spendTurnPoints(5);
            m.redraw();
        }
    }


    // --- VIEW COMPONENTS ---

    function modelPanel() {
        if (!data.isGameStarted) {
            return m("div", { class: "flex-grow flex items-center justify-center bg-gray-100 dark:bg-black" }, [
                m("div", { class: "text-center" }, [
                    m("h1", { class: "text-4xl font-bold mb-4 dark:text-white" }, "Word Battle"),
                    m("button", { class: "menu-button text-2xl p-4", onclick: startGame }, "Start Game")
                ])
            ]);
        }
        
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
                                            const payload = { word: { name: word, isCommon: true }, source: 'common' };
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
                                                        draggable: slot.player === data.turn && !slot.isLocked,
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
                                m("button", { class: "menu-button", onclick: () => { page.toast("info", `Player ${data.turn} passed their turn.`); switchTurn(); } }, "Pass"),
                                m("button", {
                                    class: "flyout-button text-center ml-2",
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

    function setup() {
        console.log("Setup");
        randomizeCommonWords();
        // Words will be prepared when the game is started
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

