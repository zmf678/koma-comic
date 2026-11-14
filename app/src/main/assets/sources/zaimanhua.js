class ZaimanhuaBuiltin extends ComicSource {
    name = "再漫画";
    key = "zaimanhua";
    version = "1.0.0";
    minAppVersion = "1.0.0";
    url = "";

    get headers() {
        return {
            "User-Agent": "Mozilla/5.0 (Linux; Android) Mobile",
            "Accept": "application/json",
        };
    }

    parseComic(item) {
        const id = String(item.comic_id && item.comic_id !== 0 ? item.comic_id : (item.id || ""));
        const tags = [];
        if (item.status) tags.push(String(item.status));
        if (item.types) {
            String(item.types).split(/[,/]/).forEach((t) => {
                const trimmed = t.trim();
                if (trimmed) tags.push(trimmed);
            });
        }
        return {
            id: id,
            title: String(item.title || item.name || ""),
            subTitle: String(item.authors || ""),
            cover: String(item.cover || ""),
            tags: tags,
            description: String(item.last_update_chapter_name || item.last_name || item.description || ""),
        };
    }

    explore = [
        {
            title: "最新更新",
            type: "multiPageComicList",
            load: async (page) => {
                const p = page || 1;
                const res = await Network.get(`https://v4api.zaimanhua.com/app/v1/comic/update/list/0/${p}`, this.headers);
                if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
                const data = JSON.parse(res.body).data || [];
                return {
                    comics: data.map((it) => this.parseComic(it)),
                    maxPage: 20,
                };
            },
        },
        {
            title: "人气排行",
            type: "multiPageComicList",
            load: async (page) => {
                const p = page || 1;
                const res = await Network.get(`https://v4api.zaimanhua.com/app/v1/comic/rank/list?tag_id=0&by_time=0&rank_type=0&page=${p}`, this.headers);
                if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
                const data = JSON.parse(res.body).data || [];
                return {
                    comics: data.map((it) => this.parseComic(it)),
                    maxPage: 10,
                };
            },
        },
    ];

    search = {
        load: async (keyword, options, page) => {
            const p = page || 1;
            const res = await Network.get(
                `https://v4api.zaimanhua.com/app/v1/search/index?keyword=${encodeURIComponent(keyword)}&page=${p}&sort=0&size=24`,
                this.headers
            );
            if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
            const list = (JSON.parse(res.body).data && JSON.parse(res.body).data.list) || [];
            return {
                comics: list.map((it) => this.parseComic(it)),
                maxPage: 10,
            };
        },
    };

    comic = {
        loadInfo: async (id) => {
            const res = await Network.get(`https://v4api.zaimanhua.com/app/v1/comic/detail/${id}?channel=android`, this.headers);
            if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
            const json = JSON.parse(res.body);
            if (json.errno !== 0) throw new Error(json.errmsg || "加载详情失败");
            const data = json.data.data;

            const chapters = new Map();
            const groups = data.chapters || [];
            for (const g of groups) {
                const groupTitle = g.title || "正篇";
                const gMap = new Map();
                const list = (g.data || []).slice().reverse();
                for (const ch of list) {
                    gMap.set(String(ch.chapter_id), String(ch.chapter_title || `章节 ${ch.chapter_id}`));
                }
                chapters.set(groupTitle, gMap);
            }

            const authors = (data.authors || []).map((a) => a.tag_name).filter(Boolean);
            const types = (data.types || []).map((t) => t.tag_name).filter(Boolean);
            const status = (data.status || []).map((s) => s.tag_name).filter(Boolean);

            return {
                title: data.title || "",
                cover: data.cover || "",
                description: data.description || "",
                tags: {
                    "作者": authors,
                    "状态": status,
                    "题材": types,
                },
                chapters: chapters,
            };
        },
        loadEp: async (comicId, epId) => {
            const res = await Network.get(`https://v4api.zaimanhua.com/app/v1/comic/chapter/${comicId}/${epId}`, this.headers);
            if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
            const json = JSON.parse(res.body);
            const data = json.data && json.data.data ? json.data.data : {};
            const urls = data.page_url_hd && data.page_url_hd.length > 0 ? data.page_url_hd : (data.page_url || []);
            return { images: urls };
        },
    };
}
