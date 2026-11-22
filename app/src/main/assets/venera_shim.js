// Clean-room Venera-compatible JS Host Runtime for KomaComic
(function () {
    if (window.__veneraInitialized) return;
    window.__veneraInitialized = true;

    window.__veneraSources = {};
    window.__veneraPending = {};
    window.__veneraAsyncCallbacks = {};
    window.__veneraAsyncSeq = 0;
    window.__currentSourceKey = "";
    const browserFetch = window.fetch?.bind(window);

    function abToBase64(buffer) {
        if (typeof buffer === "string") return btoa(unescape(encodeURIComponent(buffer)));
        let bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer || 0);
        let binary = "";
        const chunk = 0x8000;
        for (let i = 0; i < bytes.length; i += chunk) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(binary);
    }

    function base64ToAb(b64) {
        const binary = atob((b64 || "").replace(/\s+/g, ""));
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return bytes.buffer;
    }

    function abToHex(buffer) {
        const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer || 0);
        let out = "";
        for (let i = 0; i < bytes.length; i++) {
            out += bytes[i].toString(16).padStart(2, "0");
        }
        return out;
    }

    function hexToAb(hex) {
        const clean = String(hex || "").replace(/[^0-9a-fA-F]/g, "");
        const len = Math.floor(clean.length / 2);
        const out = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            out[i] = parseInt(clean.substr(i * 2, 2), 16);
        }
        return out.buffer;
    }

    function encodePayloadValue(v) {
        if (v instanceof ArrayBuffer || v instanceof Uint8Array) {
            return { __ab__: abToBase64(v) };
        }
        return v;
    }

    window.__veneraResolveAsync = function (cbId, rawJson) {
        const entry = window.__veneraAsyncCallbacks[cbId];
        if (!entry) return;
        delete window.__veneraAsyncCallbacks[cbId];
        try {
            const parsed = rawJson ? JSON.parse(rawJson) : null;
            if (parsed && parsed.__ab__ !== undefined) {
                entry.resolve(base64ToAb(parsed.__ab__));
            } else {
                entry.resolve(parsed);
            }
        } catch (err) {
            entry.reject(err);
        }
    };

    window.__veneraRejectAsync = function (cbId, errMsg) {
        const entry = window.__veneraAsyncCallbacks[cbId];
        if (!entry) return;
        delete window.__veneraAsyncCallbacks[cbId];
        entry.reject(new Error(String(errMsg || "Async call failed")));
    };

    function prepareSafeMessage(message) {
        const safeMsg = Object.assign({}, message);
        if (safeMsg.value !== undefined) safeMsg.value = encodePayloadValue(safeMsg.value);
        if (safeMsg.key !== undefined && (safeMsg.key instanceof ArrayBuffer || safeMsg.key instanceof Uint8Array)) {
            safeMsg.key = encodePayloadValue(safeMsg.key);
        }
        if (safeMsg.iv !== undefined) safeMsg.iv = encodePayloadValue(safeMsg.iv);
        if (safeMsg.data !== undefined && (safeMsg.data instanceof ArrayBuffer || safeMsg.data instanceof Uint8Array)) {
            safeMsg.data = encodePayloadValue(safeMsg.data);
        }
        if (safeMsg.image !== undefined && (safeMsg.image instanceof ArrayBuffer || safeMsg.image instanceof Uint8Array)) {
            safeMsg.image = encodePayloadValue(safeMsg.image);
        }
        return safeMsg;
    }

    function sendAsyncMessage(message) {
        if (!message || typeof message !== "object") return Promise.resolve(null);
        const cbId = "cb_" + (++window.__veneraAsyncSeq);
        const safeMsg = prepareSafeMessage(message);
        return new Promise((resolve, reject) => {
            window.__veneraAsyncCallbacks[cbId] = { resolve, reject };
            try {
                window._veneraNative.dispatchAsync(cbId, JSON.stringify(safeMsg));
            } catch (e) {
                delete window.__veneraAsyncCallbacks[cbId];
                reject(e);
            }
        });
    }

    window.sendMessage = function (message) {
        if (!message || typeof message !== "object") return null;
        if (message.method === "delay") {
            return new Promise((resolve) => globalThis.setTimeout(resolve, Number(message.time) || 0));
        }
        if (message.method === "http" || message.method === "http_bytes") {
            return sendAsyncMessage(message);
        }
        const safeMsg = prepareSafeMessage(message);
        const raw = window._veneraNative.dispatch(JSON.stringify(safeMsg));
        const res = raw ? JSON.parse(raw) : null;
        if (res && res.__ab__ !== undefined) {
            return base64ToAb(res.__ab__);
        }
        if (message.method === "cookie" && message.function === "get") {
            return Promise.resolve(res || []);
        }
        return res;
    };

    window.APP = {
        version: "1.18.0",
        locale: "zh_CN",
        platform: "android",
        toast: function (msg) {
            window.UI.showMessage(msg);
        },
    };

    window.UI = {
        showMessage: function (msg) {
            try {
                window._veneraNative.dispatch(JSON.stringify({ method: "ui", function: "toast", message: String(msg || "") }));
            } catch (_) {}
        },
        showLoading: function (msg) {
            try {
                window._veneraNative.dispatch(JSON.stringify({ method: "ui", function: "showLoading", message: String(msg || "") }));
            } catch (_) {}
            return { cancel: function () {} };
        },
        hideLoading: function () {
            try {
                window._veneraNative.dispatch(JSON.stringify({ method: "ui", function: "hideLoading" }));
            } catch (_) {}
        },
        cancelLoading: function () {
            try {
                window._veneraNative.dispatch(JSON.stringify({ method: "ui", function: "hideLoading" }));
            } catch (_) {}
        },
        launchUrl: function (url) {
            try {
                window._veneraNative.dispatch(JSON.stringify({ method: "ui", function: "launchUrl", url: String(url || "") }));
            } catch (_) {}
        },
        showDialog: async function (title, content, actions) {
            const actionList = Array.isArray(actions) ? actions : [];
            const res = await sendAsyncMessage({
                method: "ui_dialog",
                title: String(title || "提示"),
                message: String(content || ""),
                actions: actionList.map((a) => ({
                    text: String((a && a.text) || "确定"),
                    style: String((a && a.style) || "normal"),
                })),
            });
            const idx = res && typeof res.clickedIndex === "number" ? res.clickedIndex : -1;
            if (idx >= 0 && actionList[idx] && typeof actionList[idx].callback === "function") {
                return await actionList[idx].callback();
            }
            return null;
        },
        showInputDialog: async function (title, validator, image) {
            while (true) {
                const res = await sendAsyncMessage({
                    method: "ui_input",
                    title: String(title || "输入"),
                    image: image || null,
                });
                if (!res || res.cancelled) return null;
                const val = res.value !== undefined && res.value !== null ? String(res.value) : "";
                if (typeof validator === "function") {
                    const err = validator(val);
                    if (err) {
                        window.UI.showMessage(err);
                        continue;
                    }
                }
                return val;
            }
        },
        showSelectDialog: async function (title, options, initialIndex) {
            const opts = Array.isArray(options) ? options.map(String) : [];
            const res = await sendAsyncMessage({
                method: "ui_select",
                title: String(title || "选择"),
                options: opts,
                initialIndex: typeof initialIndex === "number" ? initialIndex : -1,
            });
            if (!res || res.index === null || res.index === undefined) return null;
            return Number(res.index);
        },
    };

    window.randomInt = function (min, max) {
        const lo = Math.ceil(min);
        const hi = Math.floor(max);
        return Math.floor(Math.random() * (hi - lo + 1)) + lo;
    };

    window.createUuid = function () {
        if (crypto && crypto.randomUUID) return crypto.randomUUID();
        return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
            const r = (Math.random() * 16) | 0;
            const v = c === "x" ? r : (r & 0x3) | 0x8;
            return v.toString(16);
        });
    };

    window.Convert = {
        encodeUtf8: (str) => new TextEncoder().encode(String(str || "")).buffer,
        decodeUtf8: (ab) => new TextDecoder("utf-8").decode(ab instanceof ArrayBuffer || ab instanceof Uint8Array ? ab : new Uint8Array(0)),
        encodeGbk: (str) => sendMessage({ method: "convert", type: "gbk", value: String(str || ""), isEncode: true }),
        decodeGbk: (ab) => sendMessage({ method: "convert", type: "gbk", value: ab, isEncode: false }),
        encodeBase64: (ab) => abToBase64(ab),
        decodeBase64: (str) => base64ToAb(str),
        hexEncode: (ab) => abToHex(ab),
        hexDecode: (str) => hexToAb(str),
        md5: (ab) => sendMessage({ method: "convert", type: "md5", value: ab, isEncode: true }),
        sha1: (ab) => sendMessage({ method: "convert", type: "sha1", value: ab, isEncode: true }),
        sha256: (ab) => sendMessage({ method: "convert", type: "sha256", value: ab, isEncode: true }),
        sha512: (ab) => sendMessage({ method: "convert", type: "sha512", value: ab, isEncode: true }),
        hmac: (key, val, hash) => sendMessage({ method: "convert", type: "hmac", key: key, value: val, hash: hash, isEncode: true }),
        hmacString: (key, val, hash) => sendMessage({ method: "convert", type: "hmac", key: key, value: val, hash: hash, isEncode: true, isString: true }),
        decryptAesEcb: (val, key) => sendMessage({ method: "convert", type: "aes-ecb", value: val, key: key, isEncode: false }),
        decryptAesCbc: (val, key, iv) => sendMessage({ method: "convert", type: "aes-cbc", value: val, key: key, iv: iv, isEncode: false }),
    };

    window.Cookie = class Cookie {
        constructor(opts) {
            Object.assign(this, opts || {});
        }
    };

    window.Network = {
        async sendRequest(method, url, headers, data) {
            let bodyData = data;
            if (bodyData instanceof ArrayBuffer || bodyData instanceof Uint8Array) {
                bodyData = { __ab__: abToBase64(bodyData) };
            }
            const res = await sendAsyncMessage({
                method: "http",
                http_method: String(method || "GET").toUpperCase(),
                url: String(url),
                headers: headers || {},
                data: bodyData == null ? null : bodyData,
            });
            if (res && res.error) throw new Error(res.error);
            return res;
        },
        async fetchBytes(method, url, headers, data) {
            let bodyData = data;
            if (bodyData instanceof ArrayBuffer || bodyData instanceof Uint8Array) {
                bodyData = { __ab__: abToBase64(bodyData) };
            }
            const res = await sendAsyncMessage({
                method: "http_bytes",
                http_method: String(method || "GET").toUpperCase(),
                url: String(url),
                headers: headers || {},
                data: bodyData == null ? null : bodyData,
            });
            if (res && res.error) throw new Error(res.error);
            if (res && typeof res.bodyBase64 === "string") {
                res.body = base64ToAb(res.bodyBase64);
            }
            return res;
        },
        request(opts) {
            const o = opts || {};
            return this.sendRequest(o.method || "GET", o.url || "", o.headers || {}, o.data !== undefined ? o.data : o.body);
        },
        get(url, headers) { return this.sendRequest("GET", url, headers, null); },
        post(url, headers, data) { return this.sendRequest("POST", url, headers, data); },
        put(url, headers, data) { return this.sendRequest("PUT", url, headers, data); },
        patch(url, headers, data) { return this.sendRequest("PATCH", url, headers, data); },
        delete(url, headers) { return this.sendRequest("DELETE", url, headers, null); },
        setCookies(url, cookies) {
            sendMessage({ method: "cookie", function: "set", url: String(url), cookies: cookies || [] });
        },
        getCookies(url) {
            return sendMessage({ method: "cookie", function: "get", url: String(url) });
        },
        deleteCookies(url) {
            sendMessage({ method: "cookie", function: "delete", url: String(url) });
        },
    };

    window.fetch = async function (url, options) {
        const opts = options || {};
        const method = opts.method || "GET";
        const headers = opts.headers || {};
        const body = opts.body !== undefined ? opts.body : null;
        const res = await window.Network.fetchBytes(method, url, headers, body);
        const bytes = res?.body instanceof ArrayBuffer ? res.body : new ArrayBuffer(0);
        const textBody = () => new TextDecoder().decode(bytes);
        const status = res ? Number(res.status || 0) : 0;
        return {
            ok: status >= 200 && status < 300,
            status: status,
            statusText: String(status),
            url: res?.url || String(url),
            headers: (res && res.headers) || {},
            text: async () => textBody(),
            json: async () => JSON.parse(textBody()),
            arrayBuffer: async () => bytes.slice(0),
        };
    };

    // DOMParser-backed HtmlDocument & HtmlElement compatible with Venera
    class HtmlElement {
        constructor(el) {
            this._el = el;
        }
        get text() { return (this._el.textContent || ""); }
        get innerHTML() { return this._el.innerHTML || ""; }
        get outerHTML() { return this._el.outerHTML || ""; }
        get localName() { return (this._el.localName || "").toLowerCase(); }
        get id() { return this._el.id || ""; }
        get classNames() { return Array.from(this._el.classList || []); }
        get attributes() {
            const obj = {};
            if (this._el && this._el.attributes) {
                for (let i = 0; i < this._el.attributes.length; i++) {
                    const attr = this._el.attributes[i];
                    obj[attr.name] = attr.value;
                }
            }
            return obj;
        }
        get children() {
            return Array.from(this._el.children || []).map((c) => new HtmlElement(c));
        }
        get nodes() {
            return Array.from(this._el.childNodes || []).map((n) => new HtmlNode(n));
        }
        get firstChild() {
            return this._el.firstChild ? new HtmlNode(this._el.firstChild) : null;
        }
        get lastChild() {
            return this._el.lastChild ? new HtmlNode(this._el.lastChild) : null;
        }
        get parent() {
            return this._el.parentElement ? new HtmlElement(this._el.parentElement) : null;
        }
        get nextElementSibling() {
            return this._el.nextElementSibling ? new HtmlElement(this._el.nextElementSibling) : null;
        }
        get previousElementSibling() {
            return this._el.previousElementSibling ? new HtmlElement(this._el.previousElementSibling) : null;
        }
        querySelector(q) {
            try {
                const found = this._el.querySelector(q);
                return found ? new HtmlElement(found) : null;
            } catch (_) {
                return null;
            }
        }
        querySelectorAll(q) {
            try {
                return Array.from(this._el.querySelectorAll(q)).map((c) => new HtmlElement(c));
            } catch (_) {
                return [];
            }
        }
        getElementsByTagName(tag) {
            try {
                return Array.from(this._el.getElementsByTagName(tag)).map((c) => new HtmlElement(c));
            } catch (_) {
                return [];
            }
        }
        getElementsByClassName(cls) {
            try {
                return Array.from(this._el.getElementsByClassName(cls)).map((c) => new HtmlElement(c));
            } catch (_) {
                return [];
            }
        }
        getElementById(id) {
            try {
                const found = this._el.querySelector("#" + CSS.escape(String(id)));
                return found ? new HtmlElement(found) : null;
            } catch (_) {
                return null;
            }
        }
        toString() {
            return this._el.outerHTML || "";
        }
    }

    class HtmlNode {
        constructor(node) {
            this._node = node;
        }
        get text() { return this._node.textContent || ""; }
        get type() {
            if (this._node.nodeType === 1) return "element";
            if (this._node.nodeType === 3) return "text";
            return "other";
        }
        toElement() {
            return this._node.nodeType === 1 ? new HtmlElement(this._node) : null;
        }
    }

    window.HtmlDocument = class HtmlDocument {
        constructor(html) {
            const parser = new DOMParser();
            this._doc = parser.parseFromString(String(html || ""), "text/html");
        }
        querySelector(q) {
            try {
                const found = this._doc.querySelector(q);
                return found ? new HtmlElement(found) : null;
            } catch (_) {
                return null;
            }
        }
        querySelectorAll(q) {
            try {
                return Array.from(this._doc.querySelectorAll(q)).map((c) => new HtmlElement(c));
            } catch (_) {
                return [];
            }
        }
        getElementsByTagName(tag) {
            try {
                return Array.from(this._doc.getElementsByTagName(tag)).map((c) => new HtmlElement(c));
            } catch (_) {
                return [];
            }
        }
        getElementsByClassName(cls) {
            try {
                return Array.from(this._doc.getElementsByClassName(cls)).map((c) => new HtmlElement(c));
            } catch (_) {
                return [];
            }
        }
        getElementById(id) {
            const found = this._doc.getElementById(id);
            return found ? new HtmlElement(found) : null;
        }
        dispose() {
            this._doc = null;
        }
    };

    window.Comic = class Comic {
        constructor(opts) { Object.assign(this, opts || {}); }
    };

    window.ComicDetails = class ComicDetails {
        constructor(opts) { Object.assign(this, opts || {}); }
    };

    window.ImageLoadingConfig = class ImageLoadingConfig {
        constructor(opts) { Object.assign(this, opts || {}); }
    };

    window.Comment = class Comment {
        constructor(opts) { Object.assign(this, opts || {}); }
    };

    window.ComicSource = class ComicSource {
        constructor() {
            this.key = window.__currentSourceKey || "";
        }
        get isLogged() {
            const keys = ["__logged_in__", "token", "account_token_0", "uid", "cookies", "komga_auth", "access_token", "account"];
            for (const k of keys) {
                const val = this.loadData(k);
                if (val === true || val === 1) return true;
                if (typeof val === "string" && val.length > 0 && val !== "false" && val !== "0") return true;
                if (Array.isArray(val) && val.length > 0) return true;
                if (val && typeof val === "object" && Object.keys(val).length > 0) return true;
            }
            return false;
        }
        loadSetting(key) {
            const val = sendMessage({ method: "storage", function: "loadSetting", sourceKey: this.key || "", key: String(key) });
            if (val !== null && val !== undefined) return val;
            if (this.settings && this.settings[key] !== undefined) {
                const entry = this.settings[key];
                if (entry !== null && typeof entry === "object") {
                    if (entry.default !== undefined) return entry.default;
                } else {
                    return entry;
                }
            }
            return null;
        }
        saveSetting(key, value) {
            sendMessage({ method: "storage", function: "saveSetting", sourceKey: this.key || "", key: String(key), value: value });
        }
        saveData(key, value) {
            if (value === null || value === undefined) {
                this.deleteData(key);
                return;
            }
            sendMessage({ method: "storage", function: "saveData", sourceKey: this.key || "", key: String(key), value: value });
        }
        loadData(key) {
            return sendMessage({ method: "storage", function: "loadData", sourceKey: this.key || "", key: String(key) });
        }
        deleteData(key) {
            sendMessage({ method: "storage", function: "deleteData", sourceKey: this.key || "", key: String(key) });
        }
        translate(key) {
            const k = String(key || "");
            const t = this.translation;
            if (t && typeof t === "object") {
                const loc = (window.APP && window.APP.locale) || "zh_CN";
                if (t[loc] && t[loc][k] !== undefined) return t[loc][k];
                if (t["zh_CN"] && t["zh_CN"][k] !== undefined) return t["zh_CN"][k];
                if (t["en"] && t["en"][k] !== undefined) return t["en"][k];
            }
            return k;
        }
    };

    window.__extractSourceImageHeaders = function (src) {
        try {
            const h = typeof src.headers === "function" ? src.headers() : src.headers;
            if (!h || typeof h !== "object") return {};
            const out = {};
            if (h.Referer || h.referer) out.Referer = String(h.Referer || h.referer);
            if (h["User-Agent"] || h["user-agent"]) out["User-Agent"] = String(h["User-Agent"] || h["user-agent"]);
            return out;
        } catch (_) {
            return {};
        }
    };

    function webUrl(value, base) {
        if (typeof value !== "string" || !value.trim()) return "";
        try {
            const url = new URL(value, base || undefined);
            return /^https?:$/.test(url.protocol) && !url.username && !url.password ? url.href : "";
        } catch (_) {
            return "";
        }
    }

    window.__sourceWebsite = function (src) {
        for (const candidate of [src.website, src.config?.baseUrl, src.baseUrl, src.base, src.baseURL,
            src.constructor?.baseUrl, src.constructor?.BASE_URL, src.host, src.landingPage]) {
            const url = webUrl(candidate);
            if (url && !/\.js(?:[?#]|$)/i.test(url)) return url;
        }
        return "";
    };

    window.__sourceAppWebsite = src => webUrl(src.appWebsite);

    window.__sourceComicDetails = async function (src, id) {
        if (typeof src.comic?.loadInfo !== "function") throw new Error("漫画源没有提供作品详情");
        const details = await src.comic.loadInfo(id);
        if (!details || typeof details !== "object") throw new Error("漫画源未返回作品详情");
        return {
            ...details,
            subTitle: details.subTitle ?? details.subtitle,
            headers: { ...window.__extractSourceImageHeaders(src), ...details.headers },
            url: webUrl(details.url, window.__sourceWebsite(src)),
            wholeBook: details.chapters == null && typeof src.comic.loadEp === "function"
        };
    };

    function mergeHeaders(...maps) {
        const result = {};
        for (const headers of maps) {
            for (const [key, value] of Object.entries(headers || {})) {
                if (value != null) result[key.toLowerCase()] = String(value);
            }
        }
        return result;
    }

    window.__sourceChapterPages = async function (src, id, episode) {
        if (typeof src.comic?.loadEp !== "function") throw new Error("漫画源没有提供章节阅读");
        const result = await src.comic.loadEp(id, episode);
        const pages = Array.isArray(result) ? result : result?.images;
        const base = window.__sourceWebsite(src);
        const fallback = window.__extractSourceImageHeaders(src);
        const hasImageHook = typeof src.comic.onImageLoad === "function";
        return (Array.isArray(pages) ? pages : []).flatMap(page => {
            const key = typeof page === "string" ? page : page?.url;
            if (typeof key !== "string" || !key) return [];
            const url = webUrl(key, base) || key;
            return [{ url: hasImageHook ? key : url, headers: mergeHeaders(fallback, result?.headers, page?.headers),
                useSourceLoader: hasImageHook || /^data:image\//i.test(url) }];
        });
    };

    class CanvasImage {
        constructor(canvas) { this.canvas = canvas; }
        get width() { return this.canvas.width; }
        get height() { return this.canvas.height; }
        static empty(width, height) {
            const canvas = document.createElement("canvas");
            canvas.width = width;
            canvas.height = height;
            return new CanvasImage(canvas);
        }
        copyRange(x, y, width, height) {
            const copy = CanvasImage.empty(width, height);
            copy.fillImageRangeAt(0, 0, this, x, y, width, height);
            return copy;
        }
        fillImageRangeAt(x, y, source, sourceX, sourceY, width, height) {
            if (width <= 0 || height <= 0) return;
            const context = this.canvas.getContext("2d");
            context.drawImage(source.canvas, sourceX, sourceY, width, height, x, y, width, height);
        }
    }

    window.compute = async function (script, value) {
        const fn = typeof script === "function" ? script : eval(String(script));
        return fn(value);
    };

    async function renderPageImage(bytes, script) {
        const bitmap = await createImageBitmap(new Blob([bytes]));
        const image = CanvasImage.empty(bitmap.width, bitmap.height);
        image.canvas.getContext("2d").drawImage(bitmap, 0, 0);
        bitmap.close();
        const transform = script ? new Function("Image", script + "\n;return modifyImage;")(CanvasImage) : null;
        const modified = transform ? await transform(image) : image;
        const blob = await new Promise(resolve => modified.canvas.toBlob(resolve, "image/png"));
        if (!blob) throw new Error("无法处理漫画图片");
        return blob.arrayBuffer();
    }

    function needsDesktopDecode(bytes) {
        const buffer = ArrayBuffer.isView(bytes) ? bytes.buffer : bytes;
        const offset = ArrayBuffer.isView(bytes) ? bytes.byteOffset : 0;
        const header = String.fromCharCode(...new Uint8Array(buffer, offset, Math.min(bytes.byteLength, 16)));
        return header.slice(8, 12) === "WEBP" || header.slice(4, 8) === "ftyp";
    }

    window.__sourcePageBytes = async function (src, id, episode, key, headers, desktop = false) {
        let config = typeof src.comic.onImageLoad === "function" ? await src.comic.onImageLoad(key, id, episode) : null;
        const base = window.__sourceWebsite(src);
        for (let attempt = 0; ; attempt++) {
            try {
                const value = config?.url || key;
                let bytes;
                if (/^data:image\//i.test(value)) {
                    bytes = await (await browserFetch(value)).arrayBuffer();
                } else {
                    const url = webUrl(value, base);
                    if (!url) throw new Error("漫画源未返回有效的图片地址");
                    const response = await Network.fetchBytes(config?.method || "GET", url,
                        mergeHeaders(headers, config?.headers), config?.data);
                    if (!response || response.status < 200 || response.status >= 300 || !response.body) {
                        throw new Error("图片请求失败 (HTTP " + (response?.status ?? "?") + ")");
                    }
                    bytes = response.body;
                }
                if (typeof config?.onResponse === "function") bytes = await config.onResponse(bytes);
                if (!(bytes instanceof ArrayBuffer) && !ArrayBuffer.isView(bytes)) throw new Error("漫画源未返回图片数据");
                if (config?.modifyImage || desktop && needsDesktopDecode(bytes)) {
                    bytes = await renderPageImage(bytes, config?.modifyImage);
                }
                return abToBase64(ArrayBuffer.isView(bytes) ? new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength) : bytes);
            } catch (error) {
                if (attempt >= 2 || typeof config?.onLoadFailed !== "function") throw error;
                config = await config.onLoadFailed();
                if (!config) throw error;
            }
        }
    };

    window.__normalizeComicsResult = function (r, page, isSinglePage, fallbackHeaders) {
        const out = [];
        const seen = new Set();
        const extraHeaders = fallbackHeaders && typeof fallbackHeaders === "object" ? fallbackHeaders : {};

        function pushComic(c, partName) {
            if (!c || typeof c !== "object") return;
            const rawId = c.id && c.id !== 0 ? c.id : (c.comic_id && c.comic_id !== 0 ? c.comic_id : (c.path_word || c.id));
            const id = rawId !== undefined && rawId !== null ? String(rawId).trim() : "";
            const title = String(c.title || c.name || "").trim();
            if (!id || !title || seen.has(id)) return;
            seen.add(id);
            let sub = c.subTitle || c.subtitle || c.authors || "";
            if (!sub && c.author) {
                if (Array.isArray(c.author)) {
                    sub = c.author.map((a) => (a && typeof a === "object" ? a.name : a)).filter(Boolean).join(", ");
                } else if (typeof c.author === "string") {
                    sub = c.author;
                }
            }
            if (!sub && partName) sub = String(partName);
            const mergedHeaders = Object.assign({}, extraHeaders, c.headers || {});
            out.push({
                id: id,
                title: title,
                subTitle: String(sub || ""),
                cover: String(c.cover || c.pic || c.img || c.image || ""),
                tags: Array.isArray(c.tags) ? c.tags : [],
                description: String(c.description || c.desc || c.last_update_chapter_name || c.last_name || ""),
                headers: mergedHeaders,
            });
        }

        function collect(node, partName) {
            if (!node) return;
            if (Array.isArray(node)) {
                for (const el of node) {
                    if (el && typeof el === "object" && Array.isArray(el.comics)) {
                        collect(el.comics, el.title || partName);
                    } else {
                        pushComic(el, partName);
                    }
                }
                return;
            }
            if (node instanceof Map) {
                node.forEach((v, k) => collect(v, String(k)));
                return;
            }
            if (typeof node === "object") {
                if (Array.isArray(node.comics)) {
                    collect(node.comics, partName);
                    return;
                }
                if (Array.isArray(node.list)) {
                    collect(node.list, partName);
                    return;
                }
                if (Array.isArray(node.data)) {
                    collect(node.data, partName);
                    return;
                }
                if (Array.isArray(node.parts)) {
                    collect(node.parts, partName);
                    return;
                }
                for (const k of Object.keys(node)) {
                    if (k === "headers" || k === "maxPage" || k === "max_page" || k === "next") continue;
                    collect(node[k], k);
                }
            }
        }

        collect(r, "");
        let maxPage = null;
        if (isSinglePage) {
            maxPage = 1;
        } else if (r && typeof r === "object" && !Array.isArray(r)) {
            const mp = r.maxPage !== undefined ? r.maxPage : r.max_page;
            if (mp !== undefined && mp !== null && Number(mp) > 0) {
                maxPage = Number(mp);
            }
        }
        return { comics: out, maxPage: maxPage };
    };

    const hasLoader = (entry) => entry &&
        (typeof entry.load === "function" || typeof entry.loadNext === "function");
    const exploreEntries = (src) => Array.isArray(src.explore) ? src.explore : [];
    const categoryParts = (src) => Array.isArray(src.category?.parts) ? src.category.parts : [];

    function browseTarget(src, value, label) {
        if (typeof value === "string") {
            const match = /^(category|search):(.*)$/s.exec(value);
            if (match) {
                const at = match[2].indexOf("@");
                value = match[1] === "search"
                    ? { page: "search", attributes: { text: match[2] } }
                    : { page: "category", attributes: {
                        category: at < 0 ? match[2] : match[2].slice(0, at),
                        param: at < 0 ? "" : match[2].slice(at + 1)
                    } };
            } else {
                const index = exploreEntries(src).findIndex((entry) => entry?.title === value && hasLoader(entry));
                return index < 0 ? null : { kind: "explore", index, title: label || value };
            }
        }
        const attrs = value?.attributes || {};
        if (value?.page === "category" && typeof src.categoryComics?.load === "function" && attrs.category != null) {
            const category = String(attrs.category);
            return { kind: "category", title: label || category, category, param: String(attrs.param ?? "") };
        }
        if (value?.page === "search" && hasLoader(src.search) && (attrs.text ?? attrs.keyword) != null) {
            const keyword = String(attrs.text ?? attrs.keyword);
            return { kind: "search", title: label || keyword, keyword, options: Array.isArray(attrs.options) ? attrs.options : [] };
        }
        return null;
    }

    window.__sourceBrowseMenu = function (src) {
        const ranking = src.categoryComics?.ranking;
        return {
            canSearch: Boolean(hasLoader(src.search)),
            website: window.__sourceWebsite(src),
            appWebsite: window.__sourceAppWebsite(src),
            channels: exploreEntries(src).flatMap((entry, index) => hasLoader(entry)
                ? [{ kind: "explore", index, title: String(entry.title || ("发现 " + (index + 1))) }] : []),
            groups: categoryParts(src).flatMap((part, index) => part && (
                (part.type === "dynamic" && typeof part.loader === "function") ||
                (Array.isArray(part.categories) && part.categories.length > 0)
            ) ? [{ index, title: String(part.name || "分类") }] : []),
            ranking: src.category?.enableRankingPage && ranking &&
                (hasLoader(ranking) || typeof ranking.loadWithNext === "function")
                ? { kind: "ranking", title: "排行" } : null
        };
    };

    window.__sourceBrowseCategories = async function (src, index) {
        const part = categoryParts(src)[index];
        if (!part) return [];
        const entries = part.type === "dynamic" ? await part.loader() : part.categories;
        if (!Array.isArray(entries)) return [];
        return entries.flatMap((entry, i) => {
            const label = entry && typeof entry === "object" ? entry.label : entry;
            if (label == null || !String(label).trim()) return [];
            const destination = typeof entry === "object" ? entry.target : (
                part.itemType === "search"
                    ? { page: "search", attributes: { text: label } }
                    : { page: "category", attributes: {
                        category: label, param: part.categoryParams?.[i] ?? part.groupParam ?? ""
                    } }
            );
            const target = browseTarget(src, destination, String(label));
            return target ? [target] : [];
        });
    };

    function browseChoice(choice) {
        if (choice && typeof choice === "object") {
            const value = String(choice.value ?? "");
            return { value, title: String(choice.label ?? choice.title ?? choice.name ?? value) };
        }
        const value = String(choice ?? "");
        const split = value.indexOf("-");
        return split < 0 ? { value, title: value } : { value: value.slice(0, split), title: value.slice(split + 1) };
    }

    window.__sourceBrowseFilters = async function (src, target) {
        let options;
        if (target.kind === "search") options = src.search?.optionList;
        if (target.kind === "category") {
            const category = src.categoryComics;
            options = typeof category?.optionLoader === "function"
                ? await category.optionLoader(target.category, target.param)
                : category?.optionList;
        }
        if (target.kind === "ranking") options = [{ label: "榜单", options: src.categoryComics?.ranking?.options }];
        if (!Array.isArray(options)) return { values: [], fields: [] };
        const values = [];
        const fields = [];
        options.forEach((option, index) => {
            const choices = Array.isArray(option?.options) ? option.options.map(browseChoice) : [];
            const multiple = option?.type === "multi-select";
            if (option?.type === "dropdown" && choices.length && !choices.some((choice) => choice.value === "")) {
                choices.unshift({ value: null, title: "不限" });
            }
            const initial = option?.default;
            const fallback = initial != null
                ? (Array.isArray(initial) ? JSON.stringify(initial) : String(initial))
                : (multiple ? "[]" : choices.length ? choices[0].value : "");
            const visible = option && (!Array.isArray(option.showWhen) || option.showWhen.includes(target.category)) &&
                (!Array.isArray(option.notShowWhen) || !option.notShowWhen.includes(target.category));
            values.push(index in (target.options || []) ? target.options[index] : fallback);
            if (visible && choices.length) {
                fields.push({ index, title: String(option.label || option.title || ("选项 " + (index + 1))), multiple, choices });
            }
        });
        return { values, fields };
    };

    window.__sourceBrowseFeed = async function (src, target, page, cursor, options) {
        let entry;
        let args = [];
        let type;
        let loadNext;
        if (target.kind === "explore") {
            entry = exploreEntries(src)[target.index];
            type = entry?.type;
        } else if (target.kind === "search") {
            entry = src.search;
            args = [target.keyword, options];
        } else if (target.kind === "category") {
            entry = src.categoryComics;
            args = [target.category, target.param, options];
        } else if (target.kind === "ranking") {
            entry = src.categoryComics?.ranking;
            args = [options[0] ?? ""];
            loadNext = entry?.loadWithNext;
        }
        const singlePage = type === "singlePageWithMultiPart" || type === "multiPartPage";
        if (singlePage && page > 1) return { sections: [], maxPage: 1, nextCursor: null };
        let result;
        let usesCursor = cursor != null || typeof entry?.load !== "function";
        if (!usesCursor) {
            const scriptPage = singlePage ? null : type === "mixed" ? page - 1 : page;
            result = await entry.load(...args, scriptPage);
        }
        // Some bundled scripts leave an empty load stub next to the actual cursor implementation.
        loadNext = loadNext || entry?.loadNext;
        if ((usesCursor || result == null) && typeof loadNext === "function") {
            usesCursor = true;
            if (page === 1 || cursor != null) {
                result = await loadNext.call(entry, ...args, cursor == null ? null : JSON.parse(cursor));
            }
        }
        const headers = window.__extractSourceImageHeaders(src);
        const sections = [];
        function addSection(comics, title = "", more = null) {
            const normalized = window.__normalizeComicsResult(comics, page, false, headers).comics;
            const target = browseTarget(src, more, "");
            if (normalized.length || target) sections.push({ title: String(title || ""), comics: normalized, target });
        }
        if (type === "singlePageWithMultiPart" && result && !Array.isArray(result) &&
            !Array.isArray(result.comics) && !Array.isArray(result.parts) && !Array.isArray(result.data)) {
            const parts = result instanceof Map ? result.entries() : Object.entries(result);
            for (const [title, comics] of parts) addSection(comics, title);
        } else if (singlePage || type === "mixed") {
            const parts = Array.isArray(result) ? result : result?.data ?? result?.parts ?? result?.comics;
            if (Array.isArray(parts) && parts.some((part) => Array.isArray(part) || Array.isArray(part?.comics))) {
                let batch = [];
                for (const part of parts) {
                    if (Array.isArray(part) || Array.isArray(part?.comics)) {
                        if (batch.length) addSection(batch);
                        batch = [];
                        if (Array.isArray(part)) addSection(part);
                        else addSection(part.comics, part.title, part.viewMore);
                    } else {
                        batch.push(part);
                    }
                }
                if (batch.length) addSection(batch);
            } else addSection(result);
        } else {
            addSection(result);
        }
        const candidateCursor = usesCursor && result?.next != null ? JSON.stringify(result.next) : null;
        const nextCursor = candidateCursor === cursor ? null : candidateCursor;
        const reportedMax = Number(result?.maxPage ?? result?.max_page);
        const maxPage = singlePage || !result ? page
            : usesCursor ? (nextCursor == null ? page : null)
            : Number.isFinite(reportedMax) && reportedMax > 0 ? reportedMax : sections.length ? null : page;
        return { sections, maxPage, nextCursor };
    };

    window.__serializeVenera = function (val) {
        if (val === null || val === undefined) return null;
        if (typeof val !== "object") return val;
        if (Array.isArray(val)) return val.map(window.__serializeVenera);
        if (val instanceof Map) {
            const entries = [];
            val.forEach((v, k) => {
                entries.push([String(k), window.__serializeVenera(v)]);
            });
            return { __mapEntries__: entries };
        }
        if (val instanceof Set) {
            return Array.from(val).map(window.__serializeVenera);
        }
        const out = {};
        for (const k of Object.keys(val)) {
            if (typeof val[k] === "function") continue;
            out[k] = window.__serializeVenera(val[k]);
        }
        return out;
    };

    window.__veneraInvoke = async function (callId, asyncFn) {
        try {
            const res = await asyncFn();
            const json = JSON.stringify(window.__serializeVenera(res));
            window._veneraNative.resolve(String(callId), json || "null");
        } catch (err) {
            const msg = err && err.stack ? String(err.stack) : String(err);
            window._veneraNative.reject(String(callId), msg);
        }
    };
})();
