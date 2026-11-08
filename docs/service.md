# 常驻服务部署与验收

服务支持单团队、单 GitHub 仓库、单服务实例。GitHub 工单上的授权评论触发任务，同一工单后续评论送回原 Worker。任务产生变更后，运维下载补丁验收，再批准将该版本发布为草稿 PR。参考 DeepSeek 的 Router、Worker、事件接入与持久化执行设计。 [DeepSeek 官方架构文档](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/007-architecture.md)

## 部署条件

使用专用 Linux 服务账号、本机 rootless Docker、cgroup v2 和 systemd cgroup 驱动。Docker CLI 的默认 context 需要指向本机 Unix socket。准备一个已拉取且用 SHA-256 digest 固定的 Linux 构建镜像，内含 `/bin/sh`、支持 `cp -R -P` 的文件工具及项目验证所需的工具和缓存。启动时检查 CPU、内存、swap 和进程数控制是否可用。请按 Docker 文档给专用账号委派相关控制器。 [Docker rootless 文档](https://docs.docker.com/engine/security/rootless/tips/#limiting-resources)

- Java 17 或更新版本，Git，受版本管理的源仓库。源仓库的 `origin` 和当前分支必须匹配部署配置。服务只读取已提交的基线，请用部署账号定期快进同步本地源仓库。
- 真实 Chat Completions 模型与有效 API Key。使用团队认可的模型网关，仓库文件和命令输出会作为任务上下文发送给它。
- 仓库范围的 GitHub 凭据，例如只授权目标仓库的 fine-grained PAT，授予 Contents 和 Pull requests 的写权限及 Metadata 读取权限。请使用组织允许的凭据和审核流程。发布分支使用 `superagent/` 前缀，需按组织规则授权该身份创建分支。 [GitHub 创建 PR 的权限](https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request)
- 两个独立随机密钥，用于 Webhook 签名和运维 Bearer 身份。为发起人配置不可变的 GitHub 数字用户 ID，为仓库配置数字 ID。密钥写入服务环境文件，设置 `chmod 600`。模板见 `deploy/service.env.example`。
- 独立的本地持久化状态目录，备份时停止服务并备份整个目录。状态目录包含 SQLite、任务工作副本、待发布 Git 对象和日志。恢复时保持原绝对路径，服务绑定会核对仓库、分支和来源路径。

## 启动与接入

1. 用 `mvn package` 构建 JAR，将其安装到服务账号的 `~/superagent/superagent-java.jar`。将目标仓库检出到 `~/superagent/source`，填写服务环境文件。
2. 先前台启动并检查 `curl -f http://127.0.0.1:8080/readyz`。配置确认后，将 `deploy/superagent.service` 安装到服务账号的 `~/.config/systemd/user/`，执行 `systemctl --user daemon-reload` 和 `systemctl --user enable --now superagent.service`。开机启动由部署方按 rootless Docker 配置启用用户 lingering。日志用 `journalctl --user -u superagent.service` 查看。
3. 用已有 HTTPS 反向代理只转发 `POST /webhooks/github` 到 `127.0.0.1:8080`。代理端设置 256 KiB 请求体上限、请求速率与读取超时。运维 API 通过本机或 SSH 隧道访问。
4. 在 GitHub 仓库配置 JSON Webhook，选择 Issue comments 事件并使用相同签名密钥。GitHub 按原始请求体做 HMAC 验签。服务校验仓库 ID、评论者数字 ID 和 `@superagent ` 指令前缀。GitHub 失败投递需要从仓库的 Recent Deliveries 重新投递，服务按投递 ID 和签名内容里的评论 ID 去重。 [GitHub Webhook 最佳实践](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)

前台命令：

```bash
(
  set -a
  . "$HOME/.config/superagent/service.env"
  set +a
  exec java -jar "$HOME/superagent/superagent-java.jar" --serve --repo "$HOME/superagent/source"
)
```

请先在测试仓库发一条 `@superagent 修复这个问题，先阅读复现步骤`。接口返回 `202` 与 `taskId` 时，输入已提交数据库。再次发带前缀的评论会进入同一任务。等待人工回复或命令审批的任务会释放 Worker。普通讨论、评论编辑与 PR 评论不触发执行。

## 运维操作

`/v1/*` 使用 `Authorization: Bearer <运维令牌>`。部署首版只有一个运维身份，授权的 GitHub 发起人仅具有评论接单权限。

| 方法和路径 | 行为 |
|---|---|
| `GET /healthz`、`GET /readyz` | 进程存活和状态库检查 |
| `GET /v1/status` | 按状态统计任务 |
| `GET /v1/tasks`、`GET /v1/tasks/{id}` | 最近 100 个任务或指定任务的详情 |
| `POST /v1/tasks/{id}/follow` | 保存 `{"text":"补充要求"}` |
| `POST /v1/tasks/{id}/cancel`、`POST /v1/tasks/{id}/resume` | 取消或恢复任务 |
| `GET /v1/approvals` | 查询待处理命令和审批 ID |
| `POST /v1/approvals/{id}/allow`、`POST /v1/approvals/{id}/deny` | 对当前命令逐次审批 |
| `GET /v1/tasks/{id}/patch` | 下载补丁，响应头含 `X-Patch-SHA256` |
| `POST /v1/tasks/{id}/publish` | 用 `{"patchSha256":"已验收的哈希"}` 提交发布意图 |
| `POST /v1/tasks/{id}/retry-publication` | 用原补丁哈希确认重试结果不明的发布 |

查看与下载补丁的示例，先通过本机秘密管理配置运维令牌：

```bash
agent_api() {
  printf 'header = "Authorization: Bearer %s"\n' "$SUPERAGENT_OPERATOR_TOKEN" |
    curl --config - --fail-with-body "$@"
}

agent_api http://127.0.0.1:8080/v1/tasks
agent_api -D patch.headers -o changes.patch "http://127.0.0.1:8080/v1/tasks/$task_id/patch"
sha256sum changes.patch
```

检查补丁和实际验证记录，确认文件哈希与响应头相同后发布：

```bash
reviewed_hash='<确认后的64位小写十六进制哈希>'
agent_api -H 'Content-Type: application/json' \
  --data "{\"patchSha256\":\"$reviewed_hash\"}" \
  "http://127.0.0.1:8080/v1/tasks/$task_id/publish"
```

服务重新导出补丁并校验哈希，再用独立 Git index 生成仅含该补丁的提交。后台验证目标仓库与基线祖先关系，创建只允许首次写入的发布分支，提交草稿 PR。PR 正文引用原工单。发布中、完成和结果不明分别用 `SENDING`、`PUBLISHED` 和 `UNCERTAIN` 表示，详情包含 PR 地址或错误原因。针对同一工单后续批准的不同补丁会生成新草稿分支。

## 运行和恢复

- 每个被批准的命令启动独立容器。容器禁用网络，移除 capabilities，限制 CPU、内存、进程数和日志，挂载只读代码输入并在临时目录运行。容器内的工作目录改动随容器回收，提交内容由 Worker 文件工具保存在任务副本。需要联网下载的构建依赖应在镜像制作阶段准备。
- 有持久化接单和发布记录。进程重启后，尚未开始外部动作的执行步骤可继续，结果不明的文件动作、命令和发布暂停供人工核对。服务启动会清理同一状态目录此前遗留的容器。
- 重试发布前先检查远端分支和匹配 PR。若已存在同一提交的 PR，服务保存其地址。远端分支存在但提交不匹配时，交给运维处理。
- 单实例依赖状态目录进程锁。新工单在活跃任务达到 100 时返回 429，同一工单最多记录 256 个 Webhook 输入。接入方应监控磁盘和模型用量，按团队留存制度备份和归档状态。
- 本版本覆盖明确授权的 GitHub 评论触发、工单关联和受审批交付。文章中的主动观察与判断、跨渠道关联、分布式 Worker、高可用和企业 SSO 仍需独立实施与验收。

当前自动验证使用本地 HTTP 模型替身和远端故障替身。部署验收须在真实 Linux 主机完成：授权评论触发、真实模型读写、容器执行项目检查、任务取消回收容器、补丁哈希审批、GitHub 草稿 PR 及重启后的去重与恢复。

## 容器化部署（未在真机验证）

容器镜像由 `Dockerfile` 多阶段构建（`maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre-jammy`），运行镜像非 root（uid/gid 10001）、只包含 fat jar 与 git/curl/CA 证书，入口固定为 `java -XX:MaxRAMPercentage=75 -jar /app/superagent-java.jar`。编排见 `docker-compose.yml`：只读根文件系统、`tmpfs: /tmp`、`no-new-privileges`、`cap_drop: ALL`、命名卷挂状态目录、端口只在本机回环。

**适用范围与限制**：容器内默认运行 `--serve-local`（本地 HTTP 入口，命令执行被禁用）。GitHub 常驻服务 `--serve` 启动时会强制检查宿主机的 rootless Docker（context 必须是本机 Unix socket、cgroup v2、能清理遗留沙箱），容器内没有该 CLI 与 socket；把 socket 挂进容器等于把宿主用户的容器创建权限交出去，因此 **`--serve` 继续用 systemd 部署**，不做容器化。若后续要求 `--serve` 容器化，需先单独设计 Docker socket 委派/DinD 方案并重做安全评审。

启动步骤：

```bash
cp deploy/service.env.example deploy/service.env && chmod 600 deploy/service.env
# 填写 SUPERAGENT_API_KEY、>=32 字符的 SUPERAGENT_OPERATOR_TOKEN 等；该文件不要提交
mkdir -p source && git clone <目标仓库> source
sudo chown -R 10001:10001 source     # git 所有权检查无法通过 safe.directory 绕过
docker compose up -d --build
docker compose ps && curl -fsS http://127.0.0.1:8080/readyz
```

为什么用 `network_mode: host` 而不是 `ports`：应用硬编码绑定 `127.0.0.1`，bridge 网络的端口发布到不了容器回环地址；host 网络下 8080 仍然只监听宿主机 `127.0.0.1`，不会暴露到 `0.0.0.0`，安全目标与 `127.0.0.1:8080:8080` 等价。若必须用 bridge 网络，需要改动应用监听地址（代码变更），不在当前范围。

### 容器化验收清单

**容器镜像尚未在真机验证**（开发机没有 Docker，只做了 Dockerfile/compose 的静态复核），首次部署必须按下列清单逐项执行并记录结果，未完成前不得视为可交付：

| # | 验收项 | 通过标准 | 状态 |
|---:|---|---|---|
| 1 | 秘密文件与仓库 | `deploy/service.env` 存在、权限 600、未提交；`source` 已 clone 且属主 10001 | 待验收 |
| 2 | 镜像构建 | `docker compose build` 成功；`docker image inspect` 显示 `USER 10001` | 待验收 |
| 3 | 非 root 与只读根 | `docker compose exec superagent id` 为 uid 10001；对 `/` 写入返回只读错误 | 待验收 |
| 4 | 状态持久化 | 命名卷 `superagent-state` 挂到 `/app/state`，重启容器后任务仍在 | 待验收 |
| 5 | 健康检查 | `docker compose ps` 显示 healthy，`curl -fsS http://127.0.0.1:8080/readyz` 返回 200 | 待验收 |
| 6 | 监听范围 | `ss -lntp` 只在 `127.0.0.1:8080` 监听，不出现 `0.0.0.0:8080` | 待验收 |
| 7 | 能力与提权 | `docker inspect` 中 `CapDrop: [ALL]`、`SecurityOpt: [no-new-privileges:true]`、`ReadonlyRootfs: true` | 待验收 |
| 8 | 备份恢复 | `deploy/backup.sh --scope none` 生成归档并通过 `sha256sum -c`，解包后可启动并读回任务 | 待验收 |
| 9 | 真实模型调用 | 用测试仓库提交一次任务，确认模型读写与补丁下载正常 | 待验收 |
| 10 | 容器内命令执行边界 | 确认 `--serve-local` 下命令执行被禁用；需要沙箱时改用 systemd 的 `--serve` | 待验收 |

备份与日志：宿主机用 `deploy/backup.sh`（先停服务，`sqlite3 .backup` 一致性快照，保留 N 份），容器日志用 `docker compose logs`。完整说明见 [CI、发布与容器化交付说明](ci.md)。

## CI 门禁

`.github/workflows/ci.yml` 在 `ubuntu-latest` 上以 JDK 17 与 21 双版本矩阵运行 `mvn -B -ntp verify`，使用 `actions/setup-java` 的 Maven 缓存，同分支新提交会取消旧跑，超时 25 分钟，失败时上传 `target/surefire-reports`。测试是离线的（临时 Git 仓库、本地 HTTP 模型替身、子 JVM 崩溃恢复），但临时仓库需要提交，所以流水线先配置 `git config --global` 的 `user.name`/`user.email` 和 `init.defaultBranch=main`；`ubuntu-latest` 自带 git，满足依赖。CI 只在 Linux 运行：Windows 上 git 命名管道/文件锁、符号链接（测试改用目录联接）与子 JVM 进程树语义不同，不能作为发布门禁。

发布用 `.github/workflows/release.yml`：推送 `v*` 标签（或手动触发并填 `tag`）时，校验标签名等于 `v` 加 `pom.xml` 中的项目版本，执行 `mvn -B -ntp -DskipTests package`，生成并上传 `superagent-java.jar` 与 `superagent-java.jar.sha256` 到 GitHub Release，同时保留 workflow artifact。

部署时以 CI 产物为准：先合并到 `main` 并让双 JDK 门禁变绿，再打标签；部署前用 `sha256sum -c superagent-java.jar.sha256` 校验下载的 jar，再按上面的 systemd 或容器流程安装。systemd 与镜像使用同一个 jar，不允许在部署机上重新打包。
