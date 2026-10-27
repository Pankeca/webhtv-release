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

## 七、云端服务与个人中心（2026-10-28）

### 后端（`cloud/edgeone`）

| 项 | 值 |
| --- | --- |
| 代码入口 | `cloud/edgeone/edge-functions/api/[[default]].js`（单文件路由） |
| 托管 | EdgeOne Makers 项目 `webhtv-sync`（ProjectId `makers-hlaoldkoeyli`） |
| 域名 | `https://sync.940214.xyz`；CLI 默认域名为 `webhtv-sync.edgeone.dev` |
| 存储 | EdgeOne Pages Blob，store 名 `webhtv`（`@edgeone/pages-blob`） |
| 管理员口令 | `ADMIN_CODE = "webhtv"`，与 `app/build.gradle` 的 `BuildConfig.ADMIN_CODE` **必须一致**，改一处即失效 |

入库范围：`package.json`、`package-lock.json`、`index.html`、`edge-functions/`（`cloud/.gitignore` 已忽略 `node_modules/` 与 `.edgeone/`，二者都是可重新生成的部署依赖/产物）。

部署与验证：

```bash
~/.bun/bin/edgeone login        # 首次需浏览器授权，凭据落在本机，不入库
~/.bun/bin/edgeone whoami
cd /home/s/webtv/cloud/edgeone && ~/.bun/bin/edgeone makers deploy

curl -s https://sync.940214.xyz/api/health
# 未登录访问注销接口应为 401；若返回 404 说明线上仍是旧版本，需重新部署
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://sync.940214.xyz/api/auth/delete
```

接口一览：

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/auth/register`、`/api/auth/login`、`/api/auth/logout` | 无 / token | 账号注册、登录、退出 |
| POST | `/api/auth/password` | token | 修改密码（旧密码校验） |
| POST | `/api/auth/delete` | token + 密码 | **注销账号**：删除 `un_`/`u_`/`ut_`/`bk_`/`r_` 前缀数据并吊销 token |
| GET | `/api/auth/me` | token | 校验 token 是否有效 |
| POST/GET | `/api/backup/save`、`/api/backup/load?type=` | token | 按 `type`（`keep`/`config`）存快照；响应为 `{ok,data:{type,updatedAt,appId,data:{...}}}`，读取端要做**双层解包** |
| POST | `/api/playback/sync`、`/events` | token | 播放进度增量同步/事件回传 |
| GET | `/api/public/list`、`/api/public/get?key=` | token | 公共配置读取（`get` 返回 `{ok,key,content}`，`content` 是 JSON 字符串） |
| POST | `/api/public/put`、`/api/public/delete` | `X-WebHTV-Admin` 口令 | 管理员发布/删除公共配置；客户端默认发布 key `public_config` |

### 个人中心（仅 `mobile` source set）

| 行为 | 实现要点 |
| --- | --- |
| 登录同步选择 | 未登录前有本地追更/历史时，登录后弹「双向增量（默认）/云端覆盖本地/本地覆盖云端」，取消则不处理；无本地记录时静默拉取 |
| 数据概览 | 「我的追更 / 播放历史 / 点播源 / 直播源」四行，均为 `本地 N · 云端 M`，数值右对齐 |
| 云端数量 | 追更、点播源、直播源来自最近一次备份快照的数量并缓存到 `Prefers`（`cloud_keep_count`/`cloud_track_count`/`cloud_vod_count`/`cloud_live_count`）；播放历史云端数量取最近一次同步时的本地条数（`cloud_history_count`） |
| 云端公共配置 | 勾选点播源/直播源/设置项 →「从云端获取公共配置」（增量/覆盖，可勾选记住选择）或「备份本地配置到云端」（先弹覆盖确认）。覆盖只更新同名配置，不删本地源，避免级联删除追更/历史 |
| 立即同步 | 配置备份开启且未记住策略时先弹覆盖确认；同步完成后刷新统计并 Toast 反馈 |
| 注销账号 | 二次确认 → 输入密码 → 云端永久删除；本地记录保留 |
| 底部按钮 | 「关闭 / 完成」已删除，点遮罩或返回键关闭 |
| 管理员入口 | 连点标题 7 次 + 口令 `webhtv`，解锁后多出「服务配置 / 发布配置 / 后端服务登录 / 更新设置」 |

相关文件：`setting/CloudConfigSync.java`（公共/个人配置收集与应用）、`setting/CloudSyncStore.java`（开关与云端数量缓存）、`setting/AccountSync.java`（登录同步策略与立即同步）、`playback/KeepCloudBackup.java`（追更快照）、`ui/dialog/AccountDialog.java`、`dialog_account.xml`。

### 电视端兼容

- `HistoryPresenter` + `leanback/res/layout/adapter_vod.xml` 与手机一致：已看行显示「已看 第X集 · 具体时间」，另起一行「更新至 第X集」。
- 遥控操作：长按进入删除模式，删除模式下**再次长按**即全选清空（追更在 `KeepActivity`、历史在 `HomeActivity`），都会先弹确认。
- 手机与电视仍是两个 APK（同 `applicationId`、同签名、数据互通）。两个 source set 各有一份 `HomeActivity`，直接合并会类冲突，单包方案需重命名 Activity 并新增按 UiMode 分发的启动页，暂不做。

### 真机验证（2026-10-28，Lenovo TB331FC / Android 13）

- 个人中心：统计为「追更 本地1·云端1 / 播放历史 本地37·云端37 / 点播源 本地3·云端3 / 直播源 本地1·云端1」，无底部按钮；冲突选择弹窗、注销二次确认链、立即同步覆盖提示与 Toast 均正常。
- 线上注销：临时账号走完「注册 → 备份 → 注销 → 旧 token 401 → 同用户名可重新注册」，临时账号已清理。
- 线上公共配置：管理员 `put` → 用户 token `get` 返回 content → `delete` 后 `get` 404。
