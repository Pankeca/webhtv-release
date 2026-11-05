// WebHome 通用自适应扩展（universal-enhance.js）
// 随 App 内置，由 SiteAutoImporter 在「一键导入站点」时通过 local:// 引用挂载。
// 目标：拿到任意站点，零手工配置即可获得——
//   1) 资源链接拦截（磁力/网盘/电驴/迅雷/直链）→ 原生播放 / 网盘流程
//   2) 「App播放」按钮 + 剧集面板（fm.vodInline 按集解析）
//   3) 媒体嗅探（video / fetch / XHR / performance）→ 浮动「App播放」
//   4) 保守的干扰清理与 TV 焦点支持
// 站点专属微调：导入时写入 window.__fmAutoConfig（覆盖任意 CONFIG 字段），
// 二期 AI 精修也走同一挂载点，无需改动本脚本。
//
// 旧 WebView 安全：语法保持 ES2017 及以下。禁用可选链(?.)、空值合并(??)、
// 逻辑赋值、class 字段——一个 SyntaxError 会让整个扩展在电视盒上静默失效。
(function () {
  "use strict";

  // ---------- 配置合并（defaults <- profile <- __fmAutoConfig） ----------

  var USER = {};
  try {
    if (window.__fmAutoConfig && typeof window.__fmAutoConfig === "object") USER = window.__fmAutoConfig;
  } catch (e) {}

  var PROFILES = {
    // 苹果 CMS（maccms）系：/voddetail/ /vodplay/ /vodtype/，player_aaaa 配置
    maccms: {
      episodeSelector: "a[href*='/vodplay/'], .content_playlist a, .play-list a, ul.fed-list a",
      titleSelector: "h1, .content_detail h1, .stui-content__title, .fed-part-eone",
      posterSelector: ".detail-pic img, .stui-content__thumb img, .fed-list-picb img"
    },
    // 海螺 CMS 系：/detail/ /play/ /type/，module-play-list
    halo: {
      episodeSelector: ".module-play-list a, .module-list-play a, .module-player-list a, a[href*='/play/']",
      titleSelector: "h1, .module-info-heading h1, .video-title",
      posterSelector: ".module-item-pic img, .module-info-poster img, .video-pic img"
    },
    generic: {}
  };

  var DEFAULTS = {
    name: "fm-auto",
    host: "",
    siteName: "",
    profile: "",
    scanDelay: 140,
    sniffDelay: 700,
    buttonText: "App播放",
    floatText: "App播放",
    vodFrom: "WebHome",
    // 页面角色路由：自上而下首个命中生效
    routes: [
      { type: "play", test: "\\/(vodplay|play|player|watch|episode)[/.]" },
      { type: "detail", test: "\\/(voddetail|detail|info|movie|subject|video|anime|comic)[/.]" },
      { type: "list", test: "\\/(vodtype|type|vodsearch|search|list|category|show|vodlist)[/.]" },
      { type: "home", test: "^\\/(index\\.html?)?$" }
    ],
    // 资源拦截选择器：只拦“资源”，绝不拦普通站内导航
    resourceSelector: [
      "[data-url]",
      "[data-clipboard-text]",
      "a[href^='magnet:']",
      "a[href^='ed2k:']",
      "a[href^='thunder:']",
      "a[href^='jianpian:']",
      "a[href*='pan.quark.cn']",
      "a[href*='aliyundrive.com']",
      "a[href*='alipan.com']",
      "a[href*='pan.baidu.com']",
      "a[href*='drive.uc.cn']",
      "a[href*='pan.xunlei.com']",
      "a[href*='cloud.189.cn']",
      "a[href*='115.com']",
      "a[href*='115cdn.com']",
      "a[href*='yun.139.com']",
      "a[href*='caiyun.139.com']",
      "a[href*='123pan.']",
      "a[href*='123684.']",
      "a[href*='123912.']",
      "a[href*='.m3u8']",
      "a[href*='.mpd']",
      "a[href*='.mp4']",
      "a[href*='.mkv']",
      "a[href*='.flv']",
      "a[href*='.webm']"
    ],
    // 剧集候选选择器链（依次尝试，取第一个有结果的）
    episodeSelectors: [],
    episodeSelector: "",
    titleSelector: "h1, .title, .video-title, .detail-title, .vod-title, .content h1",
    posterSelector: ".poster img, .video-pic img, .vod-pic img, .detail-pic img",
    // 剧集“当前集”类名
    activeClass: "(^|[\\s])(active|on|current|selected|playing)([\\s]|$)",
    // 站内播放器配置变量（按集解析时从集页面提取媒体地址）
    playerConfigKeys: ["player_aaaa", "player_cccc", "player_dddd", "player_eee"],
    // 干扰清理（广告基建 + 常见广告位类名；业务弹窗不在默认列表，站点自定义走 hideSelectors 覆写）
    cleanAds: true,
    hideSelectors: [
      "ins.adsbygoogle",
      "iframe[src*='pos.baidu.com']",
      "iframe[src*='doubleclick.net']",
      "iframe[src*='googlesyndication']",
      "iframe[src*='cnzz.com']",
      "iframe[src*='umeng']",
      "iframe[src*='51.la']",
      ".ad", ".ads", ".adsbox", ".ad-banner", ".ad-box", ".ad-container", ".ad-wrapper", ".ad-item",
      ".gg", ".gg-box", ".gg-img", ".guanggao", ".guanggao-box",
      "[class*='guanggao']", "[id*='guanggao']",
      ".pop-ad", ".popup-ad", ".ad-popup", ".ad-pop", ".float-ad", ".float-banner", ".float-adv",
      ".marquee-ad", ".notice-ad", ".adsbox-wrap"
    ],
    // 弹窗拦截：拦空白/广告域 window.open，站内正常跳转放行
    blockPopups: true,
    adUrlPattern: "(pos\\.baidu\\.com|doubleclick|googlesyndication|cnzz\\.com|umeng|51\\.la|adsterra|popads|propellerads|tangrenshequ|/pop\\.php|/pop_|/ad_|\\bad[s]?\\.(?:com|cn|net|org)|/guanggao|/union/)",
    // 媒体嗅探开关
    sniff: true,
    // 注入按钮样式主题色
    theme: "#0f766e"
  };

  function isPlainObject(v) {
    return !!v && typeof v === "object" && Object.prototype.toString.call(v) === "[object Object]";
  }

  function mergeConfig(base, extra) {
    var out = {};
    var key;
    for (key in base) if (Object.prototype.hasOwnProperty.call(base, key)) out[key] = base[key];
    if (!isPlainObject(extra)) return out;
    for (key in extra) {
      if (!Object.prototype.hasOwnProperty.call(extra, key)) continue;
      if (key === "routes" && Object.prototype.toString.call(extra[key]) === "[object Array]") {
        var routes = [];
        for (var i = 0; i < extra[key].length; i++) {
          var r = extra[key][i] || {};
          routes.push({ type: String(r.type || "other"), test: r.test instanceof RegExp ? r.test : new RegExp(String(r.test || "(?!)"), "i") });
        }
        out.routes = routes;
      } else {
        out[key] = extra[key];
      }
    }
    return out;
  }

  var CONFIG = mergeConfig(DEFAULTS, USER);
  // profile 预设只补空缺字段，用户覆写优先
  (function applyProfile() {
    var preset = PROFILES[String(CONFIG.profile || "")] || PROFILES.generic;
    for (var key in preset) {
      if (!Object.prototype.hasOwnProperty.call(preset, key)) continue;
      var current = CONFIG[key];
      var empty = current == null || current === "" || (Object.prototype.toString.call(current) === "[object Array]" && current.length === 0);
      if (empty) CONFIG[key] = preset[key];
    }
  })();
  // 路由正则统一成 RegExp
  (function normalizeRoutes() {
    var routes = [];
    for (var i = 0; i < CONFIG.routes.length; i++) {
      var r = CONFIG.routes[i] || {};
      routes.push({ type: String(r.type || "other"), test: r.test instanceof RegExp ? r.test : new RegExp(String(r.test || "(?!)"), "i") });
    }
    CONFIG.routes = routes;
  })();
  // 资源选择器数组拼成 CSS 选择器列表
  (function normalizeSelector() {
    if (Object.prototype.toString.call(CONFIG.resourceSelector) === "[object Array]") {
      CONFIG.resourceSelector = CONFIG.resourceSelector.join(",");
    }
  })();
  if (!CONFIG.host) {
    try { CONFIG.host = location.host; } catch (e) {}
  }

  // ---------- 基础工具 ----------

  function log() {
    try {
      if (typeof GM_log === "function") { GM_log.apply(null, arguments); return; }
    } catch (e) {}
    try { console.log.apply(console, ["[" + CONFIG.name + "]"].concat(Array.prototype.slice.call(arguments))); } catch (e) {}
  }

  function toast(message) {
    try { if (window.fm && window.fm.ext && window.fm.ext.toast) window.fm.ext.toast(String(message)); } catch (e) {}
  }

  function whenFm() {
    if (window.fm) return Promise.resolve(window.fm);
    return new Promise(function (resolve) {
      window.addEventListener("fmsdk", function () { resolve(window.fm); }, { once: true });
    });
  }

  function ready(fn) {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fn, { once: true });
    else fn();
  }

  function isTv() {
    return !!(window.fongmiClient && window.fongmiClient.isLeanback);
  }

  function cleanText(value) {
    return String(value == null ? "" : value).replace(/\s+/g, " ").trim();
  }

  function absoluteUrl(value) {
    var text = cleanText(value);
    if (!text || text === "#" || /^javascript:/i.test(text) || /^mailto:/i.test(text)) return "";
    if (/^(magnet:|ed2k:|thunder:|jianpian:)/i.test(text)) return text;
    try {
      return new URL(text, location.href).href;
    } catch (e) {
      return text;
    }
  }

  function queryFirst(selectors) {
    if (!selectors) return null;
    var list = Object.prototype.toString.call(selectors) === "[object Array]" ? selectors : [selectors];
    for (var i = 0; i < list.length; i++) {
      var sel = list[i];
      if (!sel) continue;
      try {
        var el = document.querySelector(sel);
        if (el) return el;
      } catch (e) {}
    }
    return null;
  }

  function queryAll(selectors) {
    var out = [];
    if (!selectors) return out;
    var list = Object.prototype.toString.call(selectors) === "[object Array]" ? selectors : [selectors];
    for (var i = 0; i < list.length; i++) {
      var sel = list[i];
      if (!sel) continue;
      try {
        var found = document.querySelectorAll(sel);
        for (var j = 0; j < found.length; j++) out.push(found[j]);
      } catch (e) {}
    }
    return out;
  }

  // ---------- 资源分类 ----------

  function classify(url) {
    var u = String(url || "");
    if (/^magnet:/i.test(u)) return "magnet";
    if (/^ed2k:/i.test(u)) return "ed2k";
    if (/^thunder:/i.test(u)) return "thunder";
    if (/^jianpian:/i.test(u)) return "jianpian";
    if (/pan\.quark\.cn/i.test(u)) return "quark";
    if (/aliyundrive\.com|alipan\.com/i.test(u)) return "aliyun";
    if (/pan\.baidu\.com/i.test(u)) return "baidu";
    if (/drive\.uc\.cn/i.test(u)) return "uc";
    if (/pan\.xunlei\.com/i.test(u)) return "xunlei";
    if (/cloud\.189\.cn/i.test(u)) return "tianyi";
    if (/123pan\.|123684\.|123685\.|123912\.|123592\.|123865\./i.test(u)) return "123";
    if (/115\.com|115cdn\.com/i.test(u)) return "115";
    if (/yun\.139\.com|caiyun\.139\.com/i.test(u)) return "mobile";
    if (isMediaUrl(u)) return "media";
    return "http";
  }

  function isMediaUrl(url) {
    return /\.(m3u8|mpd|mp4|mkv|flv|mov|avi|webm|m4s)(\?|#|$)/i.test(String(url || ""));
  }

  function isHlsUrl(url) {
    return /\.m3u8(\?|#|$)/i.test(String(url || ""));
  }

  function isDashUrl(url) {
    return /\.mpd(\?|#|$)/i.test(String(url || ""));
  }

  function mediaPlayOptions(url) {
    if (isHlsUrl(url)) return { format: "application/x-mpegURL" };
    if (isDashUrl(url)) return { format: "application/dash+xml" };
    return { headers: { Referer: location.href }, credentials: "include" };
  }

  // ---------- 页面角色与标题 ----------

  function pageType() {
    var path = "";
    try { path = location.pathname; } catch (e) {}
    for (var i = 0; i < CONFIG.routes.length; i++) {
      if (CONFIG.routes[i].test.test(path)) return CONFIG.routes[i].type;
    }
    return guessTypeByDom();
  }

  function guessTypeByDom() {
    if (collectEpisodes().length > 0) return "detail";
    if (document.querySelector("video, .player, .artplayer, .dplayer, #player, iframe[src*='player']")) return "play";
    return "other";
  }

  function metaContent(name) {
    try {
      var el = document.querySelector('meta[property="' + name + '"],meta[name="' + name + '"]');
      return el ? cleanText(el.getAttribute("content")) : "";
    } catch (e) {
      return "";
    }
  }

  function pageTitle() {
    var el = queryFirst(CONFIG.titleSelector);
    var text = cleanText(el && el.textContent);
    if (text) return text;
    text = metaContent("og:title");
    if (text) return text;
    text = cleanText(document.title);
    if (text) {
      // 去掉站名后缀：「剧名 - 片库」「剧名_4K影视」等
      var cut = text.split(/\s*[-|_|—|·|:：]\s*/);
      if (cut.length > 1 && cut[0].length >= 2) return cleanText(cut[0]);
    }
    return text || location.href;
  }

  function posterUrl() {
    var el = queryFirst(CONFIG.posterSelector);
    if (el) {
      var src = el.currentSrc || el.src || el.getAttribute("data-src") || el.getAttribute("data-original");
      var abs = absoluteUrl(src);
      if (abs) return abs;
    }
    return metaContent("og:image");
  }

  // ---------- 资源点击拦截 ----------

  function findPassword(el) {
    try {
      var box = el.closest("li,.item,.res-item,.module-item,.download-item,dd,td,tr") || el.parentElement;
      var text = box ? cleanText(box.textContent) : "";
      var m = text.match(/提取码[:：\s]*([A-Za-z0-9]{4,8})/) || text.match(/密码[:：\s]*([A-Za-z0-9]{4,8})/) || text.match(/访问码[:：\s]*([A-Za-z0-9]{4,8})/);
      return m ? m[1] : "";
    } catch (e) {
      return "";
    }
  }

  function linkResourceUrl(el) {
    var raw = el.getAttribute("data-url") || el.getAttribute("data-clipboard-text") || el.getAttribute("href") || "";
    if (!raw) {
      var onclick = el.getAttribute("onclick") || "";
      var m = onclick.match(/https?:\/\/[^\s'"&]+/);
      if (m) raw = m[0];
    }
    return absoluteUrl(raw);
  }

  function routeResource(url, title, password, pic) {
    return whenFm().then(function (sdk) {
      var type = classify(url);
      log("route", type, title, url);
      if (type === "media") {
        return sdk.play(url, title, mediaPlayOptions(url));
      }
      var payload = { type: type, url: url, title: title };
      if (password) payload.password = password;
      if (pic) payload.pic = pic;
      return sdk.pan.play(payload);
    });
  }

  function onClick(event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var el = null;
    try { el = target.closest(CONFIG.resourceSelector); } catch (e) {}
    if (!el) return;
    var url = linkResourceUrl(el);
    if (!url) return;
    var type = classify(url);
    if (type === "http") return; // 普通网页链接不拦截
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    routeResource(url, pageTitle(), findPassword(el), posterUrl()).catch(function (error) {
      log("route error", (error && (error.stack || error.message)) || error);
      toast("调用原生播放失败");
    });
  }

  // ---------- 剧集收集 ----------

  function activeRe() {
    try { return new RegExp(CONFIG.activeClass, "i"); } catch (e) { return null; }
  }

  function episodeSelectors() {
    var list = [];
    if (CONFIG.episodeSelector) list.push(CONFIG.episodeSelector);
    if (CONFIG.episodeSelectors && CONFIG.episodeSelectors.length) {
      for (var i = 0; i < CONFIG.episodeSelectors.length; i++) list.push(CONFIG.episodeSelectors[i]);
    }
    // 通用候选：指向集页面的链接（覆盖 maccms /vodplay/、海螺 /play/、自定义 play.php 等）
    list.push("a[href*='/vodplay/']");
    list.push("a[href*='/play/']");
    list.push("a[href*='/play.']");
    list.push("a[href*='play.php']");
    list.push("a[href*='/player/']");
    list.push("a[href*='/watch/']");
    list.push("a[href*='/episode']");
    return list;
  }

  function looksLikeEpisodeName(text) {
    return /^(第\s*\d+\s*[集话期]|ep?\s*\d+|\d{1,4}(\.\d)?)$/i.test(text) || /^\d{1,4}$/.test(text);
  }

  function collectEpisodes() {
    var seen = {};
    var raw = [];
    var re = activeRe();
    var anchors = queryAll(episodeSelectors());
    for (var i = 0; i < anchors.length; i++) {
      var el = anchors[i];
      var href = el.getAttribute("href") || "";
      if (!href || /^javascript:/i.test(href) || href === "#") continue;
      var pageUrl = absoluteUrl(href);
      if (!pageUrl || seen[pageUrl]) continue;
      var name = cleanText(el.textContent) || cleanText(el.getAttribute("title"));
      if (!name || name.length > 24) continue;
      seen[pageUrl] = true;
      var holder = el.closest("li,.item,dd,span") || el;
      var active = false;
      if (re) {
        try { active = re.test(holder.className || "") || re.test(el.className || "") || re.test(holder.getAttribute("aria-current") || ""); } catch (e) {}
      }
      raw.push({
        name: name,
        url: pageUrl,
        pageUrl: pageUrl,
        resolve: true,
        active: active
      });
    }
    if (raw.length < 2) return clusterEpisodes(seen);
    // 第一遍：只留“集名形态”的链接（01 / 第N集 / EPxx…），滤掉「立即播放」这类卡片按钮
    var filtered = [];
    for (var j = 0; j < raw.length; j++) if (looksLikeEpisodeName(raw[j].name)) filtered.push(raw[j]);
    if (filtered.length >= 2) return filtered;
    // 第二遍：放宽到全部候选（站点集名不是数字形态时）
    return raw;
  }

  function clusterEpisodes(seen) {
    var out = [];
    try {
      var candidates = document.querySelectorAll("a[href]");
      var groups = {};
      for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        var href = el.getAttribute("href") || "";
        if (!href || /^javascript:/i.test(href) || href === "#") continue;
        var name = cleanText(el.textContent);
        if (!name || !looksLikeEpisodeName(name)) continue;
        var abs = absoluteUrl(href);
        if (!abs || seen[abs]) continue;
        var key = abs.replace(/\d+/g, "#").replace(/[?#].*$/, "");
        if (!groups[key]) groups[key] = [];
        groups[key].push({ el: el, url: abs, name: name });
      }
      var best = [];
      for (var key in groups) {
        if (Object.prototype.hasOwnProperty.call(groups, key) && groups[key].length > best.length) best = groups[key];
      }
      if (best.length >= 2) {
        var re = activeRe();
        for (var j = 0; j < best.length; j++) {
          var item = best[j];
          var holder = item.el.closest("li,.item,dd,span") || item.el;
          var active = false;
          if (re) {
            try { active = re.test(holder.className || "") || re.test(item.el.className || ""); } catch (e) {}
          }
          out.push({ name: item.name, url: item.url, pageUrl: item.url, resolve: true, active: active });
        }
      }
    } catch (e) {}
    return out;
  }

  // ---------- 按集解析（resolver） ----------

  function unescapeJsUrl(text) {
    return String(text || "")
      .replace(/\\\//g, "/")
      .replace(/\\u002[fF]/g, "/")
      .replace(/\\u0026/g, "&")
      .replace(/&amp;/g, "&");
  }

  function decodePlayerUrl(raw, encrypt) {
    var s = cleanText(raw);
    if (!s) return "";
    if (/^(https?:)?\/\//i.test(s) || isMediaUrl(s)) return s;
    var attempts = [];
    attempts.push(s);
    try { attempts.push(atob(s)); } catch (e) {}
    try { attempts.push(atob(cleanText(s).split("").reverse().join(""))); } catch (e) {}
    if (String(encrypt) === "2") {
      try { attempts.push(atob(cleanText(s).split("").reverse().join(""))); } catch (e) {}
    }
    for (var i = 0; i < attempts.length; i++) {
      var value = unescapeJsUrl(attempts[i]);
      if (/^(https?:)?\/\//i.test(value) || isMediaUrl(value)) return value;
    }
    return "";
  }

  function extractPlayerConfig(html) {
    var keys = CONFIG.playerConfigKeys || [];
    for (var k = 0; k < keys.length; k++) {
      var key = keys[k];
      var re = new RegExp(key + "\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;", "m");
      var m = String(html || "").match(re);
      if (!m) continue;
      try {
        var jsonText = m[1].replace(/'/g, '"');
        var obj = JSON.parse(jsonText);
        var url = decodePlayerUrl(obj.url || obj.url_next || "", obj.encrypt);
        if (url) return url;
      } catch (e) {}
    }
    return "";
  }

  function extractMediaFromHtml(html) {
    var text = unescapeJsUrl(html);
    var configUrl = extractPlayerConfig(text);
    if (configUrl) return configUrl;
    // video / source 标签
    var tagMatch = text.match(/<(?:video|source)[^>]*src\s*=\s*["']([^"']+)["']/i);
    if (tagMatch) {
      var abs = absoluteUrl(tagMatch[1]);
      if (abs && isMediaUrl(abs)) return abs;
    }
    // 通用媒体地址
    var re = /(https?:\\?\/\\?\/[^"'\s<>]+?\.(?:m3u8|mpd|mp4|flv|mkv|webm|mov|avi|m4s)(?:[^"'\s<>]*))/gi;
    var match;
    while ((match = re.exec(text)) !== null) {
      var url = unescapeJsUrl(match[1]);
      if (url.indexOf("http") === 0) return url;
    }
    return "";
  }

  function installResolver() {
    window.__fmWebHomeInlineResolver = function (episode) {
      var pageUrl = (episode && (episode.pageUrl || episode.url)) || "";
      log("resolve", pageUrl);
      return whenFm().then(function (sdk) {
        return sdk.req(pageUrl, {
          headers: { Referer: location.href },
          credentials: "include",
          timeout: 15
        });
      }).then(function (response) {
        if (!response || response.ok === false) throw new Error("HTTP " + (response && response.status));
        var body = response.body != null ? response.body : "";
        var url = extractMediaFromHtml(String(body));
        if (!url) throw new Error("media url not found");
        url = absoluteUrl(url);
        return {
          url: url,
          format: isHlsUrl(url) ? "application/x-mpegURL" : isDashUrl(url) ? "application/dash+xml" : undefined,
          headers: { Referer: pageUrl || location.href },
          credentials: "include"
        };
      });
    };
  }

  // ---------- 媒体嗅探 ----------

  var state = {
    scanTimer: 0,
    sniffTimer: 0,
    sniffed: {},
    episodes: [],
    lastPath: "",
    button: null,
    floatButton: null,
    safeChromeMode: "",
    safeSystemBarsHidden: false
  };

  function rememberMedia(url) {
    if (!url || !isMediaUrl(url)) return;
    try {
      if (state.sniffed[url]) return;
    } catch (e) {}
    state.sniffed[url] = Date.now();
    scheduleScan();
  }

  function installSniffHooks() {
    if (!CONFIG.sniff) return;
    // fetch
    try {
      var nativeFetch = window.fetch;
      if (nativeFetch) {
        window.fetch = function (input, init) {
          try {
            var url = typeof input === "string" ? input : (input && input.url) || "";
            rememberMedia(absoluteUrl(url));
          } catch (e) {}
          return nativeFetch.apply(this, arguments);
        };
      }
    } catch (e) {}
    // XHR
    try {
      var open = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (method, url) {
        try { rememberMedia(absoluteUrl(url)); } catch (e) {}
        return open.apply(this, arguments);
      };
    } catch (e) {}
  }

  function scanMediaElements() {
    try {
      var nodes = document.querySelectorAll("video,audio,source");
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        rememberMedia(absoluteUrl(el.currentSrc || el.src || el.getAttribute("src") || el.getAttribute("data-src")));
      }
    } catch (e) {}
    try {
      if (window.performance && performance.getEntriesByType) {
        var entries = performance.getEntriesByType("resource");
        for (var j = 0; j < entries.length; j++) rememberMedia(absoluteUrl(entries[j].name));
      }
    } catch (e) {}
  }

  function bestSniffedUrl() {
    var best = "";
    var bestTime = 0;
    for (var url in state.sniffed) {
      if (!Object.prototype.hasOwnProperty.call(state.sniffed, url)) continue;
      if (isHlsUrl(url)) return url; // HLS 优先
      if (state.sniffed[url] > bestTime) {
        bestTime = state.sniffed[url];
        best = url;
      }
    }
    return best;
  }

  // ---------- 注入：App播放按钮 ----------

  function styleInjected() {
    var theme = CONFIG.theme || "#0f766e";
    var css = ""
      + ".fm-auto-play-btn{display:inline-block;margin:8px 0;padding:8px 16px;"
      + "border:1px solid " + theme + ";border-radius:8px;background:" + theme + ";color:#fff;"
      + "font-size:14px;font-weight:700;cursor:pointer;z-index:2147483000;}"
      + ".fm-auto-play-btn:focus{outline:2px solid rgba(255,255,255,.85);outline-offset:1px;}"
      + ".fm-auto-float-btn{position:fixed;right:12px;bottom:calc(16px + max(var(--fm-safe-bottom,0px),env(safe-area-inset-bottom,0px)));"
      + "padding:10px 16px;border:none;border-radius:20px;background:" + theme + ";color:#fff;"
      + "font-size:14px;font-weight:700;box-shadow:0 4px 12px rgba(0,0,0,.35);z-index:2147483000;cursor:pointer;}"
      + ".fm-auto-float-btn:focus{outline:2px solid rgba(255,255,255,.85);outline-offset:1px;}";
    try {
      if (typeof GM_addStyle === "function") GM_addStyle(css);
      else {
        var s = document.createElement("style");
        s.textContent = css;
        (document.head || document.documentElement).appendChild(s);
      }
    } catch (e) {}
  }

  function playViaInline() {
    var episodes = state.episodes.length ? state.episodes : collectEpisodes();
    if (!episodes.length) return Promise.reject(new Error("no episodes"));
    state.episodes = episodes;
    installResolver();
    var active = null;
    for (var i = 0; i < episodes.length; i++) if (episodes[i].active) { active = episodes[i]; break; }
    log("vodInline", episodes.length, "episodes mark=", active && active.name);
    return whenFm().then(function (sdk) {
      return sdk.vodInline({
        vod_id: location.pathname,
        vod_name: pageTitle(),
        vod_pic: posterUrl(),
        vod_play_from: CONFIG.vodFrom,
        mark: active ? active.name : "",
        headers: { Referer: location.href },
        credentials: "include",
        episodes: episodes
      });
    });
  }

  function playSniffed() {
    var url = bestSniffedUrl();
    if (!url) return Promise.reject(new Error("no media"));
    log("sniff play", url);
    return whenFm().then(function (sdk) {
      var options = mediaPlayOptions(url);
      var pic = posterUrl();
      if (pic) options.pic = pic;
      return sdk.play(url, pageTitle(), options);
    });
  }

  function injectPlayButton() {
    var episodes = collectEpisodes();
    state.episodes = episodes;
    if (episodes.length >= 1) {
      if (document.querySelector(".fm-auto-play-btn")) return;
      // 挂在剧集列表前/后
      var anchor = queryFirst(episodeSelectors());
      var host = null;
      if (anchor) host = anchor.closest("ul,ol,.play-list,.episode-list,.content_playlist,.module-play-list,.module-list-play") || anchor.parentElement;
      if (!host || !host.parentElement) return;
      var button = document.createElement("button");
      button.type = "button";
      button.className = "fm-auto-play-btn";
      button.setAttribute("data-fm-auto", "play");
      button.textContent = CONFIG.buttonText;
      button.addEventListener("click", function (event) {
        event.preventDefault();
        event.stopPropagation();
        playViaInline().catch(function (error) {
          log("vodInline error", (error && (error.stack || error.message)) || error);
          toast("App播放失败");
        });
      });
      host.parentElement.insertBefore(button, host);
      return;
    }
  }

  function injectFloatButton() {
    if (!CONFIG.sniff) return;
    var url = bestSniffedUrl();
    var show = !!url;
    if (show && !state.floatButton) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "fm-auto-float-btn";
      button.setAttribute("data-fm-auto", "float");
      button.textContent = CONFIG.floatText;
      button.addEventListener("click", function (event) {
        event.preventDefault();
        event.stopPropagation();
        playSniffed().catch(function (error) {
          log("sniff play error", (error && (error.stack || error.message)) || error);
          toast("App播放失败");
        });
      });
      (document.body || document.documentElement).appendChild(button);
      state.floatButton = button;
    } else if (show && state.floatButton) {
      if (state.floatButton.parentElement) state.floatButton.style.display = "";
    } else if (!show && state.floatButton) {
      state.floatButton.style.display = "none";
    }
  }

  // ---------- 清理与弹窗拦截 ----------

  function cleanup() {
    if (!CONFIG.cleanAds) return;
    var selectors = CONFIG.hideSelectors || [];
    for (var i = 0; i < selectors.length; i++) {
      try {
        var nodes = document.querySelectorAll(selectors[i]);
        for (var j = 0; j < nodes.length; j++) {
          var el = nodes[j];
          if (el.getAttribute("data-fm-hidden") === "1") continue;
          el.setAttribute("data-fm-hidden", "1");
        }
      } catch (e) {}
    }
    try {
      if (typeof GM_addStyle === "function") GM_addStyle("[data-fm-hidden='1']{display:none!important;}");
    } catch (e) {}
  }

  function adUrlRe() {
    try { return new RegExp(CONFIG.adUrlPattern, "i"); } catch (e) { return null; }
  }

  /** window.open 拦截：空白/广告域弹窗吃掉，站内正常新窗口放行。 */
  function installPopupGuard() {
    if (!CONFIG.blockPopups) return;
    try {
      var nativeOpen = window.open;
      if (typeof nativeOpen !== "function") return;
      window.open = function (url) {
        var target = "";
        try { target = String(url == null ? "" : url); } catch (e) {}
        var absolute = absoluteUrl(target);
        var re = adUrlRe();
        var isBlank = !target || target === "about:blank" || target === "javascript:;";
        var isAd = !!re && !!absolute && re.test(absolute);
        if (isBlank || isAd) {
          log("popup blocked", target || "(blank)");
          return null;
        }
        return nativeOpen.apply(window, arguments);
      };
    } catch (e) {}
  }

  // ---------- TV 焦点 ----------

  function markTvFocus() {
    if (!isTv()) return;
    var selector = "a[href],button,.card,.item,.vod-item,.module-item";
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.getAttribute("data-fm-focus") === "1") continue;
      el.setAttribute("data-fm-focus", "1");
      if (el.tagName !== "A" && el.tagName !== "BUTTON" && !el.hasAttribute("tabindex")) el.setAttribute("tabindex", "0");
    }
    try {
      if (typeof GM_addStyle === "function") {
        GM_addStyle("a:focus,button:focus,[data-fm-focus='1']:focus{outline:2px solid rgba(255,255,255,.85);outline-offset:1px;border-radius:6px;}");
      }
    } catch (e) {}
  }

  // ---------- 安全区（手机） ----------

  function readCssPixels(name) {
    if (!document.documentElement || !window.getComputedStyle) return 0;
    var value = "";
    try { value = getComputedStyle(document.documentElement).getPropertyValue(name); } catch (e) {}
    var number = parseFloat(value);
    return isNaN(number) ? 0 : number;
  }

  function updateSafeAreaMode(detail) {
    detail = detail || {};
    if (detail.chromeMode) state.safeChromeMode = cleanText(detail.chromeMode).toLowerCase();
    if (typeof detail.systemBarsHidden !== "undefined") state.safeSystemBarsHidden = !!detail.systemBarsHidden;
    if (!document.documentElement) return;
    // 仅 edge/immersive 且有安全区时加顶距
    var mode = state.safeChromeMode;
    var safeTop = Number(detail.safeTop);
    if (isNaN(safeTop)) safeTop = readCssPixels("--fm-safe-top");
    var active = !isTv() && (mode === "edge" || mode === "immersive") && safeTop > 0;
    document.documentElement.classList.toggle("fm-auto-safe-top", active);
    if (active) {
      try {
        if (typeof GM_addStyle === "function") GM_addStyle("html.fm-auto-safe-top body{padding-top:var(--fm-safe-top,0px)!important;}");
      } catch (e) {}
    }
  }

  // ---------- 扫描主循环 ----------

  function enhance() {
    if (!document.body) return;
    try {
      document.body.classList.add("fm-auto-enhanced");
      document.body.classList.toggle("fm-auto-tv", isTv());
      document.body.classList.toggle("fm-auto-mobile", !isTv());
    } catch (e) {}
    cleanup();
    injectPlayButton();
    scanMediaElements();
    injectFloatButton();
    markTvFocus();
  }

  function scheduleScan() {
    clearTimeout(state.scanTimer);
    state.scanTimer = setTimeout(enhance, CONFIG.scanDelay);
  }

  // ---------- 启动 ----------

  styleInjected();
  installSniffHooks();
  installPopupGuard();
  ready(function () {
    document.addEventListener("click", onClick, true);
    try {
      new MutationObserver(scheduleScan).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    enhance();
  });
  window.addEventListener("fmurlchange", scheduleScan);
  window.addEventListener("popstate", scheduleScan);
  window.addEventListener("fmresume", scheduleScan);
  window.addEventListener("fmviewport", function (event) {
    updateSafeAreaMode(event && event.detail ? event.detail : {});
  });
  whenFm().then(function (sdk) {
    if (sdk.ui && sdk.ui.getViewport) {
      return sdk.ui.getViewport().then(function (viewport) { updateSafeAreaMode(viewport || {}); });
    }
  }).catch(function () {});
  log("ready", CONFIG.host || location.host, "profile=", CONFIG.profile || "generic");
})();
