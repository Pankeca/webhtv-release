# tangtv 功能移植可行性评估与开发方案

> 评估对象：https://github.com/alantang1977/tangtv
> 目标工程：WebHomeTV 5.6.4（`/media/s/7A3A1DFD3A1DB6DB/REA`）
> 结论摘要：**5 项需求里 1 项无需开发（社区资源）、2 项可低成本落地、去广比预期好搬、TMDB 比预期难搬。**

| 需求 | 判决 | 一句话 |
|---|---|---|
| ① TMDB | **中（偏难）** | `TmdbDetailActivity` 单文件 13,268 行，不能整体搬；只能借它的 Service（1,210 行）+ 自写界面 |
| ② 搜索线程 | **高** | 3 个文件，改一处线程池 |
| ② 触屏优化 | **⚠️ 空转** | 硬门禁 `Util.isLeanback()`，手机上无效；要么照搬给 TV、要么我们重写成手机版 |
| ③ 短剧源配置 | **高** | 站点白名单，1 个 bean + 1 个弹窗；连播队列状态机也是现成的 |
| ④ 去广告 | **高（HLS 部分）** | 核心 **16 文件 / 2,196 行**，可直接移植；音频指纹 44 类建议放弃 |
| ⑤ 社区资源 | **零开发** | 你项目已有「站点注入」「WebHome 扩展」，今天就能用 |


---

## 0. 先说三个前提性发现

### 0.1 tangtv 和你现在的项目是「同族兄弟」，不是「另一个 app」

| 项 | 你的项目 | tangtv |
|---|---|---|
| 上游 | FongMi/TV → WebHTV（fish2018/webhtv） | FongMi/TV → WebHTV |
| applicationId | `com.fongmi.android.tv` | `com.silent.android.tangtv`（换皮） |
| versionName | 5.6.4 | 5.6.0 |
| 模块 | app / catvod / chaquo / quickjs | 同上 + nodejs |
| README | WebHomeTV 编译说明 | WebHomeTV README（同一份） |
| 关键文件对比 | `server/process/` 17 个类 | `server/process/` 20 个类（多出 CatMessage/CatWebview/SpiderApi） |

也就是说：**同源、同架构、同目录习惯**。移植不是"往陌生项目里塞东西"，而是"把兄弟分支上的增量搬过来"。这让大部分工作的风险从「架构不适配」降到「版本差异冲突」。

### 0.2 但两边核心文件已经分叉得很厉害

| 文件 | 你的项目 | tangtv | 差异 |
|---|---|---|---|
| mobile `VideoActivity.java` | 7,489 行 | 11,258 行 | **+3,769** |
| leanback `VideoActivity.java` | 6,419 行 | 11,798 行 | **+5,379** |
| `Setting.java` | 有 | 有，键位多 30+ 个（TMDB / 短剧 / 去广） | — |
| 详情页体系 | 详情页 + 内联播放器（`VideoActivity` 一体） | 重构出 `ui/detail/`、`ui/host/` 包 + 5 种详情打开模式 | 结构性分叉 |
| `TmdbDetailActivity.java` | 无 | **13,268 行**（比你的整个 VideoActivity 还大） | — |
| java 文件总数 | 1,277 | 2,273 | +996 |

`VideoActivity` 三千到五千行的差异意味着：**任何从 tangtv 的 VideoActivity 里"复制一段方法"的做法都会踩空**——它的方法体依赖一堆你项目里不存在的字段与状态机。这条约束决定了下面所有方案的取舍。


### 0.3 你的项目没有 Git

`git status` 报 "不是 git 仓库"。这在移植场景下是**最大的风险点**——没法回滚、没法 diff、没法分批提交。

> **建议先做这一步（不算开发，算施工准备）：`git init` + 首次全量提交 + 打 tag `baseline-5.6.4`。** 否则后面任何一次移植出问题都只能靠手工还原。

---

## 1. TMDB 功能

### 1.1 tangtv 里它是什么

不是"接个 API 显示评分"，而是一整套**详情页替代方案**：

```
Setting.java（30+ 个键）
├─ DETAIL_OPEN_ORIGINAL_ENHANCED / FUSION / ENHANCED / PLAYER / DIRECT   ← 5 种详情打开模式
├─ DETAIL_STYLE_NATIVE / PROFILE / CINEMA                                ← 3 种详情样式
├─ TMDB_MATCH_STRICT / SMART / STRICT_DIALOG / SMART_DIALOG              ← 4 种匹配策略
└─ tmdb_config（JSON：API key + 启用站点白名单）

bean/TmdbConfig、TmdbSeasonProgress
api/TmdbSourceCredentialIngress
service/TmdbService（全部接口调用 + SafeJsonParser 兜底）
setting/TmdbSitePolicy、TmdbSourceState
ui/activity/TmdbDetailActivity、TmdbPersonActivity
ui/dialog/TmdbSourceDialog
SettingTmdbFragment（mobile）/ SettingTmdbActivity（leanback）
+ activity_tmdb_person.xml、tmdb 详情/剧集/演员 adapter 与布局
```

它解决的问题是：CSP 站点返回的详情页信息太烂（没海报、没简介、没评分、剧集名混乱），所以把 TMDB 的元数据刮下来做一层"美化后的详情页"，再让用户点播放时回到原站点取播放地址。

### 1.2 移植到你项目的真实难点

先看规模（已逐文件统计，不含测试）：

| 项 | 量 |
|---|---|
| 涉及 TMDB 的 java 文件 | **208 个 / 93,930 行** |
| `ui/activity/TmdbDetailActivity.java` | **13,268 行** |
| `ui/helper/TmdbUIAdapter.java` | 2,666 行 |
| `ui/custom/TmdbHeaderView.java` | 2,529 行 |
| `service/TmdbService.java` | 1,210 行 |

**单是 `TmdbDetailActivity` 一个文件，就比你项目整个 `VideoActivity`（7,489 行）还大。整体搬运不现实。**

| 难点 | 说明 | 严重度 |
|---|---|---|
| 详情页体积 | 13k 行的 Activity + 2.6k 行 UIAdapter + 2.5k 行 HeaderView，且高度依赖它重构过的 `ui/detail` 体系 | 极高 |
| 详情页架构不同 | 它把详情从 VideoActivity 拆出去；你项目还是"详情+内联播放器"一体。`DETAIL_OPEN_PLAYER`（在播放器里弹详情）在你项目里没有对应位置 | 高 |
| `Setting.getDetailOpenMode()` 迁移链 | 它会读旧键 `search_detail_page` / `detail_interaction_mode` 做迁移，你项目里没有这些键，整段迁移逻辑要删 | 中 |
| `VideoActivity` 耦合 | FUSION / PLAYER 模式要改 VideoActivity 的布局与生命周期 | 高 |
| TMDB 网络可达性 | `api.themoviedb.org` 国内不稳定，需要可配置 API 镜像域名 + 图片 CDN 域名，否则详情页大面积空白 | 中 |
| 图片加载 | 依赖 Glide + TMDB 图片尺寸拼接规则；你项目已有 Glide，OK | 低 |
| 语言/地区 | TMDB 有 `zh-CN` 语言参数与分级（certification）按地区取，需保留 | 低 |

### 1.3 建议方案：借它的「服务层」，不借它的「界面层」

**结论：不要移植 `TmdbDetailActivity`（13,268 行）。** 只移植 Service + 配置，界面自己写。

**移植（约 1.5k 行，干净可搬）**：
- `service/TmdbService.java`（1,210 行）+ `utils/SafeJsonParser`（错误兜底：数据异常时降级显示而不是崩溃，这块逻辑价值最高）
- `bean/TmdbConfig`（API key、语言、启用站点白名单）、`bean/TmdbItem`、`TmdbSeasonProgress`
- `ui/helper/TmdbMatcher`（站点条目 ↔ TMDB 条目匹配）
- `ui/dialog/TmdbSourceDialog` + `SettingTmdbFragment`（配置入口）

**自己写（约 1–1.5k 行）**：
- 一个精简的 TMDB 详情页：海报 + 背景 + 简介 + 评分 + 分季选集 + 演员横滑
- 点播放时用站点 key + vod_id 回到你现有的 `VideoActivity` 播放链路

**砍掉**：
- `FUSION` / `CINEMA` / `PLAYER` 三种详情模式（都要动 VideoActivity 或详情架构）
- 4 种匹配策略简化成 **严格 / 智能** 两档
- `TmdbHeaderView`（2,529 行的沉浸式头图）、`TmdbUIAdapter`（2,666 行）——用你自己的布局体系重写

**接入点**（你的项目里）：
1. `SiteViewModel` / 详情跳转处判断 `tmdb_detail_enabled && TmdbSitePolicy.isSiteEnabled(site)` → 起新的 TMDB 详情页，否则走原逻辑。
2. 设置入口挂在「设置 → 增强功能」附近，照 `SettingEnhanceFragment` 的组织方式加一个 `SettingTmdbFragment`。

**工作量**：新增约 12–16 个文件。**可行性：中**（可做，但必须接受"自写界面"而非"整体搬运"）。


### 1.4 风险
- TMDB 是境外服务，**不配镜像域名在国内基本不可用**。方案里必须带上"自定义 API 域名 / 图片域名"两个输入框，默认值给官方地址。
- 它是"锦上添花"型功能：站点数据本身差的时候体验提升明显，站点数据好时反而多一跳。

---

## 2. 个性设置：搜索线程 + 触屏优化

### 2.1 tangtv 的原样实现（已核实源码）

```java
// Setting.java
public static int getSearchThread() {
    return clampSearchThread(Prefers.getInt("search_thread", 20));   // 默认 20，范围 1–100
}

public static boolean isTouchOptimized() {
    return Util.isLeanback() && Prefers.getBoolean("touch_optimized");  // ⚠️ 仅 TV 版生效
}
```

UI 是 `fragment_setting_personal.xml` 里两行：`searchThread` 用 `SliderNumberDialog`（1–100 滑杆），`touchOptimization` 是一个布尔开关。

### 2.2 搜索线程：可行性 = 高，几乎是白捡

你项目现在是这样：

```java
// utils/Task.java
private static final ListeningExecutorService largeExecutor =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(20));  // 写死 20
```

而搜索走的是 `Task.largeExecutor()`（`SiteViewModel` 里 `SearchTask` + `withTimeout(TIMEOUT_SEARCH)`）。

**改造点（3 个文件）**：
1. `Setting.java` 加 `getSearchThread()/putSearchThread()`，默认 20、clamp 1–100。
2. 新建 `SearchExecutor`：持有一个**可重建**的固定线程池，`Setting.putSearchThread` 时销毁重建（或用一个信号量限制并发，避免销毁正在跑的任务）。建议用"并发闸门"而不是重建线程池——搜索中途改设置不会打断进行中的请求。
3. `SiteViewModel` 把 `Task.largeExecutor()` 换成 `SearchExecutor.get()`；设置页加一行滑杆。

**副作用提示**：线程数调到 100 会让弱机/弱网下大量站点同时超时，反而更慢。建议 UI 上给出「推荐 8–20」的提示文案。

### 2.3 触屏优化：它到底做什么，以及为什么照搬过来在你手机上是空转

**真实实现**：`ui/helper/TouchOptimizationHelper.java`，**253 行**，做的事是：

```java
public static void sync(View root) {
    if (!Util.isLeanback()) return;          // ← 硬门禁：非 TV 版直接返回
    if (Setting.isTouchOptimized()) optimize(root);
    else restore(root);
}

private static void traverse(View view, boolean optimize) {
    if (view instanceof RecyclerView recycler) {
        if (optimize) attachListener(recycler);   // 接管触摸，记录 touchActive / touchSettling
        else restoreGrid(recycler);
    }
    ...
}
```

具体行为三条：
1. 遍历 View 树，把 `RecyclerView` / `BaseGridView` 的触摸状态接管下来（`touchActive` / `touchSettling`），并用 `isTouchActive(view)` 对外暴露。
2. `VideoActivity` 的按键处理里插一句 `if (!Setting.isTouchOptimized()) return mKeyDown.onTouchEvent(event);` —— 即：**开启后，遥控器的方向键事件改走"触摸事件"路径**，避免 TV 焦点导航和触摸滚动互相打架。
3. 对话框弹窗也会 `sync(decorView)`，保证弹窗里的列表同样生效。

**所以它本质是「TV 盒子上遥控器 + 触摸屏混用时的焦点/滚动冲突修复」**，不是"给手机加触控热区"。`isTouchOptimized()` 里那个 `Util.isLeanback() &&` 就是明证。

**结论**：你的平板装的是 `mobile-arm64_v8a`，**照搬过来点开关不会有任何变化**。

**建议**（二选一，需要你拍板）：

| 方案 | 内容 | 工作量 |
|---|---|---|
| A. 照搬语义 | 把 `TouchOptimizationHelper` 原样搬过来，只在 leanback 生效。你平板用不上，等于给 TV 版补功能 | 小（1 个 helper + 2 处调用 + 设置行） |
| B. 重新定义为手机版 | 我们自己写：① 播放器手势阈值放宽（单击/双击判定间隔、水平滑动起始位移）；② 列表/按钮最小触控尺寸提到 48dp；③ `CustomRecyclerView` 的 fling 加速度降低 | 中（要动 `VideoActivity` 手势判定 + 布局 dp + RecyclerView 触摸处理，且需你真机反复试手感） |

> A 和 B 不冲突，也可以都做（A 给 TV 版，B 给手机版，同一个开关按 flavor 分流）。


---

## 3. 短剧源配置

### 3.1 tangtv 里它是什么（已核实源码）

不是"新增一种站点类型"，而是一个**站点白名单筛选器**：

```java
// bean/ShortDramaConfig.java
private static final List<String> DEFAULT_ENABLED_RULES = List.of("[短]", "短剧");

public boolean isSiteEnabled(String key, String name) {
    if (isBlacklisted(key)) return false;
    List<String> rules = !enabledSites.isEmpty() ? enabledSites : DEFAULT_ENABLED_RULES;
    return matches(rules, key) || matches(rules, name);   // contains 匹配，大小写不敏感
}
```

配套：`Setting.getShortDramaConfig()/putShortDramaConfig()`、`isShortDramaSiteEnabled(key,name)`，设置页一行「短剧源配置」，界面是 `ui/dialog/ShortDramaSourceDialog`（站点多选 + 手动填关键词 + 黑名单）。

**它只负责"把哪些站点归到短剧"，不负责短剧的播放形态。** 播放形态由另外两块组成：

| 文件 | 行数 | 作用 |
|---|---|---|
| `ui/player/ShortDramaQueueCoordinator.java` | 约 200 | **短剧连播队列状态机**：纯 Java、无 Android 依赖，管"当前集 + 至多一个预解析的下一集"，用 `generation` 做代际失效（切集/换源时不污染新会话）。这是抖音式连播最核心的那块逻辑，而且是干净可复用的 |
| `service/PersonalRecommendationService.java` | — | 个性化推荐：综合 `History`、`ShortDramaConfig`、`TmdbConfig`、`AudioConfig` 产出推荐流 |
| `setting/Setting.java` | — | 短剧相关键 |
| `SHORT_DRAMA_SPEED_FIX.md` | — | 短剧模式倍速保持（切集不重置、不同剧互不污染），改 `VideoActivity.onPrepare()` 补 `setSpeed()` |

### 3.2 可行性 = 高（配置部分）/ 中（倍速修复）/ 高且值得单独排期（抖音式信息流）

**阶段 A：短剧源配置（小）**
- 移植 `bean/ShortDramaConfig.java`（只依赖 `VodConfig.get().getSites()` 和 `Site`，你项目都有）
- `Setting` 加 3 个方法
- 新建 `ShortDramaSourceDialog`：站点多选 + 关键词输入 + 黑名单
- 首页/分类页加"短剧"入口，用 `isShortDramaSiteEnabled` 过滤站点

**阶段 B：短剧倍速保持（小）**
- `VideoActivity.onPrepare()` 补 `setSpeed()`，从 `mHistory.getSpeed()` 恢复
- 修掉「调速污染全局默认速度」：现在每次调速都写 `PlayerSetting.putDefaultSpeed`，导致换剧继承上一部剧的倍速
- 你项目的 `History` 已有 `speed` 字段（`mHistory.setSpeed` 在用），只要补"读回来"

**阶段 C：抖音式纵向信息流（大，独立立项）**

tangtv **没有**现成的纵向信息流 UI（`ui/` 下只有 `novel` 小说、`audio` 音频两个新内容形态，没有 `shortdrama` 包）。但它把最难的部分做好了：

| 已有，可直接复用 | 需要新建 |
|---|---|
| `ShortDramaQueueCoordinator`（连播队列 + 代际失效，纯 Java 可单测） | 纵向 `ViewPager2` 单卡播放容器 |
| 短剧源筛选（阶段 A） | 上下滑切集 + 下一集预加载 |
| `PersonalRecommendationService`（推荐排序） | 单卡内的详情/选集/点赞/收藏浮层 |
| `PlayerManager` + Exo + `History` 续播 | "刷到哪了"的独立进度存储 |

**建议**：阶段 A + B 并入本次；阶段 C 单独开一轮——它本质是一个新页面形态，和"移植 tangtv"关系不大，只是借了它的队列状态机。


---

## 4. 「所有的去广告功能」

这是四项里**水分最大**的一项，必须先拆清楚。tangtv 里其实有三套东西。

### 4.1 三套机制的真面目（已逐文件核实）

| 机制 | 代码位置 | 规模 | 你项目现状 |
|---|---|---|---|
| ① HLS manifest 结构化净化 | `utils/HlsManifestCleaner`(343) + `utils/HlsAdblockPipeline`(109) + `utils/HlsAdblockNotice`(23) + `bean/HlsAdRule`(104) + `bean/HlsRulePackage`(33) + `bean/HlsRuleState`(33) + `api/config/HlsRuleConfig`(128) + `HlsRuleStateStore`(42) + `BuiltinHlsRuleLoader`(32) + `player/exo/ExoHlsAdblockDataSource`(183) + `androidx/media3/mpvplayer/MpvHlsProxy` 改动 + `assets/rules/hls_rules.json` | **核心 16 文件 / 2,196 行**，另有 12 个单测 | `server/process/M3u8.java` **238 行 vs 你的 201 行**——只多 37 行接入代码，说明净化逻辑独立成类，侵入极小 |
| ② 去广规则治理（用户规则 / 接口学习 / 命中统计） | `api/config/UserAdRuleStore`(119)、`AdBlockStatsStore`(311)、`InterfaceAdRuleAnalyzer`(190)、`InterfaceAdRuleLearningService`(20)、`ImportedAdRuleCandidateStore`、`bean/UserAdRule`(212)、`bean/AdBlockStats`(314) | 另约 1,000 行 | 你项目只有旧版 `api/config/RuleConfig`（`ads` / `rules.regex` / `rules.exclude`），无用户规则、无统计、无接口自动学习 |
| ②b 去广管理 UI | mobile：`SettingAdFragment`(594) + `AdRuleAdapter`(269) + `AdRuleManageDialog`(430) + `AdRuleEditDialog`(149) + `AdRulePreviewDialog`(110) + `AdBlockStatsDialog`(765) | **2,317 行**（mobile 一份，leanback 另有一份） | 无 |
| ③ 音频指纹去广（听广告词自动跳过） | `ad/audio/` **44 个类**（`AdAudioRuntimeController`、`SpectralFingerprint`、`SpeechAdMatcher`、`SignedRulePackageVerifier`…）+ 5 个单测 | 巨大 | **完全没有**；缺 PCM tap、缺语音模型、缺 `com.google.crypto.tink` 依赖 |

### 4.2 逐项可行性

**① HLS 净化 → 可行性：高，且可直接移植（不是自研）**

> ⚠️ **修正**：我第一版方案里写"仓库里没找到实现、要按文档自研"，**这是错的**。拿到完整仓库后确认实现齐全：`HlsManifestCleaner` 343 行 + `HlsAdblockPipeline` 109 行 + 完整规则体系 + 内置规则包 + 12 个单元测试（`HlsManifestCleanerTest`、`HlsAdblockPipelineTest`、`HlsAdRuleTest`、`HlsRulePackageTest`、`MpvHlsAdblockGateTest`…）。

移植要点：
- 核心是纯 Java 文本处理（AST 解析 → 规则匹配 → 重建），不碰播放器内部，失败回退原始 manifest。
- 接入点就在 `M3u8.java`：对比两边文件（238 vs 201 行，差 37 行），说明它只是"取到 m3u8 文本后交给 pipeline"这一句。
- 必须连带移植的还有：`ExoHlsAdblockDataSource`（Exo 侧独立嗅探）和 `MpvHlsProxy` 的改动（MPV 内核也要走同一净化）。**如果只接 `M3u8` 而不管 Exo/MPV 直连，同一部剧换内核就失效。**
- 设计文档里的安全约束照做：删除比例 > 35% 回退、无有效分片回退、保留 `KEY/MAP` 上下文、live playlist 只删头部并同步 `MEDIA-SEQUENCE`。
- 单测一起搬，这是这块最大的质量保障（12 个测试文件覆盖正反样本）。

**② 规则治理 → 可行性：中，建议分两步**
- 第一步只搬「用户规则 + 命中统计」（`UserAdRuleStore`、`AdBlockStatsStore`、`bean/*`），规则仍是手动填 host/regex，**不做** `InterfaceAdRuleAnalyzer` 那种"从接口配置自动学习"（它需要理解各家 CSP 的规则形态，误判成本高）。
- 第二步再考虑管理 UI（2,317 行，mobile + leanback 双份，最费工）。

**③ 音频指纹去广 → 可行性：低，建议放弃**
理由（都能从源码/文档核实）：
- 44 个类，且依赖 `PcmAdAudioSignalProvider`（要 Exo PCM tap）、`SpeechAdMatcher`（要语音识别）、`SignedRulePackageVerifier`（要 `com.google.crypto.tink`）——你项目**没有 tink 依赖**，也没有 Sherpa/ONNX 语音栈。
- 设计文档明确写「不支持 IJK/MPV 的音频指纹跳过」「不内置、不下载大规模指纹库」「不以音频指纹替代 HLS/URL/WebView 结构化路径」。
- 国内点播源的广告主要在 m3u8 片头和 Web 播放页弹窗，**长视频里的语音广告不是主战场**。

### 4.3 建议的取舍

```
做：① HLS manifest 净化（核心 16 文件 2,196 行，连单测一起搬）——含 Exo/MPV 两条内核路径
做：② 用户规则 + 命中统计（约 1,000 行），管理 UI 延后
不做：③ 音频指纹去广（44 类）
不做：② 里的「接口规则自动学习」（误判成本高，收益不确定）
保留：旧 rules/ads 的兼容映射（保证你现有配置不废）
```


---

## 5. 社区自制资源怎么进「订阅源」（这一项**不用开发**）

先把概念掰正：`https://cnb.cool/fish2035/ext` 是 **WebHome 开发套件**（文档 / 主页示例 / 扩展示例 / 模板 / AI skills），**不是一份订阅源清单**。它和"订阅"的关系是：它提供**物料**，物料通过三种通道进你的 app。

### 5.1 三种通道（你的项目**已经全部支持**）

已核实你项目里有：`WebHomeExtensionRegistry`（9 处引用）、`CustomCspSetting`（8 处）、设置页「增强功能」中的 `站点注入` 与 `WebHome 扩展` 两个入口。

#### 通道 A：站点注入（最常用）
`设置 → 增强功能 → 站点注入`
- 顶部「识别」可直接粘贴网友给的松散 JSON 片段（`{...}, {...}` 或带尾逗号的单对象），自动归类为 WebHome / 通用 CSP / 直播并追加。
- WebHome 行打开「扩展」开关后，输入框变成「扩展 URL / JSON」——**这里填社区扩展的 JS 地址或 manifest JSON 即可**；也能点「文件」选本地 `.js/.css/.json`（本地 JS 会转成内联 `code` 扩展）。
- 插入位置只能在 `sites` 前 10 位；文件落在 `/sdcard/TV/CustomCsp/`，可随「一键同步」带走。

#### 通道 B：WebHome 扩展（管理脚本）
`设置 → 增强功能 → WebHome 扩展`
- 新增时选「链接/JSON」，填 manifest URL；匹配范围默认弹窗勾选站点，存成精确 `cspKeyRegex`。
- 适合"一个脚本要给多个站点用"，或想单独启用/禁用/调试（带 Web 预览 + Console/Network/Elements 调试台）。

#### 通道 C：配置文件级（作者分发用）
在点播接口 JSON 里直接写：

```json
{
  "sites": [{
    "key": "pomo", "name": "Pomo", "type": 3, "api": "csp_Builtin",
    "homePage": "https://example.com",
    "extensions": [
      { "id": "pomo-native-router", "runAt": "document-end", "js": ["./pomo.js"] }
    ]
  }]
}
```

或根级全局扩展（必须配 `cspKeyRegex` 限定站点）：

```json
{ "webHomeExtensions": [
  { "id": "gying-native-router", "enabled": true, "runAt": "document-end",
    "cspKeyRegex": ["^gying$"], "js": ["./gying.js"] }
]}
```

### 5.2 一句话回答

> 网友自制的资源 = **一个 WebHome 主页 HTML 或一段扩展 JS/manifest**。它进"订阅源"的方式不是加一条 URL 到某个订阅列表，而是：
> **① 挂到某个站点的 `homePage`（换首页），② 或挂成该站点的 `extensions`（增强现有网站），③ 或在 app 里通过「站点注入 / WebHome 扩展」两个界面直接把 JSON/JS 粘进去。**
> `cnb.cool/fish2035/ext` 的 `examples/extensions/<站点>/*.manifest.json` 就是给你粘到 ②③ 里的现成物料；社区分享帖（`fish2018/webhtv` issues #13）贴的也是同一类片段。

**所以这一项：你的项目今天就能用，零开发。** 唯一可做的是"体验优化"：给「站点注入」加一个"从 URL 一键导入"（现在要复制粘贴 JSON），属于可选增强。

---

## 6. 总体开发方案（建议分期）

### 阶段 0：施工准备（必做）
1. `git init`，提交当前全量代码，打 tag `baseline-5.6.4`。
2. **补一次完整 diff**：tangtv 源码已完整拉到 `/home/s/tangref/tangtv2`（sparse checkout：`app/src` + `catvod` + `docs`，2,273 个 java 文件）。用 `diff -r` 或 `git diff --no-index` 对同路径文件做一次全量比对，把本方案里的"文件级清单"从"关键项核实"升级为"全量核实"。
3. 建 `docs/` 目录，把本方案与 tangtv 的 `智能去广-设计文档.md` 收进去做依据。

> 说明：第一版方案是只能用 GitHub API 逐目录看时写的，当时误判了"HLS 净化未实现"。现已拿到完整仓库并逐文件核实校正（见 4.2 ① 的修正说明）。**阶段 0 的完整 diff 仍建议做**，它能把剩余 30% 的"未逐行核对"补齐。

### 阶段 1：低风险快赢（建议先做）
| 项 | 内容 | 风险 |
|---|---|---|
| 搜索线程 | `Setting` + 并发闸门 + 设置行 | 低 |
| 短剧源配置 | `ShortDramaConfig` + `ShortDramaSourceDialog` + 首页入口过滤 | 低 |
| 短剧倍速保持 | `onPrepare` 补 `setSpeed` + 修全局速度污染 | 低 |

这三个互不依赖，可以一起做完打一个包验证。

### 阶段 2：HLS 去广（性价比最高的一块）
- 移植核心 16 文件 / 2,196 行 + 12 个单测
- **三条路径都要接**：`M3u8`（本地代理）、`ExoHlsAdblockDataSource`（Exo 直连）、`MpvHlsProxy`（MPV）
- 默认关闭，带"本次播放不去广"逃生口
- 内置规则包 `assets/rules/hls_rules.json` 先只放 3 条默认关闭的实验规则（原包只有 6 行）

### 阶段 3：TMDB（借服务层 + 自写 UI）
- 移植 `TmdbService`(1,210) + `SafeJsonParser` + `TmdbMatcher` + `TmdbConfig`/`TmdbItem`
- 自写精简详情页（海报/简介/评分/分季选集/演员），点播放回落到现有 `VideoActivity`
- 只做「原生详情 / TMDB 详情」二选一
- 必带：自定义 API 域名 / 图片域名输入框
- **不碰** `VideoActivity` 的详情模式矩阵，**不搬** `TmdbDetailActivity`(13,268)/`TmdbUIAdapter`(2,666)/`TmdbHeaderView`(2,529)

### 阶段 4（可选/延后）
- 去广的规则管理 UI（2,317 行 × 2 个 flavor）
- 用户规则 + 命中统计（约 1,000 行）
- 触屏优化（等你确认语义）
- 抖音式短剧信息流（独立立项，可复用 `ShortDramaQueueCoordinator`）

### 明确不做
- 音频指纹去广（`ad/audio/` 44 类，缺 tink + 语音栈）
- TMDB 的 FUSION / CINEMA / PLAYER 详情模式
- 「接口规则自动学习」（`InterfaceAdRuleAnalyzer`）


---

## 7. 定稿范围（已拍板）

原方案列的 5 个待决问题已由你定稿，按下面执行：

| 项 | 决定 | 影响 |
|---|---|---|
| 触屏优化 | **不做** | `TouchOptimizationHelper` 不移植 |
| 搜索线程 | **不做** | `Task.java` 的 20 线程池保持原样 |
| TMDB | **不做** | `TmdbDetailActivity` / `TmdbService` 全部不动 |
| 去广告 | **做 HLS 净化 + 单测**，管理 UI 后补，音频指纹放弃 | 核心 16 文件 / 2,196 行 + 12 个单测 |
| 社区资源 | **优先做**：站点注入 / WebHome 扩展，并给站点注入加「一键导入」 | 本轮第一个落地项 |
| 短剧源配置 | 做（属于「其他按推荐」） | `ShortDramaConfig` + 弹窗 + 倍速保持 |

### 施工顺序（按定稿重排）

1. **阶段 0**：`git init` + tag `baseline-5.6.4` —— ✅ 已完成（3,017 个文件入库）
2. **阶段 1**：站点注入「一键导入」—— 进行中，见第 8 节
3. **阶段 2**：HLS 去广（三路径 + 单测）
4. **阶段 3**：短剧源配置 + 短剧倍速保持
5. **不做**：触屏优化、搜索线程、TMDB、音频指纹去广

---

## 8. 站点注入「一键导入」实施记录

### 8.1 为什么需要它

社区分享的资源不是「一条订阅 URL」，而是散落的物料：一份 `xxx.manifest.json`、一段 `xxx.js`、一个自制主页 `xxx.html`、或者干脆是一段混着说明文字的帖子。原来只有两条路：

- 「识别」只吃**粘贴的 JSON 正文** —— 帖子给的是链接，用户得自己打开链接、全选、复制、再粘回来；
- 「扩展 URL」只挂在**单个站点**的编辑面板里 —— 得先知道该挂哪个站点，还要手动打开那个站点的编辑页。

链接 → 物料 → 落地形态，中间的人工步骤全是摩擦。

### 8.2 实现

**新增 `setting/CspUrlImporter.java`（纯逻辑，可单测）**

一条链接拉回来先分类：

| 类型 | 判据 | 落地方式 |
|---|---|---|
| `CONFIG` | JSON 里有 `sites` / `lives` / `webHomeExtensions` / `rules` / `parses`… 或站点对象/数组 | 走既有「识别」管线并入列表 |
| `EXTENSION` | JSON 里有 `extensions` / `js` / `code` / `runAt`，或正文是脚本，或后缀 `.js/.css` | 挂到选定站点的 `extensions` |
| `PAGE` | `<!DOCTYPE` / `<html` / `<head` / `<body` | 下载进 `TV/CustomCsp/<id>/index.html`，新建 WebHome 站点 |
| `LIVE` | 后缀 `.m3u/.txt` 或「频道名,http…」连续两行 | 新建直播项 |
| `UNKNOWN` | 其余 | 提示识别不了 |

关键取舍：**扩展存的是链接本身，不是拉下来的内容**。因为 `WebHomeExtensionRegistry.loadRemote()` 会以该链接为 base 去解析 manifest 里的 `"js": ["./xxx.js"]`；社区 manifest 清一色写相对路径，存内容就会解析失败。同时 registry 对「纯脚本链接」也有兜底（非 JSON 且是脚本 URL 时自动包成 `{js:[url]}`），所以 `.js` 直链也能直接存。

**改 `ui/dialog/CustomCspDialog.java`**

- 「识别」面板顶部加一个「资源链接」输入框，**自动用剪贴板里的第一个 http 链接预填** —— 从社区复制链接后进来直接点确定，两下完成；
- 整段分享文字粘进来也行，`firstHttpUrl()` 会把混在文字里的链接挑出来；
- 链接非空走链接导入，留空仍走原来的 JSON 识别，向后兼容；
- 扩展类需要目标站点：只有一个 WebHome 站点就直接挂，多个就弹站点选择；**只对 WebHome 站点生效**（`Item.site()` 只在 webHome 时写 `extensions`，挂到通用 CSP 上是静的）。

### 8.3 验证

`app/src/test/java/com/fongmi/android/tv/setting/CspUrlImporterTest.java`，16 个用例覆盖：

- 四类物料 + 垃圾内容的分类；
- Markdown 围栏 / BOM 归一化（社区帖子常把 JSON 包在 ```json 里）；
- `appendExtension` 的空数组创建、保留既有条目、解包 manifest 外壳、幂等、按 `js` 条目去重；
- `displayName` 去社区噪音（`pomo.mom.js` → `pomo`、`dm.xueximeng.manifest.json` → `dm.xueximeng`）；
- 从分享文字里抠链接。

---

## 9. 站点扩展「配了却不生效」根因与修复

### 9.1 现象

4 个 WebHome 站点的 `extensions` 都配好了社区 manifest 链接，站点列表正常、网页正常打开，但脚本一次都没跑：注入一段 `GM_addStyle('body{outline...}')` 探针后没有任何描边，控制台也看不到扩展日志。

### 9.2 根因一：`isScriptUrl()` 把清单误判成脚本（代码缺陷）

`WebHomeExtension.isScriptUrl()` 原来是**子串判断**：

```java
return lower.contains(".js") || lower.startsWith("file://") || lower.startsWith("local://");
```

而社区扩展的清单清一色叫 `*.manifest.json` —— **`".json"` 本身就包含 `".js"`**，于是：

1. `WebHomeExtensionRegistry.loadRemote()` 里 `isScriptUrl(sourceUrl)` 为真 → **清单根本不会按 JSON 拉取**；
2. 走到「对象为空且是脚本 URL」的兜底分支 → 把清单链接塞进 `js` 数组；
3. `WebHomeExtension.from()` → `addScript()` 又把那个 URL 的**内容（一段 JSON）**下载下来当脚本正文；
4. 注入 `<JSON 文本>` 必然 `SyntaxError`，而且是**解析期错误**，连包裹脚本里的 `try/catch` 都捕获不到 —— 页面无变化、控制台无日志，完全静默。

同一缺陷还波及 `WebHomeExtensionSourceStore.linkObject()`：在扩展面板里粘贴清单链接，也会被写成 `js:[清单链接]`，同样是静默失败。

**修复**（`WebHomeExtension.java` / `WebHomeExtensionRegistry.java`）

- `isScriptUrl()` 改成**后缀精确判断**：去 `?`/`#` 后以 `.js` / `.mjs` / `.jsx` 结尾，`file://`、`local://` 仍为真；
- 新增 `isManifestUrl()`：以 `.json` 结尾；
- `loadRemote()` 的兜底条件从 `isScriptUrl(sourceUrl)` 改成 `!isManifestUrl(sourceUrl)` —— 这样**没有后缀的脚本链接**也不会再被静默丢弃（旧写法同样会漏）。

**回归单测** `app/src/test/java/com/fongmi/android/tv/web/ext/WebHomeExtensionUrlTest.java`（6 个用例）：4 个真实 manifest 链接必须为假、真脚本为真、`?v=1` / `#frag` 不影响判断、`.json/entry` 这类老实现误判项为假。

### 9.3 根因二：扩展总开关是关的（配置问题）

`WebHomeExtensionRegistry.prepare()` 开头有：

```java
if (!Setting.isWebHomeExtension()) { ready.remove(siteKey); ... return; }
```

`Setting.isWebHomeExtension()` 读的是 `web_home_extension`，默认 `true`，但设备上的实际值是 **`false`**。开关一旦关闭，站点上挂多少扩展都不会加载。

**开关位置**：设置 → 增强设置 → WebHome 扩展（顶部开关）。打开后即可。

### 9.4 验证证据（真机）

打开开关后，Pomo 站点的完整链路：

```
prepared site=__custom_csp_pomo src=1 matched=1 ready=1 ids=pomo-native-router,document-end
inject    id=pomo-native-router runAt=document-end target=document-end site=__custom_csp_pomo
```

扩展缓存目录 `cache/webhome_ext` 实到两份文件：

```
manifest_38cac24912d1029fd05a903519c55723.json   211 B
script_88469ce2cef490caecb1f644300a0257.js    49,994 B   ← 与远端 pomo.mom.js 大小一致
```

即：清单被正确按 JSON 拉取 → 解析出 `js: ["./pomo.mom.js"]` → 相对路径解析成完整 URL → 下载脚本 → 注入 WebView。

### 9.5 顺带清掉的东西

排查期在 Pomo 站点上留过一个 `id=probe` 的探针条目，已从设备注册表删除（`TV/CustomCsp/registry.json`，含嵌套的原始 `site` 字段），4 个站点现在各挂 1 条 manifest 链接，无残留。

### 9.6 本机构建环境的三个坑（备查）

| 坑 | 现象 | 解法 |
|---|---|---|
| 容器只有 `C/POSIX` locale | `mergeAssets` 报 `Failed to create MD5 hash for file: Invalid file path`，真实原因是 `assets/lut_presets/` 下的**中文文件名**被 ASCII 解码打不开 | 容器加 `-v /usr/lib/locale:/usr/lib/locale:ro -e LANG=zh_CN.UTF-8` |
| 宿主 `ulimit -v` 锁 4 GB | 宿主直接跑 Gradle 时 `Could not allocate compressed class space` | 宿主不改限制，改用容器构建 |
| 设备原包是特定 debug 证书 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 用 `/home/s/.android/debug.keystore`（`androiddebugkey` / `android`）重签后再装，同签名可保留数据 |

---

## 9. 原方案留档：5 个待决问题（已由第 7 节取代）

1. **触屏优化**：A. 照搬 tangtv 语义（只在 TV 版生效，你平板无效）；B. 我们重写成手机版（手势阈值 / 48dp 触控热区 / 滚动惯性）；C. 两个都要（按 flavor 分流）。
2. **TMDB**：接受"借 Service + 自写精简详情页"（不搬 13k 行的 `TmdbDetailActivity`）吗？还是觉得 TMDB 优先级不高、先跳过？
3. **TMDB 网络**：你有没有稳定的 TMDB 访问方式（代理/镜像域名）？没有的话我默认给官方域名 + 可填镜像输入框。
4. **去广告**：确认"做 HLS 净化（16 文件 2,196 行，连单测搬）+ 放弃音频指纹 44 类"？管理 UI（2,317 行）要不要一起做，还是先做核心、界面后补？
5. **施工顺序**：阶段 1（快赢）→ 2（HLS 去广）→ 3（TMDB），还是你想把哪个提前？


---

## 附：本轮核实到的关键事实（备查）

| 结论 | 证据 |
|---|---|
| tangtv 是 WebHTV 换皮分支 | `app/build.gradle`：`applicationId "com.silent.android.tangtv"`、`versionCode 560`、`versionName "5.6.0"`；README 与 webhtv 同文 |
| `server/process/` 高度同源 | 你项目 17 类 vs tangtv 20 类，仅差 `CatMessage`/`CatWebview`/`SpiderApi` |
| 搜索线程默认 20、范围 1–100 | `Setting.getSearchThread()` → `Prefers.getInt("search_thread", 20)` + `clampSearchThread` |
| 触屏优化仅在 leanback 生效 | `Setting.isTouchOptimized()` → `Util.isLeanback() && Prefers.getBoolean("touch_optimized")`；实现是 `ui/helper/TouchOptimizationHelper.java`（253 行），`sync()` 首行即 `if (!Util.isLeanback()) return;` |
| 短剧源 = 站点白名单 | `ShortDramaConfig.DEFAULT_ENABLED_RULES = ["[短]", "短剧"]`，`isSiteEnabled(key,name)` 做 contains 匹配；配套 `ShortDramaSourceDialog` + `ShortDramaQueueCoordinator`（连播状态机）+ `PersonalRecommendationService` |
| HLS 去广**已完整实现**（第一版误判，已修正） | `utils/HlsManifestCleaner`(343) + `HlsAdblockPipeline`(109) + `bean/HlsAdRule`(104) 等核心 16 文件 2,196 行；`assets/rules/hls_rules.json`；12 个单测；`M3u8.java` 238 行 vs 你的 201 行（只多 37 行接入代码） |
| TMDB 体积远超预期 | `TmdbDetailActivity` **13,268 行**、`TmdbUIAdapter` 2,666 行、`TmdbHeaderView` 2,529 行；涉及 TMDB 的 java 文件 208 个 / 93,930 行 |
| 去广告音频子系统规模 | `ad/audio/` 44 个类；依赖 tink（你项目无此依赖） |
| 社区资源通道已具备 | 你项目 `WebHomeExtensionRegistry`（9 处）、`CustomCspSetting`（8 处）、`SettingEnhanceFragment` 有 `customCsp` / `webHomeExtension` 入口 |
