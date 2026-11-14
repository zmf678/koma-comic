class XkcdBuiltin extends ComicSource {
    name = "XKCD 极客";
    key = "xkcd";
    version = "1.0.0";
    minAppVersion = "1.0.0";
    url = "";

    explore = [
        {
            title: "最新连载",
            type: "multiPageComicList",
            load: async (page) => {
                const p = page || 1;
                const latestRes = await Network.get("https://xkcd.com/info.0.json", {});
                if (latestRes.status !== 200) throw new Error(`HTTP ${latestRes.status}`);
                const latest = JSON.parse(latestRes.body);
                const maxNum = latest.num || 3000;
                const count = 8;
                const startNum = Math.max(1, maxNum - (p - 1) * count);
                const comics = [];
                for (let n = startNum; n > Math.max(0, startNum - count); n--) {
                    try {
                        const r = await Network.get(`https://xkcd.com/${n}/info.0.json`, {});
                        if (r.status === 200) {
                            const j = JSON.parse(r.body);
                            comics.push({
                                id: String(j.num),
                                title: `#${j.num} ${j.safe_title || j.title}`,
                                subTitle: `${j.year}-${j.month}-${j.day}`,
                                cover: j.img || "",
                                tags: ["XKCD"],
                                description: j.alt || "",
                            });
                        }
                    } catch (_) {}
                }
                return { comics: comics, maxPage: Math.ceil(maxNum / count) };
            },
        },
    ];

    search = {
        load: async (keyword) => {
            const num = parseInt(String(keyword).trim(), 10);
            if (!isNaN(num) && num > 0) {
                const r = await Network.get(`https://xkcd.com/${num}/info.0.json`, {});
                if (r.status === 200) {
                    const j = JSON.parse(r.body);
                    return {
                        comics: [{
                            id: String(j.num),
                            title: `#${j.num} ${j.safe_title || j.title}`,
                            subTitle: `${j.year}-${j.month}-${j.day}`,
                            cover: j.img || "",
                            description: j.alt || "",
                        }],
                        maxPage: 1,
                    };
                }
            }
            return { comics: [], maxPage: 1 };
        },
    };

    comic = {
        loadInfo: async (id) => {
            const r = await Network.get(`https://xkcd.com/${id}/info.0.json`, {});
            if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
            const j = JSON.parse(r.body);
            const chapters = new Map();
            chapters.set(String(j.num), `#${j.num} ${j.safe_title || j.title}`);
            return {
                title: `#${j.num} ${j.safe_title || j.title}`,
                cover: j.img || "",
                description: j.alt || "",
                tags: { "日期": [`${j.year}-${j.month}-${j.day}`] },
                chapters: chapters,
            };
        },
        loadEp: async (comicId) => {
            const r = await Network.get(`https://xkcd.com/${comicId}/info.0.json`, {});
            const j = JSON.parse(r.body);
            return { images: [j.img] };
        },
    };
}
