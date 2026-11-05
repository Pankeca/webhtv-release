# P0 服务端容错修复部署记录（2026-11-04）

## 部署信息

- 项目：`webhtv-sync`（Project ID：`makers-hlaoldkoeyli`）
- 环境：Production（global area）
- Deployment ID：`dp5onwn5dpq6`
- Deploy URL：`https://webhtv-sync.edgeone.dev`
- Console：`https://console.tencentcloud.com/edgeone/pages/project/makers-hlaoldkoeyli/deployment/dp5onwn5dpq6`
- 变更文件：`cloud/edgeone/edge-functions/api/[[default]].js`
- 变更内容：`pullChanges` 对 `kv.get(key)` 逐 key `try/catch`，单条 blob 读取失败（COS 502 等）跳过并记录；响应新增 `skipped` / `warnings` 可选字段。未删除任何 blob。

## 备份与校验

本目录下保存了部署前后的产物与线上响应：

| 文件 | 说明 |
|---|---|
| `[[default]].js.before` / `.after` | 部署前 / 后函数源码 |
| `project.json.before` / `.after` | EdgeOne 项目配置 |
| `package.json.before` | 部署时包配置 |
| `legacy-sync.before.json` / `.after.json` | 旧分区 sync 响应（部署前 500，部署后 200 + skipped） |
| `account-sync.before.json` / `.after.json` | 账号分区 sync 响应 |
| `account-page5.after.json` / `account-page2.after.json` | 分页验证响应 |
| `public_config.before/after.json`、`subscriptions.before/after.json`、`health.before/after.json` | 线上数据基线 |
| `SHA256SUMS.txt` | 以上全部文件的 SHA256 |

部署前源码 SHA256：`3ff873aac08605d03f1e2d12a8eb5be3bba92753d7ff40a1b045ecafbc38c5fe`
部署后源码 SHA256：`9fb9d4c00df7cfca95bd4aac8196b07e54894bce0a10740386fb9a4161d0edc2`

`public_config.before/after.json` 与 `subscriptions.before/after.json` 哈希一致，内容未被本次部署修改；`health` 仅 `time` 字段变化。

## 回滚命令

```bash
cd /media/s/7A3A1DFD3A1DB6DB/REA/cloud/edgeone
cp ../../docs/p0-backups/2026-11-04/'[[default]].js.before' 'edge-functions/api/[[default]].js'
~/.bun/bin/edgeone makers deploy
```

回滚后会产生新的 Deployment ID；也可在 Console 对 `dp5onwn5dpq6` 的上一版本执行回滚。

## 部署后验证结果

| 项 | 结果 |
|---|---|
| 旧分区 `playback/sync` | `200`，`skipped=27`、`warnings` 前 5 条；`changes=19`、`deleted=3` |
| 账号分区 `playback/sync` | `200`，`skipped=0`、`changes=27`、`deleted=0` |
| 增量游标 | `nextSince` 随记录推进，正常 |
| 分页 | `limit=5` 第一页 5 条、`nextSince` 前进；第二页再 5 条，无重复 |
| `public_config` | 哈希不变 |
| `subscriptions.json` | 哈希不变 |
| `health` | `ok:true`（仅 time 变化） |

旧分区共 27 条 blob 返回 COS 502，按“跳过不删”处理；客户端迁移读取因此可以完成（详见 `P0账号级数据合并规格.md` 第 5 节）。
