/**
 * ChatExport — Export conversation history as Markdown or JSON (ESM)
 */

const ChatExport = {
    /**
     * Export chat history as a downloadable file.
     * @param {object} history - { messages: [{role, content}] }
     * @param {string} sessionName - Name for the file
     * @param {string} format - "markdown" | "json"
     * @param {object} chatCfg - Optional config with system/user character names
     */
    exportChat: function(history, sessionName, format, chatCfg) {
        if (!history || !history.messages || history.messages.length === 0) return;

        let filename = (sessionName || "chat").replace(/[^a-zA-Z0-9_-]/g, "_");
        let content, mimeType, ext;

        if (format === "json") {
            content = JSON.stringify(history, null, 2);
            mimeType = "application/json";
            ext = "json";
        } else {
            content = toMarkdown(history, chatCfg);
            mimeType = "text/markdown";
            ext = "md";
        }

        let blob = new Blob([content], { type: mimeType });
        let url = URL.createObjectURL(blob);
        let a = document.createElement("a");
        a.href = url;
        a.download = filename + "." + ext;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }
};

function toMarkdown(history, chatCfg) {
    let lines = ["# Chat Export", ""];
    lines.push("*Exported: " + new Date().toISOString() + "*", "");
    lines.push("---", "");

    let systemName = chatCfg && chatCfg.system ? chatCfg.system.name : "Assistant";

    history.messages.forEach(function(msg) {
        let role = msg.role === "user" ? "**You**" : "**" + systemName + "**";
        lines.push("### " + role, "");
        lines.push(msg.content || "", "");
        lines.push("---", "");
    });

    return lines.join("\n");
}

export { ChatExport };
export default ChatExport;
