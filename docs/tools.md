# 工具注册表、只读 Git 工具与结构化测试运行

本文件说明企业级工具面的稳定契约。模型看到的工具定义、风险等级、执行入口都集中在
`dev.superagent.tool.ToolRegistry`；文件工具仍由 `FileTools` 实现，Git 与测试运行由新实现承担。
源码：`src/main/java/dev/superagent/tool/`。自动测试：`src/test/java/dev/superagent/tool/`。

## 工具清单

| 工具 | 风险等级 | 行为 | 实现 |
|---|---|---|---|
| `list_directory` | `READ_ONLY` | 列出目录，隐藏 `.git` | `FileTools` |
| `search_text` | `READ_ONLY` | 字面文本搜索，命中格式 `文件:行号:内容` | `FileTools` |
| `read_file` | `READ_ONLY` | 读取 UTF-8 文本，超过 1MB 拒绝 | `FileTools` |
| `write_file` | `MUTATING` | 原子写入/覆盖 | `FileTools` |
| `edit_file` | `MUTATING` | `old_text` 必须精确且唯一匹配 | `FileTools` |
| `git_status` | `READ_ONLY` | `--porcelain=v1 -z`，可选 `path` 过滤 | `GitTools` |
| `git_diff` | `READ_ONLY` | `--no-color --no-ext-diff`，可选 `path`、`stat` | `GitTools` |
| `git_log` | `READ_ONLY` | `--oneline -n <count>`，默认 10，上限 50 | `GitTools` |
| `run_test` | `EXECUTES_COMMAND` | 识别项目测试命令，argv 直调，返回结构化摘要 | `ToolRegistry` |
| `execute_command` | `EXECUTES_COMMAND` | 需要用户审批，由调用方（Worker）处理 | 调用方 |
| `ask_user` | `INTERACTIVE` | 提问并等待回复，由调用方（Worker）处理 | 调用方 |

`ToolRegistry.risk(name)` 对未注册工具抛 `IllegalArgumentException("未知工具: " + name)`；
`known(name)` 只回答是否已注册，`known(null)` 返回 `false`。

## ToolRegistry 契约

```java
public static ArrayNode specifications();      // 模型可见的 JSON Schema 数组
public static boolean known(String name);
public static ToolRisk risk(String name);
public static String execute(String name, JsonNode args, Path workspace) throws Exception;
```

- `specifications()` 覆盖上表全部 11 个工具，名字与 `known`/`risk` 一一对应；文件工具和
  交互工具的 schema 复用 `FileTools.specifications()`，不重复维护。
- `execute` 只执行非交互工具：file 工具委托 `new FileTools(workspace)`，Git 工具委托
  `new GitTools(workspace)`，`run_test` 由注册表内部执行。
- `execute("execute_command" | "ask_user", ...)` 抛
  `IllegalArgumentException("交互工具由调用方处理: " + name)`，审批与等待输入语义留在 Worker，
  注册表不复制状态机。
- 未知工具、非对象参数、空工作目录都在执行前抛清晰中文错误。
- 工作目录直接传给实现类；Git 工具会再取 `toRealPath()`，因此传入副本根目录即可。

### 接入 Worker 时的约定

1. 模型工具定义改用 `ToolRegistry.specifications()`；关闭命令执行的入口仍需按名字移除
   `execute_command`（现有 Worker 的过滤逻辑可以保留）。
2. `ask_user`、`execute_command` 保持现有分支；其余工具统一调
   `ToolRegistry.execute(call.name, args, Path.of(task.workspace))`。
3. 写文件和 `execute_command` 的“先保存意图、后保存结果”不变。`run_test` 会启动真实进程，
   调用前同样应记录执行意图；结果不明时不能自动重跑（沿用现有 `UNKNOWN` 处理）。
4. 审批策略：`READ_ONLY` 可自动执行；`MUTATING` 与 `EXECUTES_COMMAND` 是否逐次审批由入口策略
   决定。注册表只提供风险等级，不自行审批。

## 只读 Git 工具

统一调用形式（`GLOBAL` 前缀保证输出可解析）：

```
git -c core.quotepath=false --no-pager <status|diff|log> ...
```

- **不经过 shell**：`ProcessBuilder` 只接收 argv 数组，`path` 作为单个 argv 元素放在 `--` 之后，
  因此 `; rm -rf /`、`$(...)`、反引号都只是字面 pathspec，不会产生副作用。
- `path` 校验：必须是仓库内相对路径，拒绝绝对路径、盘符路径和含 `..` 的越界路径；`path` 为空、
  类型错误时在执行前报错。
- 环境：`GIT_TERMINAL_PROMPT=0`、`GIT_PAGER=cat`、`GIT_OPTIONAL_LOCKS=0`，不设
  `GIT_CONFIG_NOSYSTEM`（保留用户配置），始终使用 `--no-pager`。
- 工作目录：`workspace.toRealPath()`；缺少 `.git` 时抛
  `IllegalArgumentException("不是 git 仓库: ...")`，嵌套子目录、非仓库目录都得到同一类中文错误。
- 超时 30 秒，超时后杀进程并抛 `IllegalStateException("git 命令超时（30 秒）: ...")`。
- stdout 按 UTF-8 解码，stderr 只用于错误信息；git 的换行符警告不会混进 `git_diff` 输出。
- 输出上限 24000 字符，超出追加 `[结果已截断]`（复用 `FileTools.bounded`）。
- 只提供 `status`/`diff`/`log`，没有 `push`/`commit`/`reset` 等写操作，天然只读。
- `git_status` 使用 `--porcelain=v1 -z`：按 NUL 解析条目，重命名条目显示为
  `R  新路径 <- 原路径`；最多列出 500 条改动。`git_log` 在空仓库上返回
  `IllegalStateException("git log 失败（exit=128）: fatal: ...")`，把 git 的英文原因包在中文上下文里。
- `git_diff` 额外使用 `--no-ext-diff`，避免 `.gitattributes` 配置的外部 diff 驱动被执行。

## run_test

自动识别项目类型（`framework=auto` 或不传）：

| 检测标记 | 命令 |
|---|---|
| `pom.xml` | `mvn -B test` |
| `build.gradle` / `build.gradle.kts` | `gradle test` |
| `package.json` | `npm test` |
| `Cargo.toml` | `cargo test` |
| `go.mod` | `go test ./...` |
| `pyproject.toml` / `pytest.ini` / `setup.cfg` / `tox.ini` | `python -m pytest`（非 Windows 用 `python3`） |

参数：

- `framework`：`auto|maven|gradle|npm|pytest|cargo|go|custom`。
- `args`：字符串数组，直接追加到测试命令 argv；`npm` 会先插入 `--` 再追加。
- `argv`：仅 `framework=custom` 使用，必须是完整 argv 数组；首项若是
  `cmd`/`powershell`/`pwsh`/`sh`/`bash`/... 会被拒绝，避免经 shell 重新拼命令。
- `timeout_seconds`：默认 300，限制在 1–1800 秒；超时杀进程并标记 `timedOut=true`。
- 所有 argv 都不经过 shell；参数含 NUL 或数组结构非法时在执行前报错。

返回文本包含：`测试结果: <digest>`、完整命令、`exit`/`timedOut`/`testsRun`、最多 20 条失败用例，
以及 `--- 输出末尾 ---` 后的至多 6000 字符原文。整体仍受 24000 字符上限约束。

注意：`run_test` 执行的是项目自己的构建/测试命令，本质上可运行任意项目代码；shell 拒绝只是
命令拼接层面的防护，真正的隔离边界仍是任务副本与容器/沙箱。

## TestOutput：结构化测试结果

```java
public record Summary(int exitCode, boolean timedOut, String command, List<String> failures,
                      int testsRun, String digest, String tail)
public static Summary parse(String command, String output, int exitCode, boolean timedOut, int maxTail)
```

- 失败用例提取（best-effort、去重、上限 20 条、单条最多 160 字符）：
  - Maven surefire：`[ERROR]   FooTest.bar:42`、`[ERROR] dev.example.FooTest.bar -- Time elapsed`、
    旧式 `[ERROR] bar(com.example.FooTest)`；
  - Gradle：`FooTest > bar() FAILED`（任务路径 `:test`/`Task :test` 不算用例）；
  - Cargo：`test tests::bar ... FAILED`；
  - Go：`--- FAIL: TestBar`；
  - pytest：`FAILED tests/test_x.py::test_bar - ...`；
  - javac：`Foo.java:12: error: cannot find symbol`；Go 编译错误 `x.go:3:1: ...`；
  - Rust：`error[E0308]: mismatched types`。
- `testsRun`：取 surefire 最后一条 `Tests run: N` 汇总；否则识别
  `N tests completed`（Gradle）、`test result: ... N passed; M failed`（Cargo）、
  `Tests: ... N total`（Jest）。
- `digest` 示例：`mvn -B test => exit=1, 失败 2 个: FooTest.bar, BazTest.qux`、
  `mvn -B test => exit=0, 测试 12 个全部通过`、超时为 `... => 超时, ...`。
  汇总行给出 `Failures: N`/`Errors: N` 但没提取到用例名时，会写“未提取到用例名，见输出末尾”。
- `tail`：原文末尾至多 `maxTail` 字符（`maxTail` 为负按 0 处理），换行统一为 LF。
- **契约：解析绝不抛异常。** 任何内部异常都会退化为 `tail` + “解析失败，见输出末尾”的摘要；
  `null` 输出按空串处理。

## 测试

`src/test/java/dev/superagent/tool/`：

| 测试类 | 验证内容（真实 git / 真实进程） |
|---|---|
| `GitToolsTest` | 干净与脏工作区状态及 `path` 过滤；`--stat` 反映真实两个文件改动、`git_log` 条数与提交数一致且 count 被限制；`; rm -rf /`、`$(...)`、反引号只作为 pathspec（无副作用文件，另有文件名带 `;` 的真实 diff 证明只匹配该路径）；非仓库/缺失目录/嵌套目录的中文错误；超长 diff 截断且带 `[结果已截断]`；非法参数与空仓库 `git_log` 清晰报错 |
| `ToolRegistryTest` | `specifications()`、`known`、`risk` 三方一一对应（11 个名字、required ⊆ properties）；交互工具拒绝语义；文件工具经注册表读写与路径越界拒绝；框架自动识别与 npm `--`、custom argv；`run_test` 真实启动 `java -version` 得到 `exit=0`、缺失主类 `exit=1`，`cmd /c`/`sh -c` 被拒绝 |
| `TestOutputTest` | 真实 surefire 失败日志的用例名与汇总计数、成功摘要；Gradle/Cargo/Go/javac/Rust/pytest 提取；tail 长度与截断；乱码/空输入不抛异常；超时摘要与 20 条上限 |

离线运行（不联网、不调模型、不启动 Docker），本机总耗时约 5–7 秒：

```powershell
mvn -o test '-Dtest=GitToolsTest,ToolRegistryTest,TestOutputTest'
```

注意：Surefire 3.5.2 下 `-Dtest='dev.superagent.tool.*'` 实测匹配不到任何用例（类名模式按文件路径匹配），
请用上面的显式类名，或 `-Dtest='*Tool*Test'` 这类通配。
