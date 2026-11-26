/* Luna game-text bridge for RPG Maker MV/MZ and Tyrano. ES5 for older runtimes. */
(function (root) {
    "use strict";
    if (root.LunaGameBridge) return;
    var session = String(Date.now()) + "-" + String(Math.random()).slice(2);
    var sequence = 0;
    var installed = {};
    var writer = null;
    var status = { engine: null, hooks: installed, transport: "unavailable", lastError: null };

    function attachAudioFetchCompatibility() {
        // Some Android exports replace relative fetch with a text-only file://
        // shim: no response.body and an empty arrayBuffer. Our assets have a
        // real HTTPS origin, so retain the game's loader and give its fetch the
        // equivalent absolute URL, which that shim delegates to native fetch.
        var location = root.location, Audio = root.WebAudio;
        if (!location || location.protocol !== "https:" ||
            !/^[a-f0-9]{16}\.appassets\.androidplatform\.net$/i.test(location.hostname) ||
            !root.document || typeof root.URL !== "function" || !Audio || !Audio.prototype) return;
        var original = Audio.prototype._startFetching;
        if (typeof original !== "function" || original._lunaAbsoluteAudio) return;
        var replacement = function (url) {
            var args = Array.prototype.slice.call(arguments);
            if (typeof url === "string") {
                var resolved = new root.URL(url, root.document.baseURI);
                if (resolved.origin === location.origin) args[0] = resolved.href;
            }
            return original.apply(this, args);
        };
        replacement._lunaAbsoluteAudio = true;
        Audio.prototype._startFetching = replacement;
        installed["same-origin-audio-fetch"] = true;
        status.audioFetchCompatibility = "same-origin absolute audio URLs";
    }

    // Some exported games scope a Node-only JSON decoder inside loadDataFile.
    // Supply just that function's read-only dependencies, without making the
    // whole browser pretend to be NW.js or modifying any game source files.
    function attachDataCompatibility() {
        var manager = root.DataManager;
        if (!manager || typeof manager.loadDataFile !== "function" || typeof root.require === "function") return;
        var original = manager.loadDataFile;
        var code = original.toString();
        if (original._lunaScopedNode || code.indexOf("Buffer.from(c.data") < 0 || code.indexOf("window._K") < 0) return;
        var buffer = { from: function (data, encoding) {
            if (encoding !== "base64") throw new Error("Unsupported buffer encoding");
            var binary = root.atob(data);
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
            bytes.toString = function (format) {
                if (format !== "utf8") throw new Error("Unsupported text encoding");
                return new TextDecoder("utf-8").decode(bytes);
            };
            return bytes;
        } };
        var scopedRequire = function (name) {
            if (name === "fs") return { existsSync: function (value) {
                return typeof root.LunaGameFileExists === "function" && root.LunaGameFileExists(String(value));
            } };
            if (name === "path") return { join: function () { return Array.prototype.join.call(arguments, "/"); } };
            throw new Error("Unsupported scoped Node module: " + name);
        };
        var scopedGraphics = Object.create(root.Graphics);
        var ScopedBitmap = function () {};
        ScopedBitmap.prototype = Object.create(root.Bitmap.prototype);
        manager.loadDataFile = new Function("require", "Buffer", "process", "Graphics", "Bitmap", "return (" + code + ");")(
            scopedRequire, buffer, { cwd: function () { return ""; } }, scopedGraphics, ScopedBitmap);
        manager.loadDataFile._lunaScopedNode = true;
        status.compatibility = "scoped Node JSON loader";
    }

    // Synrec's desktop motion plugins synchronously read their own animation
    // assets. Keep their functions and data, supplying only a scoped read-only
    // filesystem for this game's asset origin. Do not make the browser report
    // itself as NW.js or expose a global require/file-write API.
    var gameReadOnlyFs = null;
    function readOnlyAssetFs() {
        if (gameReadOnlyFs) return gameReadOnlyFs;
        function pathOf(value) {
            var path = String(value).replace(/\\/g, "/");
            if (!path || /[\x00?#:]/.test(path) || path.charAt(0) === "/") throw new Error("Game asset path required");
            var parts = path.split("/").filter(function (part) { return part && part !== "."; });
            if (parts.some(function (part) { return part === ".." || /%/.test(part); })) throw new Error("Game asset path required");
            return parts.join("/");
        }
        function assetOf(value) {
            var path = pathOf(value);
            var nativeExists = typeof root.LunaGameFileExists === "function" && root.LunaGameFileExists(path);
            if (!nativeExists && /\.png$/i.test(path) && root.Utils && root.Utils.hasEncryptedImages &&
                root.Utils.hasEncryptedImages() && root.Utils.decryptArrayBuffer &&
                root.LunaGameFileExists(path + "_")) return { path: path + "_", encrypted: true, exists: true };
            return { path: path, encrypted: false, exists: nativeExists };
        }
        function exists(value) {
            try {
                return !!assetOf(value).exists;
            } catch (_) { return false; }
        }
        function read(value, encoding) {
            var asset = assetOf(value), path = asset.path;
            var format = typeof encoding === "string" ? encoding : encoding && encoding.encoding;
            if (format && !/^utf-?8$/i.test(format)) throw new Error("Unsupported game asset encoding");
            var request = new root.XMLHttpRequest();
            request.open("GET", path.split("/").map(encodeURIComponent).join("/"), false);
            request.overrideMimeType(format && !asset.encrypted ? "text/plain; charset=utf-8" : "text/plain; charset=x-user-defined");
            request.send(null);
            if (request.status < 200 || request.status >= 300) throw new Error("Game asset not found: " + path);
            if (format && !asset.encrypted) return request.responseText;
            var text = request.responseText, bytes = new Uint8Array(text.length);
            for (var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i) & 255;
            if (asset.encrypted) bytes = new Uint8Array(root.Utils.decryptArrayBuffer(bytes.buffer));
            if (format) return new TextDecoder("utf-8").decode(bytes);
            bytes.toString = function (format) {
                if (format && !/^utf-?8$/i.test(format)) throw new Error("Unsupported game asset encoding");
                return new TextDecoder("utf-8").decode(bytes);
            };
            return bytes;
        }
        gameReadOnlyFs = { existsSync: exists, readFileSync: read, promises: {
            access: function (path) { return exists(path) ? Promise.resolve() : Promise.reject(new Error("Game asset not found")); }
        } };
        return gameReadOnlyFs;
    }
    function attachSynrecAssets() {
        if (typeof root.require === "function" || typeof root.LunaGameFileExists !== "function") return;
        ["Game_MapBattler", "Window_LogTBS"].forEach(function (name) {
            var klass = root[name], proto = klass && klass.prototype;
            if (!proto) return;
            Object.getOwnPropertyNames(proto).forEach(function (method) {
                var descriptor = Object.getOwnPropertyDescriptor(proto, method);
                var original = descriptor && descriptor.value;
                if (typeof original !== "function" || original._lunaScopedFs) return;
                var code = original.toString();
                if (!/require\s*\(\s*['"]fs['"]\s*\)/.test(code)) return;
                var replacement = new Function("require", "return (" + code + ");")(function (module) {
                    if (module !== "fs") throw new Error("Unsupported game asset module");
                    return readOnlyAssetFs();
                });
                replacement._lunaScopedFs = true;
                Object.defineProperty(proto, method, Object.assign({}, descriptor, { value: replacement }));
                installed["synrec-assets-" + name + "." + method] = true;
                status.synrecAssets = "scoped read-only game assets";
            });
        });
    }

    function output(kind, text, bounds) {
        text = String(text || "").replace(/\x1b[A-Za-z]+\[[^\]]*\]/g, "")
            .replace(/\x1b(?:CR|OC|FR|FB|FI)\b/gi, "")
            .replace(/\x1b[.!|^><{}$]/g, "").trim();
        if (!text) return;
        if (!emittingPicturePage) pictureCaptionActive = false;
        reportTbsVisibility(true);
        if (kind !== "choices") tbsCaptionActive = false;
        try {
            if (typeof root.LunaGameTextTransport === "function") {
                root.LunaGameTextTransport({ session: session, sequence: ++sequence, kind: kind, text: text, bounds: bounds || null });
                status.transport = "host";
                return;
            }
            if (!writer) {
                var fs = require("fs");
                var path = require("path");
                var folder = process.mainModule && process.mainModule.filename ?
                    path.dirname(process.mainModule.filename) : process.cwd();
                var destination = path.join(folder, "LunaGameText.txt");
                writer = function (value) {
                    var size = fs.existsSync(destination) ? fs.statSync(destination).size : 0;
                    fs.writeFileSync(destination, value, { encoding: "utf8", flag: size < 131072 ? "a" : "w" });
                };
            }
            var hex = Buffer.from(text, "utf8").toString("hex");
            writer(session + "\t" + (++sequence) + "\t" + kind + "\t" + hex + "\n");
            status.transport = "file";
            status.lastError = null;
        } catch (error) {
            status.lastError = error.name || "WriteError";
        }
    }

    function hookObject(target, method, name, before, after) {
        if (!target || typeof target[method] !== "function" || target[method]._lunaWrapped) return;
        var original = target[method];
        target[method] = function () {
            try { if (before) before.apply(this, arguments); }
            catch (error) { status.lastError = error.name || "CaptureError"; }
            var result = original.apply(this, arguments);
            try { if (after) after.apply(this, arguments); }
            catch (error) { status.lastError = error.name || "CaptureError"; }
            return result;
        };
        target[method]._lunaWrapped = true;
        installed[name] = true;
    }
    function hook(klass, method, name, before, after) {
        if (klass) hookObject(klass.prototype, method, name, before, after);
    }

    var lastPictureText = "";
    var pictureSeen = new WeakMap();
    var pictureTimer = null;
    var pendingPictureText = "";
    var pictureCaptionActive = false;
    var emittingPicturePage = false;
    var observedPicturePage = null;
    function pictureText(picture) {
        return picture && (picture.mzkp_text || picture.dTextValue ||
            (picture.dTextInfo && (picture.dTextInfo.value || picture.dTextInfo.text)));
    }
    function inspectPicture(picture) {
        if (!picture) return;
        var text = pictureText(picture);
        if (typeof text === "string" && pictureSeen.get(picture) !== text) {
            pictureSeen.set(picture, text);
            capturePictureText(text);
        }
    }
    function capturePictureText(text) {
        if (typeof text !== "string" || !/[a-zA-Z\u3040-\u30ff\u3400-\u9fff]/.test(text)) return;
        pendingPictureText = text;
        queuePicturePage();
    }
    function queuePicturePage() {
        // Keep the first deadline: continuously redrawn pictures must not
        // postpone the complete visible page forever.
        if (pictureTimer) return;
        pictureTimer = root.setTimeout(flushPicturePage, 100);
    }
    function visiblePicturePage(scene) {
        var screen = root.$gameScreen;
        if (screen && screen._pictures) {
            var entries = [];
            var sprites = scene && scene._spriteset && scene._spriteset._pictureContainer;
            if (sprites && sprites.children && typeof screen.picture === "function") {
                sprites.children.forEach(function (sprite) {
                    if (sprite._pictureId && sprite.visible && sprite.alpha > 0) entries.push(screen.picture(sprite._pictureId));
                });
            } else {
                var max = typeof screen.maxPictures === "function" ? screen.maxPictures() : 100;
                var start = root.$gameParty && root.$gameParty.inBattle && root.$gameParty.inBattle() ? max : 0;
                entries = screen._pictures.slice(start + 1, start + max + 1);
            }
            entries = entries.filter(function (picture) {
                return picture && picture._opacity !== 0 && typeof pictureText(picture) === "string";
            }).sort(function (a, b) { return (a._y || 0) - (b._y || 0) || (a._x || 0) - (b._x || 0); });
            var values = [];
            entries.forEach(function (picture) {
                var value = pictureText(picture);
                if (value && values.indexOf(value) < 0) values.push(value);
            });
            return values.join("\n");
        }
        return null;
    }
    function monitorPicturePage() {
        var scene = root.SceneManager && root.SceneManager._scene;
        var text = visiblePicturePage(scene);
        if (text === null) return;
        var busy = hasRegularMessageText();
        var signature = (busy ? "message:" : "picture:") + text;
        if (signature !== observedPicturePage) {
            observedPicturePage = signature;
            queuePicturePage();
        }
    }
    function flushPicturePage() {
        pictureTimer = null;
        var scene = root.SceneManager && root.SceneManager._scene;
        var win = scene && scene._messageWindow;
        var current = visiblePicturePage(scene);
        var text = current === null ? pendingPictureText : current;
        pendingPictureText = "";
        // A normal message window owns its complete dialogue page.
        if (hasRegularMessageText()) return;
        if (!text) {
            lastPictureText = "";
            if (pictureCaptionActive) reportTbsVisibility(false);
            pictureCaptionActive = false;
            return;
        }
        if (win && win.convertEscapeCharacters) text = win.convertEscapeCharacters(text);
        if (text === lastPictureText && pictureCaptionActive) return;
        lastPictureText = text;
        emittingPicturePage = true;
        try { output("dialogue", text); pictureCaptionActive = true; }
        finally { emittingPicturePage = false; }
    }
    function hasRegularMessageText() {
        // Picture-dialogue plugins can return hasText=true only to wait for
        // input, while allText() is empty. That flag does not own a text page.
        var message = root.$gameMessage;
        return !!(message && typeof message.allText === "function" && String(message.allText() || "").trim());
    }

    function windowBounds(win) {
        if (!root.Graphics || !root.Graphics._canvas || !root.innerHeight) return null;
        var rect = root.Graphics._canvas.getBoundingClientRect();
        var height = root.Graphics.height || root.Graphics._height;
        return { top: (rect.top + win.y * rect.height / height) / root.innerHeight,
            bottom: (rect.top + (win.y + win.height) * rect.height / height) / root.innerHeight };
    }

    var lastTbsChoices = "";
    var tbsCaptionVisible = true;
    var tbsCaptionActive = false;
    function reportTbsVisibility(visible) {
        if (visible === tbsCaptionVisible) return;
        tbsCaptionVisible = visible;
        status.captionVisible = visible;
        if (typeof root.LunaGameTextTransport === "function") {
            root.LunaGameTextTransport({session:session,sequence:++sequence,kind:"visibility",visible:visible});
        }
    }
    function captureTbsChoices() {
        if (!root.$gameMap || !root.$gameMap._tbs) { lastTbsChoices = ""; tbsCaptionActive = false; return; }
        if (root.$gameMessage && root.$gameMessage.isBusy()) { lastTbsChoices = ""; return; }
        var scene = this, windows = [];
        Object.keys(scene).forEach(function (key) {
            var win = scene[key];
            if (!root.Window_Selectable || !(win instanceof root.Window_Selectable) ||
                !win.visible || !win.active || win.openness === 0) return;
            var values = win._list || win._data;
            if (!Array.isArray(values)) return;
            var names = values.map(function (value) {
                if (!value) return "";
                return typeof value.name === "function" ? value.name() : String(value.name || "");
            }).filter(function (name) { return /[A-Za-z\u3040-\u30ff\u3400-\u9fff]/.test(name); });
            if (names.length) windows.push({ win: win, text: names.slice(0, 40).join("\n") });
        });
        var signature = windows.map(function (entry) { return entry.text; }).join("\n");
        if (signature) {
            reportTbsVisibility(true);
            if (signature !== lastTbsChoices) {
                output("choices", signature, windowBounds(windows[0].win));
                tbsCaptionActive = true;
            }
        } else if (tbsCaptionActive) reportTbsVisibility(false);
        lastTbsChoices = signature;
    }

    var tyranoPage = [];
    var tyranoChoices = [];
    var tyranoTimer = null;
    var tyranoVersion = 0;
    var tyranoTextSerial = 0;
    var tyranoEmitted = -1;
    var tyranoCaptionVisible = null;
    function tyranoClear() {
        if (tyranoTimer) root.clearTimeout(tyranoTimer);
        tyranoPage = []; tyranoChoices = []; tyranoVersion++;
    }
    function tyranoSpeaker() {
        try {
            var kag = root.TYRANO && root.TYRANO.kag;
            var name = kag && kag.chara && kag.chara.getCharaNameArea && kag.chara.getCharaNameArea();
            return name && typeof name.text === "function" ? name.text().trim() : "";
        } catch (_) { return ""; }
    }
    function attachTyranoStorage() {
        var jq = root.jQuery;
        if (root.LunaJoiWeb && (!root.LunaJoiWeb.status || root.LunaJoiWeb.status.engine !== "TYRANO")) return;
        if (!jq || (typeof root.require === "function" && !root.LunaJoiWeb) || root.studio_api) return;
        if (root.LunaJoiWeb && typeof jq.isNWJS === "function" && !jq.isNWJS._lunaJoiBrowser) {
            // JoiPlay exposes a partial NW.js API; exported Tyrano 5 desktop
            // helpers require Electron studio_api even though it is absent.
            // Select Tyrano's existing browser branches, not a fake Electron API.
            jq.isNWJS = function () { return false; };
            jq.isNWJS._lunaJoiBrowser = true;
            status.joiTyranoCompatibility = "native browser storage branches";
        }
        ["get", "set", "remove"].forEach(function (operation) {
            var file = operation + "StorageFile", web = operation + "StorageWeb";
            if (typeof jq[file] === "function" && typeof jq[web] === "function" && !jq[file]._lunaStorage) {
                jq[file] = function () { return jq[web].apply(this, arguments); };
                jq[file]._lunaStorage = true;
                status.storage = "isolated browser storage";
            }
        });
    }
    function tyranoBounds() {
        try {
            var kag = root.TYRANO && root.TYRANO.kag;
            var layer = kag && kag.getMessageOuterLayer && kag.getMessageOuterLayer();
            var element = layer && layer.get && layer.get(0);
            if (!element || !root.innerHeight) return null;
            var rect = element.getBoundingClientRect();
            if (!rect.height) return null;
            return { top: rect.top / root.innerHeight, bottom: rect.bottom / root.innerHeight };
        } catch (_) { return null; }
    }
    function tyranoVisibility() {
        var kag = root.TYRANO && root.TYRANO.kag;
        var layer = kag && kag.getMessageInnerLayer && kag.getMessageInnerLayer();
        if (!layer || typeof layer.is !== "function") return null;
        return !!(layer.is(":visible") || tyranoChoices.length);
    }
    function reportTyranoVisibility() {
        var visible = tyranoVisibility();
        if (visible === null || visible === tyranoCaptionVisible) return;
        tyranoCaptionVisible = visible;
        status.captionVisible = visible;
        if (typeof root.LunaGameTextTransport === "function") {
            root.LunaGameTextTransport({session:session,sequence:++sequence,kind:"visibility",visible:visible});
        }
    }
    function tyranoFlush() {
        reportTyranoVisibility();
        if (tyranoVisibility() === false) { tyranoClear(); return; }
        if ((tyranoPage.length || tyranoChoices.length) && tyranoEmitted !== tyranoVersion) {
            var speaker = tyranoSpeaker();
            output(tyranoChoices.length ? "choices" : "dialogue",
                (speaker ? [speaker] : []).concat(tyranoPage, tyranoChoices).join("\n"), tyranoBounds());
            tyranoEmitted = tyranoVersion;
        }
    }
    function attachTyranoLifecycle(kag) {
        // nextOrder clears flag_ref_page before text.start is called. Observe
        // the boundary here so the previous page never leaks into the next.
        hookObject(kag.ftag, "nextOrder", "tyrano-page-boundary", function () {
            if (this.kag.stat.flag_ref_page) tyranoClear();
        }, reportTyranoVisibility);
        // Loading restores message layers without executing their text tags.
        // Read that engine-owned layer after restoration, once, unless normal
        // text callbacks have already supplied a newer page in the meantime.
        hookObject(kag.menu, "loadGameData", "tyrano-loaded-page", tyranoClear, function () {
            var serial = tyranoTextSerial, attempts = 0;
            function restored() {
                if (serial !== tyranoTextSerial) return;
                var layer = kag.getMessageInnerLayer && kag.getMessageInnerLayer();
                var element = layer && layer.get && layer.get(0);
                var text = element && element.innerText;
                if (text && !kag.stat.is_adding_text && kag.stat.current_scenario !== "make.ks") {
                    tyranoPage = [text]; tyranoVersion++; tyranoFlush();
                } else if (++attempts < 50) root.setTimeout(restored, 100);
            }
            root.setTimeout(restored, 150);
        });
    }
    function attachTyrano(tags, suffix) {
        if (!tags) return;
        hookObject(tags.text, "start", "tyrano-text-" + suffix, function (pm) {
            if (!pm || this.kag.stat.is_script || this.kag.stat.is_html) return;
            if (this.kag.stat.flag_ref_page) tyranoClear();
            tyranoPage.push(String(pm.val || ""));
            tyranoTextSerial++;
            tyranoVersion++;
            if (tyranoPage.length > 30) tyranoPage.shift();
            if (tyranoTimer) root.clearTimeout(tyranoTimer);
            tyranoTimer = root.setTimeout(tyranoFlush, 250);
        });
        hookObject(tags.glink, "start", "tyrano-choices-" + suffix, function (pm) {
            if (!pm || !pm.text) return;
            tyranoTextSerial++;
            tyranoChoices.push(String(pm.text)); tyranoVersion++;
            if (tyranoTimer) root.clearTimeout(tyranoTimer);
            tyranoTimer = root.setTimeout(tyranoFlush, 250);
        });
        ["p", "l", "s"].forEach(function (name) {
            hookObject(tags[name], "start", "tyrano-wait-" + suffix + name, function () {
                if (tyranoTimer) root.clearTimeout(tyranoTimer);
                tyranoFlush();
            });
        });
        ["cm", "er"].forEach(function (name) {
            hookObject(tags[name], "start", "tyrano-clear-" + suffix + name, tyranoClear);
        });
    }

    function attachTyranoTouch() {
        if (installed["tyrano-touch-click"] || !root.addEventListener || typeof root.require === "function") return;
        var touch = null;
        root.addEventListener("touchend", function (event) {
            if (!event.isTrusted || !event.changedTouches || event.changedTouches.length !== 1) return;
            var point = event.changedTouches[0];
            touch = {target:event.target,x:point.clientX,y:point.clientY,pageX:point.pageX,pageY:point.pageY,at:Date.now()};
        }, true);
        root.addEventListener("click", function (event) {
            // Legacy Tyrano synthesizes a zero-coordinate click from touchend.
            // Repair that same event in place; never dispatch an extra click.
            if (!touch || event.isTrusted || event.clientX !== 0 || event.clientY !== 0 ||
                event.target !== touch.target || Date.now() - touch.at > 750) return;
            var point = touch; touch = null;
            var rect = event.target.getBoundingClientRect();
            if (!rect.width || !rect.height) return;
            var values = {clientX:point.x,clientY:point.y,pageX:point.pageX,pageY:point.pageY,
                offsetX:(point.x-rect.left)*(event.target.offsetWidth||rect.width)/rect.width,
                offsetY:(point.y-rect.top)*(event.target.offsetHeight||rect.height)/rect.height};
            try {
                Object.keys(values).forEach(function (key) {
                    Object.defineProperty(event,key,{value:values[key],configurable:true});
                });
                status.touchClicksRepaired = (status.touchClicksRepaired || 0) + 1;
            } catch (_) { status.lastError = "TouchCompatibilityError"; }
        }, true);
        installed["tyrano-touch-click"] = true;
    }

    function attach() {
        attachAudioFetchCompatibility();
        attachTyranoStorage();
        try { attachDataCompatibility(); } catch (error) { status.lastError = error.name || "CompatibilityError"; }
        try { attachSynrecAssets(); } catch (error) { status.lastError = error.name || "AssetCompatibilityError"; }
        if (root.Utils) status.engine = root.Utils.RPGMAKER_NAME || null;
        var kag = root.TYRANO && root.TYRANO.kag;
        if (kag && kag.ftag) {
            status.engine = "Tyrano";
            attachTyranoTouch();
            attachTyranoLifecycle(kag);
            attachTyrano(kag.ftag.master_tag, "runtime");
            if (root.tyrano && root.tyrano.plugin) attachTyrano(root.tyrano.plugin.kag.tag, "template");
        }
        hook(root.Window_Message, "startMessage", "message", function () {
            var message = root.$gameMessage;
            if (!message) return;
            var speaker = typeof message.speakerName === "function" ? message.speakerName() : "";
            var nameTag = message.allText().match(/\\n<([^>]+)>/i);
            if (!speaker && nameTag) speaker = nameTag[1];
            var page = (speaker ? [speaker, message.allText()] : [message.allText()]).concat(message.choices());
            var text = this.convertEscapeCharacters(page.join("\n"));
            output(root.$gameParty && root.$gameParty.inBattle() ? "battle_message" : "dialogue", text, windowBounds(this));
        });
        hook(root.Window_ChoiceList, "start", "choices", function () {
            if (!root.$gameMessage.hasText()) {
                output("choices", this.convertEscapeCharacters(root.$gameMessage.choices().join("\n")), windowBounds(this));
            }
        });
        hook(root.Window_BattleLog, "addText", "battle", function (text) {
            var scene = root.SceneManager && root.SceneManager._scene;
            var controls = scene && (scene._actorCommandWindow || scene._partyCommandWindow);
            // Keep a multi-line reading page above the battle controls even
            // when a plugin allocates a very tall, mostly empty log window.
            output("battle_log", this.convertEscapeCharacters(text), windowBounds(controls && controls.y > 0 ? controls : this));
        });
        // Tactical map combat has no ordinary battle log. Capture its actual
        // active command/skill list, without inventing combat narration.
        hook(root.Scene_Map, "update", "tactical-map-choices", null, captureTbsChoices);
        hook(root.Scene_Base, "update", "picture-page-visibility", null, monitorPicturePage);
        hook(root.Game_Screen, "setDTextPicture", "dynamic-picture-text", function (text) { capturePictureText(text); });
        hook(root.Game_Picture, "show", "picture-text", null, function () {
            inspectPicture(this);
        });
        hook(root.Game_Screen, "erasePicture", "picture-page-erase", function (id) {
            if (typeof this.picture === "function" && pictureText(this.picture(id))) queuePicturePage();
        });
        hookObject(root.PluginManager, "callCommand", "text-picture-command", function (self, plugin, command, args) {
            if (/^(DTextPicture|TextPicture|TextPictureEX)$/.test(plugin) && args) capturePictureText(args.text);
        });
        if (root.$gameScreen && root.$gameScreen._pictures) root.$gameScreen._pictures.forEach(inspectPicture);
        return installed.message && installed.choices && installed.battle;
    }

    root.LunaGameBridge = { status: status, attach: attach };
    // MZ loads its classes asynchronously from main.js. Listen on each loaded
    // script as well as polling, before the player can begin a conversation.
    if (root.document) root.document.addEventListener("load", attach, true);
    attach();
    var attempts = 0;
    var timer = root.setInterval(function () {
        attach();
        if (++attempts > 600) root.clearInterval(timer);
    }, 50);
}(typeof window !== "undefined" ? window : this));
