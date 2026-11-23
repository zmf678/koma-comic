const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const context = vm.createContext({});
context.window = context;
const shim = path.join(__dirname, "../app/src/main/assets/venera_shim.js");
vm.runInContext(fs.readFileSync(shim, "utf8"), context);

const menuFor = context.__sourceBrowseMenu;
const categoriesFor = context.__sourceBrowseCategories;
const filtersFor = context.__sourceBrowseFilters;
const load = context.__sourceBrowseFeed;
const comic = { id: "1", title: "漫画" };

test("comic previews retain source tags and synopsis in lists and details", async () => {
    const map = vm.runInContext('new Map([["题材", ["冒险", { name: "奇幻" }]], ["作者", ["原作者"]]])', context);
    const list = context.__normalizeComicsResult([
        { ...comic, tags: map, introduction: "一段由漫画源提供的作品简介" },
        { id: "2", title: "只有更新信息", last_update_chapter_name: "第 23 话" }
    ], 1, true).comics;
    assert.equal(list[0].description, "一段由漫画源提供的作品简介");
    assert.deepEqual(Array.from(list[0].tags), ["冒险", "奇幻", "原作者"]);
    assert.equal(list[1].description, "");
    assert.deepEqual(Array.from(list[1].tags), []);
    const details = await context.__sourceComicDetails({
        comic: { loadInfo: () => ({ title: comic.title, desc: "详情里的作品介绍", tags: ["冒险", { label: "校园" }, "冒险", { id: "0" }] }) }
    }, comic.id);
    assert.equal(details.description, "详情里的作品介绍");
    assert.deepEqual(Array.from(details.tags), ["冒险", "校园"]);
});

test("discovery sections retain their titles and supported more links", async () => {
    const source = {
        explore: [
            { title: "推荐", type: "multiPartPage", load(page) {
                assert.equal(page, null);
                return [
                    { title: "本周精选", comics: [comic], viewMore: "category:精选@weekly" },
                    { title: "最近更新", comics: [comic], viewMore: "最新" }
                ];
            } },
            { title: "最新", type: "multiPageComicList", load: () => ({ comics: [comic] }) }
        ],
        categoryComics: { load() {} }
    };
    const [discovery, latest] = menuFor(source).channels;
    const result = await load(source, discovery, 1, null, []);
    assert.equal(result.sections[0].title, "本周精选");
    assert.equal(result.sections[0].target.param, "weekly");
    assert.equal(result.sections[1].target.index, latest.index);
    assert.equal(result.sections[1].comics[0].id, "1");
    assert.equal(result.maxPage, 1);
    assert.equal((await load(source, discovery, 2, null, [])).sections.length, 0);
});

test("mixed feeds start at index zero and empty feeds keep their original destination", async () => {
    const source = {
        explore: [{ type: "mixed", load(index) {
            return { data: index === 0 ? [{ title: "混合分区", comics: [comic] }] : [], maxPage: 2 };
        } }],
        categoryComics: { load() { assert.fail("An empty discovery page must not load a category"); } }
    };
    const [target] = menuFor(source).channels;
    const first = await load(source, target, 1, null, []);
    assert.equal(first.sections[0].title, "混合分区");
    assert.equal(first.maxPage, 2);
    assert.equal((await load(source, target, 2, null, [])).sections.length, 0);
});

test("cursor-based search and discovery use only the supplied request cursor", async () => {
    const source = {
        explore: [{ loadNext(token) {
            assert.equal(token, null);
            return { comics: [comic], next: { offset: 12 } };
        } }],
        search: { loadNext(keyword, options, token) {
            assert.equal(keyword, "关键词");
            assert.equal(options[0], "popular");
            assert.ok(token === null || token === "");
            return { comics: [comic], next: token === null ? "" : null };
        } }
    };
    const menu = menuFor(source);
    assert.equal(menu.canSearch, true);
    const target = { kind: "search", keyword: "关键词" };
    const search = await load(source, target, 1, null, ["popular"]);
    assert.equal(search.nextCursor, '""');
    assert.equal((await load(source, menu.channels[0], 1, null, [])).nextCursor, '{"offset":12}');
    assert.equal((await load(source, target, 2, search.nextCursor, ["popular"])).maxPage, 2);
});

test("dynamic category targets preserve filter positions and optional values", async () => {
    const source = {
        category: { parts: [{ name: "书库", type: "dynamic", loader: () => [{
            label: "冒险", target: { page: "category", attributes: { category: "冒险", param: "library:1" } }
        }] }] },
        categoryComics: {
            optionLoader(category, param) {
                assert.equal(category, "冒险");
                assert.equal(param, "library:1");
                return [
                    { label: "隐藏项", showWhen: ["其他"], options: ["all-全部"] },
                    { label: "状态", type: "dropdown", options: ["done-已完结"] }
                ];
            },
            load() {}
        }
    };
    const menu = menuFor(source);
    assert.equal(menu.canSearch, false);
    const [target] = await categoriesFor(source, menu.groups[0].index);
    const filters = await filtersFor(source, target);
    assert.deepEqual(Array.from(filters.values), ["all", null]);
    assert.equal(filters.fields.length, 1);
    assert.equal(filters.fields[0].index, 1);
    assert.equal(filters.fields[0].choices[0].value, null);
});
