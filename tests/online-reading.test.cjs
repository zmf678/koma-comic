const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const shim = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/venera_shim.js"), "utf8");

function createRuntime() {
    const runtime = vm.createContext({ URL, ArrayBuffer, Uint8Array, TextDecoder, btoa, atob });
    runtime.window = runtime;
    vm.runInContext(shim, runtime);
    return runtime;
}

test("chapter loading keeps source image keys and merges chapter and page headers", async () => {
    const runtime = createRuntime();
    const source = {
        website: "https://reader.example/",
        headers: { Referer: "https://reader.example/" },
        comic: {
            loadInfo: () => ({ title: "Comic", url: "/series/1" }),
            loadEp(id, episode) {
                assert.equal(id, "library/1");
                assert.equal(episode, null);
                return {
                    headers: { Referer: "https://reader.example/series/1", "X-Source": "chapter" },
                    images: [{ url: "image-key", headers: { "x-source": "page" } }]
                };
            },
            onImageLoad() {}
        }
    };
    const details = await runtime.__sourceComicDetails(source, "library/1");
    assert.equal(details.wholeBook, true);
    assert.equal(details.url, "https://reader.example/series/1");
    const [page] = await runtime.__sourceChapterPages(source, "library/1", null);
    assert.equal(page.url, "image-key");
    assert.equal(page.headers.referer, "https://reader.example/series/1");
    assert.equal(page.headers["x-source"], "page");
    assert.equal(page.useSourceLoader, true);
});

test("image loading runs source request, binary callbacks and fallback in order", async () => {
    const runtime = createRuntime();
    const requests = [];
    runtime.Network.fetchBytes = async (method, url, headers, data) => {
        requests.push({ method, url, auth: headers.authorization, data });
        if (url.endsWith("/first")) return { status: 503 };
        return { status: 200, body: Uint8Array.of(9, 1, 2, 8).buffer };
    };
    const source = {
        website: "https://reader.example/",
        comic: {
            onImageLoad: async (key, id, episode) => {
                assert.deepEqual([key, id, episode], ["image:1", "comic", "chapter"]);
                return {
                    method: "POST", url: "/first", data: "from-source",
                    onLoadFailed: async () => ({
                        url: "/second", headers: { Authorization: "fallback" },
                        onResponse: async buffer => new Uint8Array(buffer, 1, 2)
                    })
                };
            }
        }
    };
    const bytes = await runtime.__sourcePageBytes(source, "comic", "chapter", "image:1", { authorization: "original" });
    assert.equal(bytes, "AQI=");
    assert.deepEqual(requests, [
        { method: "POST", url: "https://reader.example/first", auth: "original", data: "from-source" },
        { method: "GET", url: "https://reader.example/second", auth: "fallback", data: undefined }
    ]);
});

test("Komiic opens the requested comic even when recommendations arrive out of order", async () => {
    const runtime = createRuntime();
    const script = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/sources/Komiic.js"), "utf8");
    const source = vm.runInContext(script + "\n;new Komiic()", runtime);
    source.queryJson = async query => query.operationName === "recommendComicById"
        ? { data: { recommendComicById: ["other"] } }
        : { data: { chaptersByComicId: [{ id: "first", type: "chapter", serial: "1" }] } };
    source.queryComics = async () => ({ comics: [
        { id: "wanted", title: "Selected title", cover: "", tags: [] },
        { id: "other", title: "Recommended title", cover: "", tags: [] }
    ] });
    const details = await runtime.__sourceComicDetails(source, "wanted");
    assert.equal(details.title, "Selected title");
    assert.equal(details.url, "https://komiic.com/comic/wanted");
    assert.equal(details.chapters.get("first"), "1");
    source.loadData = () => null;
    await assert.rejects(runtime.__sourceChapterPages(source, "wanted", "first"), /登录 Komiic/);
});
