# 以 tangtv 为基座的可行性评估

> 评估日期：2026-10-31
>
> **结论已定（2026-10-31 用户拍板）：两项都不做。**
> 1. **不开发音频/语音识别去广**（本文档 §1 仅作为技术鉴定存档备查）；
> 2. **不换 tangtv 基座**（本文档 §2/§3 仅作为代价评估存档备查）。
> 后续如需再议，先看 `docs/开发进度与交接.md` 的「明确不做的项」一节。
> 评估对象：`alantang1977/tangtv`（本地 blobless 克隆 `/home/s/tangref/tangtv2`，HEAD `8cd2ccc`，2026-09-23）
> 我方基线：`/media/s/7A3A1DFD3A1DB6DB/REA`，versionCode 565 / 5.6.5，提交 `7540890`
> 说明：tangtv 与我方同为 `com.fongmi.android.tv` 包名的 WebHTV 系分支，这是所有迁移可行性的前提。

---

## 0. 一句话结论

- **音频（语音）去广在 tangtv 里是「真做完」的**（45 个类 / 9,848 行 + 36 个单测 + 自带 Sherpa-ONNX 原生库 + 播放器接线 + 设置 UI），不是半成品；但它**默认关闭、只支持 ExoPlayer 点播、依赖运行时下载 ASR 模型**，"能不能用"取决于模型能否下载与设备算力，建议先做真机实测再决定。
- **换基座（方案 B）技术可行，但代价远大于收益**：我方要搬的不是 4 个文件而是 8,971 行独有代码 + 一堆"改在核心文件里"的合并；而 tangtv 把 `VideoActivity` / `PlayerManager` / `HomeWebController` 等核心文件全部重写过（最大 +5,379 行），我们的改动必须在其重写版上二次落地；外加**基座从 5.6.4 退回 5.6.0** 和 **GPL-3.0 传染性**两个硬风险。
- **推荐方案 C**：保持我方基座，单点反向移植「音频去广 + 实时字幕」（方案 A 的范围），再按需 cherry-pick tangtv 的短剧源配置等小功能。风险可控、不丢失任何已验证功能。

---

## 1. 音频识别去广：能不能用？

### 1.1 实现证据（都是仓库里的实物，不是文档承诺）

| 证据 | 内容 |
|---|---|
| 代码量 | `ad/audio/` **45 个文件 / 9,848 行**，另有 `player/audio/` 6 文件 / 874 行（PCM 旁路） |
| 单测 | `app/src/test/.../ad/audio/` **36 个测试**，含 `SpeechAdRuntimeEndToEndTest`、`AudioFingerprintContractTest`、`AdSkipCoordinatorTest`、`SpeechAdPlaybackHealthTest` |
| 识别引擎 | 自带 `jniLibs/arm64-v8a/libsherpa-onnx-jni.so`（**23.6 MB**）+ `armeabi-v7a` 同款；上层 `subtitle/SpeechRecognitionFactory` + `RealtimeSubtitleRecognizer`(373) + `RealtimeSubtitleController`(809) |
| 播放器接线 | `PlayerManager` 引用 `AdSkipCoordinator` / `AdAudioRuntimeController`；确认弹窗 `AdSkipPromptPresenter` |
| 设置入口 | mobile `SettingAdFragment`、leanback `SettingAdActivity` 均已接 `AdAudioSetting` / `SpeechAdSetting` |
| 签名规则包 | `SignedRulePackage*`（7 个类）+ 依赖 `com.google.crypto.tink:tink-android:1.23.0` |

### 1.2 两条检测通道

1. **语音关键词通道**（`SpeechAdSignalProvider`）：PCM → 16 kHz 重采样 → Sherpa-ONNX 中文识别 → 命中关键词 → 确认后相对 seek。默认关键词是博彩/赌场类口播广告词：`麻将来了 / 澳门 / 赌场 / 娱乐城 / 荷官 / 百家乐 / 老虎机 / 时时彩 / 六合彩 / 彩票 / 下注 / 投注 / 首充 / 提现 / 棋牌 / 捕鱼 / 斗地主`。节流 800 ms、同词冷却 5 s（实现里 30 s）、固定跳 15 s（可配 5–30 s）。
2. **音频指纹通道**（`PcmAdAudioSignalProvider` + `AudioFingerprint*`）：对 PCM 做频谱指纹匹配广告片段，规则包支持签名校验与在线更新（`ProbeRuleDownloader`）。

### 1.3 使用限制（写死在设计与代码里）

- **默认关闭**：`AdAudioSetting.isEnabled()` 默认 `false`，自动跳默认 `false`；未开不占用模型与 CPU。
- **只支持 ExoPlayer 点播**：直播、音频直通、DRM 受限、未知 duration、不可 seek 一律安全降级（不跳）。
- **确认模式优先**：`observe / confirm / auto`，首版默认 `confirm`（不确认不 seek），`auto` 需用户显式开启，带 OSD 与撤销。
- **模型要联网下**：`silero_vad.onnx` 来自 `github.com/k2-fsa/sherpa-onnx/releases`，ASR 模型来自 HuggingFace —— **国内网络可能下不动**（我方设备实测环境需验证，必要时换镜像/内置）。
- **体积与算力**：jniLibs arm64 由我方 18 MB → tangtv 34 MB（+16 MB/ABI，含 sherpa 23.6 MB + libc++ 1.37 MB）；arm64 单 ABI APK 预计 144 MB → 约 169 MB；16 kHz 实时识别对低端盒子有 CPU 压力。

### 1.4 判定

> **代码层面可用（完成度高、有端到端单测）；真实可用性待真机验证。**
> 建议验证三件事：① 模型能否在国内网络下载成功；② 我方设备（HA1YL5GG / Android 13）跑实时识别的 CPU 与发热；③ 命中后跳过的准确率与误报率。

---

## 2. 换基座：我方要搬走的全部功能

开发过程文档里**没有**下载中心、账号云同步、追更、详情页站点显示这些功能的过程记录（它们是在别的 agent/会话里做的），因此下面的清单是**以代码差异反推**的：把我方与 tangtv 的 `app/src` 做文件级比对得出。

比对基数：我方 1,300 个 java 文件 / tangtv 2,273 个；**1,259 个同路径文件（其中 954 个完全相同、305 个内容不同）**、**41 个仅我方有（8,971 行）**、1,014 个仅 tangtv 有。

### 2.1 迁移清单（41 个我方独有文件，按功能簇）

| 功能簇 | 主要文件 | 行数 | tangtv 是否有 | 移植难度 |
|---|---|---:|---|---|
| **下载中心** | `download/DownloadManager|Service|Task|M3u8|TsTimeline` + mobile `DownloadCenterActivity` + `DownloadDialog`（含 2 个单测） | ~3,000 | ❌ 完全没有 | 中（自成模块，仅入口与详情页 `下载` 按钮需接线） |
| **账号 / 云同步 / 备份** | `api/AuthApi`、`setting/AccountStore|AccountSync|CloudConfigSync|CloudSyncStore`、`ui/dialog/AccountDialog`、`playback/KeepCloudBackup` | ~1,800 | ❌ 完全没有 | 中（依赖后端接口，需确认服务端是否还在） |
| **弹幕多渠道来源** | `bean/DanmakuSource`、`api/DanmakuHttp`、`setting/DanmakuSourceSetting|DanmakuPreset`、`ui/adapter/DanmakuSourceAdapter`、`ui/dialog/DanmakuSourceDialog` + 本轮修复 | ~1,900 | ❌ 完全没有 | 中高（页面改动散布在 `DanmakuApi`(345)、`DanmakuSettingPanel`(384)、`Danmaku`(251) 等共有文件里，需在 tangtv 版本上重做） |
| **追更 / 更新跟踪** | `track/UpdateTracker|UpdateTrack|HistoryEpisodeCache`、`ui/dialog/ScheduleDialog` | ~1,000 | ❌ 完全没有 | 中（与 History(1,375 行版)、HomeActivity 有耦合） |
| **网盘代理兼容** | `api/loader/PanProxyCompat`（+ 单测 + 设备测试） | ~400 | ❌ 没有 | 低 |
| **站点注入一键导入** | `setting/CspUrlImporter`（+ 16 例单测）+ `CustomCspDialog` 改动（2,136 行） | ~400 | ❌ 没有 | 中（`CustomCspDialog` 两边差异巨大，合并要手工） |
| **更新/代理** | `update/GithubProxy`(+单测)、`update/CnbArchiveTransfer` | ~500 | 仅 `GithubProxy` 有 | 低 |
| **UI 定制** | `PipExpandActivity`、`NetworkVideoActivity`、`mobile/ui/adapter/SiteChipAdapter|SiteGridAdapter`（详情页站点显示）、`EpisodeChipConfig` | ~900 | ❌ 完全没有 | 中（都是 tangtv 重写过的页面区域） |
| **WebHome 扩展修复** | `web/ext/WebHomeExtension`(375) + `WebHomeExtensionUrlTest` | ~400 | 有同名文件但**内容不同**（tangtv 版 350 vs 我方 375） | 中（要保住我们的修复不被覆盖） |

> 另外还有 **305 个同名但内容不同的文件**：其中一部分是我方改动（上表已含主要项），另一部分是**上游 5.6.0 → 5.6.4 的演进**（我方基座 5.6.4，tangtv 560=5.6.0）——这部分如果换基座会被静默回退，需要逐个鉴别，是本方案最大的隐性工作量。

### 2.2 核心文件被 tangtv 大规模重写（换基座后我们的改动要二次落地）

| 文件 | 我方 | tangtv | 差 |
|---|---:|---:|---:|
| `mobile/ui/activity/VideoActivity.java` | 7,489 | 11,258 | +3,769 |
| `leanback/ui/activity/VideoActivity.java` | 6,419 | 11,798 | +5,379 |
| `player/PlayerManager.java` | 8,168 | 9,406 | +1,238 |
| `web/HomeWebController.java` | 1,005 | 2,280 | +1,275 |
| `bean/History.java` | 479 | 1,375 | +896 |
| `setting/Setting.java` | 916 | 1,648 | +732 |
| `leanback/ui/activity/CollectActivity.java` | 410 | 1,130 | +720 |
| `ui/dialog/TrackDialog.java` | 219 | 839 | +620 |
| `leanback/ui/activity/HomeActivity.java` | 923 | 1,477 | +554 |
| `setting/SiteHealthStore.java` | 255 | 707 | +452 |

含义：我们所有"改在核心文件里"的改动（弹幕面板、站点注入、详情页、播放控制）都必须在这些重写版上重新实现一遍，**等于二次开发 + 大量人工三向合并**。

### 2.3 其它硬风险

| 风险 | 说明 |
|---|---|
| **许可证（最硬）** | tangtv 是 **GPL-3.0**（`LICENSE.md`）。以它为基座 = 衍生作品，对外分发须以 GPL-3.0 开源全部源码。我方仓库目前**没有 LICENSE**；若不想被 GPL 约束，方案 B 不可选。（补充：tangtv 把本身非 GPL 的上游重新以 GPL-3.0 发布，法律上只能约束它自己的新增部分，属于灰色地带。） |
| **基座版本回退** | 我方 5.6.4（自升 5.6.5）→ tangtv 5.6.0；上游 5.6.1–5.6.4 的修复在 tangtv 里可能缺失，需逐个鉴别（见 2.1 末）。 |
| **稳定性信号** | tangtv 根目录堆着 `CRASH_ROOT_CAUSE.md`、`STACK_TRACE_ANALYSIS.md`、`stacktrace.txt`、多个 `*_FIX_*.md`；最后提交 2026-09-23（约 5 周前），单人维护 → 后续上游同步只能靠我们自己。 |
| **构建环境** | ✅ 无阻塞：tangtv 与我方同为 Gradle 9.5.1 + AGP 9.2.1 + Chaquopy Python，`settings.gradle` 用阿里云/腾讯镜像、本地 `third_party/maven`，与我们的容器构建链路兼容。 |

---

## 3. 三个方案的对比

| 维度 | A. 保持我方基座，反向移植音频去广 | B. 换 tangtv 为基座，全量搬我方功能 | C. A + 按需挑 tangtv 小功能 |
|---|---|---|---|
| 工作量 | ~1.3 万行新代码 + 接线（中） | 8,971 行独有 + 305 个文件合并 + 核心文件二次开发（大，数周级） | 中 + 小 |
| 主要风险 | 模型下载（国内网络）、APK +16 MB/ABI、CPU | GPL-3.0 传染、基座回退到 5.6.0、核心文件合并地狱、稳定性 | 可控 |
| 是否丢已有功能 | 不会 | 不会丢功能，但会丢上游 5.6.1–5.6.4 修复 | 不会 |
| 额外得到 | 音频去广 + 实时字幕 | 音频去广 + 实时字幕 + 短剧源配置 + Web 规则 V2 + 详情页多模式等 1,014 个 tangtv 独有文件 | 音频去广 + 实时字幕 + 选定的小功能 |
| 验收成本 | 低（现有功能全保留） | 高（全部功能要重验） | 低 |
| 推荐度 | ★★★★ | ★★（仅在"必须 GPL 开源 + 想要 tangtv 全家桶"时才考虑） | ★★★★★ |

---

## 4. 若选方案 A/C，建议的分阶段执行

1. **阶段 A1 — PCM 旁路 + ASR 底座**：移植 `player/audio/`(6) + `subtitle/` 的 ASR 相关（~1,850 行）+ `libsherpa-onnx-jni.so` + tink 依赖；先只做"实时字幕"跑通，验证模型下载与 CPU。
2. **阶段 A2 — 语音关键词去广**：移植 `ad/audio/` 中 Speech 通道（`SpeechAd*`）+ `AdSkipCoordinator` + `AdSkipPromptPresenter` + 设置 UI；**默认关闭、仅 `observe` 模式**上线，看诊断数据。
3. **阶段 A3 — 确认/自动模式 + 指纹通道**：`confirm` → `auto`，再评估 `AudioFingerprint*` 与签名规则包（要 tink）。
4. **阶段 A4（可选）— 挑 tangtv 小功能**：`ShortDramaConfig` + 短剧倍速修复（正好是我方原定的阶段 3）。

每阶段都：单测 + 容器构建 + **真机 adb 验证**（按既定约定）。

---

## 5. 需要你拍板的决策点

1. **方案选择**：A / B / C？（推荐 C）
2. **许可证**：能否接受整个项目以 **GPL-3.0** 开源（若选 B 则必须）？
3. **体积**：能否接受 APK 增加约 **16 MB/ABI**？
4. **模型来源**：是否允许运行时从 GitHub / HuggingFace 下载模型？若不允许，需要内置模型（体积进一步上升）。
5. **音频去广的目标广告类型**：现在这套主要针对**博彩/赌场口播广告词**（澳门/百家乐/时时彩…）；如果你要的是"片头会员广告/贴片广告"，语音关键词命中率可能很低，需要重新评估（这种情况 HLS 去广更有效）。
