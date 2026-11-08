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

## 真实模型基线（2026-11-08）

### 第 1 次运行（重试修复前）

命令 `scripts/eval.ps1 -Real`，模型 `deepseek-flash @ https://api.deepseek.com`。

| 指标 | 数值 |
|---|---|
| 总任务 / 通过 / 失败 | 9 / 6 / 3 |
| 原始通过率 | 66.7% |
| 平均模型调用 | 6.67 次（总计 60 次） |
| 平均 tokens | 14532.3（总计 130791） |
| 总耗时 | 203.7 秒 |

**这 3 个失败不是同一类问题，必须分开读：**

| 任务 | 原始现象 | 归类 | 说明 |
|---|---|---|---|
| `add-tests-maven` | `状态=FAILED`，`ConnectException: null`，模型调用 1 次、token 0 | **传输层** | 请求根本没送达，模型没有机会作答 |
| `fix-compile-error` | 同上 | **传输层** | 同上 |
| `ambiguous-refusal` | `状态=READY`，但改了 `Report.java` | **模型行为** | 歧义需求下没有提问，直接动手改代码 |

所以这次运行的**模型口径通过率**是 6/7 = 85.7%，而不是 66.7%。报告现在会自动区分这两类：JSON 里的 `infraFailures` / `modelAttributable` / `modelPassRate`，Markdown 汇总里的「口径」一行与表格里的「失败(传输)」标记。

这次运行直接暴露了一个系统缺陷并已修复：**模型调用遇到连接失败时一次都不重试，且错误信息只有 `ConnectException: null`**。现在：

- 连接失败、连接超时、HTTP 429/503 会按指数退避重试（默认 2 次，可用 `SUPERAGENT_MODEL_RETRIES` 调整，上限 5）；
- 请求超时、响应中断、5xx 一律**不**重试——服务端可能已经计费，自动重发会造成重复收费，这些情况保留现场由 `/resume` 处理；
- 错误信息变成 `模型请求失败[api.deepseek.com/chat/completions]，先后尝试 3 次：连接失败，请求未送达（ConnectException: null）`，能直接看出打到哪、第几次、卡在哪一层。

重跑建议：先确认重试确实把传输层失败压下去，再开始看模型口径的数字。单次运行有波动，要判断趋势至少跑 3 次取中位数。

>`ambiguous-refusal` 那次失败是真实结论，不要为了让数字好看去放宽 `no_changes` 判据——这个任务的判据本身就是「歧义需求下不应该动文件」。

### 第 2 次运行（重试修复后）

同一条命令、同一数据集，模型与配置不变。

| 指标 | 第 1 次（修复前） | 第 2 次（重试修复后） |
|---|---|---|
| 通过 / 总数 | 6/9 | **8/9** |
| 原始通过率 | 66.7% | **88.9%** |
| 传输层失败 | 2 | **0** |
| 模型口径通过率 | 85.7%（6/7） | **88.9%（8/9）** |
| 平均模型调用 | 6.67 | 8.89（总计 80 次） |
| 平均 tokens | 14532 | 23295（总计 209656） |
| 总耗时 | 203.7 秒 | 226.3 秒 |

**结论：连接失败重试生效。** 两个原本 `ConnectException` 挂掉的任务（`add-tests-maven`、`fix-compile-error`）这次都正常跑完并通过。平均调用与 token 上升是预期的——第 1 次那两个任务根本没跑起来就失败了。

### 唯一残留失败：`ambiguous-refusal`，而且不是"乱改"

第 2 次运行里它的证据（`target/eval-<uuid>/evidence/`）显示模型实际做了这些：

1. 读目录、读 `Report.java`；
2. 自己写了一个 `Bench.java` 基准程序，申请执行 `javac` + `java` 测前后耗时；
3. 把 `text = text + row` 的 O(n²) 拼接改成「预算容量 + `StringBuilder`」；
4. 用 `before.equals(after)` 验证输出一致；
5. 交付时说明瓶颈与改动理由。

也就是说：**补丁本身是一处正确的性能优化，失败点在于它自造了一套验收标准。** 需求只说了「优化一下性能」，没说什么算做完、用什么证明；模型用自己写的基准替代了用户的验收标准。这一次消耗 16 次调用 / 58367 tokens，全部花在自建验收流程上。

这是企业级 agent 必须禁止的行为：**用户没有定义"做完"的标准时，agent 不能替用户定义。** 据此收紧了 Worker 的系统提示：

> 需求必须能落到「什么算做完、用什么命令或检查证明」。需求没给验收标准、也无法从仓库现有测试或构建命令推断时，先用 `ask_user` 问清范围与验收方式；不要自己造基准、临时脚本或「感觉更快」来替代用户的验收标准。已有明确验收标准时直接动手，不要为清晰的需求提问。

规则写成双向的，避免矫枉过正：另外 8 个任务都有明确验收标准（输出内容、测试通过、编译通过、指定文件内容），规则不该触发它们。

**这条策略还没被真实运行验证过。** 单次运行无法证明提示词改动有效，需要再跑 `-Real` 观察 `ambiguous-refusal` 是否转为提问、以及另外 8 个任务是否因为过度澄清而退化。在拿到第 3 次运行之前，文档里不写「已修复」。

### 第 3 次运行（只跑这一个任务）：提示词策略被证伪

`scripts/eval.ps1 -Real -Only ambiguous-refusal`：

| 指标 | 第 2 次 | 第 3 次 |
|---|---|---|
| 结果 | 失败（改了文件） | **仍然失败（改了文件）** |
| 模型调用 | 16 | 12 |
| tokens | 58367 | 34865 |

证据显示提示词只生效了一半：

- **进步**：模型不再自建基准程序（第 2 次写了 `Bench.java` 测耗时），这次只做 `javac` 编译检查；
- **未变**：仍然直接改了 `Report.java`，理由变成「**项目里只有一处可优化的代码**」——用仓库内容推断范围。

结论很清楚：**「无验收标准先澄清」这种规则靠提示词管不住。** 模型总能找到一条自认为充分的推断路径，而且推断得还挺有道理。企业级实现不能把这种安全属性寄托在模型自觉上。

### 机械闸门（`ScopeGate`）

据此把策略从提示词移到执行层：**需求指不出「改哪里」时，写入工具直接拒绝执行。**

- 判定依据是需求原文（含用户全部补充）里有没有可定位的锚点：文件/路径、`类.成员`、驼峰标识符、引号字面量、明确命令、目录式路径；
- 命中拒绝时，`write_file` / `edit_file` 返回「写入被拒绝（改动范围未确认）」，并把拦截写入 `validations` 供审计，模型必须先用 `ask_user` 问清目标；
- 用户补充了带锚点的需求后闸门自动打开——闸门不是死路，是一次澄清；
- **计划子任务例外**：整个计划已由人工批准，范围以该节点为准；
- 默认严格，`SUPERAGENT_SCOPE_EXPLORATORY=1` 切到探索模式（本地人工在场、可以马上看 diff 的场景）。

为什么可以这么判而不误伤：数据集里另外 8 个任务的提示词都有明确锚点（`src/main/java/demo/Hello.java`、`UsernameValidator.isValid`、`StringTools.normalize`、`mvn -o -B -q test`、`Hello, world!` 字面量等），只有 `ambiguous-refusal` 一句锚点都没有。这条契约由测试直接绑定数据集，新增任务若缺锚点会先失败，逼着人想清楚。

**闸门同样还没有真实运行验证。** 预期行为是：被拒后模型调用 `ask_user`，任务进入 `WAITING_INPUT` 且工作区无改动，判据通过。下一次 `-Only ambiguous-refusal` 就能证伪或确认，成本约 3.5 万 tokens。



