/* Identified bounded file transport for JoiPlay's native HTML/NW.js runtime. */
(function (root) {
    "use strict";
    if (root.LunaJoiWeb) return;
    var config = __LUNA_JOI_CONFIG__;
    var session = String(Date.now()) + "-" + String(Math.random()).slice(2);
    var sequence = 0, frames = [], pendingVisibility = null, lastVisible = false;
    var status = { engine: config.engine, writes: 0, lastError: null };
    function hex(value) {
        var bytes = unescape(encodeURIComponent(String(value)));
        // Protocol accepts at most 16 KiB of UTF-8 per frame. Never split a code point.
        if (bytes.length > 16384) return null;
        var result = "";
        for (var i = 0; i < bytes.length; i++) result += ("0" + bytes.charCodeAt(i).toString(16)).slice(-2);
        return result;
    }
    function row(kind, value) {
        var encoded = hex(value);
        return encoded === null ? null : session + "\t" + (++sequence) + "\t" + kind + "\t" + encoded + "\n";
    }
    function visible() {
        if (config.engine === "TYRANO" || config.engine === "HTML") return pendingVisibility === true;
        var message = root.$gameMessage;
        var busy = message && typeof message.isBusy === "function" && message.isBusy();
        var battle = root.$gameParty && root.$gameParty.inBattle && root.$gameParty.inBattle();
        var scene = root.SceneManager && root.SceneManager._scene;
        // A picture/dialogue or tactical command capture may outlive Game_Message.
        var custom = pendingVisibility && root.LunaGameBridge && root.LunaGameBridge.status.captionVisible;
        if (scene && root.Scene_Title && scene instanceof root.Scene_Title) return false;
        return !!(busy || battle || custom);
    }
    function flush(force) {
        try {
            var isVisible = visible();
            if (!isVisible && lastVisible) frames = [];
            lastVisible = isVisible;
            var title = root.$dataSystem && root.$dataSystem.gameTitle || root.document && root.document.title || config.title;
            title = String(title || config.title).replace(/[\r\n\t]/g, " ").slice(0, 120);
            var meta = row("luna_game", ["LUNA_JOI_1", config.id, config.engine, title, Date.now(), isVisible ? "1" : "0"].join("\n"));
            while (frames.length > 16 || frames.join("").length > 120000) frames.shift();
            var fs = root.require("fs"), path = root.require("path"), process = root.require("process");
            var folder = process.mainModule && process.mainModule.filename ? path.dirname(process.mainModule.filename) : process.cwd();
            fs.writeFileSync(path.join(folder, "LunaGameText.txt"), frames.join("") + meta, "utf8");
            status.writes++; status.lastError = null;
        } catch (error) { status.lastError = error.name || "WriteError"; }
    }
    root.LunaGameTextTransport = function (event) {
        if (event.kind === "visibility") pendingVisibility = event.visible === true;
        else if (/^(dialogue|choices|battle_message|battle_log)$/.test(event.kind)) {
            var text = String(event.text || "").trim();
            if (!text) return;
            var value = row(event.kind, text);
            if (!value) return;
            var message = root.$gameMessage;
            var nativeBusy = message && message.isBusy && message.isBusy();
            if (!nativeBusy) pendingVisibility = true;
            frames.push(value);
        } else return;
        flush(true);
    };
    root.LunaJoiWeb = { status: status };
    root.setInterval(function () { flush(false); }, 1000);
    // Native NWJS API is injected by JoiPlay before game scripts.
    flush(false);
}(typeof window !== "undefined" ? window : this));
