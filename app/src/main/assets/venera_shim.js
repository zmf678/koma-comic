// Clean-room Venera-compatible JS Host Runtime for KomaComic
(function () {
    if (window.__veneraInitialized) return;
    window.__veneraInitialized = true;

    window.__veneraSources = {};
    window.__veneraPending = {};

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

    window.sendMessage = function (message) {
        if (!message || typeof message !== "object") return null;
        if (message.method === "delay") {
            return new Promise((resolve) => globalThis.setTimeout(resolve, Number(message.time) || 0));
        }
        const safeMsg = Object.assign({}, message);
        if (safeMsg.value !== undefined) safeMsg.value = encodePayloadValue(safeMsg.value);
        if (safeMsg.key !== undefined && (safeMsg.key instanceof ArrayBuffer || safeMsg.key instanceof Uint8Array)) {
            safeMsg.key = encodePayloadValue(safeMsg.key);
        }
        if (safeMsg.iv !== undefined) safeMsg.iv = encodePayloadValue(safeMsg.iv);

        const raw = window._veneraNative.dispatch(JSON.stringify(safeMsg));
        const res = raw ? JSON.parse(raw) : null;
        if (res && res.__ab__ !== undefined) {
            return base64ToAb(res.__ab__);
        }
        if (message.method === "http" || message.method === "http_bytes") {
            return Promise.resolve(res);
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
    };

    window.UI = {
        showMessage: function (msg) {
            try {
                window._veneraNative.dispatch(JSON.stringify({ method: "ui", function: "toast", message: String(msg || "") }));
            } catch (_) {}
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
            const res = await sendMessage({
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
            const res = await sendMessage({
                method: "http_bytes",
                http_method: String(method || "GET").toUpperCase(),
                url: String(url),
                headers: headers || {},
                data: data == null ? null : data,
            });
            if (res && res.error) throw new Error(res.error);
            if (res && res.bodyBase64) {
                res.body = base64ToAb(res.bodyBase64);
            }
            return res;
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
        const res = await window.Network.sendRequest(method, url, headers, body);
        const textBody = res && typeof res.body === "string" ? res.body : "";
        const status = res ? Number(res.status || 0) : 0;
        return {
            ok: status >= 200 && status < 300,
            status: status,
            statusText: String(status),
            headers: (res && res.headers) || {},
            text: async () => textBody,
            json: async () => JSON.parse(textBody),
            arrayBuffer: async () => window.Convert.encodeUtf8(textBody),
        };
    };

    // DOMParser-backed HtmlDocument & HtmlElement compatible with Venera
    class HtmlElement {
        constructor(el) {
            this._el = el;
        }
        get text() { return (this._el.textContent || ""); }
        get innerHTML() { return this._el.innerHTML || ""; }
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

    window.ComicSource = class ComicSource {
        get isLogged() {
            const token = this.loadData("token");
            return !!(token && String(token).length > 0);
        }
        loadSetting(key) {
            const val = sendMessage({ method: "storage", function: "loadSetting", sourceKey: this.key || "", key: String(key) });
            if (val !== null && val !== undefined) return val;
            if (this.settings && this.settings[key] && this.settings[key].default !== undefined) {
                return this.settings[key].default;
            }
            return null;
        }
        saveData(key, value) {
            sendMessage({ method: "storage", function: "saveData", sourceKey: this.key || "", key: String(key), value: value });
        }
        loadData(key) {
            return sendMessage({ method: "storage", function: "loadData", sourceKey: this.key || "", key: String(key) });
        }
        deleteData(key) {
            sendMessage({ method: "storage", function: "deleteData", sourceKey: this.key || "", key: String(key) });
        }
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
