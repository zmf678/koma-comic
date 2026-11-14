class MangaDexBuiltin extends ComicSource {
    name = "MangaDex";
    key = "manga_dex";
    version = "1.0.0";
    minAppVersion = "1.0.0";
    url = "";

    get headers() {
        return {
            "User-Agent": "KomaComic/1.0 Android",
            "Accept": "application/json",
        };
    }

    parseComic(data) {
        const id = data.id;
        const attrs = data.attributes || {};
        const titles = attrs.title || {};
        const alt = {};
        (attrs.altTitles || []).forEach((obj) => {
            Object.keys(obj).forEach((lang) => {
                if (!alt[lang]) alt[lang] = obj[lang];
            });
        });
        const allTitles = Object.assign({}, alt, titles);
        const title = allTitles["zh"] || allTitles["zh-hk"] || allTitles["zh-ro"] || allTitles["en"] || Object.values(allTitles)[0] || "Untitled";

        let cover = "";
        const rels = data.relationships || [];
        for (const r of rels) {
            if (r.type === "cover_art" && r.attributes && r.attributes.fileName) {
                cover = `https://uploads.mangadex.org/covers/${id}/${r.attributes.fileName}.256.jpg`;
                break;
            }
        }
        let author = "";
        for (const r of rels) {
            if (r.type === "author" && r.attributes && r.attributes.name) {
                author = r.attributes.name;
                break;
            }
        }
        const tags = (attrs.tags || []).map((t) => (t.attributes && t.attributes.name && t.attributes.name.en) || "").filter(Boolean);
        const descs = attrs.description || {};
        const description = descs["zh"] || descs["zh-hk"] || descs["en"] || Object.values(descs)[0] || "";

        return {
            id: id,
            title: title,
            subTitle: author,
            cover: cover,
            tags: tags,
            description: description,
        };
    }

    explore = [
        {
            title: "热门排行",
            type: "multiPageComicList",
            load: async (page) => {
                const limit = 24;
                const offset = ((page || 1) - 1) * limit;
                const url = `https://api.mangadex.org/manga?includes[]=cover_art&includes[]=author&order[followedCount]=desc&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true&limit=${limit}&offset=${offset}`;
                const res = await Network.get(url, this.headers);
                if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
                const data = JSON.parse(res.body);
                return {
                    comics: (data.data || []).map((c) => this.parseComic(c)),
                    maxPage: Math.min(20, Math.ceil((data.total || 0) / limit)),
                };
            },
        },
        {
            title: "最近更新",
            type: "multiPageComicList",
            load: async (page) => {
                const limit = 24;
                const offset = ((page || 1) - 1) * limit;
                const url = `https://api.mangadex.org/manga?includes[]=cover_art&includes[]=author&order[latestUploadedChapter]=desc&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true&limit=${limit}&offset=${offset}`;
                const res = await Network.get(url, this.headers);
                if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
                const data = JSON.parse(res.body);
                return {
                    comics: (data.data || []).map((c) => this.parseComic(c)),
                    maxPage: Math.min(20, Math.ceil((data.total || 0) / limit)),
                };
            },
        },
    ];

    search = {
        load: async (keyword, options, page) => {
            const limit = 24;
            const offset = ((page || 1) - 1) * limit;
            const url = `https://api.mangadex.org/manga?title=${encodeURIComponent(keyword)}&includes[]=cover_art&includes[]=author&contentRating[]=safe&contentRating[]=suggestive&hasAvailableChapters=true&limit=${limit}&offset=${offset}`;
            const res = await Network.get(url, this.headers);
            if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
            const data = JSON.parse(res.body);
            return {
                comics: (data.data || []).map((c) => this.parseComic(c)),
                maxPage: Math.min(10, Math.ceil((data.total || 0) / limit)),
            };
        },
    };

    comic = {
        loadInfo: async (id) => {
            const infoRes = await Network.get(`https://api.mangadex.org/manga/${id}?includes[]=cover_art&includes[]=author`, this.headers);
            if (infoRes.status !== 200) throw new Error(`HTTP ${infoRes.status}`);
            const parsed = this.parseComic(JSON.parse(infoRes.body).data);

            const feedRes = await Network.get(
                `https://api.mangadex.org/manga/${id}/feed?limit=150&order[volume]=asc&order[chapter]=asc&contentRating[]=safe&contentRating[]=suggestive`,
                this.headers
            );
            const feed = JSON.parse(feedRes.body).data || [];
            const zhChapters = new Map();
            const otherChapters = new Map();

            for (const ch of feed) {
                const a = ch.attributes || {};
                if (!a.pages || a.pages <= 0) continue;
                const chNum = a.chapter ? `Ch.${a.chapter}` : "OneShot";
                const title = a.title ? `${chNum} - ${a.title}` : chNum;
                const lang = a.translatedLanguage || "other";
                if (lang === "zh" || lang === "zh-hk") {
                    zhChapters.set(ch.id, title);
                } else {
                    otherChapters.set(ch.id, `[${lang}] ${title}`);
                }
            }

            const chapters = new Map();
            if (zhChapters.size > 0) chapters.set("中文章节", zhChapters);
            if (otherChapters.size > 0) chapters.set("外语章节", otherChapters);

            return {
                title: parsed.title,
                cover: parsed.cover,
                description: parsed.description,
                tags: {
                    "作者": parsed.subTitle ? [parsed.subTitle] : [],
                    "标签": parsed.tags || [],
                },
                chapters: chapters,
            };
        },
        loadEp: async (comicId, epId) => {
            const res = await Network.get(`https://api.mangadex.org/at-home/server/${epId}`, this.headers);
            if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
            const json = JSON.parse(res.body);
            const base = json.baseUrl;
            const hash = json.chapter && json.chapter.hash;
            const files = (json.chapter && json.chapter.data) || [];
            return {
                images: files.map((f) => `${base}/data/${hash}/${f}`),
            };
        },
    };
}
