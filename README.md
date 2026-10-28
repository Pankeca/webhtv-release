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

Release 包不配置签名时会自动用 debug 签名。正式签名的口令**只允许**放在本机 `local.properties` 或环境变量里，仓库内不保存任何明文：

```properties
storeFile=/home/s/xxx.jks
keyAlias=xxx
storePassword=xxx
keyPassword=xxx
```

`app/build.gradle` 的读取优先级：`local.properties` → 环境变量（`WEBHTV_STORE_FILE` / `WEBHTV_KEY_ALIAS` / `WEBHTV_STORE_PASSWORD` / `WEBHTV_KEY_PASSWORD`）。

本机已生成的发布签名（口令不入库，构建前 source 一次）：

```bash
set -a; . /home/s/Android/secrets/webhtv-release.env; set +a
export JAVA_HOME=/home/s/Android/jdk21
```

| 项 | 值 |
| --- | --- |
| Keystore | `/home/s/Android/secrets/webhtv-release.jks`（0600） |
| 口令文件 | `/home/s/Android/secrets/webhtv-release.env`（0600，`WEBHTV_*` 四个变量） |
| Alias | `webhtv` |
| 证书 | `CN=WebHTV, OU=Release, O=WebHTV, L=Beijing, ST=Beijing, C=CN`，RSA 2048，10000 天 |
| 证书 SHA-256 | `5c3b6ccce5f7f1418599de9b85dd91a4b5839b38fe5f30a43ef17ad1cf7fb7f7` |

发布 Token（GitHub / CNB）只在当前终端会话里用环境变量传入，**不要写入任何文件或提交**：

```bash
export GH_TOKEN=...     # GitHub PAT（repo 权限）
export CNB_TOKEN=...    # CNB 令牌 PC-push
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

## 八、交互与 UI 优化（2026-10-28 第二轮）

| # | 需求 | 实现 |
| --- | --- | --- |
| 1 | 个人中心界面美化 | `AccountDialog` 重排为：用户卡 → 「数据概览」2×2 宫格（追更/历史/点播源/直播源，`本地 N · 云端 M`）→ 二级菜单入口卡 → 「账号安全」卡 |
| 2 | 云端同步/公共配置改二级菜单 | 新增 `entryRow()`（标题 + 摘要 + 说明 + 右箭头），点击弹 `showOptionsDialog()`；摘要见 `account_entry_sync_state` / `account_entry_config_state` |
| 3 | 删除「我的追更」后的「追踪」 | `account_keep_desc_synced` 去掉追踪参数，改为「云端已备份 · 追更 %1$d · %2$s」，三语言同步 |
| 4 | 追更单击直接追更、双击设置 | `VideoActivity.onTrackTap()` 用 `SystemClock` 做 350ms 判定：单击 `onUpdateTrackQuick()`（建 Keep + `UpdateTrack.DAYS_EVERY` + `markSeen`，Toast `track_on`），双击走 `ScheduleDialog` |
| 5 | 追更/历史全选位置与语义 | 菜单顺序改为 `select_all → sync → delete`，全选用 `action_select_all.xml` 的 `CheckBox` actionView；`KeepAdapter`/`HistoryAdapter` 新增 `selected` 集合与 `toggleSelection/setAllSelected/deleteSelected`，删除确认文案 `dialog_delete_selected`（未勾选提示 `select_required`） |
| 6 | 下载中心 Tab 固定 + 底栏按钮重排 | Tab 改为自定义视图（label 15sp + count 13sp，count 固定 `minWidth 26dp`），数量变化不再推动文字；`layoutBottomBar()` 按当前 Tab 把 `selectAll` 插到「全部开始」右侧（下载中）或「删除」左侧（已下载） |
| 7 | 增强功能顶部图标默认隐藏 | `SettingEnhanceFragment` / `SettingEnhanceActivity` 每次进入先 `putEnhanceRepoVisible(false)`，长按标题临时解锁，离开即恢复隐藏 |
| 8 | 主题壁纸重置 + 护眼纯色皮肤 | `ThemeDialog` 重写：主题色板 + 「护眼纯色皮肤」6 色（炭黑/深海蓝/墨绿/暗紫/暖棕/青灰，`Setting.WALL_SOLID_*` 37–42）+ 标题行「重置」；选纯色会同时写 `wall/wallType/wallColor` 并 `ConfigEvent.wall()` |
| 9 | 检查更新直接下载并显示进度 | `Updater.downloadStable(activity, DownloadListener)` 绕过 `UpdateDialog` 直接下稳定版；`dialog_about.xml` 内联 `LinearProgressIndicator + updateStatus`，显示百分比与文件大小；失败仅隐藏进度条并恢复更新提示 |
| 10 | 最近观看「更新至第几集」 | `HistoryAdapter` 先用 `UpdateTrack.find(key).getCount()`，为 0 时回退解析 `vodRemarks` 里最后一个数字（`parseEpisode`），未追更也能显示 |

其他修复：

- 新增更新相关字符串：`update_downloading_start` / `update_downloading_percent` / `update_downloading_progress`（避免与既有 `update_downloading` 冲突）。
- `UpdateTracker` 首轮检查由「等 6 小时」改为「启动 30 秒后先跑一轮」，再按 6 小时间隔调度，避免新追更长时间不更新。
- 个人中心「账号安全」恢复「修改密码 / 退出登录 / 注销账号」三按钮（首轮改版时退出登录被误删，本轮补回）。

真机验证（同为 Lenovo TB331FC）：登录态个人中心宫格与两个二级菜单、追更单击/双击、全选打钩与「确定删除选中的 N 项？」、下载中心 Tab 不位移与底栏按钮位置、增强功能图标默认隐藏、护眼纯色皮肤与重置、最近观看集数显示均已截图确认。

## 九、交互与 UI 优化（2026-10-28 第三轮）

| # | 需求 | 实现 |
| --- | --- | --- |
| 1 | 云端同步、云端公共配置同一行 | `AccountDialog.entryRow(View, View)` 左右两列各 `weight=1`、间距 8dp，入口改为竖排小卡（14sp 标题 + 11sp 摘要 + 右上「›」） |
| 2 | 历史播放记录数量不准 | `AccountSync.localHistoryCount()` 直接返回 `History.get().size()`，与「最近观看」列表口径完全一致（当前配置 + 保留期） |
| 3 | 取消全选后残留勾选圈 / 恢复删除图标直删 | `KeepAdapter`/`HistoryAdapter`：删除模式未勾选显示垃圾桶 `ic_vod_delete`、勾选显示红底白勾 `ic_vod_check_on`，取消全选即回到垃圾桶；新增 `onItemDelete()` + `deleteItem()`，删除模式下点海报垃圾桶直接删该条 |
| 4 | 追更/历史新增「更新」按钮 | `menu_keep`/`menu_history` 在 `sync` 前插入 `update`（刷新图标）；`UpdateTracker.checkKeys()` 拉详情后 `countEpisodes()` 更新 `count/seen`，Toast 汇总 `全部已是最新（共 N 部）` 或 `N 部有更新：《名》更新至第 N 集` |
| 5 | 下载中心页签切换位移 | `activity_download_center.xml` 去掉 `TabLayout`，改用等宽两列 `LinearLayoutCompat#tabsBar`；`expandAll` 空态改 `INVISIBLE` 并 `minWidth=120dp` 占位，页签宽度不再被挤动 |
| 6 | 底栏「全选」位置 | `layoutBottomBar()` 把 `selectAll` 插到「全部开始」（下载中）或「删除」（已下载）**之前**，间距统一 10dp |
| 7 | 纯色皮肤参考 tvbox 1.1.17 | `Setting.WALL_SOLID_*` 扩到 21 色（37–57，取自 tvbox 皮肤中心：经典黑/星河/流霞/烟墨/玄墨/墨影/藕荷/紫宸/紫烟/天青/沧溟/云水/竹青/松墨/青岚/藕粉/绛桃/桃夭/素灰/黛灰/烟灰），`ThemeDialog` 由 `getSolidWalls()` 动态生成取色格 |
| 8 | 更新报 `Download failed: HTTP 413` | `UpdateRoutePlanner.addGithub()` 除当前代理外再追加 `ghfast / gh_acmsz / github_chenc / gh_monlor` 四条公共加速线路，某条被拒后可自动回退重试 |
| 9 | 追更按钮单击切换、双击进日程 | `VideoActivity.onTrackTap()` 改为 260ms 延迟判定：单击 `onUpdateTrackQuick()` 有 track 则 `UpdateTrack.remove` + 删 Keep（`track_off`），无则建 Keep 并 `DAYS_EVERY`（`track_on`）；双击 `onUpdateTrackSettings()` 先确保已追更再弹 `ScheduleDialog`，直接取消也保持追更 |

关键缺陷修复：

- `UpdateTrack.remove(String key)` 原先「先 `find()` 拿 A 列表里的对象，再 `list()` 从 B 列表里 `remove()`」，两批对象引用不同，删除永远失败 → 取消追更后按钮仍显示「已追更」、追更条目删不掉。改为 `items.removeIf(it -> TextUtils.equals(it.key, key))`。
- `ExpandAll` 在「已下载」页由 `GONE→VISIBLE` 会改变 `tabsBar` 可用宽度，是页签位移的真正根因（上一轮只处理了文字宽度变化）。

真机验证（Lenovo TB331FC，`com.fongmi.android.tv.dev`）：个人中心两入口同行且「播放历史 本地 2」与最近观看 2 条一致；追更页删除图标/全选/取消全选无残留圆圈、更新提示「全部已是最新（共 4 部）」；最近观看更新提示「全部已是最新（共 2 部）」；下载中/已下载页签 `uiautomator` 坐标完全一致（`下载中` 文本均 `[385,174][451,203]`），底栏「全选 全部开始 全部暂停 删除」；追更按钮单击「已追更→追更→已追更」、双击弹出「追更设置」且取消后仍为已追更。

## 十、换站体验、个人中心一屏化与更新 413 修复（2026-10-28 第四轮）

| # | 需求 | 实现 |
| --- | --- | --- |
| 1 | 点某些站点变成整页刷新 / 切换迟钝 | 站点胶囊改为独立回调 `onSiteClick()`，一定是同片换源，走 `getDetail(item, true)` 局部刷新（不再按片名比较，站点命名差异也不会误判成「另一部影片」）；点击瞬间 `mSiteAdapter.setSelectedKey()` 先高亮，请求期间线路/选集 `setSwitchingVisual(true)` 半透明提示，返回后恢复 |
| 2 | 个人中心一屏展示所有一级菜单 | 头像 + 四项统计压成一张 `heroCard()`（统计单行 4 列 `本地/云端`），云端同步 / 公共配置改为图标 + 标题 + 状态角标卡（角标为圆角 pill，浅蓝/浅绿），安全设置由整宽按钮改为 36dp 点击行 + 箭头，一屏即可看全 |
| 3 | 同步类操作要有反馈 | `立即同步` 完成提示按已开启项汇总「同步完成：追更 N 条 · 历史 N 条 · 影视 X 项 / 直播 Y 项」；`从云端获取公共配置` / `备份本地配置到云端` 先提示「正在…」，完成后返回明细（点播源 x 项、直播源 y 项、设置项 z 项） |
| 4 | 去掉追更/最近观看的更新按钮 | 删除 `menu_keep`/`menu_history` 的 `update` 项、`KeepActivity/HistoryActivity.onUpdate()`、`UpdateTracker.checkKeys()/CheckCallback` 及 6 条专用字符串，工具栏回到「全选 · 同步 · 删除」 |
| 5 | 单击追更补充双击说明 | `track_on` 三语文案改为「已开启追更 · 双击追更按钮可设置追更日程」 |
| 6 | 关于-检查更新后点更新报 HTTP 413 | 见下节：CNB 直链大小限制导致，新增仓库 zip 归档兜底 |

### 关于「检查更新」413 的真实根因与修复

- 自定义更新地址为 CNB 仓库直链 `https://cnb.cool/mytools-2026/app-release/-/git/raw/main/app.apk`，安装包 160,456,096 字节（153 MiB）。
- CNB 对 raw 直链硬性限制 100 MiB，超过直接返回 `413`，body 为 `{"errcode":2000033,"errmsg":"raw file size 153 MiB exceeded 100 MiB，please pull via git client"}`，与网络环境无关，Range 分段请求同样 413。
- 修复：新增 `CnbArchiveTransfer`，当 HTTP 直链返回 413 时自动改下同仓库 `/-/git/archive/{ref}.zip`（实测 90 MB、4.8 s），流式解包出 `app.apk` 并按清单 `size` 校验；`UpdateTarget.Kind.ARCHIVE` + `UpdateRoutePlanner.addCnbArchive()/planForApk()` 生成「直链 → 归档」两条路由，`Updater.createTransfer()` 按类型分流。
- 改动范围仅限更新下载路由（`update/` 包与 `Updater`），未触碰站源/线路更新与站点配置逻辑。
- 真机验证：日志 `route 1/2 kind=GITHUB host=cnb.cool` → `413` → `route 2/2 kind=ARCHIVE host=cnb.cool` → 约 13 s 后弹出系统安装器「影视(5.6.0) 将替换当前版本」（验证后已拒绝安装，避免覆盖调试包）。

## 十一、追更入口回退、详情页控件重排与更新源分端（2026-10-28 第五轮，v5.6.1）

> 本节回退第十节第 4 项：**保留**「检查剧集更新」按钮，删除的是工具栏原有的「同步」按钮。

| # | 需求 | 实现 |
| --- | --- | --- |
| 1 | 恢复检查更新、删除自带的同步按钮 | `menu_keep`/`menu_history` 改回 `update`（`ic_action_refresh`）并移除 `sync`；`KeepActivity/HistoryActivity` 删除 `onSync()` 与 `SyncDialog` 依赖，恢复 `onUpdate()` → `UpdateTracker.checkKeys()`，提示「正在检查新剧集…」→「N 部有更新：…」/「全部已是最新」 |
| 2 | 详情页选集行重排 + 开关美化 | 四个布局变体（`layout`、`layout-land`、`layout-sw600dp`、`layout-sw600dp-land`）统一为「选集 → 倒序图标 → 本地优先 → 开关」；`SwitchCompat` 加 `switchMinWidth=44dp` 与 `switch_track_tint`（开 #1A73E8 / 关 #9AA0A6）、`switch_thumb_tint`（白色），单色高对比，不用花花绿绿 |
| 3 | 追更长按进「我的追更」、提示单行 | `VideoActivity` 给追更按钮加 `setOnLongClickListener` → `KeepActivity.start()`，并清掉单击延迟任务（与长按下载按钮进下载中心一致）；`track_on` 缩短为「已开启追更 · 双击可设日程」，系统 Toast 保证单行 |
| 4 | 升版本并区分更新源 | `versionCode 561 / versionName 5.6.1`；`BuildConfig.UPDATE_MANIFEST_BASE` + `getDefaultManifestUrl()` 按 `mobile/leanback` × ABI 读取 `update-mobile-arm64_v8a.json`、`update-leanback-armeabi_v7a.json` 等，手机端与电视端互不串包；manifest 的 `apk` 若为完整 https 地址，直接走 `planForApk()`（含 CNB 归档兜底） |
| 5 | 个人中心视觉再调整 | 弹窗底色改 `shape_account_dialog`（#F5F7FB）；hero 卡白→浅蓝渐变加 1dp 描边；统计拆为「本地 N / 云端 N / 名称」三行；安全行 `linkRow()` 文字居中 |

## 十二、播放器换源与全屏返回（2026-10-28 第六轮）

| # | 需求 | 实现 |
| --- | --- | --- |
| 1 | 全屏「换源」点击无反应 | 根因：`checkSearch(true)` 命中手动站点锁定 `mManualSite` 直接 return；且搜索返回后强制切换仍受「自动换源」开关限制。现改为用户主动换源时解除锁定，并置 `forceNextSource`，搜索返回后无论开关状态都执行 `nextSite()`；无其他可用站点时 Toast「没有找到其他可用站点」，点击瞬间 Toast「正在搜索其他可用站点…」 |
| 2 | 全屏界面右侧增加返回图标 | `view_control_right.xml` 新增 `back`（`ic_control_back`），仅全屏且未锁定时显示，点击 `onBack()` 退出全屏回到详情页 |
| 3 | 删除详情页小窗口右上角收藏图标 | 移除 `view_control_vod.xml` 的 `keep` 星标及 `control.keep` 绑定/显隐/图标刷新，音频页 `audioKeepAction` 与「更多」菜单的收藏入口保留 |

## 十三、发布 5.6.2（2026-10-28 第七轮）

### 版本与产物

`versionCode 562 / versionName 5.6.2`，四种组合全部签名发布：

| 产物 | 大小 | SHA-256（前 8 位） |
| --- | --- | --- |
| `mobile-arm64_v8a.apk` | 139,225,565 | `138f55b4` |
| `mobile-armeabi_v7a.apk` | 117,057,109 | `5e66b7a0` |
| `leanback-arm64_v8a.apk` | 139,289,351 | `f8f6c25e` |
| `leanback-armeabi_v7a.apk` | 117,120,895 | `deddf6a6` |

构建命令（每个变体约 8 分钟，建议逐个执行避免同终端互相打断）：

```bash
cd /home/s/webtv && set -a && . /home/s/Android/secrets/webhtv-release.env && set +a
export JAVA_HOME=/home/s/Android/jdk21
./gradlew :app:assembleMobileArm64_v8aRelease --no-daemon
./gradlew :app:assembleMobileArmeabi_v7aRelease --no-daemon
./gradlew :app:assembleLeanbackArm64_v8aRelease --no-daemon
./gradlew :app:assembleLeanbackArmeabi_v7aRelease --no-daemon
```

### 发布位置

| 用途 | 地址 |
| --- | --- |
| GitHub 发布仓库 | `https://github.com/Pankeca/webhtv-release` |
| GitHub Release v5.6.2 | `https://github.com/Pankeca/webhtv-release/releases/tag/v5.6.2` |
| 更新清单（CNB，客户端读取） | `https://cnb.cool/mytools-2026/app-release/-/git/raw/main/<端名>-<ABI>.json` |

- 4 个 APK 作为 Release assets 上传，清单里的 `apk` 指向 GitHub 资源直链；`UpdateRoutePlanner` 会追加 `ghfast` 等公共加速线路兜底。
- CNB `app-release` 仓库只提交 4 个 JSON 清单（`mobile-arm64_v8a.json` 等），不重复存大文件；清单含 `name/versionName/code/apk/size/sha256/notes`。
- GitHub 上传（REST，`uploads.github.com` 实测 139 MB / 25 s）：

```bash
curl -X POST -H "Authorization: Bearer $GH_TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@app/build/outputs/apk/leanbackArm64_v8a/release/leanback-arm64_v8a.apk" \
  "https://uploads.github.com/repos/Pankeca/webhtv-release/releases/<releaseId>/assets?name=leanback-arm64_v8a.apk"
```

### 本轮代码改动

| # | 需求 | 实现 |
| --- | --- | --- |
| 1 | 个人中心概览统计四列三行 | `AccountDialog` 统计区改为浅蓝圆角面板（`#F4F8FF` 底 + `#E3EDFB` 描边 + 12dp 圆角 + 8dp 内边距），四列之间 1dp 分隔线；每列自上而下「名称（11sp 加粗 #202124）/ 本地 N（#1A73E8）/ 云端 N（#137333）」居中 |
| 2 | 最近观看「更新」数量不正确 | 新增 `HistoryEpisodeCache` 记录未追更影片的最新集数；`UpdateTracker.checkHistory(List<Target>)` 遍历**全部**可见历史记录（不再只查追更项），有追更则同步 `UpdateTrack.count`；提示总数按实际检查条数统计 |
| 3 | 已看集数 / 更新至 标签显示策略 | `HistoryAdapter` 取 `max(追更集数, 本地缓存, 备注解析)`；已看集数 ≥ 最新集数时隐藏「更新至」，避免两个标签矛盾；`HistoryProgressFormatter` 统一「已看 第X集 · 时间」 |
| 4 | 标签视觉 | `shape_vod_watched.xml`（`#332D7DFF` 蓝）/ `shape_vod_update.xml`（`#33FF9F43` 橙）6dp 圆角；10sp 文字 `#A8CBFF` / `#FFD59A`，4×1dp 内边距 |
| 5 | 内置默认弹幕地址 | `BuildConfig.DANMAKU_API_URL`（`-PwebhtvDanmakuUrl` / `WEBHTV_DANMAKU_URL` 覆盖），`DanmakuSetting.getEffectiveApiUrl()` 优先级：用户设置 → 配置 JSON `danmaku` → 内置默认 |

### 弹幕服务（EdgeOne Makers，已上线）

用 `https://github.com/Veneno515/danmu_api` 部署在 EdgeOne Makers：

| 项 | 值 |
| --- | --- |
| 线上地址（已内置进 5.6.3） | `https://danmu.940214.xyz` |
| 接口 | `POST /danmaku`，body `{"name":"...","episode":"1"}`，返回 `[{name,url}]` |
| EdgeOne 项目 | `webhtv-danmu2`，ProjectId `makers-ogmxyjalspdn`，海外区（不含中国大陆） |
| 默认域名 | `https://webhtv-danmu2.edgeone.dev`（有登录墙 401，**不能**直接给 App 用） |
| 自定义域名 | `danmu.940214.xyz`，CNAME → `danmu.940214.xyz.pages.dnsoe8.com`（Cloudflare，灰云不代理） |
| 免费证书 | EdgeOne 控制台域名管理的 HTTPS 配置里申请，签发后 HTTPS 正常 |
| 源码 | `/home/s/webtv/.build/danmu/Veneno515-danmu_api-fbd4b19` |

部署步骤（CLI，需已 `edgeone` 登录）：

```bash
cp -a <danmu_api 源码> /tmp/danmu_deploy
cd /tmp/danmu_deploy
# 上游文件名有误：node-functions/[[...path]]..js → 改成 [[...path]].js
# edgeone.json 删掉 rewrites（校验不通过）
edgeone validate
edgeone makers deploy . -n webhtv-danmu2 -a overseas
```

### 踩坑记录（EdgeOne）

- 新建项目部署报 `FailedOperation / teo.CreatePagesResources`：先是平台瞬时故障，**重试即可**，与控制台报错一致，不是配置问题。
- EdgeOne Pages 默认域名（`*.edgeone.cool` / `*.edgeone.dev`，包括 `webhtv-sync.edgeone.dev`）都有登录墙，必须绑自定义域名才能公网访问；全球区项目的「添加自定义域名」按钮是灰的，海外区可用。
- 国外域名的 DNS 在 Cloudflare：只加 CNAME（灰云、不代理），TXT 归属权验证通过后才签发免费证书。
- `wrangler` 未登录且无 Cloudflare Token，故未走 Cloudflare Workers 方案。

## 十四、5.6.3 发布与换机交接（2026-10-28）

### 版本

`versionCode 563 / versionName 5.6.3`，相对 5.6.2 只增加了内置默认弹幕地址：

```groovy
buildConfigField "String", "DANMAKU_API_URL", "\"... ?: 'https://danmu.940214.xyz'\""
```

### 构建进度（换机前）

| 变体 | 状态 |
| --- | --- |
| `mobile-arm64_v8a` | 已完成，已装到 TB331FC 平板实机运行（5.6.3 正常拉起） |
| `mobile-armeabi_v7a` | 已完成 |
| `leanback-arm64_v8a` | 未开始 |
| `leanback-armeabi_v7a` | 未开始 |

`versionCode` 已改成 563 但 **5.6.2 已发布过 562**，5.6.3 尚未上传 GitHub / 更新 CNB 清单，换机后按第十三节的发布流程补完即可：

```bash
cd <项目根> && set -a && . secrets/webhtv-release.env && set +a
export JAVA_HOME=<jdk21 路径>
./gradlew :app:assembleLeanbackArm64_v8aRelease --no-daemon
./gradlew :app:assembleLeanbackArmeabi_v7aRelease --no-daemon
```

发布位置沿用：GitHub `Pankeca/webhtv-release`（Release v5.6.3 + 4 个 APK 资源），CNB `mytools-2026/app-release` 的 4 个 `*.json` 清单。

### 换机必读

1. **签名证书必须一起带走**，否则新包无法覆盖安装（签名不同）：`secrets/webhtv-release.jks` + `secrets/webhtv-release.env`，已随项目复制到 U 盘 `WebTV/secrets/`。口令只在那份 env 文件里，仓库内没有。
2. 构建前先 `source secrets/webhtv-release.env`，否则会退化成 debug 签名。
3. 证书指纹（SHA-256）：`5c3b6ccce5f7f1418599de9b85dd91a4b5839b38fe5f30a43ef17ad1cf7fb7f7`，新机器可用 `apksigner verify --print-certs` 校验。
4. 已排除 `app/build`、`.gradle`、`.git`，到新机器首次编译会重新下载/生成，属正常现象。

## 十五、编译资源优化与在线构建（2026-10-28）

本机（4 核 / 7.6G 内存 / 2G swap，swap 已用到 1.3G）编一个变体就会卡死，原因是原来的 `-Xmx4g` + `parallel=true` 同时开多个编译进程，内存被挤到 swap。已改成：

```properties
org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
org.gradle.caching=true
org.gradle.parallel=false
org.gradle.workers.max=2
```

实测 `compileMobileArm64_v8aDebugJavaWithJavac` 42 秒、峰值约 1G，不再抖。改完仍需注意：

- **一次只编一个变体**，不要同时跑两个 Gradle；每次编完等 CPU 落下再开下一个。
- 加 swap 能显著减少卡死（需要 sudo）：`sudo fallocate -l 8G /swapfile2 && sudo chmod 600 /swapfile2 && sudo mkswap /swapfile2 && sudo swapon /swapfile2`
- 内存紧张时关掉浏览器/Android Studio，`free -h` 里 `available` 低于 1.5G 就别开新编译。
- `--no-daemon` 每次重新起 JVM，慢但不会残留占内存；本机保持这种用法即可。

### GitHub Actions 在线构建

已加 [.github/workflows/build-release.yml](.github/workflows/build-release.yml)：

- 触发：手动（Actions → Build Release APK → Run workflow）或推送 `v*` 标签
- 4 个变体并行矩阵构建，公开仓是 4 核 / 16G，速度和内存都比本机好
- 需要的仓库 Secrets：

| Secret | 内容 |
| --- | --- |
| `WEBHTV_KEYSTORE_BASE64` | `base64 -w0 secrets/webhtv-release.jks` 的输出 |
| `WEBHTV_KEY_ALIAS` | `webhtv` |
| `WEBHTV_STORE_PASSWORD` | JKS 口令（见 `secrets/webhtv-release.env`） |
| `WEBHTV_KEY_PASSWORD` | 同上 |

- 缺 `WEBHTV_KEYSTORE_BASE64` 时会自动退化成 debug 签名，不会因为没配密钥失败
- 产物在 Actions 的 Artifacts（`apk-<变体>`，保留 14 天）；推 `v*` 标签时自动创建 Release 并附 4 个 APK

前提是 GitHub 上要有**源码仓库**（当前 `Pankeca/webhtv-release` 只有 Release 资源），换机后把源码推上去，再在 Settings → Secrets and variables → Actions 里填上面 4 项。



## 十六、5.6.4 手机真机适配与云端业务重做（2026-10-29）

5.6.2 的 9 个问题集中在「小屏真机」上：开发者用的 adb 平板是 800dp 宽、1920 高，所有布局都能一屏显示，看不出问题；真机竖屏约 360×640dp，横屏约 640×360dp，大量固定尺寸控件叠加后就会溢出。本版按小屏尺寸重做适配。

### 1. 详情页（竖屏）

- 站点胶囊在竖屏改为**单行左右滑动**（`FlexboxLayoutManager` + `FlexWrap.NOWRAP`），横屏仍多行；旋转时同步换行策略。
- flexbox 在 nowrap 下默认 `flexShrink=1`，会把胶囊压成一条条竖排文字；[SiteChipAdapter](app/src/mobile/java/com/fongmi/android/tv/ui/adapter/SiteChipAdapter.java) 里给每个胶囊 `setFlexShrink(0)` 并保持 `WRAP_CONTENT`，超出部分交给 RecyclerView 横向滚动。
- **竖向无法滚动**的真凶是 [FixedNestedScrollView](app/src/mobile/java/com/fongmi/android/tv/ui/custom/FixedNestedScrollView.java) 里 `onInterceptTouchEvent/onTouchEvent` 直接返回 false、`scrollTo` 强制 y=0、`fling` 置空（为电视遥控器写的），手机上手指根本滚不动，线路和选集自然「消失」。手机端改为标准 `NestedScrollView`。
- 同时把滚动内容根布局由 `match_parent` 改为 `wrap_content`，内容高于一屏时才有滚动余量。

### 2. 首页顶栏

- `menu_vod.xml` 中刷新/追更/历史/下载由 `always` 改为 `ifRoom`：平板全部平铺，窄屏自动收进「⋮」溢出菜单，不再挤掉标题和账号图标。
- 窄屏（`screenWidthDp < 600`）搜索框由 150dp 收窄到 104dp。
- 手机横屏（`screenHeightDp < 480`）顶栏压到 44dp、分类行底部留白减半。

### 3. 下载中心

- 底栏三个按钮 `minWidth` 由 92dp 改为 0、字号 14sp→13sp、内边距收窄，360dp 竖屏可完整放下。
- 横屏矮屏下顶栏 44dp、页签 40dp、总览行收紧，列表可见高度显著增加。

### 4. 个人中心弹窗

- 一级弹窗滚动区高度按屏高动态计算（竖屏 66%、横屏 54%，并预留标题/按钮高度），不再被系统栏裁掉。
- 二级菜单（云端同步、公共配置）统一放进 `CustomNestedScrollView`（竖屏 62%、横屏 52% 屏高），横屏条目多时也能滑动查看。

### 5. 云端公共配置（业务重做）

- **服务端** `cloud/edgeone`：`/api/public/get`、`/api/public/list` 改为匿名可读（此前要求登录，未登录永远「公共资源不存在」）。已重新部署到 EdgeOne（项目 `webhtv-sync`）。
- 已发布基线 `public_config`：
  - 点播源：纯净版（34 站点）、4K（10 站点）、10 线路（10 站点），均指向 CNB raw JSON；
  - 设置项：弹幕 load/auto/show 默认开启；
  - 直播源暂留空，后续可在 App 内「云同步 → 公共配置」由管理员覆盖发布。
- **业务拆分**：个人中心「云端同步」只负责个人数据备份/恢复（追更、历史、配置项，需登录）；「云配置」只负责拉取公共配置，菜单里原来的「备份本地配置到云端」已删除，避免两套逻辑混淆。
- `pullPublic` 不再要求登录；新装首次启动（无任何点播源）自动拉取一次公共配置并选中第一个点播源（标记 `public_config_bootstrapped`，失败下次启动重试）。

### 6. 弹幕默认配置

- `danmaku_load` / `danmaku_auto` / `danmaku_show` 默认值改为 `true`，并写入公共配置设置项，新装开箱即用。

### 7. 检查更新下载超时

- `UpdateRoutePlanner.planForApk`：GitHub 直链除 CNB zip 归档兜底外，再补 4 条公共加速线路，直连超时后会自动切换。
- 发布时必须把 `mobile-*.json` / `leanback-*.json` 里的 `apk` 改为 CNB 地址（GitHub Release 在部分网络下下载 130MB+ 会直接 timeout），本版更新清单按此生成。

### 真机/小屏验证方法

平板可以用 adb 模拟小屏，不必另找手机：

```bash
adb shell wm size 1080x1920
adb shell wm density 480      # 逻辑 360x640dp，竖屏手机
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
# 恢复
adb shell wm size reset
adb shell wm density reset
```

已用该方法确认（5.6.4 release 包，模拟 360x640dp）：

- 竖屏首页顶栏完整（标题+搜索+账号+⋮），溢出菜单含刷新/追更/历史/下载；
- 横屏首页顶栏压缩为单行，不再占半屏；
- 竖屏详情页站点胶囊单行横滑，上滑可完整滚动到选集最后一行；
- 竖屏下载中心底栏四个按钮完整显示；横屏顶栏压缩，内容与底栏均在首屏内；
- 竖屏个人中心一级菜单一屏完整；横屏一级菜单可滚动到底，二级菜单可滚动且按钮完整可见；
- 公共配置拉取实测：增量合并后点播源 2 → 4，不再提示「暂无公共配置」。

修正：一级菜单「云端公共配置」副标题由「自动备份：开」改为「从云端获取公共配置」；公共配置页说明改为仅拉取、不上传不删除本地数据。

### 构建记录

- GitHub Actions 运行 `40758229210`：四端全部 success（约 12 分钟）。文案修正后已提交并触发新一轮构建。

