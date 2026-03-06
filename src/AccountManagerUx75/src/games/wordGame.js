/**
 * wordGame.js — Word Battle game component (ESM)
 * Port of Ux7 client/components/wordGame.js
 *
 * Two-player drag-and-drop word composition on an 8x10 grid.
 * Phrases are evaluated by LLM for coherence; scoring, timer, autopilot.
 */
import m from 'mithril';
import { am7model } from '../core/model.js';

function getPage() { return am7model._page; }
function getClient() { return am7model._client; }

let entity = {};
let fullMode = false;
let gridPath = "/Olio/Universes/My Grid Universe/Worlds/My Grid World";

const commonWords = [
    'a', 'an', 'the', 'this', 'that', 'these', 'those', 'my', 'your', 'its', 'our', 'their', 'some', 'any', 'every', 'each', 'no',
    'of', 'in', 'on', 'for', 'to', 'from', 'with', 'by', 'at', 'as', 'like', 'into', 'unto', 'over', 'under', 'above', 'below', 'beneath', 'beside', 'between', 'beyond', 'through', 'against', 'without', 'within', 'before', 'after', 'since', 'until',
    'I', 'me', 'you', 'he', 'him', 'she', 'her', 'it', 'we', 'us', 'they', 'them', 'who', 'what', 'where', 'when', 'why', 'how',
    'and', 'but', 'or', 'nor', 'so', 'yet', 'for', 'if', 'then', 'than', 'while', 'because', 'though',
    'is', 'am', 'are', 'was', 'were', 'be', 'being', 'been', 'has', 'have', 'had', 'do', 'does', 'did', 'will', 'would', 'shall', 'should', 'can', 'could', 'may', 'might', 'must'
];

function randomizeCommonWords() {
    const shuffled = commonWords.sort(() => 0.5 - Math.random());
    data.activeCommonWords = shuffled.slice(0, 10);
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
        case 'n': return classes + 'bg-red-200 text-red-800';
        case 'v': return classes + 'bg-yellow-200 text-yellow-800';
        case 's': return classes + 'bg-purple-200 text-purple-800';
        case 'a': return classes + 'bg-purple-200 text-purple-800';
        case 'r': return classes + 'bg-indigo-200 text-indigo-800';
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
        endGameInfo: null,
        isProcessingEndOfRound: false,
        isGameStarted: false,
        turn: 1,
        turnPoints: 5,
        currentRound: 1,
        actionsThisRound: 0,
        commonWordUsedThisTurn: false,
        timerId: null,
        timeLeft: 30,
        isPaused: false,
        player1: { name: "Player 1", score: 0, customWord: '', customWordTimeout: null },
        player2: { name: "Player 2", score: 0, customWord: '', customWordTimeout: null, autopilot: false },
        board: Array(8).fill(null).map(() => Array(10).fill(null)),
        activeCommonWords: [],
        left: [],
        right: [],
        foundCombinations: []
    };
}

let data = getInitialData();
let coherenceScoreThreshold = 0.6;
let analyzing = false;

async function analyzeAndLockPhrases() {
    let page = getPage();
    if (analyzing) return;
    scanForCombinations();
    const phrasesToAnalyze = data.foundCombinations
        .filter(combo => !combo.isLocked)
        .map(combo => combo.phrase);
    if (phrasesToAnalyze.length === 0) return;
    analyzing = true;
    try {
        let am7client = getClient();
        let resp = await m.request({
            method: 'POST',
            url: am7client.base().replace("/rest", "") + '/rest/word/analyze',
            withCredentials: true,
            headers: { 'Content-Type': 'application/json' },
            body: { phrases: phrasesToAnalyze }
        });
        if (resp && resp.scores) {
            resp.scores.forEach(scoredPhrase => {
                if (scoredPhrase.score >= coherenceScoreThreshold) {
                    const comboToLock = data.foundCombinations.find(c => c.phrase === scoredPhrase.phrase);
                    if (comboToLock) {
                        const p1Words = comboToLock.words.filter(w => w.player === 1).length;
                        const p2Words = comboToLock.words.filter(w => w.player === 2).length;
                        const owner = p1Words > p2Words ? 1 : (p2Words > p1Words ? 2 : data.turn);
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
                analyzing = false;
            });
        }
    } catch (e) {
        console.error("Error analyzing phrases:", e);
        page.toast("error", "Could not analyze phrases.");
        analyzing = false;
    }
}

function scanForCombinations() {
    const combinations = [];
    data.board.forEach((row, rIdx) => {
        let currentCombination = [];
        row.forEach((cell, cIdx) => {
            if (cell) {
                currentCombination.push({ word: cell.name, player: cell.player, row: rIdx, col: cIdx, isLocked: !!cell.isLocked });
            } else {
                if (currentCombination.length >= 2) {
                    combinations.push({ phrase: currentCombination.map(c => c.word).join(' '), row: rIdx, words: currentCombination, isLocked: currentCombination.every(c => c.isLocked) });
                }
                currentCombination = [];
            }
        });
        if (currentCombination.length >= 2) {
            combinations.push({ phrase: currentCombination.map(c => c.word).join(' '), row: rIdx, words: currentCombination, isLocked: currentCombination.every(c => c.isLocked) });
        }
    });
    data.foundCombinations = combinations;
}

async function handleEndOfRound() {
    let page = getPage();
    data.isProcessingEndOfRound = true;
    page.toast("info", `Round ${data.currentRound} has ended! Calculating scores...`);
    clearInterval(data.timerId);
    data.isPaused = true;
    m.redraw();

    scanForCombinations();
    const combinationsThisRound = [...data.foundCombinations];

    combinationsThisRound.forEach(combo => {
        const p1Words = combo.words.filter(w => w.player === 1).length;
        const p2Words = combo.words.filter(w => w.player === 2).length;
        let owner = p1Words > p2Words ? 1 : (p2Words > p1Words ? 2 : 0);
        if (owner !== 0) {
            combo.owner = owner;
            if (owner === 1) data.player1.score += 5; else data.player2.score += 5;
            page.toast("success", `Player ${owner} gets 5 points for forming "${combo.phrase}"`);
        }
    });

    const survivingPhrases = [];
    const combosToEvaluate = combinationsThisRound.filter(c => c.owner);

    for (const combo of combosToEvaluate) {
        try {
            let am7chat = page.components.chat;
            const response = am7chat ? await am7chat.chat(gameChat, combo.phrase) : null;
            const lastMessage = response?.messages?.[response.messages.length - 1]?.content;
            if (lastMessage && lastMessage.toLowerCase().trim() !== 'incoherent') {
                page.toast("success", `Phrase "${combo.phrase}" is coherent! Player ${combo.owner} gets 10 bonus points!`);
                if (combo.owner === 1) data.player1.score += 10; else data.player2.score += 10;
                survivingPhrases.push(combo);
            } else {
                page.toast("info", `Phrase "${combo.phrase}" was incoherent and is removed.`);
            }
        } catch (e) {
            console.error("Error evaluating phrase:", combo.phrase, e);
            page.toast("error", `Could not evaluate phrase: "${combo.phrase}"`);
        }
    }

    const newBoard = Array(8).fill(null).map(() => Array(10).fill(null));
    let nextAvailableRow = 0;
    if (survivingPhrases.length === 0) {
        page.toast("info", "No coherent phrases survived this round. Going to next round.");
    }
    survivingPhrases.forEach(combo => {
        if (nextAvailableRow < data.board.length) {
            combo.words.forEach(wordInfo => {
                const originalWord = data.board[wordInfo.row][wordInfo.col];
                if (originalWord) newBoard[nextAvailableRow][wordInfo.col] = originalWord;
            });
            nextAvailableRow++;
        }
    });
    data.board = newBoard;

    if (survivingPhrases.length > 0 && survivingPhrases.length >= data.board.length) {
        const finalPhraseStrings = survivingPhrases.map(p => p.phrase);
        triggerEndGame(finalPhraseStrings);
        return;
    }

    data.currentRound++;
    data.actionsThisRound = 0;
    data.isPaused = false;
    data.isProcessingEndOfRound = false;
    page.toast("success", `Starting Round ${data.currentRound}!`);
    switchTurn();
    m.redraw();
}

async function triggerEndGame(finalPhrases) {
    let page = getPage();
    page.toast("success", "Game Over! All rows are coherent phrases!");

    let winner = "It's a tie!";
    if (data.player1.score > data.player2.score) winner = "Player 1 Wins!";
    if (data.player2.score > data.player1.score) winner = "Player 2 Wins!";

    const finalPrompt = `Create a poem and brief analysis from the following coherent phrases:\n\n${finalPhrases.join("\n")}.`;
    let finalMessage = "### Final Summary\n\nThe AI summary could not be generated at this time.";
    try {
        let am7chat = page.components.chat;
        const response = am7chat ? await am7chat.chat(endGameChat, finalPrompt) : null;
        const content = response?.messages?.[response.messages.length - 1]?.content;
        if (content) finalMessage = content;
    } catch (e) {
        console.error("Failed to get final game summary:", e);
    }

    data.endGameInfo = { winner, finalMessage };
    data.isProcessingEndOfRound = false;
    m.redraw();
}

let chatName = "A1 Mini";
async function prepareChatConfig() {
    let page = getPage();
    if (page.components.llmConnector) {
        let libCfg = await page.components.llmConnector.resolveConfig("generalChat");
        if (libCfg) return libCfg;
    }
    let am7chat = page.components.chat;
    return am7chat ? am7chat.makeChat(chatName, "herm-local", "http://localhost:11434", "ollama") : null;
}

let endPromptName = "WordBattleEndRound.prompt";
async function prepareEndPrompt() {
    let am7chat = getPage().components.chat;
    if (!am7chat) return null;
    return am7chat.makePrompt(endPromptName, [
        "You are the controller for a word battle game. You receive phrases you have identified as coherent.",
        "Your job is to format these phrases into a poem. You are allowed to make changes to each phrase by changing word order, tense, count, or adding pronoun, preposition, or conjunction.",
        "The resulting poem may use any or no rhyme and meter.",
        "Following the poem, you will include an analysis of the poem. For the analysis, you will adopt the persona of a very pompous literary critic who extolls the brilliance, profoundness, and genius of the poem while downplaying any negative qualities.",
        "USE *markdown* TO FORMAT YOUR RESPONSE."
    ]);
}

let promptName = "WordBattlePhraseCoherence.prompt";
async function preparePrompt() {
    let am7chat = getPage().components.chat;
    if (!am7chat) return null;
    return am7chat.makePrompt(promptName, [
        "You are the controller for a word battle game. Random words will be assembled into phrases. Possible coherent phrases will be sent to you to be evaluated.",
        "Your job is to evaluate these phrases and respond in one of three ways:",
        "1. If the phrase is incoherent, respond with 'incoherent'.",
        "2. If the phrase is coherent as-is, respond with the phrase itself.",
        "3. If the phrase can be improved by changing word order, tense, count, etc, or by adding a pronoun, preposition, or conjunction, then respond with the improved phrase.",
        "Example: 'Holbein fatally choke vicariously' -> 'Holbein fatally choked vicariously'",
        "Example: 'aphyllous blood count' -> 'incoherent'",
        "Example: 'blessed whistle' -> 'blessed whistle'",
        "Example: 'like blessed whistle knock' -> 'knocked like a blessed whistle'"
    ]);
}

let chatRequestName = "WordBattle Chat";
let endChatRequestName = "WordBattle End Chat";
let endPromptCfg = null;
let promptCfg = null;
let chatCfg = null;
let gameChat = null;
let endGameChat = null;

async function startGame() {
    let page = getPage();
    let am7chat = page.components.chat;

    promptCfg = await preparePrompt();
    if (!promptCfg) { page.toast("error", "Could not start game without prompt config"); return; }
    endPromptCfg = await prepareEndPrompt();
    if (!endPromptCfg) { page.toast("error", "Could not start game without end prompt config"); return; }
    chatCfg = await prepareChatConfig();
    if (!chatCfg) { page.toast("error", "Could not start game without chat config"); return; }

    if (am7chat) {
        gameChat = await am7chat.getChatRequest(chatRequestName, chatCfg, promptCfg);
        if (!gameChat) { page.toast("error", "Could not start game without chat request"); return; }
        endGameChat = await am7chat.getChatRequest(endChatRequestName, chatCfg, endPromptCfg);
        if (!endGameChat) { page.toast("error", "Could not start game without end chat request"); return; }
    }

    await prepareWords();
    data.isGameStarted = true;
    startTimer();
}

function resetGame() {
    let page = getPage();
    clearInterval(data.timerId);
    page.toast("info", "Game has been reset.");
    data = getInitialData();
    setup();
}

function togglePause() {
    let page = getPage();
    if (!data.isGameStarted) return;
    data.isPaused = !data.isPaused;
    page.toast("info", `Timer ${data.isPaused ? 'paused' : 'resumed'}.`);
    m.redraw();
}

function exportBoardState() {
    let page = getPage();
    scanForCombinations();
    const exportedData = {
        gameState: { currentRound: data.currentRound, actionsRemainingInRound: 20 - data.actionsThisRound, scores: { player1: data.player1.score, player2: data.player2.score } },
        playerHands: {
            player1: data.left.map(w => ({ name: w.name, type: w.type || (w.isCustom ? 'custom' : 'unknown') })),
            player2: data.right.map(w => ({ name: w.name, type: w.type || (w.isCustom ? 'custom' : 'unknown') }))
        },
        board: [],
        wordCombinations: data.foundCombinations
    };
    data.board.forEach((row, rIdx) => {
        row.forEach((cell, cIdx) => {
            if (cell) {
                exportedData.board.push({ row: rIdx, col: cIdx, word: cell.name, type: cell.type || (cell.isCommon ? 'common' : 'custom'), player: cell.player, isLocked: !!cell.isLocked, owner: cell.owner || null });
            }
        });
    });
    const jsonString = JSON.stringify(exportedData, null, 2);
    try {
        navigator.clipboard.writeText(jsonString);
        page.toast("success", "Board state copied to clipboard!");
    } catch (err) {
        page.toast("error", "Could not copy. See browser console for data.");
        console.error("Clipboard write failed:", err);
    }
}

function startTimer() {
    let page = getPage();
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
    if (data.turnPoints <= 0) {
        data.actionsThisRound++;
        if (data.actionsThisRound >= 20) {
            handleEndOfRound();
        } else {
            setTimeout(switchTurn, 200);
        }
    }
}

function handleRefreshWords(playerNum) {
    let page = getPage();
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
    player.customWordTimeout = setTimeout(() => { addCustomWord(playerNum); }, 5000);
}

function addCustomWord(playerNum) {
    let page = getPage();
    let player = playerNum === 1 ? data.player1 : data.player2;
    const wordName = player.customWord.trim();
    if (wordName) {
        const isDuplicate = data.left.some(w => w.name.toLowerCase() === wordName.toLowerCase()) ||
            data.right.some(w => w.name.toLowerCase() === wordName.toLowerCase());
        if (isDuplicate) { page.toast("error", "Cannot add a duplicate word."); player.customWord = ''; return; }
        const newWord = { name: wordName, isCustom: true, definition: "A custom word." };
        if (playerNum === 1) data.left.unshift(newWord);
        else data.right.unshift(newWord);
        page.toast("info", `Player ${playerNum} added the custom word '${wordName}'.`);
        player.customWord = '';
        m.redraw();
    }
}

function runAutopilot() {
    let page = getPage();
    if (data.turn !== 2 || !data.player2.autopilot) return;
    const choice = Math.random();
    let emptyCells = [];
    data.board.forEach((row, rIdx) => row.forEach((cell, cIdx) => { if (!cell) emptyCells.push({ r: rIdx, c: cIdx }); }));

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
    } else if (choice < 0.9 && !data.commonWordUsedThisTurn && emptyCells.length > 0) {
        const wordToPlace = data.activeCommonWords[Math.floor(Math.random() * data.activeCommonWords.length)];
        const targetCell = emptyCells[Math.floor(Math.random() * emptyCells.length)];
        const wordWithPlayer = { name: wordToPlace, isCommon: true, player: 2 };
        data.board[targetCell.r][targetCell.c] = wordWithPlayer;
        data.commonWordUsedThisTurn = true;
        page.toast("info", `Autopilot placed common word '${wordWithPlayer.name}'.`);
        analyzeAndLockPhrases();
        setTimeout(switchTurn, 200);
    } else {
        page.toast("info", "Autopilot passed its turn.");
        switchTurn();
    }
    m.redraw();
}

async function shiftWords(direction) {
    let page = getPage();
    data.board.forEach(row => row.forEach(cell => {
        if (cell) { cell.isLocked = false; cell.phraseId = null; cell.owner = null; }
    }));
    const rows = data.board.length;
    const cols = data.board[0].length;
    const newBoard = Array(rows).fill(null).map(() => Array(cols).fill(null));
    const allWords = [];
    data.board.forEach((row, rIdx) => row.forEach((cell, cIdx) => {
        if (cell) allWords.push({ ...cell, row: rIdx, col: cIdx });
    }));

    if (direction === 'up') {
        for (let c = 0; c < cols; c++) {
            const colWords = allWords.filter(w => w.col === c).sort((a, b) => a.row - b.row);
            colWords.forEach((word, r) => newBoard[r][c] = word);
        }
    } else if (direction === 'down') {
        for (let c = 0; c < cols; c++) {
            const colWords = allWords.filter(w => w.col === c).sort((a, b) => b.row - a.row);
            colWords.forEach((word, i) => newBoard[rows - 1 - i][c] = word);
        }
    } else if (direction === 'left') {
        for (let r = 0; r < rows; r++) {
            const rowWords = allWords.filter(w => w.row === r).sort((a, b) => a.col - b.col);
            rowWords.forEach((word, c) => newBoard[r][c] = word);
        }
    } else if (direction === 'right') {
        for (let r = 0; r < rows; r++) {
            const rowWords = allWords.filter(w => w.row === r).sort((a, b) => b.col - a.col);
            rowWords.forEach((word, i) => newBoard[r][cols - 1 - i] = word);
        }
    }

    data.board = newBoard;
    page.toast("info", `Player ${data.turn} shifted the board ${direction}.`);
    await analyzeAndLockPhrases();
    spendTurnPoints(5);
    m.redraw();
}

// --- DRAG AND DROP HANDLERS ---

function findEmptyAdjacent(row, col) {
    const directions = [[0, 1], [0, -1], [1, 0], [-1, 0], [1, 1], [1, -1], [-1, 1], [-1, -1]];
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
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData("text/plain", JSON.stringify({ word, source: 'board', rowIndex, colIndex }));
}

function handleDrop(e, targetRowIndex, targetColIndex) {
    let page = getPage();
    e.preventDefault();
    e.stopPropagation();
    const payload = JSON.parse(e.dataTransfer.getData("text/plain"));
    const targetCell = data.board[targetRowIndex][targetColIndex];
    const emptySpot = findEmptyAdjacent(targetRowIndex, targetColIndex);

    if (targetCell && targetCell.isLocked && emptySpot) { page.toast("error", "Cannot bump a locked phrase."); return; }
    if (payload.source === 'common' && data.commonWordUsedThisTurn) return;
    if ((payload.source === 'left' && data.turn !== 1) || (payload.source === 'right' && data.turn !== 2)) return;
    if (payload.source === 'board' && payload.word.player !== data.turn) return;

    const wordWithPlayer = { ...payload.word, player: data.turn };

    if (targetCell) {
        const bumpedWord = targetCell;
        if (emptySpot) {
            data.board[emptySpot.r][emptySpot.c] = bumpedWord;
            page.toast("info", `Player ${bumpedWord.player}'s word '${bumpedWord.name}' was bumped.`);
        } else {
            if (bumpedWord.isLocked) {
                data.board.forEach(row => row.forEach(cell => {
                    if (cell && cell.phraseId === bumpedWord.phraseId) { cell.isLocked = false; cell.phraseId = null; cell.owner = null; }
                }));
                page.toast("info", `A locked phrase owned by Player ${bumpedWord.owner} was broken!`);
            }
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

    if (payload.source === 'left') { data.left.splice(payload.index, 1); if (data.left.length < 5) prepareWords(1, true); }
    else if (payload.source === 'right') { data.right.splice(payload.index, 1); if (data.right.length < 5) prepareWords(2, true); }
    else if (payload.source === 'board') { data.board[payload.rowIndex][payload.colIndex] = null; }
    else if (payload.source === 'common') { data.commonWordUsedThisTurn = true; }

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
    let page = getPage();
    e.preventDefault();
    const payload = JSON.parse(e.dataTransfer.getData("text/plain"));
    if (payload.source === 'board' && payload.word.player === data.turn) {
        const wordToTrash = payload.word;
        if (wordToTrash.isLocked) {
            data.board.forEach(row => row.forEach(cell => {
                if (cell && cell.phraseId === wordToTrash.phraseId) { cell.isLocked = false; cell.phraseId = null; cell.owner = null; }
            }));
            page.toast("info", "A locked phrase was broken!");
        }
        if (!wordToTrash.isCommon && !wordToTrash.isCustom) {
            if (data.turn === 1) data.player1.score -= 5;
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
    let page = getPage();
    if (!data.isGameStarted) {
        return m("div", { class: "flex-grow flex items-center justify-center bg-gray-100 dark:bg-black" }, [
            m("div", { class: "text-center" }, [
                m("h1", { class: "text-4xl font-bold mb-4 dark:text-white" }, "Word Battle"),
                m("button", { class: "px-6 py-3 bg-blue-600 text-white font-bold rounded-lg hover:bg-blue-500 text-2xl", onclick: startGame }, "Start Game")
            ])
        ]);
    }

    return m("div", { class: "relative flex-grow overflow-hidden flex bg-white border border-gray-200 dark:bg-black dark:border-gray-700 dark:text-gray-200" }, [
        data.isProcessingEndOfRound ? m("div", { class: "absolute inset-0 bg-black bg-opacity-70 flex items-center justify-center z-50" }, [
            m("div", { class: "text-white text-2xl font-bold animate-pulse" }, "Calculating End of Round Scores...")
        ]) : null,
        data.endGameInfo ? m("div", { class: "absolute inset-0 bg-black bg-opacity-80 flex items-center justify-center z-50 p-4" },
            m("div", { class: "bg-white dark:bg-gray-800 rounded-lg shadow-xl p-6 max-w-2xl w-full text-center" }, [
                m("h1", { class: "text-4xl font-bold text-blue-500 mb-2" }, "Game Over!"),
                m("h2", { class: "text-3xl font-semibold mb-4 dark:text-white" }, data.endGameInfo.winner),
                m("div", { class: "prose dark:prose-invert text-left p-4 my-4 border rounded-md max-h-60 overflow-y-auto" },
                    m.trust(typeof marked !== 'undefined' ? marked.parse(data.endGameInfo.finalMessage) : data.endGameInfo.finalMessage)
                ),
                m("button", { class: "px-6 py-3 bg-blue-600 text-white font-bold rounded-lg hover:bg-blue-500 text-xl mt-4", onclick: resetGame }, "Play Again")
            ])
        ) : null,
        m("div", { class: "flex flex-col w-full overflow-hidden p-0" }, [
            m("div", { class: "flex-1 overflow-hidden" }, [
                m("div", { class: "max-h-full h-full flex" }, [
                    // Player 1 Panel
                    m("div", { class: `flex flex-col w-1/4 p-4 overflow-y-auto mx-auto ${data.turn === 1 ? 'bg-blue-100 dark:bg-blue-900' : ''}` }, [
                        m("div", { class: "flex justify-between items-center" }, [
                            m("h3", { class: "text-lg font-bold" }, "Player 1"),
                            m("div", { class: "font-bold text-xl p-2 bg-gray-200 dark:bg-gray-700 rounded" }, data.player1.score)
                        ]),
                        m("input", {
                            class: "w-full p-1 my-2 border rounded dark:bg-gray-900 dark:border-gray-600",
                            placeholder: "Add custom word...",
                            value: data.player1.customWord,
                            oninput: (e) => handleCustomWord(1, e.target.value),
                            onblur: () => addCustomWord(1),
                            disabled: data.turn !== 1
                        }),
                        m("button", { class: "px-3 py-1.5 rounded bg-gray-200 hover:bg-gray-300 dark:bg-gray-700 dark:hover:bg-gray-600 flex items-center gap-1 text-sm", onclick: () => handleRefreshWords(1), disabled: data.turn !== 1 }, [
                            m("span", { class: "material-symbols-outlined text-base" }, "refresh"), "Refresh Words"
                        ]),
                        leftWords()
                    ]),

                    // Center Game Board
                    m("div", { class: "flex flex-col w-3/5 p-2 overflow-auto mx-auto" }, [
                        m("h2", { class: "text-center text-2xl font-bold" }, "Word Battle"),
                        m("div", { class: "text-center font-semibold text-lg" }, `Round: ${data.currentRound} | Actions Left: ${20 - data.actionsThisRound}`),
                        m("div", { class: "text-center font-semibold text-lg" }, `Turn: Player ${data.turn} | Time: ${data.timeLeft}s | Turn Actions: ${data.turnPoints}`),
                        m("div", { class: "flex flex-wrap gap-1 my-2 justify-center" },
                            data.activeCommonWords.map(word =>
                                m("button", {
                                    draggable: !data.commonWordUsedThisTurn,
                                    class: `px-2 py-1 rounded text-sm ${getWordColorClass({ isCommon: true })} ${data.commonWordUsedThisTurn ? 'opacity-50 cursor-not-allowed' : ''}`,
                                    ondragstart: (e) => {
                                        if (data.commonWordUsedThisTurn) { e.preventDefault(); return; }
                                        e.dataTransfer.effectAllowed = 'move';
                                        e.dataTransfer.setData("text/plain", JSON.stringify({ word: { name: word, isCommon: true }, source: 'common' }));
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
                        m("div", { class: "flex justify-center items-center gap-1 mt-2" }, [
                            m("button", { class: "p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700", onclick: resetGame }, m("span", { class: "material-symbols-outlined" }, "restart_alt")),
                            m("button", { class: "p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700", onclick: togglePause }, m("span", { class: "material-symbols-outlined" }, data.isPaused ? "play_arrow" : "pause")),
                            m("button", { class: "p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700", onclick: exportBoardState }, m("span", { class: "material-symbols-outlined" }, "download")),
                            m("button", { class: "p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700", onclick: () => shiftWords('up'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_upward")),
                            m("button", { class: "p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700", onclick: () => shiftWords('down'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_downward")),
                            m("button", { class: "p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700", onclick: () => shiftWords('left'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_back")),
                            m("button", { class: "p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700", onclick: () => shiftWords('right'), disabled: data.turnPoints < 5 }, m("span", { class: "material-symbols-outlined" }, "arrow_forward")),
                            m("button", { class: "px-3 py-1.5 rounded bg-gray-200 hover:bg-gray-300 dark:bg-gray-700 dark:hover:bg-gray-600 text-sm", onclick: () => { page.toast("info", `Player ${data.turn} passed their turn.`); switchTurn(); } }, "Pass"),
                            m("button", {
                                class: "px-3 py-1.5 rounded bg-red-100 hover:bg-red-200 dark:bg-red-900 dark:hover:bg-red-800 text-sm flex items-center gap-1 ml-2",
                                ondragover: (e) => e.preventDefault(),
                                ondrop: handleTrashDrop
                            }, [m("span", { class: "material-symbols-outlined text-base" }, "delete"), "Trash"])
                        ])
                    ]),

                    // Player 2 Panel
                    m("div", { class: `flex flex-col w-1/4 p-4 overflow-y-auto mx-auto ${data.turn === 2 ? 'bg-green-100 dark:bg-green-900' : ''}` }, [
                        m("div", { class: "flex justify-between items-center" }, [
                            m("h3", { class: "text-lg font-bold" }, "Player 2"),
                            m("div", { class: "font-bold text-xl p-2 bg-gray-200 dark:bg-gray-700 rounded" }, data.player2.score)
                        ]),
                        m("input", {
                            class: "w-full p-1 my-2 border rounded dark:bg-gray-900 dark:border-gray-600",
                            placeholder: "Add custom word...",
                            value: data.player2.customWord,
                            oninput: (e) => handleCustomWord(2, e.target.value),
                            onblur: () => addCustomWord(2),
                            disabled: data.turn !== 2
                        }),
                        m("button", { class: "px-3 py-1.5 rounded bg-gray-200 hover:bg-gray-300 dark:bg-gray-700 dark:hover:bg-gray-600 flex items-center gap-1 text-sm", onclick: () => handleRefreshWords(2), disabled: data.turn !== 2 }, [
                            m("span", { class: "material-symbols-outlined text-base" }, "refresh"), "Refresh Words"
                        ]),
                        m("label", { class: "flex items-center my-2 text-sm" }, [
                            m("input", {
                                type: "checkbox",
                                checked: data.player2.autopilot,
                                onchange: (e) => { data.player2.autopilot = e.target.checked; if (data.turn === 2 && data.player2.autopilot) runAutopilot(); }
                            }),
                            m("span", { class: "ml-2" }, "Autopilot")
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
        class: `px-2 py-1 rounded text-sm mb-1 w-full text-left ${getWordColorClass(word)}`,
        title: word.definition,
        ondragstart: (e) => {
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData("text/plain", JSON.stringify({ word, source: 'left', index }));
        }
    }, word.name));
}

function rightWords() {
    return data.right.map((word, index) => m("button", {
        draggable: data.turn === 2,
        class: `px-2 py-1 rounded text-sm mb-1 w-full text-left ${getWordColorClass(word)}`,
        title: word.definition,
        ondragstart: (e) => {
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData("text/plain", JSON.stringify({ word, source: 'right', index }));
        }
    }, word.name));
}

// --- DATA FETCHING AND SETUP ---

async function prepareWords(playerNum, isAutoRefresh) {
    let page = getPage();
    let am7client = getClient();
    let grp = await page.findObject("auth.group", "data", "/Library/Dictionary");
    if (!grp) return;

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
        q.range(0, 30);
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
        if (isAutoRefresh || playerNum) return playerList.concat(newWords);
        return newWords;
    };

    if (playerNum === 1) data.left = assignWords(data.left);
    else if (playerNum === 2) data.right = assignWords(data.right);
    else { data.left = assignWords([]); data.right = assignWords([]); }
    m.redraw();
}

function setup() {
    randomizeCommonWords();
}

// --- Component ---

const wordGame = {
    scoreCard: () => "",
    oninit: function() { setup(); },
    onremove: function() {
        let page = getPage();
        let am7chat = page.components.chat;
        if (data) data.isPaused = true;
        if (am7chat) {
            if (gameChat) am7chat.deleteChat(gameChat, true);
            if (endGameChat) am7chat.deleteChat(endGameChat, true);
        }
        clearInterval(data.timerId);
    },
    view: function() { return modelPanel(); }
};

export { wordGame };
export default wordGame;
