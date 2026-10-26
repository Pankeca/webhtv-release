# WebHomeTV 编译说明

本目录只保留 APK 编译所需的工程文件，构建缓存不入库；仓库已建立 Git 基线（见第四节「接手开发须知」），下载中心模块的实现要点、真机验证结论和待办见文末第四～六节。

## 一、编译环境（本机已装好）

| 组件 | 版本 / 路径 |
| --- | --- |
| JDK | 21（`/home/s/Android/jdk21`） |
| Android SDK | `/home/s/Android/Sdk`；Platform 37、Build-Tools 37.0.0 |
| Android NDK | 29.0.14206865（`/home/s/Android/Sdk/ndk/29.0.14206865`） |
| Python | 3.10（`/home/s/.local/bin/python3.10`，Chaquo 要求） |
| Gradle | 工程自带 Wrapper（`./gradlew`），无需另装 |

SDK 路径已写入 `local.properties`：`sdk.dir=/home/s/Android/Sdk`。该文件**不入库**（Release 签名口令也从这里读，见 `app/build.gradle`），换机器需自行创建。

## 二、编译命令

```bash
export JAVA_HOME=/home/s/Android/jdk21
export PATH="$JAVA_HOME/bin:$PATH"
cd /home/s/webtv

# 手机版（常用）
./gradlew assembleMobileArm64_v8aDebug

# 电视版
./gradlew assembleLeanbackArm64_v8aDebug
```

产物路径：`/home/s/webtv/app/build/outputs/apk/<模式><架构>/<buildType>/`，例如
`app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`

四种组合：`mobile` / `leanback` × `Arm64_v8a` / `Armeabi_v7a`。

依赖需要联网下载，走代理时先设置：

```bash
export https_proxy=http://127.0.0.1:7897
export http_proxy=http://127.0.0.1:7897
```

## 三、签名与 Token（Release 发布用，按需填写）

Release 包不配置签名时会自动用 debug 签名。要正式签名，在 `local.properties` 追加四项：

```properties
storeFile=/home/s/xxx.jks
keyAlias=xxx
storePassword=xxx
keyPassword=xxx
```

CI 发布使用仓库 Secrets（名称记录在此，值不落盘、不入库）：
`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`、`OCI_USERNAME`、`OCI_TOKEN`、`CNB_TOKEN`、`GH_TOKEN`。

## 四、下载中心模块

### 代码位置

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/fongmi/android/tv/download/M3u8.java` | m3u8 解析：分片级 `EXT-X-KEY`、默认 IV（big-endian 序号）、`EXT-X-MAP` fMP4 init、master 按 BANDWIDTH 取最高码率、`SAMPLE-AES` 标记 |
| `.../download/DownloadTask.java` | 单集下载与状态机：直连走 Range + `.part` 续传；HLS 按分片缓存后合并；误标成 `.m3u8` 的直链自动回退到 HLS；`findLocal/deleteLocal` 负责本地文件定位与清理 |
| `.../download/DownloadManager.java` | 队列（`MAX_CONCURRENT = 2`）、Gson 持久化（Prefers key `download_tasks`）、秒级 ticker、`reconcile()` 与孤儿文件收养、`enqueue()` 按「剧名+集名」去重 |
| `.../download/DownloadService.java` | 前台服务（`dataSync`）与通知栏进度/暂停入口 |
| `app/src/mobile/java/com/fongmi/android/tv/ui/dialog/DownloadDialog.java` | 详情页「下载」选集弹窗 |
| `app/src/mobile/java/com/fongmi/android/tv/ui/activity/DownloadCenterActivity.java`（布局 `activity_download_center.xml`） | 下载中心：下载中 / 已下载两个 tab、按剧集分组、展开收起、长按多选、批量删除、存储占用 |
| `app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java` | `showLocal()` 本地缓存选集、`setupLocalBar()` 本地播放提示条、`switchToNetwork()` 跳回站点线路播放 |

> 约束：`DownloadDialog`、`DownloadCenterActivity` 只存在于 `mobile` source set（leanback 无下载中心入口）。
> `DownloadDialog` 引用了 `DownloadCenterActivity`，因此必须留在 `mobile/`，放到 `main/` 会导致 leanback 编译失败。

### 落盘路径与命名

```
/sdcard/Download/webhtv/<剧名(净化)>/<集名(净化)>.<ts|mp4>
```

HLS 分片临时缓存在 `Path.cache()` 下（`%06d.bin`、fMP4 为 `init-%03d.bin`），合并完成后写回上面的正式文件。

### 任务状态

`WAITING → RUNNING → DONE`，异常分支 `FAILED / CANCELED / MISSING`（`MISSING` = 记录已完成但文件已被删除，`reconcile()` 负责纠正）。

### 通知（易踩坑）

渠道 `download`，两个 id 必须分开：

- `9528`：前台服务进度通知（百分比 + 速度 + 「全部暂停」按钮）。
- `9529`：空闲通知（`下载已暂停` / `下载完成`，可点击进下载中心、autoCancel）。

空闲通知先 `notify(9529)`，再 `stopForeground(true)` 移除 `9528`，最后 `stopSelf()`；若复用同一个 id，`stopForeground` 会把刚发出的完成/暂停提示一并删掉。恢复下载和任务列表清空时都要 `cancel(9529)`。

### 验证命令

```bash
export JAVA_HOME=/home/s/Android/jdk21
cd /home/s/webtv

./gradlew :app:assembleMobileArm64_v8aDebug                                    # 手机包
./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac                       # 电视版仅编译校验
./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests 'com.fongmi.android.tv.download.M3u8Test'

git status --porcelain                                                                    # 改前确认工作区干净
git diff 4e2474f -- app/src/mobile/java/com/fongmi/android/tv/ui/activity/DownloadCenterActivity.java   # 与基线比对某个文件
```

### 接手开发须知

- 仓库已初始化：`main` 分支第一个提交 `4e2474f` 是 2026-10-26 下载中心开发完成、真机验证通过后的**基线快照**（2937 个文件；`.git` 约 194MB）。`.gitignore` 排除 `build/`、`.gradle/`、`.cxx/` 与 `local.properties`；`app/libs`、`third_party/**/prebuilt` 里的 `.so/.a/.aar` 是 vendored 预编译产物，**故意入库**，删了会编不出包。改动前先 `git status` 确认干净，回滚用 `git diff 4e2474f -- 路径`。`app/build.gradle` 会把 `git rev-parse HEAD` 和工作区状态写进 `BuildConfig.GIT_REVISION` / `GIT_STATE`（`defaultConfig`，所有变体生效）；第五节验证所用的 17:08 APK 打于建库之前，字段还是 `unavailable`，之后重新构建才会带上真实提交号。
- 下载中心入口链路：详情页 →「下载」图标 → `DownloadDialog` →「查看全部下载」→ `DownloadCenterActivity`。该 Activity **未导出**，`adb shell am start` 会被 `SecurityException` 拒绝，只能走 UI 链路（通知栏点击是另一个入口）。
- 文案改一处不够：`download_*` 字符串同时存在于 `values/strings.xml`（英文）、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`，新增/修改要三份一起改。
- 界面构建方式不同：下载中心是 XML（`app/src/mobile/res/layout/activity_download_center.xml`）+ 代码动态加分组卡片；`DownloadDialog` 与本地播放提示条（`VideoActivity.setupLocalBar()`）完全是代码里拼 View，没有对应 XML，改样式要找 Java 而不是 layout。
- 集数按钮的列数目前**分散在三处硬编码**，做「统一每行个数 / 按钮宽度」时要一起改：`DownloadDialog.COLUMNS = 3`（详情页下载弹窗）、`DownloadCenterActivity.CHIP_COLUMNS = 4`（下载中心集数胶囊）、`EpisodeGridLayoutPolicy.getMaxSpan()`（详情页选集，横屏或平板 6 列、竖屏 4 列，有 `EpisodeGridLayoutPolicyTest` 覆盖）。
- 涉及下载逻辑的改动，最低验证集：`M3u8Test` 单测 + leanback 编译 + mobile 真机跑一次「下载→暂停→续传→完成→本地播放→删除」。
- 第六节的待办是需求级描述，其中第 3 条（《嫁金钗》本地选集回退）尚未定位根因，只给了排查方向。

## 五、真机验证结论（2026-10-26，Lenovo TB331FC / Android 13，站源：暴风资源 `bfzym3u8`）

已通过：

- 详情页下载弹窗选集、`立即下载`、重复入队提示「该集已在下载队列中」。
- 下载中：进度、速度、剩余时间、存储占用；并发上限 2 运行 + 1 等待。
- 暂停 / 继续（断点续传，从 20% → 21% 继续）；杀进程重启后任务保留并自动续传。
- 通知栏：下载中 `9528`；暂停后 `9529「下载已暂停」`在服务停止后仍保留；完成后 `9529「下载完成」`保留、`9528` 已移除。
- 长完成校验：单集约 950MB 的 `.ts` 落盘成功且可播放。
- 下载中心点已下载集 → 详情页线路自动切到「本地缓存」并播放（提示「已自动播放本地下载的文件」）。
- 分组「全选 + 删除」：确认后本地文件真实删除，分组消失，计数与存储同步（12 → 10、8.83GB → 7.07GB）。
- `M3u8Test` 4/4 通过；leanback 变体编译通过。

## 六、已知问题与待办

1. 下载中心可读性：`下载中 / 已下载` tab 字号偏小，底部「全部开始 / 全部暂停 / 删除」三键在半透明背景 + 壁纸上对比度不足，需要加深色底或描边。
2. 本地播放页「观看网络多线路完整版」需完整对齐可可影视：当前 `switchToNetwork()` 已能跳站点详情，交互与返回栈待细化。
3. **严重**：《嫁金钗》本地缓存播放中，点选集里其他已下载集会播放失败并跳回首次点击的那一集；其他剧集正常。该剧已下载集为 `第18集.ts` / `陪看1.ts`（集名不规则），待排查：`Util.getNumber("陪看1")` 的解析与 `showLocal()` 排序、`findLocal()` 命中情况、播放失败后的自动切源回退逻辑。
4. 下载弹窗集数按钮宽度与每行个数需统一（`DownloadDialog.COLUMNS = 3`，下载中心胶囊为 `DownloadCenterActivity.CHIP_COLUMNS = 4`，详情页选集走 `EpisodeGridLayoutPolicy.getMaxSpan()`：横屏/平板 6 列、竖屏 4 列），按拇指可点区域（≥48dp）重排并收敛成一处配置。
5. 已下载 tab 展开分组后，最后一行集数按钮会被底部操作条遮挡（列表缺少底部 padding）。
