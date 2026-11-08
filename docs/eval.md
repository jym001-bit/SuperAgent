# 评测 harness（离线 / 真实模型）

这个 harness 把「Agent 能不能把活干完」变成可复现的数字：每个任务是否有改动、改动是否符合判据、
用了多少次模型调用、消耗多少 token、耗时多少。它不改动项目运行逻辑，只新增 `dev.superagent.eval`
包、`eval/tasks/*.json` 数据集和 `scripts/eval.ps1` 入口。

## 一句话区分两种模式

| 模式 | 触发方式 | 模型 | 能证明什么 |
|---|---|---|---|
| 离线（默认） | `scripts/eval.ps1` | `ScriptedModel` 按脚本返回固定工具调用 | 评测 harness、Git 隔离、Worker 循环、命令审批、判据与报告本身可用 |
| 真实模型 | `scripts/eval.ps1 -Real` | `HttpModelClient` 调用真实模型 | 模型在该数据集上的成功率、平均模型调用数、token 与耗时 |

**离线模式通过不等于真实模型能力已验证。** 离线模式只证明「把确定性的工具调用喂进真实执行链路后，
系统能正确判定成功与失败」；它完全不评价模型选工具、看测试输出、改代码的质量。

## 运行

```powershell
# 离线：不联网、不调真实模型、不启动 Docker；默认模式
.\scripts\eval.ps1

# 只跑一个或几个任务（先用 -List 看 id）
.\scripts\eval.ps1 -List
.\scripts\eval.ps1 -Only hello-output,read-only-analysis

# 真实模型：必须显式传 -Real；没有 Key 会打印中文提示并以退出码 2 结束
.\scripts\eval.ps1 -Real

# 已有 jar 时跳过 mvn package
.\scripts\eval.ps1 -SkipBuild
```

真实模型模式的 Key 来源与项目其他入口一致：环境变量 `SUPERAGENT_API_KEY` 或
`DEEPSEEK_API_KEY`，或项目根目录 `.env` 里的 `DEEPSEEK_API_KEY`（`.env` 已被 `.gitignore` 忽略）。
Key 不会写进报告、日志或数据集。入口默认模型/地址由 `SUPERAGENT_MODEL`、`SUPERAGENT_BASE_URL` 控制。

退出码：`0` 全部任务通过；`1` 有任务未通过；`2` 参数、数据集或模型配置错误。

评测预算由 harness 固定：每个任务最多 40 次模型调用、1,000,000 tokens（两种模式一致，避免环境变量差异
影响可比性）；单任务超时默认 300 秒（`-TimeoutSeconds`），命令判据默认 180 秒（`timeoutSeconds` 可覆盖）。
`.\scripts\eval.ps1 -Help` 可打印脚本用法。

外部依赖：`git`（必需）、`javac` / `java` / `mvn`（部分任务的命令判据需要，`mvn` 请先跑过一次在线构建，
让本地仓库具备离线插件缓存）。入口会检查并在缺失时打印警告。

## 目录与产物

```text
eval/tasks/*.json          评测数据集（9 个任务，覆盖 8 类场景 + 1 个精确写入场景）
scripts/eval.ps1           PowerShell 入口
src/main/java/dev/superagent/eval/
  EvalTask                 任务模型与 JSON 解析、字段校验
  EvalCheck                判据模型与 JSON 解析、字段校验
  EvalChecks               判据解释执行（文件 / Git / 命令 / 回答）
  ScriptedModel            离线确定性模型替身（实现 ModelClient）
  EvalRunner               每个任务建独立仓库与状态目录，驱动 TaskManager/Worker，收集指标
  EvalTaskResult           单任务结果与指标
  EvalReport               汇总、JSON / Markdown 报告
  EvalSuite                数据集加载与 id 唯一性校验
  EvalCli                  命令行入口（-Real / -Only / -List ...）
  EvalJson                 JSON 解析小工具
target/eval-<uuid>/                        每个任务一份，跑完保留
  source/                                  独立 Git 仓库（setup 预置文件 + 一次提交）
  state/                                   Store / Workspaces 的独立状态目录（含 work/<taskId> 任务副本）
  evidence/git-status.txt                  模型改动前后的 Git 状态（HEAD + porcelain）
  evidence/changes.patch                   任务副本相对基线的补丁（含新增文件）
  evidence/task.log                        调度器与 Worker 的通知记录
  evidence/check-command-*.log             每条命令判据的完整输出
  evidence/git-*.log                       仓库初始化等 Git 日志
target/eval-reports/eval-report.json       机器可读报告
target/eval-reports/eval-report.md         Markdown 表格，可直接贴进文档
```

任务之间互不污染：每个任务重新建 `source` 仓库、重新建 `state` 状态目录，
`D:\superagent-java` 自身仓库不会被修改，也不使用 Docker 或网络（真实模型模式只访问模型接口）。

## 数据集格式

一个任务一个 JSON 文件。示例（节选）：

```json
{
  "id": "hello-output",
  "name": "单文件修改：改掉 Hello 的输出",
  "prompt": "把 src/main/java/demo/Hello.java 控制台输出的问候语改成 Hello, Super-agent!。",
  "setup": { "files": { "src/main/java/demo/Hello.java": ["package demo;", "", "..."] } },
  "setupCommit": "eval: initial setup",
  "checks": [
    { "op": "file_contains", "path": "src/main/java/demo/Hello.java", "text": "Hello, Super-agent!" },
    { "op": "command", "command": "javac -d out src/main/java/demo/Hello.java", "exitCode": 0 },
    { "op": "command", "command": "java -cp out demo.Hello", "outputContains": "Hello, Super-agent!" }
  ],
  "script": [
    { "tool": "read_file", "arguments": { "path": "src/main/java/demo/Hello.java" } },
    { "tool": "edit_file", "arguments": { "path": "src/main/java/demo/Hello.java", "old_text": "\"Hello, world!\"", "new_text": "\"Hello, Super-agent!\"" } },
    { "answer": "已把 Hello 的输出改为 Hello, Super-agent!。" }
  ]
}
```

字段说明：

- `id`：小写字母 / 数字 / 连字符，数据集内唯一。
- `name`：报告里的中文短名。
- `prompt`：给 Agent 的真实需求（真实模型模式原样发送）。
- `setup.files`：临时仓库预置文件；值可以是字符串，也可以是字符串数组（按 `\n` 连接，便于手写 Java 源码）。
  路径必须是相对路径，不允许 `..` 越出仓库。
- `setupCommit`：初始提交信息，可省略。
- `checks`：判据数组，至少一条。
- `script`：离线模式步骤数组。真实模型模式忽略它；离线模式下缺 `script` 的任务会直接判失败并说明原因。

### 判据类型

| op | 参数 | 判定 |
|---|---|---|
| `file_equals` | `path` + `content`/`contentLines` | 文件内容在「统一换行、去掉每行行尾空白、去掉末尾空行」后完全相等 |
| `file_contains` | `path` + `text` | 文件存在且包含该子串 |
| `file_absent` | `path` | 路径不存在（文件或目录） |
| `no_changes` | 无 | 任务副本相对基线 `git status --porcelain` 为空，且 `HEAD` 仍等于基线提交（提交上去的改动也会被发现） |
| `command` | `command`，可选 `exitCode`（默认 0）、`outputContains`、`timeoutSeconds` | 在任务副本里真实执行 shell 命令（Windows 用 PowerShell，其他平台用 `/bin/sh`），比对退出码与输出 |
| `answer_contains` | `text` | 模型最终文本（最终回答；若在提问则为问题文本）包含该子串 |

判据按数组顺序执行，每条都记录独立结论；任务 `success` 要求全部通过，且任务结束状态不是
`FAILED` / `INTERRUPTED` / `BUDGET_EXCEEDED`（`WAITING_INPUT` 视为可接受的「先提问」结局）。

`content` 与 `contentLines` 二选一；`contentLines` 是字符串数组，方便手写 Java 源码而不用转义整段。

### 离线脚本步骤

| 步骤 | 含义 |
|---|---|
| `{"tool": "read_file", "arguments": {...}}` | 返回一次工具调用（工具名与参数原样交给 Worker 校验和执行） |
| `{"answer": "..."}` | 返回最终文本，任务进入待验收（或提问） |
| `{"error": "..."}` | 抛错模拟模型失败，任务进入 `FAILED`（用于验证判定逻辑会拒绝） |

`write_file` 的参数支持 `contentLines` 数组（`ScriptedModel` 会转成 `content`），
这样脚本可以直接书写多行源码。命令工具照样走正式审批流程：评测按需自动批准，但仍然记录每条命令和退出码。

## 指标与报告字段

单任务（`eval-report.json` 的 `tasks[]`）：

| 字段 | 含义 |
|---|---|
| `success` | 判据全部通过且任务没有硬失败 |
| `status` | 任务最终状态（`READY` / `WAITING_INPUT` / `FAILED` ...） |
| `failures` | 判据失败原因列表；通过时为空 |
| `modelCalls` | 真实经过 Worker 的模型调用次数 |
| `tokens` | 真实累加的 token 数（脚本模式下为脚本设定的数值） |
| `durationMs` | 该任务的墙钟耗时（含建仓、clone、模型循环、判据执行） |
| `artifactsDir` | 证据目录绝对路径 |
| `answer` | 模型最终文本（截断到 2000 字符） |
| `checks[]` | 每条判据的 `op` / `passed` / `detail` |
| `commands[]` | Worker 实际执行过的命令及其退出码 |

汇总：`total`、`passed`、`failed`、`passRate`、`avgModelCalls`、`avgTokens`、`avgDurationMs`、
`totalModelCalls`、`totalTokens`、`totalDurationMs`，以及 `mode` / `model` / `modeNote`
（`modeNote` 明确写出这次数字能证明什么，避免把离线结果当成模型能力）。

## 数据集任务清单

| id | 场景 |
|---|---|
| `hello-output` | 单文件修改：改掉 `Hello.java` 的问候语，并用 `javac` + `java` 验证输出 |
| `username-validation` | 增加参数校验：按 `docs/username-case.md` 实现 3–16 位 ASCII 用户名校验，`verify/UsernameProbe` 作为机械探针 |
| `add-tests-maven` | 新增测试并让 `mvn -o -B -q test` 通过 |
| `fix-compile-error` | 修复预置的编译错误（`int value = "42";`），编译并运行验证 `value=42` |
| `cross-file-greeting` | 跨两个文件关联修改：改常量 + 改输出，运行结果为 `Hello, world!` |
| `read-only-analysis` | 只读分析：引用 `items.length` 与 `30000` 两条事实，且源仓库无改动 |
| `test-then-fix` | 先跑测试观察失败，再改实现让 `mvn -o -B -q test` 通过；测试文件必须保持原样 |
| `ambiguous-refusal` | 需求歧义（「优化性能」）时应提问：判据是没有任何文件改动 |
| `exact-config-file` | 删除遗留 `debug.log` 并精确写入 `config/app.properties` 两行内容 |

其中 `username-validation`、`add-tests-maven`、`test-then-fix` 用 `file_equals` 锁住「探针 / 测试文件」，
防止模型通过放宽测试来「通过」评测。

## 离线模式能证明什么、不能证明什么

**能证明：**

- 每个任务确实在独立 Git 仓库 + 独立状态目录里跑完，源仓库无改动、改动可从 `changes.patch` 导出；
- 工具调用真的经过 Worker 的持久化循环（模型调用 → 工具执行 → 结果回填 → 下一轮）；
- 命令判据真的执行进程（测试用「写文件后以退出码 7 结束」证明副作用和退出码都被读到）；
- 判定逻辑会拒绝错误结果：写错内容、偷偷改文件、脚本报错都不会被判通过；
- 真实模型模式下同一套判据可以直接度量成功率、模型调用数、token 和耗时。

**不能证明：**

- 不能说明真实模型能完成这些任务（脚本是人为编排的固定动作）；
- 不能说明模型的工具选择、读测试输出、写测试的质量；
- 不能替代真实模型模式下的多次运行统计（真实结果受模型、提示词、网络波动影响）。

因此在面试或文档里引用离线结果时，应写成「评测 harness 与执行链路验证通过」，而不是「模型能力已验证」。

## 故意制造一次失败

想验证「判定逻辑真的会判失败」，可以复制一个任务 JSON 后做任意一种破坏：

1. 把 `checks` 里的期望值改错（例如 `file_equals` 期望 `value=done`，脚本却写 `value=wrong`）；
2. 把只读任务的 `script` 加一步 `write_file`（`no_changes` 会报「工作区有改动」）；
3. 把脚本某步改成 `{"error": "..."}`（任务进入 `FAILED`，报告把结束状态写进 `failures`）。

然后 `.\scripts\eval.ps1 -Only <新任务 id>`，应当看到 `success=false`、`failures` 非空、退出码 1。
仓库内的自动测试用同样方式覆盖了这三类失败。

## 自动测试

```powershell
mvn -o test -Dtest='dev.superagent.eval.*Test'
```

> 注意：Surefire 的 `-Dtest` 需要匹配到类名，`-Dtest='dev.superagent.eval.*'` 会因匹配不到而报
> “No tests matching pattern”；请使用上面的 `*Test` 写法。

| 测试 | 验证的行为 |
|---|---|
| `EvalRunnerTest.scriptedTaskRunsThroughTheWholeStackAndPassesItsChecks` | 脚本化任务真的经过 TaskManager/Worker/Workspaces/Store，`success=true`、`modelCalls=3`、`tokens>0`，证据目录与 JSON/Markdown 报告可读 |
| `EvalRunnerTest.aWrongEditMakesTheTaskFailInsteadOfPassing` | 必然失败的任务 `success=false`、`failures` 非空且指出文件与行差异 |
| `EvalRunnerTest.noChangesCatchesAModelThatSecretlyEditsFiles` | `no_changes` 能识别只读任务里偷偷改文件 |
| `EvalRunnerTest.aScriptedModelFailureIsRecordedAsAFailedTask` | 模型报错被记录为 `FAILED` 任务，而不是静默通过 |
| `EvalChecksTest.noChangesDetectsModifiedUntrackedAndCommittedFiles` | `no_changes` 覆盖已改、未跟踪、已提交三种情况 |
| `EvalChecksTest.commandCheckReallyExecutesAndReportsNonZeroExit` | `command` 真跑进程：真实输出匹配、非零退出码判失败、`exitCode` 字段生效 |
| `EvalChecksTest.fileAndAnswerChecksCompareRealContent` | 文件判据比较真实内容（含换行归一、缺文件、越界路径），回答判据匹配子串 |
| `EvalSuiteTest.datasetSatisfiesTheDataContract` | 数据集全部可解析、id 唯一、每个任务至少一条 check、覆盖全部判据类型 |
| `EvalSuiteTest.onlyFiltersAndRejectsUnknownIds` | `-Only` 过滤正确，未知 id 明确报错而不是静默跑全量 |
| `EvalSuiteTest.duplicateIdsAreRejected` / `invalidChecksAreRejectedBeforeRunning` | 重复 id、未知 op、空 checks、越界 setup 路径、非法 id 在执行前就被拒绝 |

测试全部使用临时仓库与脚本化模型，不联网、不调用真实模型、不启动 Docker，单次运行在十几秒量级。

## 已知限制

- `command` 判据依赖宿主 shell 与 PATH 上的 JDK / Maven；缺失时对应任务判失败并在 `detail` 里给出日志路径。
- `mvn -o` 要求本地仓库已有编译与 surefire 插件、JUnit 5 依赖（跑过一次 `mvn package` 即满足）。
- 每个任务都把 `source` 与 `state` 保留在 `target/eval-<uuid>/`，多跑几次会占用磁盘，可整体删除 `target/eval-*`。
- 离线脚本与数据集内容绑定；改动 setup 文件后要同步更新对应 `script` 与 `checks`，否则离线模式会先暴露不一致。
