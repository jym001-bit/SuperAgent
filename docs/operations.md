# 运维手册：指标、审计、成本与生命周期

面向部署方和值班人员。所有接口都只监听 `127.0.0.1`，对外暴露由反向代理决定。

## 健康检查

| 路径 | 用途 | 说明 |
|---|---|---|
| `GET /healthz` | 进程存活 | 不访问数据库 |
| `GET /readyz` | 可以接单 | 会真的查询状态库，失败返回 5xx |

编排系统用 `/healthz` 做存活探针，用 `/readyz` 做就绪探针。`/readyz` 通过之前不要接流量。

## 指标

`GET /metrics` 输出 Prometheus 文本格式（`text/plain; version=0.0.4`）。不需要运维令牌，因为它只暴露聚合计数，不含任务内容；如果部署环境要求一致鉴权，在反向代理层加即可。

| 指标 | 类型 | 含义 |
|---|---|---|
| `superagent_tasks{status="..."}` | gauge | 按状态的任务数 |
| `superagent_tasks_total` | gauge | 任务总数 |
| `superagent_queue_depth` | gauge | 排队中的任务数，持续增长说明 Worker 不够或任务卡住 |
| `superagent_waiting_approvals` | gauge | 等待命令审批的任务数，长期不降说明没人处理审批 |
| `superagent_tokens_total` | counter | 任务累计 token（来自供应商 `usage`，缺失时保守估算） |
| `superagent_model_calls_total` | counter | 任务累计模型调用次数 |
| `superagent_events_total{kind="..."}` | counter | 按类型统计的审计事件数 |
| `superagent_schema_version` | gauge | 状态库 schema 版本，应为 2 |
| `superagent_uptime_seconds` | gauge | 进程存活秒数，用于发现意外重启 |

刻意**没有**暴露模型延迟直方图和审批等待时长：当前没有采集这两个数据，编造指标比留白更糟。需要时可以基于 `events` 表的时间戳补，但必须先真实采集再宣称。

推荐告警：

- `superagent_queue_depth > 0` 持续 10 分钟 → Worker 吞吐不足或调度卡死；
- `superagent_waiting_approvals > 0` 持续 30 分钟 → 值班没人看审批；
- `superagent_uptime_seconds` 回落 → 进程重启，检查是否中断了任务；
- `superagent_tokens_total` 增速异常 → 检查是否有任务在循环调用模型。

## 审计

`GET /v1/audit` 需要 `Authorization: Bearer <运维令牌>`。

| 参数 | 默认 | 说明 |
|---|---|---|
| `taskId` | 全部 | 只看某任务 |
| `kind` | 全部 | 事件类型，如 `created`、`model_response`、`approval_decided`、`command_result` |
| `after` | 0 | 游标：只返回该 `seq` 之后的事件 |
| `limit` | 100 | 单次上限 500 |

按 `seq` 升序返回，每项含 `seq`、`taskId`、`kind`、`at`、`data`。游标分页示例：

```bash
cursor=0
while :; do
  batch=$(agent_api "http://127.0.0.1:8080/v1/audit?limit=200&after=$cursor")
  [ "$(echo "$batch" | jq length)" = 0 ] && break
  echo "$batch" | jq -r '.[] | "\(.seq)\t\(.taskId)\t\(.kind)\t\(.at)"'
  cursor=$(echo "$batch" | jq '.[-1].seq')
done
```

非法 `after` 会被拒绝为 400，而不是静默当作 0——分页错误不应该悄悄从头开始。

## 成本视图

`GET /v1/costs`（需要运维令牌）返回累计 token、模型调用次数、任务状态分布与事件类型分布。用于回答「这套系统这个月烧了多少」这类问题，不替代供应商账单。

单任务细节在 `GET /v1/tasks/{id}` 的 `tokens`、`calls`、`contextTokens`、`contextTrims` 字段里。`contextTrims` 是上下文折叠次数，持续增长说明任务对话已经很长，可能需要拆分任务。

## 状态库自检

```text
/verify                    # CLI
GET /v1/verify             # 运维 API，需要 Bearer 令牌
```

自检只读，不修改数据，返回 `healthy` 与逐项结果（`checks[]`，每项含 `name` / `ok` / `detail`）：

| 检查项 | 说明 |
|---|---|
| `sqlite_integrity` | `PRAGMA integrity_check` |
| `schema_version` | 是否为期望版本 2 |
| `foreign_keys` | `PRAGMA foreign_key_check` 是否有违反 |
| `orphan_messages`、`orphan_calls` | 追加表里有、主表里没有对应任务的记录 |
| `status_column` | 状态列与任务 JSON 里的状态不一致 |
| `message_sequence`、`call_sequence` | 追加表 `seq` 有缺口或重复 |
| `dangling_children` | 父任务的 `children` 指向不存在的任务 |
| `workspace_present` | 已标记准备完成但副本目录已经不在了 |

建议在升级后、归档前后、异常重启后各跑一次。发现问题时**不要**直接删库重建：先停服务、备份整个状态目录，再按 `detail` 定位。自检报错通常是运维操作或磁盘问题留下的痕迹，而不是代码缺陷。

## 只读命令白名单

默认关闭。开启方式：环境变量 `SUPERAGENT_AUTO_APPROVE_READONLY=1`，或 JVM 系统属性 `-Dsuperagent.autoApproveReadOnly=true`。

开启后，命令必须同时满足以下条件才免去人工点击：

- 不含任何 shell 元字符：`;` `&` `|` `<` `>` `$` 反引号 `(` `)` `{` `}` `[` `]` 换行 `%` `^` `*` `?` `!`；
- 首个 token 在窄白名单内（`git`、版本查询类的 `mvn`/`javac`/`java`/`node`/`npm`/`python`/`go`/`cargo`/`dotnet`，以及 `pwd`/`ls`/`dir`/`cat`/`type`/`head`/`tail`/`wc`/`grep`/`findstr`/`stat`/`file`）；
- `git` 只允许 `status`/`diff`/`log`/`show`/`branch`/`rev-parse`/`ls-files`/`describe`/`blame`/`shortlog`，且参数里不能出现 `-c`、`--exec`、`--ext-diff`、`--textconv`、`--output`、`--upload-pack`、`--receive-pack`、`--config-env` 这类能间接执行外部程序的选项；
- 构建工具只允许版本查询（`-v` / `--version` / `-version`），`mvn test` 一律人工审批。

安全底线：

- 自动批准**仍然**写审批记录（最终 `decision` 为 `USED_AUTO`）和 `approval_auto_approved` 审计事件，事后查得出这一次不是人工点的；
- `run_test` 永不自动批准，它执行的是仓库里的构建脚本；
- 判不定就退回人工。白名单写窄的代价只是多点一次，写宽了就是绕过审批。

不建议在共享部署或对公网暴露的实例上开启。

## 生命周期与归档

首版不自动清理。现在提供显式归档：

```text
/archive <保留天数>
```

在状态目录的 `archive/` 下生成 `archive-<时间戳>.jsonl`，每行是一个完整任务（含对话原文、工具调用、事件）加它的审计事件，然后把这些任务从主库删除。

安全边界（实现里已强制）：

- 只归档 `COMPLETED` 与 `CANCELED`；
- 父任务仍在运行时，其子任务**不会**被单独归档——否则父任务的 `children` 会指向不存在的任务；
- **先写导出文件，再删除**。进程在两者之间崩溃只会留下多余归档，不会丢数据；
- 重复执行安全，第二次不会再处理同一批任务；
- 导出文件与会话原文包含代码内容，按团队留存制度处理，不要随意外传。

建议做法：先跑一次小保留天数在演练环境确认导出内容，再在生产按月度执行；归档后不要把导出文件放回状态目录。

## 状态目录

| 路径 | 内容 |
|---|---|
| `state.db` | SQLite 主库（WAL 模式） |
| `state.db-wal` / `state.db-shm` | WAL 与共享内存，备份时必须一起处理 |
| `state.db.v1.bak` | 旧 schema 升级前的一次性备份，升级确认无误后可删除 |
| `instance.lock` | 单实例进程锁，第二个实例会启动失败 |
| `work/` | 每个任务的 Git 副本，删除会导致该任务的补丁与恢复能力丢失 |
| `logs/` | 命令与测试日志，`/show` 会引用这些路径 |
| `archive/` | 归档导出 |

schema 版本升级是自动的：打开旧库时先 `wal_checkpoint`，复制一份 `state.db.v1.bak`，再把整块 JSON 拆进追加表并回填 `status` 列。迁移幂等，失败不会破坏原库（原库在事务前已备份）。

## 备份

停服务后整目录打包是最简单的一致备份：

```bash
systemctl --user stop superagent.service
tar -C "$HOME" -czf "superagent-$(date +%F).tar.gz" superagent
systemctl --user start superagent.service
```

不要在服务运行时直接 `cp state.db`：WAL 里可能有未检查点的事务，拷出来的库可能缺数据。运行中备份请用 `sqlite3 state.db ".backup"`，或者直接停服。
