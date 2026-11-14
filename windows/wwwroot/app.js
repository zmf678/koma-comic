/* KomaComic Desktop Application Logic */
(function () {
  "use strict";

  const DEFAULT_REPO_URLS = [
    "https://cdn.jsdelivr.net/gh/zmf678/venera-sources@main/index.json",
    "https://raw.githubusercontent.com/zmf678/venera-sources/main/index.json"
  ];

  const appState = {
    theme: "dark",
    gridCompact: false,
    activeNavTab: "home",
    activeHomeMode: "copy_manga",
    currentPage: 1,
    maxPage: null,
    currentExploreIdx: 0,
    currentExploreNextToken: null,
    currentCategory: null,
    currentSearchKeyword: "",
    feedItems: [],
    sources: [],
    localComics: [],
    warehousePaths: [],
    favorites: [],
    history: [],
    collections: [{ id: "col_default", name: "默认作品集", pin: "", hideFromAll: false, items: [] }],
    activeCollectionId: "col_default",
    unlockedCollections: {},
    webdav: { url: "", username: "", password: "" },
    readerDefaults: {
      mode: "rtl",
      spread: "double",
      brightness: 100,
      quality: "ultra",
      trimMargins: false,
      doubleTapZoom: true,
      showCommentPage: false,
      showPageNumber: true,
      applyGlobal: false
    },
    comicPrefs: {}
  };

  const readerState = {
    open: false,
    isLocal: false,
    comic: null,
    chapters: [],
    chapterIdx: 0,
    pages: [],
    pageIndex: 0,
    spreadShift: 0,
    zoom: 1,
    chromeVisible: true,
    wideFlags: {}
  };

  let saveTimer = null;

  // Hook UI messages so source feedback renders visibly
  if (window.UI) {
    window.UI.showMessage = function (msg) {
      if (msg) showToast(String(msg));
    };
  }

  function showToast(msg) {
    const host = document.getElementById("toastContainer");
    const div = document.createElement("div");
    div.className = "toast-msg";
    div.textContent = String(msg);
    host.appendChild(div);
    setTimeout(() => {
      div.remove();
    }, 3200);
  }

  function escapeHtml(str) {
    return String(str || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function proxiedImageUrl(url, headers) {
    if (!url) return "";
    if (url.startsWith("/") || url.startsWith("data:") || url.startsWith("blob:")) return url;
    const h = headers && Object.keys(headers).length ? JSON.stringify(headers) : "";
    let out = "/api/image?url=" + encodeURIComponent(url);
    if (h) out += "&headers=" + encodeURIComponent(h);
    return out;
  }

  function scheduleSaveState() {
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(saveStateNow, 300);
  }

  async function saveStateNow() {
    const persisted = {
      theme: appState.theme,
      gridCompact: appState.gridCompact,
      activeHomeMode: appState.activeHomeMode,
      favorites: appState.favorites,
      history: appState.history,
      collections: appState.collections,
      activeCollectionId: appState.activeCollectionId,
      webdav: appState.webdav,
      readerDefaults: appState.readerDefaults,
      comicPrefs: appState.comicPrefs,
      sourceConfigs: appState.sources.map((s) => ({
        key: s.key,
        name: s.name,
        version: s.version,
        fileName: s.fileName,
        url: s.url || "",
        enabled: !!s.enabled,
        customScript: s.customScript || ""
      }))
    };
    try {
      await fetch("/api/state", {
        method: "POST",
        headers: { "Content-Type": "application/json; charset=utf-8" },
        body: JSON.stringify(persisted)
      });
    } catch (e) {
      console.warn("Save state failed", e);
    }
  }

  function applyTheme(theme) {
    appState.theme = theme || "dark";
    let actual = appState.theme;
    if (actual === "system") {
      actual = window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
    }
    document.body.classList.toggle("theme-light", actual === "light");
    document.body.classList.toggle("theme-dark", actual !== "light");
    document.querySelectorAll("#themeModeGroup .seg-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.theme === appState.theme);
    });
  }

  async function loadInitialStateAndSources() {
    let saved = {};
    try {
      const r = await fetch("/api/state");
      if (r.ok) saved = (await r.json()) || {};
    } catch (_) {}

    if (saved.theme) appState.theme = saved.theme;
    if (typeof saved.gridCompact === "boolean") appState.gridCompact = saved.gridCompact;
    if (Array.isArray(saved.favorites)) appState.favorites = saved.favorites;
    if (Array.isArray(saved.history)) appState.history = saved.history;
    if (Array.isArray(saved.collections) && saved.collections.length) appState.collections = saved.collections;
    if (saved.activeCollectionId) appState.activeCollectionId = saved.activeCollectionId;
    if (saved.webdav) appState.webdav = Object.assign(appState.webdav, saved.webdav);
    if (saved.readerDefaults) appState.readerDefaults = Object.assign(appState.readerDefaults, saved.readerDefaults);
    if (saved.comicPrefs) appState.comicPrefs = saved.comicPrefs;
    if (saved.activeHomeMode) appState.activeHomeMode = saved.activeHomeMode;

    // Load 148 built-in venera-sources
    let builtin = [];
    try {
      const br = await fetch("/assets/sources/builtin_sources.json");
      if (br.ok) builtin = await br.json();
    } catch (_) {}

    const savedByKey = {};
    if (Array.isArray(saved.sourceConfigs)) {
      saved.sourceConfigs.forEach((sc) => {
        if (sc && sc.key) savedByKey[sc.key] = sc;
      });
    }

    const merged = [];
    const seen = new Set();
    builtin.forEach((entry) => {
      if (!entry || !entry.key || seen.has(entry.key)) return;
      seen.add(entry.key);
      const prev = savedByKey[entry.key];
      merged.push({
        key: entry.key,
        name: entry.name || entry.key,
        version: entry.version || "1.0.0",
        fileName: entry.fileName || ("sources/" + entry.key + ".js"),
        url: (prev && prev.url) || "",
        enabled: prev ? !!prev.enabled : true,
        customScript: (prev && prev.customScript) || ""
      });
    });

    // Include user-subscribed/custom imported sources
    if (Array.isArray(saved.sourceConfigs)) {
      saved.sourceConfigs.forEach((sc) => {
        if (sc && sc.key && !seen.has(sc.key)) {
          seen.add(sc.key);
          merged.push({
            key: sc.key,
            name: sc.name || sc.key,
            version: sc.version || "1.0.0",
            fileName: sc.fileName || "",
            url: sc.url || "",
            enabled: sc.enabled !== false,
            customScript: sc.customScript || ""
          });
        }
      });
    }

    appState.sources = merged;
    if (!appState.sources.some((s) => s.key === appState.activeHomeMode) &&
        !["local", "fav", "history"].includes(appState.activeHomeMode)) {
      appState.activeHomeMode = appState.sources.length ? appState.sources[0].key : "local";
    }

    applyTheme(appState.theme);
    syncSettingsInputs();
    renderWarehousePanel();
    try {
      const wr = await fetch("/api/warehouse/rescan", { method: "POST" });
      if (wr.ok) {
        const wd = await wr.json();
        appState.warehousePaths = wd.paths || [];
        appState.localComics = wd.comics || [];
        renderWarehousePanel();
      }
    } catch (_) {}

    rebuildHomeChips();
    loadActiveHomeFeed(1);
  }

  function parseVeneraClassName(script) {
    const m = /class\s+([A-Za-z_$][A-Za-z0-9_$]*)\s+extends\s+ComicSource\b/.exec(script);
    return m ? m[1] : null;
  }

  async function ensureSourceInstance(sourceKey) {
    if (window.__veneraSources && window.__veneraSources[sourceKey]) {
      return window.__veneraSources[sourceKey];
    }
    const meta = appState.sources.find((s) => s.key === sourceKey);
    if (!meta) throw new Error("未找到漫画源: " + sourceKey);

    let script = meta.customScript || "";
    if (!script && meta.fileName) {
      const path = meta.fileName.startsWith("/") ? meta.fileName : "/assets/" + meta.fileName.replace(/^\/+/, "");
      const res = await fetch(path);
      if (!res.ok) throw new Error("加载源脚本失败: " + path);
      script = await res.text();
    }
    if (!script) throw new Error("源脚本内容为空: " + meta.name);

    const className = parseVeneraClassName(script);
    if (!className) throw new Error("脚本格式不支持: " + meta.name);

    const wrapped =
      "(function() {\n" +
      "  window.__currentSourceKey = " + JSON.stringify(sourceKey) + ";\n" +
      script + "\n" +
      "  const inst = new " + className + "();\n" +
      "  inst.key = inst.key || " + JSON.stringify(sourceKey) + ";\n" +
      "  try { inst.__settingsSchema = inst.settings ? JSON.parse(JSON.stringify(inst.settings)) : {}; }\n" +
      "  catch (_) { inst.__settingsSchema = inst.settings || {}; }\n" +
      "  window.__veneraSources[" + JSON.stringify(sourceKey) + "] = inst;\n" +
      "  return inst;\n" +
      "})()";

    const inst = (0, eval)(wrapped);
    if (typeof inst.init === "function") {
      try {
        await Promise.resolve(inst.init());
      } catch (e) {
        console.warn("Source init warning:", sourceKey, e);
      }
    }
    return inst;
  }

  function rebuildHomeChips() {
    const bar = document.getElementById("homeChipsRow");
    bar.innerHTML = "";

    const addChip = (label, id, extraClass, onClick) => {
      const btn = document.createElement("button");
      btn.className = "chip" + (extraClass ? " " + extraClass : "") + (appState.activeHomeMode === id ? " active" : "");
      btn.textContent = label;
      btn.onclick = onClick;
      bar.appendChild(btn);
    };

    const enabledSources = appState.sources.filter((s) => s.enabled);
    addChip("本地全部", "local", "", () => switchHomeMode("local"));
    addChip("收藏", "fav", "", () => switchHomeMode("fav"));
    addChip("最近阅读", "history", "", () => switchHomeMode("history"));
    addChip("选源 (" + enabledSources.length + "/" + appState.sources.length + ")", "__picker__", "action-chip", openQuickSourcePicker);
    addChip("+ 源管理", "__mgr__", "action-chip", openSourceManagerModal);

    // Promote active online source near the front if it sits far down
    const ordered = enabledSources.slice();
    const activeIdx = ordered.findIndex((s) => s.key === appState.activeHomeMode);
    if (activeIdx > 8) {
      const [picked] = ordered.splice(activeIdx, 1);
      ordered.unshift(picked);
    }

    ordered.slice(0, 36).forEach((s) => {
      addChip(s.name, s.key, "", () => switchHomeMode(s.key));
    });
  }

  function switchHomeMode(mode) {
    appState.activeHomeMode = mode;
    appState.currentPage = 1;
    appState.currentExploreIdx = 0;
    appState.currentExploreNextToken = null;
    appState.currentCategory = null;
    scheduleSaveState();
    rebuildHomeChips();
    loadActiveHomeFeed(1);
  }

  async function loadActiveHomeFeed(page) {
    appState.currentPage = page || 1;
    const mode = appState.activeHomeMode;
    const kw = document.getElementById("searchInput").value.trim();
    appState.currentSearchKeyword = kw;

    const subBar = document.getElementById("sourceSubBar");
    const statusBanner = document.getElementById("feedStatusBanner");
    statusBanner.classList.add("hidden");

    if (mode === "local" || mode === "fav" || mode === "history") {
      subBar.classList.add("hidden");
      document.getElementById("paginationBar").classList.add("hidden");

      let list = [];
      if (mode === "local") {
        const hiddenIds = new Set();
        appState.collections.forEach((c) => {
          if (c.hideFromAll) (c.items || []).forEach((it) => hiddenIds.add(it.uid));
        });
        list = appState.localComics
          .map((lc) => ({
            uid: lc.id,
            id: lc.id,
            isLocal: true,
            title: lc.title,
            subTitle: lc.format + " · " + lc.pageCount + " 页",
            cover: lc.coverUrl,
            headers: {}
          }))
          .filter((it) => !hiddenIds.has(it.uid));
      } else if (mode === "fav") {
        list = appState.favorites.slice();
      } else {
        list = appState.history.slice();
      }

      if (kw) {
        const lq = kw.toLowerCase();
        list = list.filter((it) => (it.title || "").toLowerCase().includes(lq) || (it.subTitle || "").toLowerCase().includes(lq));
      }
      renderComicGrid(list);
      return;
    }

    // Online Venera source
    subBar.classList.remove("hidden");
    statusBanner.textContent = "正在加载「" + getSourceDisplayName(mode) + "」...";
    statusBanner.classList.remove("hidden");
    renderComicGrid([]);

    try {
      const src = await ensureSourceInstance(mode);
      renderSourceSubBar(src);

      const fallbackHeaders = window.__extractSourceImageHeaders(src);
      let normalized = null;

      if (kw && src.search && typeof src.search.load === "function") {
        const opts = window.__defaultOptionsFor(src.search.optionList);
        const r = await src.search.load(kw, opts, appState.currentPage);
        normalized = window.__normalizeComicsResult(r, appState.currentPage, false, fallbackHeaders);
      } else if (appState.currentCategory && src.categoryComics && typeof src.categoryComics.load === "function") {
        const opts = window.__defaultOptionsFor(src.categoryComics.optionList);
        const cr = await src.categoryComics.load(
          appState.currentCategory.category,
          appState.currentCategory.param || "",
          opts,
          appState.currentPage
        );
        normalized = window.__normalizeComicsResult(cr, appState.currentPage, false, fallbackHeaders);
      } else {
        const arr = Array.isArray(src.explore) ? src.explore : [];
        if (arr.length > 0) {
          const item = arr[Math.min(appState.currentExploreIdx, arr.length - 1)];
          const isSingle = item.type === "singlePageWithMultiPart";
          if (isSingle && appState.currentPage > 1) {
            normalized = { comics: [], maxPage: 1 };
          } else if (typeof item.load === "function") {
            const r = await item.load(appState.currentPage);
            normalized = window.__normalizeComicsResult(r, appState.currentPage, isSingle, fallbackHeaders);
          } else if (typeof item.loadNext === "function") {
            const token = appState.currentPage <= 1 ? null : appState.currentExploreNextToken;
            const r = await item.loadNext(token);
            appState.currentExploreNextToken = r ? r.next : null;
            const maxP = r && r.next ? appState.currentPage + 1 : appState.currentPage;
            normalized = window.__normalizeComicsResult({ comics: (r && r.comics) || [], maxPage: maxP }, appState.currentPage, false, fallbackHeaders);
          }
        }
        if ((!normalized || !normalized.comics || normalized.comics.length === 0) &&
            src.categoryComics && typeof src.categoryComics.load === "function") {
          let firstCat = "全部";
          let firstParam = "";
          const parts = src.category && Array.isArray(src.category.parts) ? src.category.parts : [];
          if (parts.length > 0) {
            const p0 = parts[0];
            if (Array.isArray(p0.categories) && p0.categories.length) firstCat = p0.categories[0];
            if (Array.isArray(p0.categoryParams) && p0.categoryParams.length) firstParam = p0.categoryParams[0];
          }
          const opts = window.__defaultOptionsFor(src.categoryComics.optionList);
          const cr = await src.categoryComics.load(firstCat, firstParam, opts, appState.currentPage);
          normalized = window.__normalizeComicsResult(cr, appState.currentPage, false, fallbackHeaders);
        }
      }

      statusBanner.classList.add("hidden");
      const items = ((normalized && normalized.comics) || []).map((c) => ({
        uid: mode + "::" + c.id,
        id: c.id,
        sourceKey: mode,
        isLocal: false,
        title: c.title,
        subTitle: c.subTitle || getSourceDisplayName(mode),
        cover: c.cover,
        headers: c.headers || fallbackHeaders,
        badge: getSourceDisplayName(mode)
      }));

      appState.maxPage = normalized && normalized.maxPage ? normalized.maxPage : null;
      renderComicGrid(items);
      updatePaginationUi(items.length);
    } catch (err) {
      statusBanner.textContent = "加载失败: " + (err && err.message ? err.message : String(err)) + "（若该站点需登录或镜像被墙，可点「选源」换源或点「账号」登录）";
      statusBanner.classList.remove("hidden");
      document.getElementById("paginationBar").classList.add("hidden");
    }
  }

  function getSourceDisplayName(key) {
    const s = appState.sources.find((x) => x.key === key);
    return s ? s.name : key;
  }

  function renderSourceSubBar(src) {
    const tabsEl = document.getElementById("exploreTabs");
    tabsEl.innerHTML = "";
    const exploreArr = Array.isArray(src.explore) ? src.explore : [];
    exploreArr.forEach((exp, idx) => {
      const btn = document.createElement("button");
      btn.className = "sub-tab" + (!appState.currentCategory && idx === appState.currentExploreIdx ? " active" : "");
      btn.textContent = exp.title || ("发现 " + (idx + 1));
      btn.onclick = () => {
        appState.currentCategory = null;
        appState.currentExploreIdx = idx;
        appState.currentExploreNextToken = null;
        loadActiveHomeFeed(1);
      };
      tabsEl.appendChild(btn);
    });

    const catBtn = document.getElementById("btnCategoryPicker");
    const hasCat = src.category && Array.isArray(src.category.parts) && src.category.parts.length > 0;
    catBtn.classList.toggle("hidden", !hasCat);
    if (hasCat) {
      catBtn.textContent = appState.currentCategory ? ("分类: " + appState.currentCategory.category + " ▾") : "分类筛选 ▾";
      catBtn.onclick = () => openCategoryModal(src);
    }

    const loginBtn = document.getElementById("btnSourceLoginQuick");
    const hasAcc = !!(src.account && (src.account.login || src.account.loginWithCookies));
    loginBtn.classList.toggle("hidden", !hasAcc);
    if (hasAcc) {
      loginBtn.textContent = src.isLogged ? "已登录 ✓" : "登录源账号";
      loginBtn.onclick = () => openSourceLoginModal(appState.activeHomeMode);
    }
  }

  function updatePaginationUi(count) {
    const bar = document.getElementById("paginationBar");
    if (count === 0 && appState.currentPage === 1) {
      bar.classList.add("hidden");
      return;
    }
    bar.classList.remove("hidden");
    document.getElementById("btnPrevPage").disabled = appState.currentPage <= 1;
    const maxStr = appState.maxPage ? (" / " + appState.maxPage) : "";
    document.getElementById("pageIndicator").textContent = "第 " + appState.currentPage + maxStr + " 页";
    document.getElementById("btnNextPage").disabled = !!(appState.maxPage && appState.currentPage >= appState.maxPage);
  }

  function renderComicGrid(items) {
    const grid = document.getElementById("comicGrid");
    const empty = document.getElementById("feedEmpty");
    grid.classList.toggle("compact", !!appState.gridCompact);
    grid.innerHTML = "";

    if (!items || items.length === 0) {
      empty.classList.remove("hidden");
      return;
    }
    empty.classList.add("hidden");

    items.forEach((it) => {
      grid.appendChild(buildComicCard(it));
    });
  }

  function buildComicCard(it) {
    const card = document.createElement("div");
    card.className = "comic-card";
    const coverSrc = it.isLocal ? it.cover : proxiedImageUrl(it.cover, it.headers);
    card.innerHTML =
      '<div class="comic-cover-wrap">' +
      (coverSrc ? '<img class="comic-cover" loading="lazy" src="' + escapeHtml(coverSrc) + '" alt="" />' : "") +
      (it.badge ? '<span class="comic-badge">' + escapeHtml(it.badge) + "</span>" : "") +
      "</div>" +
      '<div class="comic-meta">' +
      '<div class="comic-title">' + escapeHtml(it.title) + "</div>" +
      '<div class="comic-sub">' + escapeHtml(it.subTitle || "") + "</div>" +
      "</div>";

    const img = card.querySelector("img");
    if (img) {
      img.onerror = function () {
        img.style.opacity = "0.18";
      };
    }

    card.onclick = () => {
      if (it.isLocal) openLocalComicInReader(it);
      else openOnlineComicDetail(it);
    };
    return card;
  }

  /* Quick Source Picker (148+ sources searchable) */
  function openQuickSourcePicker() {
    openModal("快速切换动漫源 (" + appState.sources.length + ")", (body) => {
      body.innerHTML =
        '<div class="mgr-toolbar">' +
        '<input id="quickPickFilter" class="text-input" type="search" placeholder="输入源名称或 key，例如：拷贝、再漫画、picacg、jm、mangadex..." autofocus />' +
        '<button id="quickPickOpenMgr" class="pill-btn">源管理</button>' +
        "</div>" +
        '<div id="quickPickList" class="source-list"></div>';

      const listEl = body.querySelector("#quickPickList");
      const inputEl = body.querySelector("#quickPickFilter");
      body.querySelector("#quickPickOpenMgr").onclick = () => {
        closeModal();
        openSourceManagerModal();
      };

      const renderRows = (q) => {
        const lq = (q || "").trim().toLowerCase();
        const filtered = appState.sources.filter(
          (s) => !lq || s.name.toLowerCase().includes(lq) || s.key.toLowerCase().includes(lq)
        );
        listEl.innerHTML = "";
        filtered.forEach((s) => {
          const row = document.createElement("div");
          row.className = "source-item";
          row.innerHTML =
            '<div class="source-info"><div class="source-title-row">' +
            '<span class="source-name">' + escapeHtml(s.name) + "</span>" +
            '<span class="source-key">' + escapeHtml(s.key) + " · v" + escapeHtml(s.version) + "</span>" +
            (!s.enabled ? '<span class="badge">已停用</span>' : "") +
            "</div></div>" +
            '<div class="source-controls"><button class="pill-btn small primary">切换到此源</button></div>';
          row.querySelector("button").onclick = () => {
            if (!s.enabled) s.enabled = true;
            closeModal();
            switchHomeMode(s.key);
          };
          listEl.appendChild(row);
        });
      };

      inputEl.oninput = () => renderRows(inputEl.value);
      renderRows("");
      setTimeout(() => inputEl.focus(), 40);
    });
  }

  /* Full Source Manager Modal */
  function openSourceManagerModal() {
    const enabledCount = () => appState.sources.filter((s) => s.enabled).length;
    openModal("在线漫画源管理 (" + enabledCount() + "/" + appState.sources.length + ")", (body) => {
      body.innerHTML =
        '<div class="mgr-toolbar">' +
        '<input id="mgrSearchInput" class="text-input" type="search" placeholder="搜索内置源名称或 key..." />' +
        '<button id="btnMgrEnableAll" class="pill-btn small">全启</button>' +
        '<button id="btnMgrDisableAll" class="pill-btn small">全停</button>' +
        '<button id="btnMgrSubRepo" class="pill-btn small primary">源仓库订阅</button>' +
        '<button id="btnMgrImportJs" class="pill-btn small">导入 .js</button>' +
        '<input id="mgrFileInput" type="file" accept=".js" class="hidden" />' +
        "</div>" +
        '<div id="mgrSourceList" class="source-list"></div>';

      const searchEl = body.querySelector("#mgrSearchInput");
      const listEl = body.querySelector("#mgrSourceList");
      const updateTitle = () => {
        document.getElementById("modalTitle").textContent = "在线漫画源管理 (" + enabledCount() + "/" + appState.sources.length + ")";
      };

      const renderList = () => {
        const q = searchEl.value.trim().toLowerCase();
        const items = appState.sources.filter(
          (s) => !q || s.name.toLowerCase().includes(q) || s.key.toLowerCase().includes(q)
        );
        listEl.innerHTML = "";
        items.forEach((s) => {
          const row = document.createElement("div");
          row.className = "source-item";
          row.innerHTML =
            '<div class="source-info">' +
            '<div class="source-title-row">' +
            '<span class="source-name">' + escapeHtml(s.name) + "</span>" +
            '<span class="source-key">' + escapeHtml(s.key) + " · v" + escapeHtml(s.version) + "</span>" +
            "</div></div>" +
            '<div class="source-controls">' +
            '<button class="pill-btn small btn-browse">浏览</button>' +
            '<button class="pill-btn small btn-login">账号</button>' +
            '<button class="pill-btn small btn-cfg">设置</button>' +
            '<input type="checkbox" class="toggle-switch" ' + (s.enabled ? "checked" : "") + " />" +
            "</div>";

          row.querySelector(".toggle-switch").onchange = (ev) => {
            s.enabled = ev.target.checked;
            scheduleSaveState();
            rebuildHomeChips();
            updateTitle();
          };
          row.querySelector(".btn-browse").onclick = () => {
            s.enabled = true;
            scheduleSaveState();
            closeModal();
            switchHomeMode(s.key);
          };
          row.querySelector(".btn-login").onclick = () => openSourceLoginModal(s.key);
          row.querySelector(".btn-cfg").onclick = () => openSourceSettingsModal(s.key);
          listEl.appendChild(row);
        });
      };

      searchEl.oninput = renderList;
      body.querySelector("#btnMgrEnableAll").onclick = () => {
        appState.sources.forEach((s) => (s.enabled = true));
        scheduleSaveState();
        rebuildHomeChips();
        updateTitle();
        renderList();
      };
      body.querySelector("#btnMgrDisableAll").onclick = () => {
        appState.sources.forEach((s) => (s.enabled = false));
        scheduleSaveState();
        rebuildHomeChips();
        updateTitle();
        renderList();
      };
      body.querySelector("#btnMgrSubRepo").onclick = () => openRepoSubscribeModal();
      const fileInp = body.querySelector("#mgrFileInput");
      body.querySelector("#btnMgrImportJs").onclick = () => fileInp.click();
      fileInp.onchange = async () => {
        const f = fileInp.files && fileInp.files[0];
        if (!f) return;
        const text = await f.text();
        importCustomScript(text, f.name.replace(/\.js$/i, ""), () => {
          updateTitle();
          renderList();
        });
      };

      renderList();
    });
  }

  async function importCustomScript(scriptText, fallbackKey, onDone) {
    const className = parseVeneraClassName(scriptText);
    if (!className) {
      showToast("导入失败：脚本中未找到 extends ComicSource");
      return;
    }
    const km = /(?:\bkey\s*[:=]|["']key["']\s*:)\s*["']([^"']+)["']/.exec(scriptText);
    const nm = /(?:\bname\s*[:=]|["']name["']\s*:)\s*["']([^"']+)["']/.exec(scriptText);
    const vm = /(?:\bversion\s*[:=]|["']version["']\s*:)\s*["']([^"']+)["']/.exec(scriptText);
    const key = (km && km[1]) || fallbackKey || ("custom_" + Date.now());
    const name = (nm && nm[1]) || key;
    const version = (vm && vm[1]) || "1.0.0";

    const existing = appState.sources.find((s) => s.key === key);
    if (existing) {
      existing.name = name;
      existing.version = version;
      existing.customScript = scriptText;
      existing.enabled = true;
    } else {
      appState.sources.unshift({ key, name, version, fileName: "", url: "", enabled: true, customScript: scriptText });
    }
    if (window.__veneraSources) delete window.__veneraSources[key];
    scheduleSaveState();
    rebuildHomeChips();
    showToast("已导入漫画源: " + name);
    if (onDone) onDone();
  }

  function openRepoSubscribeModal() {
    openModal("订阅 Venera 源仓库 (index.json)", (body) => {
      body.innerHTML =
        '<div class="mgr-toolbar">' +
        '<input id="repoUrlInput" class="text-input" type="url" value="' + escapeHtml(DEFAULT_REPO_URLS[1]) + '" />' +
        '<button id="btnFetchRepo" class="pill-btn primary">拉取仓库</button>' +
        "</div>" +
        '<div id="repoStatus" class="muted" style="margin-bottom:10px;">默认指向 zmf678/venera-sources 社区源仓库，支持同步更新最新版本脚本。</div>' +
        '<div id="repoItemsList" class="source-list"></div>';

      const statusEl = body.querySelector("#repoStatus");
      const listEl = body.querySelector("#repoItemsList");

      const loadRepo = async () => {
        const inpUrl = body.querySelector("#repoUrlInput").value.trim();
        const tryUrls = inpUrl ? [inpUrl] : DEFAULT_REPO_URLS;
        statusEl.textContent = "正在通过原生代理请求仓库索引...";
        listEl.innerHTML = "";
        let data = null;
        let usedUrl = "";

        for (const u of tryUrls) {
          try {
            const resp = await window.Network.get(u, {});
            if (resp && resp.status >= 200 && resp.status < 300) {
              data = JSON.parse(resp.body);
              usedUrl = u;
              break;
            }
          } catch (_) {}
        }

        if (!Array.isArray(data)) {
          statusEl.textContent = "拉取仓库失败，请检查网络连接或仓库地址。";
          return;
        }
        const base = usedUrl.substring(0, usedUrl.lastIndexOf("/") + 1);
        statusEl.textContent = "共拉取到 " + data.length + " 个源。点击右侧可单独更新或重新下载覆盖内置脚本。";

        data.forEach((item) => {
          const row = document.createElement("div");
          row.className = "source-item";
          row.innerHTML =
            '<div class="source-info"><div class="source-title-row">' +
            '<span class="source-name">' + escapeHtml(item.name || item.key) + "</span>" +
            '<span class="source-key">' + escapeHtml(item.key) + " · v" + escapeHtml(item.version || "1.0.0") + "</span>" +
            "</div></div>" +
            '<button class="pill-btn small primary">同步/安装</button>';

          row.querySelector("button").onclick = async () => {
            const scriptUrl = /^https?:\/\//i.test(item.fileName) ? item.fileName : (base + item.fileName.replace(/^\/+/, ""));
            try {
              const sr = await window.Network.get(scriptUrl, {});
              if (!sr || sr.status >= 300) throw new Error("HTTP " + (sr && sr.status));
              await importCustomScript(sr.body, item.key);
            } catch (e) {
              showToast("安装失败: " + (e.message || e));
            }
          };
          listEl.appendChild(row);
        });
      };

      body.querySelector("#btnFetchRepo").onclick = loadRepo;
      loadRepo();
    });
  }

  async function openSourceLoginModal(sourceKey) {
    try {
      const src = await ensureSourceInstance(sourceKey);
      const acc = src.account || {};
      const hasPwd = typeof acc.login === "function";
      const cookieFields = acc.loginWithCookies && Array.isArray(acc.loginWithCookies.fields) ? acc.loginWithCookies.fields : [];

      openModal("源账号管理 — " + getSourceDisplayName(sourceKey), (body) => {
        let html = '<div style="display:flex;flex-direction:column;gap:12px;">';
        html += '<div class="muted">当前状态：' + (src.isLogged ? "已登录 ✓" : "未登录") + "</div>";

        if (hasPwd) {
          html +=
            '<label class="field"><span>用户名 / 邮箱</span><input id="loginUser" type="text" /></label>' +
            '<label class="field"><span>密码</span><input id="loginPass" type="password" /></label>' +
            '<div class="card-actions"><button id="btnPwdLogin" class="pill-btn primary">账号密码登录</button>';
          if (src.isLogged) html += '<button id="btnLogoutAcc" class="pill-btn danger">退出登录</button>';
          html += "</div>";
        }

        if (cookieFields.length > 0) {
          html += '<hr style="border-color:var(--border);width:100%;" />';
          cookieFields.forEach((f, idx) => {
            html += '<label class="field"><span>Cookie 字段: ' + escapeHtml(f) + '</span><input class="cookie-field" data-idx="' + idx + '" type="text" /></label>';
          });
          html += '<div><button id="btnCookieLogin" class="pill-btn primary">使用 Cookie 验证登录</button></div>';
        }

        if (!hasPwd && cookieFields.length === 0) {
          html += '<div class="empty-desc">该漫画源无需账号登录，或仅支持网页端登录。</div>';
        }
        if (acc.registerWebsite) {
          html += '<div class="muted">注册/官网：<a href="' + escapeHtml(acc.registerWebsite) + '" target="_blank" style="color:var(--accent);">' + escapeHtml(acc.registerWebsite) + "</a></div>";
        }
        html += "</div>";
        body.innerHTML = html;

        const pwdBtn = body.querySelector("#btnPwdLogin");
        if (pwdBtn) {
          pwdBtn.onclick = async () => {
            const u = body.querySelector("#loginUser").value.trim();
            const p = body.querySelector("#loginPass").value;
            try {
              const res = await acc.login(u, p);
              if (res === false) throw new Error("账号或密码不正确");
              src.saveData("__logged_in__", true);
              showToast("登录成功");
              closeModal();
              loadActiveHomeFeed(1);
            } catch (e) {
              showToast("登录失败: " + (e.message || e));
            }
          };
        }

        const outBtn = body.querySelector("#btnLogoutAcc");
        if (outBtn) {
          outBtn.onclick = async () => {
            if (typeof acc.logout === "function") await Promise.resolve(acc.logout());
            src.deleteData("__logged_in__");
            showToast("已退出登录");
            closeModal();
          };
        }

        const ckBtn = body.querySelector("#btnCookieLogin");
        if (ckBtn) {
          ckBtn.onclick = async () => {
            const vals = Array.from(body.querySelectorAll(".cookie-field")).map((el) => el.value.trim());
            try {
              const ok = await acc.loginWithCookies.validate(vals);
              if (!ok) throw new Error("Cookie 无效");
              src.saveData("__logged_in__", true);
              showToast("Cookie 登录成功");
              closeModal();
              loadActiveHomeFeed(1);
            } catch (e) {
              showToast("验证失败: " + (e.message || e));
            }
          };
        }
      });
    } catch (e) {
      showToast("无法打开账号界面: " + (e.message || e));
    }
  }

  async function openSourceSettingsModal(sourceKey) {
    try {
      const src = await ensureSourceInstance(sourceKey);
      const schema = src.__settingsSchema || src.settings || {};
      const keys = Object.keys(schema);

      openModal("源设置 — " + getSourceDisplayName(sourceKey), (body) => {
        if (keys.length === 0) {
          body.innerHTML = '<div class="empty-desc">该漫画源暂无可配置选项。</div>';
          return;
        }
        const wrap = document.createElement("div");
        wrap.style.display = "flex";
        wrap.style.flexDirection = "column";
        wrap.style.gap = "12px";

        keys.forEach((k) => {
          const item = schema[k];
          if (!item || typeof item !== "object") return;
          const row = document.createElement("div");
          row.className = "setting-row";
          const left = document.createElement("div");
          left.innerHTML = '<div class="setting-name">' + escapeHtml(item.title || k) + "</div>";
          row.appendChild(left);

          const curVal = src.loadSetting(k);
          if (item.type === "switch") {
            const chk = document.createElement("input");
            chk.type = "checkbox";
            chk.className = "toggle-switch";
            chk.checked = !!curVal;
            chk.onchange = () => src.saveSetting(k, chk.checked);
            row.appendChild(chk);
          } else if (item.type === "select" && Array.isArray(item.options)) {
            const sel = document.createElement("select");
            sel.className = "select-input";
            item.options.forEach((opt) => {
              const o = document.createElement("option");
              o.value = opt.value;
              o.textContent = opt.text || opt.value;
              if (String(curVal) === String(opt.value)) o.selected = true;
              sel.appendChild(o);
            });
            sel.onchange = () => src.saveSetting(k, sel.value);
            row.appendChild(sel);
          } else if (item.type === "callback") {
            const btn = document.createElement("button");
            btn.className = "pill-btn small primary";
            btn.textContent = item.buttonText || "执行";
            btn.onclick = async () => {
              try {
                await src.settings[k].callback();
                showToast("操作完成");
              } catch (e) {
                showToast("执行失败: " + (e.message || e));
              }
            };
            row.appendChild(btn);
          } else {
            const inp = document.createElement("input");
            inp.type = "text";
            inp.className = "text-input";
            inp.value = curVal == null ? "" : String(curVal);
            inp.onchange = () => src.saveSetting(k, inp.value);
            row.appendChild(inp);
          }
          wrap.appendChild(row);
        });
        body.appendChild(wrap);
      });
    } catch (e) {
      showToast("加载源设置失败: " + (e.message || e));
    }
  }

  function openCategoryModal(src) {
    const parts = (src.category && Array.isArray(src.category.parts)) ? src.category.parts : [];
    openModal("选择分类", (body) => {
      parts.forEach((part) => {
        const title = document.createElement("h4");
        title.textContent = part.name || "分类";
        title.style.margin = "8px 0";
        body.appendChild(title);

        const wrap = document.createElement("div");
        wrap.style.display = "flex";
        wrap.style.flexWrap = "wrap";
        wrap.style.gap = "8px";
        const cats = Array.isArray(part.categories) ? part.categories : [];
        const params = Array.isArray(part.categoryParams) ? part.categoryParams : [];
        cats.forEach((cName, idx) => {
          const b = document.createElement("button");
          b.className = "chip";
          b.textContent = cName;
          b.onclick = () => {
            appState.currentCategory = { category: cName, param: params[idx] || "" };
            closeModal();
            loadActiveHomeFeed(1);
          };
          wrap.appendChild(b);
        });
        body.appendChild(wrap);
      });
    });
  }

  /* Online Comic Detail + Chapter Picker */
  async function openOnlineComicDetail(item) {
    openModal(item.title, (body) => {
      body.innerHTML = '<div class="empty-desc">正在加载漫画详情与章节列表...</div>';
    });

    try {
      const src = await ensureSourceInstance(item.sourceKey);
      if (!src.comic || typeof src.comic.loadInfo !== "function") {
        throw new Error("该漫画源未实现详情接口");
      }
      const res = await src.comic.loadInfo(item.id);
      const fallbackHeaders = window.__extractSourceImageHeaders(src);
      const headers = Object.assign({}, fallbackHeaders, item.headers || {}, (res && res.headers) || {});
      const serialized = window.__serializeVenera(res) || {};
      const chapters = extractChaptersList(serialized.chapters);
      const tags = extractTagsList(serialized.tags);

      const modalBody = document.getElementById("modalBody");
      modalBody.innerHTML = "";

      const coverUrl = proxiedImageUrl(serialized.cover || item.cover, headers);
      const isFav = appState.favorites.some((f) => f.uid === item.uid);

      const hero = document.createElement("div");
      hero.className = "detail-hero";
      hero.innerHTML =
        '<img class="detail-cover" src="' + escapeHtml(coverUrl) + '" alt="" />' +
        '<div class="detail-info">' +
        '<h2 class="detail-title">' + escapeHtml(serialized.title || item.title) + "</h2>" +
        '<div class="muted">' + escapeHtml(serialized.subTitle || item.subTitle || "") + "</div>" +
        '<div class="detail-tags">' + tags.slice(0, 14).map((t) => '<span class="tag-pill">' + escapeHtml(t) + "</span>").join("") + "</div>" +
        '<div class="detail-desc">' + escapeHtml(serialized.description || "暂无简介") + "</div>" +
        '<div class="card-actions">' +
        '<button id="btnDetailRead" class="pill-btn primary">开始阅读 (' + chapters.length + " 话)</button>" +
        '<button id="btnDetailFav" class="pill-btn">' + (isFav ? "已收藏 ★" : "加入收藏 ☆") + "</button>" +
        '<button id="btnDetailCollect" class="pill-btn">加入作品集</button>' +
        "</div></div>";
      modalBody.appendChild(hero);

      hero.querySelector("#btnDetailFav").onclick = (ev) => {
        toggleFavorite(item);
        const nowFav = appState.favorites.some((f) => f.uid === item.uid);
        ev.target.textContent = nowFav ? "已收藏 ★" : "加入收藏 ☆";
      };
      hero.querySelector("#btnDetailCollect").onclick = () => openAddToCollectionPicker(item);
      hero.querySelector("#btnDetailRead").onclick = () => {
        if (!chapters.length) {
          showToast("暂无可阅读章节");
          return;
        }
        const savedPref = appState.comicPrefs[item.uid];
        const chIdx = savedPref && typeof savedPref.lastChapterIdx === "number"
          ? Math.max(0, Math.min(chapters.length - 1, savedPref.lastChapterIdx))
          : 0;
        const pageIdx = savedPref && typeof savedPref.lastPage === "number" ? savedPref.lastPage : 0;
        closeModal();
        startOnlineReader(item, chapters, chIdx, pageIdx, headers);
      };

      const chHeader = document.createElement("div");
      chHeader.style.fontWeight = "700";
      chHeader.textContent = "章节目录 (" + chapters.length + ")";
      modalBody.appendChild(chHeader);

      const chGrid = document.createElement("div");
      chGrid.className = "chapters-grid";
      chapters.forEach((ch, idx) => {
        const b = document.createElement("button");
        b.className = "chapter-btn";
        b.textContent = ch.title;
        b.onclick = () => {
          closeModal();
          startOnlineReader(item, chapters, idx, 0, headers);
        };
        chGrid.appendChild(b);
      });
      modalBody.appendChild(chGrid);
    } catch (e) {
      document.getElementById("modalBody").innerHTML = '<div class="empty-desc">加载详情失败：' + escapeHtml(e.message || e) + "</div>";
    }
  }

  function extractChaptersList(chObj) {
    const out = [];
    function walk(node, prefix) {
      if (!node || typeof node !== "object") return;
      if (Array.isArray(node.__mapEntries__)) {
        node.__mapEntries__.forEach(([k, v]) => {
          if (v && typeof v === "object") walk(v, prefix ? prefix + " · " + k : String(k));
          else out.push({ id: String(k), title: prefix && prefix !== "默认" ? (prefix + " - " + (v || k)) : String(v || k) });
        });
        return;
      }
      Object.keys(node).forEach((k) => {
        const v = node[k];
        if (v && typeof v === "object") walk(v, prefix ? prefix + " · " + k : k);
        else out.push({ id: k, title: prefix && prefix !== "默认" ? (prefix + " - " + (v || k)) : String(v || k) });
      });
    }
    walk(chObj, "");
    return out;
  }

  function extractTagsList(tagsObj) {
    const out = [];
    if (!tagsObj || typeof tagsObj !== "object") return out;
    if (Array.isArray(tagsObj.__mapEntries__)) {
      tagsObj.__mapEntries__.forEach(([, arr]) => {
        if (Array.isArray(arr)) arr.forEach((v) => out.push(String(v)));
      });
    } else {
      Object.keys(tagsObj).forEach((k) => {
        if (Array.isArray(tagsObj[k])) tagsObj[k].forEach((v) => out.push(String(v)));
      });
    }
    return out;
  }

  function toggleFavorite(item) {
    const idx = appState.favorites.findIndex((f) => f.uid === item.uid);
    if (idx >= 0) {
      appState.favorites.splice(idx, 1);
      showToast("已取消收藏");
    } else {
      appState.favorites.unshift(item);
      showToast("已加入收藏");
    }
    scheduleSaveState();
    if (appState.activeHomeMode === "fav") loadActiveHomeFeed(1);
  }

  function pushHistory(item) {
    appState.history = appState.history.filter((h) => h.uid !== item.uid);
    appState.history.unshift(item);
    if (appState.history.length > 120) appState.history.length = 120;
    scheduleSaveState();
  }

  /* Collections */
  function openAddToCollectionPicker(item) {
    openModal("加入作品集", (body) => {
      const list = document.createElement("div");
      list.className = "source-list";
      appState.collections.forEach((col) => {
        const exists = (col.items || []).some((x) => x.uid === item.uid);
        const row = document.createElement("div");
        row.className = "source-item";
        row.innerHTML =
          '<span class="source-name">' + escapeHtml(col.name) + ' (' + (col.items ? col.items.length : 0) + ")</span>" +
          '<button class="pill-btn small ' + (exists ? "danger" : "primary") + '">' + (exists ? "移出" : "加入") + "</button>";
        row.querySelector("button").onclick = () => {
          col.items = col.items || [];
          if (exists) col.items = col.items.filter((x) => x.uid !== item.uid);
          else col.items.unshift(item);
          scheduleSaveState();
          closeModal();
          renderCollectionsPanel();
          showToast(exists ? "已移出作品集" : "已加入「" + col.name + "」");
        };
        list.appendChild(row);
      });
      body.appendChild(list);
    });
  }

  function renderCollectionsPanel() {
    const bar = document.getElementById("collectionChipsBar");
    bar.innerHTML = "";
    appState.collections.forEach((c) => {
      const btn = document.createElement("button");
      btn.className = "chip" + (c.id === appState.activeCollectionId ? " active" : "");
      btn.textContent = (c.pin ? "🔒 " : "") + c.name + " (" + (c.items ? c.items.length : 0) + ")";
      btn.onclick = () => {
        appState.activeCollectionId = c.id;
        scheduleSaveState();
        renderCollectionsPanel();
      };
      bar.appendChild(btn);
    });

    const cur = appState.collections.find((c) => c.id === appState.activeCollectionId) || appState.collections[0];
    const lockedPanel = document.getElementById("collectionLockedNotice");
    const grid = document.getElementById("collectionGrid");
    const empty = document.getElementById("collectionEmpty");
    grid.innerHTML = "";

    if (!cur) return;
    const isLocked = cur.pin && !appState.unlockedCollections[cur.id];
    lockedPanel.classList.toggle("hidden", !isLocked);
    if (isLocked) {
      empty.classList.add("hidden");
      document.getElementById("collectionLockedTitle").textContent = "作品集「" + cur.name + "」已设定 PIN 锁";
      document.getElementById("btnUnlockCollection").onclick = () => {
        const entered = window.prompt("请输入作品集「" + cur.name + "」的 PIN 密码：");
        if (entered === cur.pin) {
          appState.unlockedCollections[cur.id] = true;
          renderCollectionsPanel();
        } else if (entered !== null) {
          showToast("PIN 密码错误");
        }
      };
      return;
    }

    const items = cur.items || [];
    empty.classList.toggle("hidden", items.length > 0);
    items.forEach((it) => grid.appendChild(buildComicCard(it)));
  }

  /* Warehouse */
  function renderWarehousePanel() {
    document.getElementById("warehouseSummaryBadge").textContent = appState.localComics.length + " 本本地漫画";
    document.getElementById("webdavUrl").value = appState.webdav.url || "";
    document.getElementById("webdavUser").value = appState.webdav.username || "";
    document.getElementById("webdavPass").value = appState.webdav.password || "";

    const list = document.getElementById("warehousePathList");
    list.innerHTML = "";
    if (!appState.warehousePaths.length) {
      list.innerHTML = '<div class="muted">尚未添加任何本机漫画目录或压缩包。点击上方按钮挂载本机目录或 ZIP/CBZ/EPUB 文件。</div>';
      return;
    }
    appState.warehousePaths.forEach((p) => {
      const row = document.createElement("div");
      row.className = "path-item";
      row.innerHTML = '<span class="path-text">' + escapeHtml(p) + '</span><button class="pill-btn small danger">移除</button>';
      row.querySelector("button").onclick = async () => {
        const r = await fetch("/api/warehouse/remove", {
          method: "POST",
          headers: { "Content-Type": "application/json; charset=utf-8" },
          body: JSON.stringify({ path: p })
        });
        if (r.ok) {
          const wd = await r.json();
          appState.warehousePaths = wd.paths || [];
          appState.localComics = wd.comics || [];
          renderWarehousePanel();
          if (appState.activeHomeMode === "local") loadActiveHomeFeed(1);
        }
      };
      list.appendChild(row);
    });
  }

  /* Reader */
  function getActiveReaderProfile() {
    const uid = readerState.comic && readerState.comic.uid;
    const perComic = (!appState.readerDefaults.applyGlobal && uid && appState.comicPrefs[uid]) ? appState.comicPrefs[uid] : {};
    return Object.assign({}, appState.readerDefaults, perComic);
  }

  function updateActiveReaderProfile(patch) {
    Object.assign(appState.readerDefaults, patch);
    if (readerState.comic && readerState.comic.uid) {
      appState.comicPrefs[readerState.comic.uid] = Object.assign(
        {},
        appState.comicPrefs[readerState.comic.uid] || {},
        patch
      );
    }
    scheduleSaveState();
    syncReaderSettingsUi();
    renderReaderPages();
  }

  async function openLocalComicInReader(item) {
    pushHistory(item);
    readerState.open = true;
    readerState.isLocal = true;
    readerState.comic = item;
    readerState.chapters = [{ id: "local", title: "全本 (" + item.subTitle + ")" }];
    readerState.chapterIdx = 0;
    const saved = appState.comicPrefs[item.uid];
    readerState.pageIndex = (saved && typeof saved.lastPage === "number") ? saved.lastPage : 0;
    readerState.spreadShift = 0;
    readerState.zoom = 1;
    readerState.wideFlags = {};
    readerState.chromeVisible = false;

    document.getElementById("readerOverlay").classList.remove("hidden");
    syncReaderSettingsUi();
    document.getElementById("readerLoading").classList.remove("hidden");
    try {
      const r = await fetch("/api/local/comic?id=" + encodeURIComponent(item.id));
      const info = await r.json();
      const pages = [];
      for (let i = 0; i < (info.pageCount || 0); i++) {
        pages.push({ url: "/api/local/page?id=" + encodeURIComponent(item.id) + "&page=" + i });
      }
      readerState.pages = pages;
      if (readerState.pageIndex >= pages.length) readerState.pageIndex = 0;
      renderReaderPages();
    } catch (e) {
      showToast("读取本地漫画失败: " + (e.message || e));
    } finally {
      document.getElementById("readerLoading").classList.add("hidden");
    }
  }

  async function startOnlineReader(item, chapters, chapterIdx, pageIdx, headers) {
    pushHistory(item);
    readerState.open = true;
    readerState.isLocal = false;
    readerState.comic = Object.assign({}, item, { headers: headers || item.headers || {} });
    readerState.chapters = chapters;
    readerState.chapterIdx = chapterIdx;
    readerState.pageIndex = pageIdx || 0;
    readerState.spreadShift = 0;
    readerState.zoom = 1;
    readerState.wideFlags = {};
    readerState.chromeVisible = false;

    document.getElementById("readerOverlay").classList.remove("hidden");
    syncReaderSettingsUi();
    await loadOnlineChapter(chapterIdx, pageIdx || 0);
  }

  async function loadOnlineChapter(chapterIdx, targetPageIdx) {
    readerState.chapterIdx = chapterIdx;
    readerState.pages = [];
    readerState.wideFlags = {};
    readerState.pageIndex = targetPageIdx || 0;
    document.getElementById("readerLoading").classList.remove("hidden");
    renderReaderPages();

    const ch = readerState.chapters[chapterIdx];
    try {
      const src = await ensureSourceInstance(readerState.comic.sourceKey);
      const r = await src.comic.loadEp(readerState.comic.id, ch.id);
      const urls = (r && r.images) || [];
      const globalHeaders = Object.assign({}, window.__extractSourceImageHeaders(src), (r && r.headers) || {});
      const pages = [];
      for (const u of urls) {
        let finalUrl = typeof u === "string" ? u : (u && u.url) || "";
        let h = Object.assign({}, globalHeaders, (u && u.headers) || {});
        if (typeof src.comic.onImageLoad === "function") {
          try {
            const cfg = await src.comic.onImageLoad(finalUrl, readerState.comic.id, ch.id);
            if (cfg && cfg.url) finalUrl = cfg.url;
            if (cfg && cfg.headers) h = Object.assign({}, h, cfg.headers);
          } catch (_) {}
        }
        if (finalUrl) pages.push({ url: proxiedImageUrl(finalUrl, h) });
      }
      readerState.pages = pages;
      if (readerState.pageIndex >= pages.length) readerState.pageIndex = Math.max(0, pages.length - 1);
      saveReaderProgress();
      renderReaderPages();
    } catch (e) {
      showToast("章节加载失败: " + (e.message || e));
    } finally {
      document.getElementById("readerLoading").classList.add("hidden");
    }
  }

  function saveReaderProgress() {
    if (!readerState.comic || !readerState.comic.uid) return;
    const uid = readerState.comic.uid;
    appState.comicPrefs[uid] = Object.assign({}, appState.comicPrefs[uid] || {}, {
      lastChapterIdx: readerState.chapterIdx,
      lastPage: readerState.pageIndex
    });
    scheduleSaveState();
  }

  function syncReaderSettingsUi() {
    const prof = getActiveReaderProfile();
    const overlay = document.getElementById("readerOverlay");
    overlay.classList.toggle("chrome-hidden", !readerState.chromeVisible);

    document.getElementById("readerComicTitle").textContent = (readerState.comic && readerState.comic.title) || "";
    const ch = readerState.chapters[readerState.chapterIdx];
    document.getElementById("readerChapterTitle").textContent = ch ? ch.title : "";

    document.getElementById("rngBrightness").value = prof.brightness;
    document.getElementById("lblBrightness").textContent = prof.brightness + "%";
    document.querySelectorAll("#drawerReadingModeGroup .drawer-opt-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.mode === prof.mode);
    });
    document.querySelectorAll("#drawerQualityGroup .drawer-opt-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.quality === prof.quality);
    });
    document.querySelectorAll("#drawerSpreadGroup .drawer-opt-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.spread === prof.spread);
    });
    document.getElementById("chkApplyGlobalMode").checked = !!appState.readerDefaults.applyGlobal;
    document.getElementById("chkTrimMargins").checked = !!prof.trimMargins;
    document.getElementById("chkDoubleTapZoom").checked = !!prof.doubleTapZoom;
    document.getElementById("chkShowCommentPage").checked = !!prof.showCommentPage;
    document.getElementById("chkShowPageNumber").checked = !!prof.showPageNumber;

    const showShift = prof.mode !== "scroll" && prof.spread === "double";
    document.getElementById("btnSpreadPairShift").classList.toggle("hidden", !showShift);
    document.getElementById("readerPageBadge").classList.toggle("hidden", !prof.showPageNumber);
  }

  function renderReaderPages() {
    const prof = getActiveReaderProfile();
    const vp = document.getElementById("readerViewport");
    const stage = document.getElementById("readerStage");
    const pages = readerState.pages || [];

    stage.innerHTML = "";
    stage.style.filter = "brightness(" + (prof.brightness || 100) + "%)";
    stage.style.transform = "scale(" + readerState.zoom + ")";
    stage.classList.toggle("trim-margins", !!prof.trimMargins);

    const isScroll = prof.mode === "scroll";
    const isDouble = !isScroll && prof.spread === "double";
    vp.classList.toggle("scroll-mode", isScroll);
    stage.classList.toggle("double-spread", isDouble);

    const total = pages.length;
    const seek = document.getElementById("readerSeekBar");
    seek.max = Math.max(1, total);
    seek.value = Math.min(Math.max(1, readerState.pageIndex + 1), Math.max(1, total));
    const pageTxt = total ? ((readerState.pageIndex + 1) + "/" + total) : "0/0";
    document.getElementById("readerSeekText").textContent = pageTxt;
    document.getElementById("readerPageBadge").textContent = pageTxt;

    if (!total) return;

    const makeImg = (idx, isWideSingle) => {
      const img = document.createElement("img");
      img.className = "reader-page-img" + (isWideSingle ? " wide-single" : "");
      img.src = pages[idx].url;
      img.draggable = false;
      img.onload = () => {
        if (img.naturalWidth > img.naturalHeight * 1.15 && !readerState.wideFlags[idx]) {
          readerState.wideFlags[idx] = true;
          if (isDouble && stage.children.length === 2) {
            renderReaderPages();
          }
        }
      };
      return img;
    };

    if (isScroll) {
      pages.forEach((_, idx) => stage.appendChild(makeImg(idx, false)));
    } else if (isDouble) {
      const cur = readerState.pageIndex;
      const forceSingle = (cur === 0 && readerState.spreadShift === 1) || !!readerState.wideFlags[cur];
      if (forceSingle || cur + 1 >= total || !!readerState.wideFlags[cur + 1]) {
        stage.appendChild(makeImg(cur, true));
      } else if (prof.mode === "rtl") {
        stage.appendChild(makeImg(cur + 1, false));
        stage.appendChild(makeImg(cur, false));
      } else {
        stage.appendChild(makeImg(cur, false));
        stage.appendChild(makeImg(cur + 1, false));
      }
    } else {
      stage.appendChild(makeImg(readerState.pageIndex, true));
    }
  }

  function turnReaderPage(delta) {
    const prof = getActiveReaderProfile();
    const total = readerState.pages.length;
    if (!total) return;
    if (prof.mode === "scroll") {
      const vp = document.getElementById("readerViewport");
      vp.scrollBy({ top: delta * vp.clientHeight * 0.85, behavior: "smooth" });
      return;
    }

    const step = (prof.spread === "double" && !readerState.wideFlags[readerState.pageIndex] &&
      !(readerState.pageIndex === 0 && readerState.spreadShift === 1)) ? 2 : 1;

    let next = readerState.pageIndex + (delta > 0 ? step : -step);
    if (delta < 0 && prof.spread === "double" && readerState.pageIndex === 1 && readerState.spreadShift === 1) {
      next = 0;
    }
    if (next < 0) {
      if (readerState.chapterIdx > 0) {
        loadOnlineChapter(readerState.chapterIdx - 1, 0);
      } else {
        showToast("已经是第一页");
      }
      return;
    }
    if (next >= total) {
      if (readerState.chapterIdx + 1 < readerState.chapters.length) {
        loadOnlineChapter(readerState.chapterIdx + 1, 0);
      } else {
        showToast("已是最后一页");
      }
      return;
    }
    readerState.pageIndex = next;
    readerState.zoom = 1;
    saveReaderProgress();
    renderReaderPages();
  }

  /* Modal Helpers */
  function openModal(title, renderFn) {
    document.getElementById("modalTitle").textContent = title;
    const body = document.getElementById("modalBody");
    const footer = document.getElementById("modalFooter");
    body.innerHTML = "";
    footer.innerHTML = "";
    footer.classList.add("hidden");
    renderFn(body, footer);
    document.getElementById("modalBackdrop").classList.remove("hidden");
  }

  function closeModal() {
    document.getElementById("modalBackdrop").classList.add("hidden");
  }

  function syncSettingsInputs() {
    document.getElementById("settingDefaultMode").value = appState.readerDefaults.mode;
    document.getElementById("settingDefaultSpread").value = appState.readerDefaults.spread;
    document.getElementById("settingShowPageNum").checked = !!appState.readerDefaults.showPageNumber;
  }

  function wireEvents() {
    // Left Rail navigation (keeps rail visible, switches right panel)
    document.querySelectorAll(".rail-btn").forEach((btn) => {
      btn.onclick = () => {
        const tab = btn.dataset.tab;
        appState.activeNavTab = tab;
        document.querySelectorAll(".rail-btn").forEach((b) => b.classList.toggle("active", b === btn));
        document.querySelectorAll(".view-panel").forEach((p) => p.classList.toggle("active", p.id === "view-" + tab));
        if (tab === "collections") renderCollectionsPanel();
        if (tab === "warehouse") renderWarehousePanel();
      };
    });

    document.getElementById("btnToggleThemeRail").onclick = () => {
      applyTheme(appState.theme === "dark" ? "light" : "dark");
      scheduleSaveState();
    };

    // Home Toolbar
    const searchInp = document.getElementById("searchInput");
    const clearBtn = document.getElementById("btnClearSearch");
    searchInp.oninput = () => {
      clearBtn.classList.toggle("hidden", !searchInp.value);
      if (["local", "fav", "history"].includes(appState.activeHomeMode)) loadActiveHomeFeed(1);
    };
    searchInp.onkeydown = (e) => {
      if (e.key === "Enter") loadActiveHomeFeed(1);
    };
    clearBtn.onclick = () => {
      searchInp.value = "";
      clearBtn.classList.add("hidden");
      loadActiveHomeFeed(1);
    };
    document.getElementById("btnDoSearch").onclick = () => loadActiveHomeFeed(1);
    document.getElementById("btnRefreshFeed").onclick = () => loadActiveHomeFeed(appState.currentPage);
    document.getElementById("btnDensity").onclick = () => {
      appState.gridCompact = !appState.gridCompact;
      scheduleSaveState();
      document.getElementById("comicGrid").classList.toggle("compact", appState.gridCompact);
    };
    document.getElementById("btnPrevPage").onclick = () => {
      if (appState.currentPage > 1) loadActiveHomeFeed(appState.currentPage - 1);
    };
    document.getElementById("btnNextPage").onclick = () => loadActiveHomeFeed(appState.currentPage + 1);

    // Collections
    document.getElementById("btnNewCollection").onclick = () => {
      const name = window.prompt("输入新作品集名称：");
      if (!name || !name.trim()) return;
      const id = "col_" + Date.now();
      appState.collections.push({ id, name: name.trim(), pin: "", hideFromAll: false, items: [] });
      appState.activeCollectionId = id;
      scheduleSaveState();
      renderCollectionsPanel();
    };
    document.getElementById("btnManageCollection").onclick = () => {
      const cur = appState.collections.find((c) => c.id === appState.activeCollectionId);
      if (!cur) return;
      openModal("管理作品集 — " + cur.name, (body) => {
        body.innerHTML =
          '<div style="display:flex;flex-direction:column;gap:12px;">' +
          '<label class="field"><span>名称</span><input id="colEditName" type="text" value="' + escapeHtml(cur.name) + '" /></label>' +
          '<label class="field"><span>隐私 PIN（留空则不加密）</span><input id="colEditPin" type="text" value="' + escapeHtml(cur.pin || "") + '" /></label>' +
          '<div class="setting-row"><span>从「本地全部」隐藏该作品集内漫画</span><input id="colEditHide" type="checkbox" class="toggle-switch" ' + (cur.hideFromAll ? "checked" : "") + ' /></div>' +
          '<div class="card-actions">' +
          '<button id="btnColSave" class="pill-btn primary">保存</button>' +
          (appState.collections.length > 1 ? '<button id="btnColDelete" class="pill-btn danger">删除该作品集</button>' : "") +
          "</div></div>";
        body.querySelector("#btnColSave").onclick = () => {
          cur.name = body.querySelector("#colEditName").value.trim() || cur.name;
          cur.pin = body.querySelector("#colEditPin").value.trim();
          cur.hideFromAll = body.querySelector("#colEditHide").checked;
          if (!cur.pin) delete appState.unlockedCollections[cur.id];
          scheduleSaveState();
          closeModal();
          renderCollectionsPanel();
        };
        const del = body.querySelector("#btnColDelete");
        if (del) {
          del.onclick = () => {
            appState.collections = appState.collections.filter((c) => c.id !== cur.id);
            appState.activeCollectionId = appState.collections[0].id;
            scheduleSaveState();
            closeModal();
            renderCollectionsPanel();
          };
        }
      });
    };

    // Warehouse
    document.getElementById("btnAddFolder").onclick = async () => {
      const r = await fetch("/api/warehouse/pick-folder", { method: "POST" });
      if (r.ok) {
        const wd = await r.json();
        if (!wd.cancelled) {
          appState.warehousePaths = wd.paths || [];
          appState.localComics = wd.comics || [];
          renderWarehousePanel();
          showToast("已扫描 " + appState.localComics.length + " 本本地漫画");
        }
      }
    };
    document.getElementById("btnAddArchive").onclick = async () => {
      const r = await fetch("/api/warehouse/pick-archive", { method: "POST" });
      if (r.ok) {
        const wd = await r.json();
        if (!wd.cancelled) {
          appState.warehousePaths = wd.paths || [];
          appState.localComics = wd.comics || [];
          renderWarehousePanel();
          showToast("已导入，共 " + appState.localComics.length + " 本本地漫画");
        }
      }
    };
    document.getElementById("btnRescanWarehouse").onclick = async () => {
      const r = await fetch("/api/warehouse/rescan", { method: "POST" });
      if (r.ok) {
        const wd = await r.json();
        appState.warehousePaths = wd.paths || [];
        appState.localComics = wd.comics || [];
        renderWarehousePanel();
        showToast("重新扫描完成：共 " + appState.localComics.length + " 本");
      }
    };
    document.getElementById("btnSaveWebDav").onclick = () => {
      appState.webdav = {
        url: document.getElementById("webdavUrl").value.trim(),
        username: document.getElementById("webdavUser").value.trim(),
        password: document.getElementById("webdavPass").value
      };
      scheduleSaveState();
      showToast("WebDAV 配置已保存");
    };
    document.getElementById("btnTestWebDav").onclick = async () => {
      const payload = {
        url: document.getElementById("webdavUrl").value.trim(),
        username: document.getElementById("webdavUser").value.trim(),
        password: document.getElementById("webdavPass").value
      };
      const badge = document.getElementById("webdavStatusBadge");
      badge.textContent = "测试中...";
      const r = await fetch("/api/webdav/test", {
        method: "POST",
        headers: { "Content-Type": "application/json; charset=utf-8" },
        body: JSON.stringify(payload)
      });
      const res = await r.json();
      badge.textContent = res.ok ? "连接成功 ✓" : "连接失败";
      showToast(res.message || "完成");
    };

    // Settings
    document.querySelectorAll("#themeModeGroup .seg-btn").forEach((b) => {
      b.onclick = () => {
        applyTheme(b.dataset.theme);
        scheduleSaveState();
      };
    });
    document.getElementById("settingDefaultMode").onchange = (e) => {
      appState.readerDefaults.mode = e.target.value;
      scheduleSaveState();
    };
    document.getElementById("settingDefaultSpread").onchange = (e) => {
      appState.readerDefaults.spread = e.target.value;
      scheduleSaveState();
    };
    document.getElementById("settingShowPageNum").onchange = (e) => {
      appState.readerDefaults.showPageNumber = e.target.checked;
      scheduleSaveState();
    };
    document.getElementById("btnOpenSourceMgrFromSettings").onclick = openSourceManagerModal;
    document.getElementById("btnClearImgCache").onclick = async () => {
      const r = await fetch("/api/cache/clear", { method: "POST" });
      if (r.ok) {
        const d = await r.json();
        showToast("已清理 " + (d.clearedFiles || 0) + " 个缓存文件");
      }
    };

    // Modal
    document.getElementById("btnCloseModal").onclick = closeModal;
    document.getElementById("modalBackdrop").onclick = (e) => {
      if (e.target.id === "modalBackdrop") closeModal();
    };

    // Reader controls
    document.getElementById("btnReaderClose").onclick = () => {
      readerState.open = false;
      document.getElementById("readerOverlay").classList.add("hidden");
      document.getElementById("readerSettingsDrawer").classList.add("hidden");
    };
    document.getElementById("btnReaderFullscreen").onclick = async () => {
      await fetch("/api/window/fullscreen", { method: "POST" });
    };
    document.getElementById("btnSpreadPairShift").onclick = () => {
      readerState.spreadShift = readerState.spreadShift ? 0 : 1;
      renderReaderPages();
      showToast(readerState.spreadShift ? "跨页匹配：首页单页起" : "跨页匹配：默认双页起");
    };

    const vp = document.getElementById("readerViewport");
    vp.onclick = (e) => {
      const rect = vp.getBoundingClientRect();
      const relX = (e.clientX - rect.left) / rect.width;
      const prof = getActiveReaderProfile();
      if (relX > 0.3 && relX < 0.7) {
        readerState.chromeVisible = !readerState.chromeVisible;
        syncReaderSettingsUi();
      } else if (prof.mode !== "scroll") {
        const goNext = prof.mode === "rtl" ? (relX <= 0.3) : (relX >= 0.7);
        turnReaderPage(goNext ? 1 : -1);
      }
    };
    vp.ondblclick = () => {
      const prof = getActiveReaderProfile();
      if (!prof.doubleTapZoom) return;
      readerState.zoom = readerState.zoom > 1.05 ? 1 : 1.75;
      document.getElementById("readerStage").style.transform = "scale(" + readerState.zoom + ")";
    };

    document.getElementById("readerSeekBar").oninput = (e) => {
      const target = Math.max(0, Number(e.target.value) - 1);
      readerState.pageIndex = target;
      renderReaderPages();
    };
    document.getElementById("btnPrevChapter").onclick = () => {
      if (readerState.chapterIdx > 0) loadOnlineChapter(readerState.chapterIdx - 1, 0);
      else showToast("已是第一话");
    };
    document.getElementById("btnNextChapter").onclick = () => {
      if (readerState.chapterIdx + 1 < readerState.chapters.length) loadOnlineChapter(readerState.chapterIdx + 1, 0);
      else showToast("已是最新一话");
    };
    document.getElementById("btnReaderSettingsToggle").onclick = () => {
      document.getElementById("readerSettingsDrawer").classList.toggle("hidden");
    };
    document.getElementById("btnCloseReaderDrawer").onclick = () => {
      document.getElementById("readerSettingsDrawer").classList.add("hidden");
    };
    document.getElementById("btnReaderBookmark").onclick = () => {
      if (readerState.comic) toggleFavorite(readerState.comic);
    };
    document.getElementById("btnReaderChapters").onclick = () => {
      openModal("章节目录", (body) => {
        const grid = document.createElement("div");
        grid.className = "chapters-grid";
        readerState.chapters.forEach((ch, idx) => {
          const b = document.createElement("button");
          b.className = "chapter-btn";
          b.textContent = (idx === readerState.chapterIdx ? "▶ " : "") + ch.title;
          b.onclick = () => {
            closeModal();
            if (!readerState.isLocal) loadOnlineChapter(idx, 0);
          };
          grid.appendChild(b);
        });
        body.appendChild(grid);
      });
    };

    // Drawer setting controls
    document.getElementById("rngBrightness").oninput = (e) => {
      updateActiveReaderProfile({ brightness: Number(e.target.value) });
    };
    document.querySelectorAll("#drawerReadingModeGroup .drawer-opt-btn").forEach((b) => {
      b.onclick = () => updateActiveReaderProfile({ mode: b.dataset.mode });
    });
    document.querySelectorAll("#drawerQualityGroup .drawer-opt-btn").forEach((b) => {
      b.onclick = () => updateActiveReaderProfile({ quality: b.dataset.quality });
    });
    document.querySelectorAll("#drawerSpreadGroup .drawer-opt-btn").forEach((b) => {
      b.onclick = () => updateActiveReaderProfile({ spread: b.dataset.spread });
    });
    document.getElementById("chkApplyGlobalMode").onchange = (e) => {
      appState.readerDefaults.applyGlobal = e.target.checked;
      scheduleSaveState();
    };
    document.getElementById("chkTrimMargins").onchange = (e) => {
      updateActiveReaderProfile({ trimMargins: e.target.checked });
    };
    document.getElementById("chkDoubleTapZoom").onchange = (e) => {
      updateActiveReaderProfile({ doubleTapZoom: e.target.checked });
    };
    document.getElementById("chkShowCommentPage").onchange = (e) => {
      updateActiveReaderProfile({ showCommentPage: e.target.checked });
    };
    document.getElementById("chkShowPageNumber").onchange = (e) => {
      updateActiveReaderProfile({ showPageNumber: e.target.checked });
    };

    // Global keyboard shortcuts
    window.addEventListener("keydown", (e) => {
      if (!readerState.open) return;
      const prof = getActiveReaderProfile();
      if (e.key === "Escape") {
        const drw = document.getElementById("readerSettingsDrawer");
        if (!drw.classList.contains("hidden")) drw.classList.add("hidden");
        else document.getElementById("btnReaderClose").click();
      } else if (e.key === "F11") {
        e.preventDefault();
        fetch("/api/window/fullscreen", { method: "POST" });
      } else if (e.key === "ArrowLeft") {
        e.preventDefault();
        turnReaderPage(prof.mode === "rtl" ? 1 : -1);
      } else if (e.key === "ArrowRight" || e.key === " ") {
        e.preventDefault();
        turnReaderPage(prof.mode === "rtl" ? -1 : 1);
      }
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    wireEvents();
    loadInitialStateAndSources();
  });
})();
