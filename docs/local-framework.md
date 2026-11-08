# Windows 本地框架运行手册

准备 PowerShell 7、Java 17 或更新版本、Git、一个至少有一次提交的 Git 仓库，以及 DeepSeek API Key。项目使用该仓库的当前提交作为计划基线。发布产物是 `target/superagent-java.jar`，源码变更后用 `mvn package` 重建。

## 运行 CLI

在 PowerShell 执行。首次运行先用 `powershell -NoProfile -File .\scripts\configure-deepseek.ps1` 保存 Key。示例仓库路径可替换成自己的测试 Git 仓库。

```powershell
Set-Location D:\superagent-java
$env:SUPERAGENT_STATE_DIR = Join-Path $PWD 'target\framework-state'
java -jar .\target\superagent-java.jar --repo .\target\demo-source
```

先提交一个小计划。用以下需求可以检查两个独立节点和一个依赖节点。

```text
/plan 拆成三个子任务。A 写 alpha.txt，内容 alpha。B 写 beta.txt，内容 beta。C 依赖 A 和 B，读取这两个文件并写 summary.txt，每行放一个文件的内容。只操作这三个文件。
```

出现 `PLAN_READY` 后，用实际的父任务 ID 执行：

```text
/show <父任务ID>
/start <父任务ID>
/tasks
/diff <父任务ID>
/accept <父任务ID>
```

`/show` 展示目标、依赖、验收条件和子任务 ID。`/start` 是批准计划的操作，重复执行不会创建第二批子任务。总补丁在父任务进入 `READY` 后可导出。检查三个新增文件与内容再验收。验收只记录接受状态，向目标仓库应用补丁由你执行。

简单任务使用 `/new <需求>`，普通输入交给 Router 判断是否需要拆分或跟进。补充信息用 `/follow <子任务ID> <内容>`。CLI 中命令使用当前 Windows 账号运行，每条命令分别审批。Git 副本隔开代码改动，Windows 账号权限决定命令可访问的内容。

## 运行本地 API

要快速验收，可以在项目目录运行 `powershell -NoProfile -File .\scripts\verify-local-model.ps1`。脚本支持 Windows 自带的 PowerShell 5.1 和 PowerShell 7，使用独立测试仓库和本地服务，必要时隐藏输入 API Key。它会展示计划并等待输入 `start`，然后检查总补丁的哈希、三个文件的内容，以及补丁能否应用到干净基线。你确认结果并输入 `accept` 后，证据保存在 `target/model-check-*/acceptance.json`。手动操作 API 的步骤见下文。

在最终确认处直接回车会保留待验收状态。稍后用 `powershell -NoProfile -File .\scripts\verify-local-model.ps1 -Resume <运行目录>` 打开已有任务，用 `/tasks` 查到处于 `READY` 的父任务，检查原补丁后用 `/accept <父任务ID>` 记录到 SQLite。续验可以使用已经生成的补丁。

先退出使用同一状态目录的 CLI，再启动 HTTP 进程。它监听本机 `127.0.0.1`，默认端口 8080。使用你保存在本机的独立随机令牌，至少 32 个字符。

```powershell
Set-Location D:\superagent-java
$env:SUPERAGENT_OPERATOR_TOKEN = Read-Host '本地运维令牌' -MaskInput
$env:SUPERAGENT_STATE_DIR = Join-Path $PWD 'target\framework-state'
java -jar .\target\superagent-java.jar --serve-local --repo .\target\demo-source
```

在另一个 PowerShell 7 窗口用同一个运维令牌发请求：

```powershell
$api = 'http://127.0.0.1:8080'
$token = Read-Host '本地运维令牌' -MaskInput
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod "$api/readyz"

$body = @{
    mode = 'plan'
    text = 'A 写 alpha.txt，内容 alpha。B 写 beta.txt，内容 beta。C 依赖 A 和 B，读取并合成 summary.txt。只操作这三个文件。'
} | ConvertTo-Json
$plan = Invoke-RestMethod "$api/v1/tasks" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body
$task = "$api/v1/tasks/$($plan.id)"
Invoke-RestMethod $task -Headers $headers
```

检查计划后执行批准。等详情返回 `READY` 再下载和验收。

```powershell
Invoke-RestMethod "$task/start" -Method Post -Headers $headers
Invoke-RestMethod $task -Headers $headers
Invoke-WebRequest "$task/patch" -Headers $headers -OutFile .\changes.patch
Get-FileHash .\changes.patch -Algorithm SHA256
Invoke-RestMethod "$task/accept" -Method Post -Headers $headers
```

| 接口 | 用途 |
|---|---|
| `POST /v1/tasks` | 传入 `text` 和 `mode`。 `single` 直接执行，`plan` 返回待审计划，`auto` 由 Router 决策并返回 `decision` 和可能的 `taskId` |
| `GET /v1/tasks`、`GET /v1/tasks/{id}` | 列表与详情。父任务详情包含计划和子任务状态 |
| `POST /v1/tasks/{id}/start` | 批准已生成的计划 |
| `POST /v1/tasks/{id}/follow` | 以 `{"text":"补充内容"}` 回复指定任务或子任务 |
| `POST /v1/tasks/{id}/cancel`、`POST /v1/tasks/{id}/resume` | 停止任务或恢复失败、中断的执行 |
| `GET /v1/tasks/{id}/patch` | 下载补丁，响应头提供 `X-Patch-SHA256` |
| `POST /v1/tasks/{id}/resolve` | 在合成目录处理冲突后重新导出总补丁 |
| `POST /v1/tasks/{id}/accept` | 验收总补丁或独立任务 |

API 模式可读写任务副本并交付补丁，宿主机命令工具关闭。`SUPERAGENT_PORT` 可调整监听端口，两个入口可以轮流使用同一个单仓库状态目录。

## 查看阻塞和冲突

- `BLOCKED` 表示至少一个子任务需要处理。用详情定位等待回复、审批或失败的节点。已满足依赖的其它分支继续运行。
- `CONFLICT` 表示应用某个子任务增量时失败。父任务详情给出节点、涉及的文件、Git 错误和合成目录，子任务仍可各自导出补丁。检查所有子任务的差异，在该合成目录手工完成剩余改动，保持原始 HEAD，清除未解决的索引冲突，再执行 `/resolve <父任务ID>` 或对应 API。系统会重新冻结补丁，供人工验收。
- 如果要放弃当前合成现场、按原有子补丁从头试一次，用 `/resume <父任务ID>`。它会用新的干净目录重试，原目录保留。若冲突发生在准备下游基线的过程中，相关子任务会显示错误，可调整拆分和依赖后新建计划。
- 取消父任务会停止尚未交付的子任务，已冻结补丁继续可查。重启后已完成节点保持冻结状态。对结果不明的命令，先核对工作目录，再明确恢复对应子任务。
