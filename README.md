# Super-agent Java

Java 编码助手。通过 CLI 或本地 API 提交任务，批准复杂任务的依赖计划后，由多个 Worker 并行处理并交付一个总补丁。计划、对话、工具结果、审批和补丁哈希保存在 SQLite。

这是独立项目，不依赖 PaiCLI，不需要安装 DeepSeek Harness。参考了 [DeepSeek Super-agent 公开原型](https://github.com/deepseek-ai/dsh-superagent-prototype) 的设计思路，没有复制它的源码，也不代表完整复现 DeepSeek 内部系统。

## Windows 本地框架

`--repo <仓库>` 打开 CLI，`--serve-local --repo <仓库>` 启动本地 API。通过 `/plan` 生成计划，`/show` 检查，`/start` 批准执行，`/diff` 导出总补丁。Windows 本地框架只需 Java、Git 和模型 API。启动命令、HTTP 示例和冲突处理见 [本地框架运行手册](docs/local-framework.md)。

## GitHub 常驻服务

构建后用 `java -jar target/superagent-java.jar --serve --repo <目标仓库>` 启动。需要真实模型、GitHub 凭据、本机 rootless Docker 和独立状态目录。配置、systemd 部署、工单接入、运维 API、审批发布和故障处理见 [常驻服务部署与验收](docs/service.md)。

## 快速开始：先离线运行

需要 Java 17+、Maven 3.9+、Git，以及一个至少有一次提交的本地 Git 仓库。已在 Windows 11 / JDK 23 上验证。

```powershell
cd D:\superagent-java
mvn test
mvn package
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo.ps1
```

演示脚本在 `target/demo-source` 创建很小的示例 Git 仓库，状态放在 `target/demo-state`，启动打包后的 JAR，不读取任何真实 API Key。重复运行会保留之前的任务。

进入提示符后输入：

```text
/new 第一个离线任务
/new 第二个离线任务
/tasks
```

两个任务各自在独立副本中创建、读取 `demo-result.txt`，之后进入 `READY`（待验收）。用输出的实际任务 ID 继续：

```text
/show task_实际ID
/diff task_实际ID
/accept task_实际ID
```

再试人工等待与命令审批：

```text
/new [ask] 演示等待用户回答
/new [command] 演示命令审批
/tasks
/approvals
/follow task_实际ID 使用中文标题
/approve ap_实际ID
```

`[ask]` 和 `[command]` 仅是离线模拟模型的演示标记。离线模型只验证工具与调度流程，不具备真实编码或自然语言理解能力。

## 使用真实模型

应用优先读取环境变量。从项目目录启动时，官方 DeepSeek 接口也可从本机 `.env` 读取 `DEEPSEEK_API_KEY`。在 PowerShell 运行 `powershell -NoProfile -File .\scripts\configure-deepseek.ps1`，按提示隐藏输入。Git 会忽略该文件。其他配置从环境变量读取，字段见 [.env.example](.env.example)。

```powershell
$env:SUPERAGENT_BASE_URL = 'https://api.deepseek.com'
$env:SUPERAGENT_MODEL = 'deepseek-flash'
$env:SUPERAGENT_PROVIDER = 'deepseek'
java -jar .\target\superagent-java.jar --repo D:\你的Git项目
```

也支持 `DEEPSEEK_API_KEY` 作为 Key 的备用来源。真实模式使用模型 API，会按供应商规则消耗 Token；自动测试和默认演示均不调用真实服务。

连接其他标准 Chat Completions 服务时，将 `SUPERAGENT_PROVIDER` 设为 `standard`，填写自己的地址与模型。地址可填服务根路径、含 `/v1` 的前缀或完整 `/chat/completions` 地址。服务必须支持函数工具调用；兼容程度取决于具体服务，不能仅凭“兼容 OpenAI”字样保证。

真实模式可以直接输入：

```text
阅读这个项目，给 Hello.java 增加参数校验。先看文件，修改后提出测试命令，等待我批准。
```

调度者可以新建任务、把补充消息送给已有任务、询问消息归属或普通回复。命令行明确操作无需调用调度模型。调度模型返回无效内容时，不猜测、不创建任务，终端会报错，可改用 `/new` 或 `/follow`。

## 命令

| 命令 | 行为 |
|---|---|
| `/new <需求>` | 新建任务，显示基线提交及未提交改动提示 |
| `/tasks` | 列出任务状态和进展 |
| `/show <任务ID>` | 查看需求、问题、目录、结果、预算和命令执行记录 |
| `/follow <任务ID> <补充>` | 追加需求或回答 Worker 的问题 |
| `/cancel <任务ID>` | 请求停止模型调用和命令进程，保留工作 |
| `/resume <任务ID>` | 恢复失败、取消、中断或预算耗尽任务，重置本次执行预算 |
| `/approvals` | 显示当前待处理审批的完整命令和目录 |
| `/approve <审批ID>` | 批准当前这一次命令 |
| `/deny <审批ID>` | 拒绝当前这一次命令，原因作为工具结果反馈 |
| `/diff <任务ID>` | 导出修改列表和含新增文件的二进制兼容补丁 |
| `/accept <任务ID>` | 将待验收任务标记为已完成 |
| `/archive <保留天数>` | 把更早的已结束任务导出到 `archive/*.jsonl` 后从主库清理 |
| `/verify` | 状态库自检：完整性、序号缺口、孤儿记录、缺失副本 |
| `/help`、`/exit` | 帮助、保存状态并退出 |

`Ctrl+C` 在输入框中只取消本次输入；取消后台任务使用 `/cancel`。`Ctrl+D` 或 `/exit` 退出。

向待验收、已完成任务补充要求，会在原副本与会话上继续执行。向已取消、失败或中断任务补充消息只保存输入，需要再 `/resume`。一个任务等待用户或审批时不占用 Worker 线程，其他任务仍可运行。

## 文件、审批与交付

每个任务记录创建时的 `HEAD`，使用 `git clone --no-hardlinks --no-local` 创建副本，在 `agent/<任务ID>` 分支工作。未提交及未跟踪文件不复制进任务；Git 子模块、LFS 自动下载、外部依赖安装不在首版自动准备范围内。

内置文件工具包括 `list_directory`、`search_text`、`read_file`、`write_file`、`edit_file`。文件读写检查目录边界、符号链接及 Windows 目录联接，拒绝访问 `.git`；编辑必须唯一、精确匹配。文本工具使用 UTF-8，不做自动编码转换。

工具面统一注册在 `ToolRegistry`，每个工具有风险等级。另有只读的 `git_status`、`git_diff`、`git_log`：直接用 argv 调用 git、不经 shell，path 只作为 pathspec 传入，只在任务副本内可用。`run_test` 自动识别 Maven / Gradle / npm / pytest / Cargo / Go，把输出解析成「退出码 + 失败用例 + 结论摘要」再回填上下文；它执行的是仓库里的构建脚本，所以和 `execute_command` 一样**每次都要审批**，不会因为「只是测试」而放行。

**改动范围闸门**：需求指不出要改哪个文件、类或方法时（例如只说「优化一下性能」），`write_file`/`edit_file` 会被直接拒绝，并在验证记录里留一条拦截，模型必须先用 `ask_user` 问清目标；用户补充明确目标后闸门自动打开。计划子任务以人工批准的计划节点为准。这是执行层的机械策略，不依赖模型自觉——实测提示词管不住模型用「项目里只有一处可优化」来推断范围。`SUPERAGENT_SCOPE_EXPLORATORY=1` 可切到探索模式。

`execute_command` 在任务根目录运行，每次执行都要审批。Windows 使用不加载配置的 PowerShell，其他系统使用 `/bin/sh`。命令子进程只继承开发运行必需的环境变量，不自动继承模型 Key；默认 120 秒超时。需要项目专用秘密或特殊环境的命令，应由用户自行运行。

**目录检查和审批不是操作系统沙箱。** 批准的 Shell 命令仍具有当前系统账号的权限，可以访问任务目录之外、联网或启动其他程序；应按终端展示的完整命令进行判断。取消会尝试终止所启动的进程树；系统级脱离或另行注册的服务不在本地进程管理保证范围内。

只读命令白名单默认关闭。设置 `SUPERAGENT_AUTO_APPROVE_READONLY=1` 后，`git status`、`git diff`、`mvn -v`、`grep -rn TODO src` 这类命令可以免去逐次点击，但**仍然写入审批记录与 `approval_auto_approved` 审计事件**，事后能查出这次不是人工批准的。含 shell 元字符（`;` `|` `>` `$` 反引号等）或能间接执行外部程序的选项（`git -c`、`--ext-diff`）一律退回人工审批。`run_test` 无论开关如何都要审批，因为它执行的是仓库里的构建脚本。

`/diff` 在任务暂停或待验收时导出，避免与正在运行的工具竞争。导出使用独立 Git index，不改 Worker 的暂存区，包含非忽略的新增文件及二进制差异；记录相对于创建时基线提交的改动。被 `.gitignore` 忽略且未跟踪的文件不进入补丁。

确认补丁后，你可以在匹配基线的仓库中手动检查并应用：

```powershell
git apply --check '完整路径\changes.patch'
git apply '完整路径\changes.patch'
```

源仓库不会自动被覆盖、提交或合并。`READY` 代表 Worker 交付待验收，不代表测试通过；验证结论以 `/show` 的实际命令和退出码为准。

## 状态和恢复

| 状态 | 含义 |
|---|---|
| `QUEUED` / `RUNNING` | 排队 / 当前正在执行一步 |
| `WAITING_INPUT` | Worker 正等待你的回答 |
| `WAITING_APPROVAL` | 一次命令正在等待批准 |
| `READY` / `COMPLETED` | 待验收 / 用户已验收 |
| `FAILED` | 模型、文件准备或执行流程出错，需要检查 |
| `CANCELED` | 用户请求取消，已有工作保留 |
| `INTERRUPTED` | 进程中断或命令结果无法确认，需检查后恢复 |
| `BUDGET_EXCEEDED` | 本次调用数或 Token 预算耗尽 |

重启后，尚未启动的任务继续排队，等待人工回答的任务继续等待；执行中的任务暂停为 `INTERRUPTED`。旧的未执行审批失效，恢复时生成新的审批。

状态库使用分表结构：任务骨架存状态与审批，对话和工具调用存追加表，调度只读骨架，所以长对话不会拖慢调度。旧版本的单表状态库在打开时自动迁移，并留一份 `state.db.v1.bak`。送给模型的请求受上下文预算约束，超出后折叠早期轮次为确定性摘要，原始消息一条不删。

执行意图先保存，再执行工具，最后保存结果。若进程恰好在“命令已执行，但结果未保存”的间隙退出，系统不会自动重跑命令。先通过 `/show` 查看目录与日志，再 `/resume`；Worker 会收到结果不明的提示。写文件、精确替换保存预期结果，恢复时可以通过内容核对确认完成。

这提供可检查的恢复行为，不保证任意 Shell 命令具有“恰好一次”效果。模型调用只对「请求确定没送达」的失败做有限重试（连接失败、连接超时、HTTP 429/503），默认重试 2 次；请求超时与响应中断不重试，因为服务端可能已经计费，自动重发会造成重复收费。系统不会重试整个任务。任务目录损坏或克隆准备不完整时，系统保留现场并报错，不擅自删除目录重新创建。

## 配置

| 环境变量 | 默认值 |
|---|---|
| `SUPERAGENT_STATE_DIR` | 用户目录下 `.superagent-java` |
| `SUPERAGENT_BASE_URL` | `https://api.deepseek.com` |
| `SUPERAGENT_MODEL` | `deepseek-flash` |
| `SUPERAGENT_API_KEY` | 空；备用读取 `DEEPSEEK_API_KEY` |
| `SUPERAGENT_PROVIDER` | `deepseek`；另可选 `standard` |
| `SUPERAGENT_THINKING` | `true`；仅 DeepSeek 请求使用 |
| `SUPERAGENT_WORKERS` | `2` |
| `SUPERAGENT_MAX_MODEL_CALLS` | `30`，每个任务本次执行累计 |
| `SUPERAGENT_MAX_TOKENS` | `100000`，每个任务本次执行累计 |
| `SUPERAGENT_CONTEXT_TOKENS` | `48000`，单次请求的上下文预算（超出后折叠早期轮次） |
| `SUPERAGENT_AUTO_APPROVE_READONLY` | 关闭；设为 `1` 后只读命令免逐次批准，但仍写审批记录与审计事件 |
| `SUPERAGENT_COMMAND_TIMEOUT_SECONDS` | `120` |
| `SUPERAGENT_HTTP_TIMEOUT_SECONDS` | `120` |
| `SUPERAGENT_MODEL_RETRIES` | `2`，模型调用重试次数（仅连接失败、连接超时、429/503），上限 5 |
| `SUPERAGENT_SCOPE_EXPLORATORY` | 关闭；设为 `1` 允许「需求没指明改哪里」时也直接动手（本地探索用） |

同一个状态目录只能启动一个实例。它可以保存多个目标仓库的任务，每个任务保留自己的来源。日志、对话和代码副本持续保留，首版不自动清理，不自动压缩上下文。

Token 预算采用供应商 `usage.total_tokens`，包含重复发送的历史输入；缺少 usage 时按字符数做保守估算。预算在发起下一次模型调用前检查，当前调用的实际使用量可能越过阈值；不代表供应商费用硬上限。调度者每条消息调用一次模型，不计入某个 Worker 的预算。达到预算后的已有工具结果和文件会保留。

## 开发与验证

```powershell
mvn test
mvn package
java -jar .\target\superagent-java.jar --help
```

测试只使用临时 Git 仓库、模拟模型、本地 HTTP 服务和本地命令，不访问真实模型、不启动 Docker。当前 90 个测试，覆盖审批、并行隔离、追问、取消、预算、路径边界、DeepSeek 思考字段回传、故障和恢复，包括子 JVM 执行外部命令后 `Runtime.halt` 的场景。

内核阶段新增的行为验收：旧库迁移与幂等、对话追加顺序与事务回滚、调度载荷不含对话、并发读写不出现撕裂快照、审计游标、归档安全边界、上下文折叠不破坏锚点与工具协议、指标与真实状态一致、工具注册表契约、真实 git 命令与注入拒绝、评测判据真的会把失败任务判为失败。这些都是「删掉被测逻辑就会红」的用例，没有为凑数写 getter 测试。

`mvn test` 需要完整的进程与命名管道能力：受限沙箱里 `git clone` 会失败并表现为大量超时假失败，这类环境请放宽沙箱或在 Linux 上跑（CI 即如此）。

进一步阅读：[架构与代码阅读顺序](docs/architecture.md)、[企业级演进路线图](docs/roadmap.md)、[运维手册：指标、审计与归档](docs/operations.md)、[从演示到真实模型的验收步骤](docs/walkthrough.md)、[首版验收记录](docs/validation.md)。
