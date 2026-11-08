# CI、发布与容器化交付说明

本文件说明本次交付新增的自动化门禁、发布流水线、容器镜像、部署加固与备份方案，以及哪些部分已经在当前机器上做过静态校验、哪些必须在 Linux 真机上验收。

## 交付物一览

| 文件 | 作用 |
|---|---|
| `.github/workflows/ci.yml` | 双 JDK 矩阵的离线测试门禁与产物冒烟检查 |
| `.github/workflows/release.yml` | `v*` 标签构建 fat jar 并发布到 GitHub Release（附 SHA-256） |
| `Dockerfile` | 多阶段构建：Maven 构建 → JRE 17 运行镜像，非 root |
| `.dockerignore` | 构建上下文裁剪，避免秘密文件和本地产物进入镜像 |
| `docker-compose.yml` | 单实例编排：只读根、命名卷、健康检查、能力清空 |
| `deploy/backup.sh` | 停服 + `sqlite3 .backup` 的一致性备份，校验并保留 N 份 |
| `deploy/superagent.service` | 在保留原启动语义的前提下加入 systemd 沙箱加固 |
| `docs/ci.md` | 本文件 |

## CI 门禁（ci.yml）

| 项目 | 取值 | 说明 |
|---|---|---|
| runner | `ubuntu-latest` | 见下文“为什么只在 Linux” |
| JDK | `17`、`21`（temurin） | 编译目标是 release 17，同时在当前 LTS 21 上验证 |
| 命令 | `mvn -B -ntp verify` | 覆盖编译、全部测试和 shade 打包 |
| 缓存 | `actions/setup-java` 的 `cache: maven` | 依赖下载复用缓存 |
| 并发 | `concurrency.group` + `cancel-in-progress: true` | 同分支/PR 新提交取消旧跑 |
| 超时 | `timeout-minutes: 25` | 覆盖双版本矩阵 |
| 失败上报 | `actions/upload-artifact@v4` 上传 `target/surefire-reports` | 仅 `failure()` 时上传，保留 7 天 |
| 权限 | `permissions: contents: read` | CI 不需要写权限 |

补充的确定性步骤：

1. **配置 Git 身份**：测试会 `git init` 临时仓库并提交，没有 `user.name`/`user.email` 会直接失败。同时设置 `init.defaultBranch=main`，让临时仓库默认分支可预期，避免 master/main 差异导致用例偶发失败。
2. **产物冒烟检查**：`java -jar target/superagent-java.jar --help` 并断言输出含 `--serve-local`。这能发现“测试通过但 fat jar 打不开/Main-Class 丢失”的交付事故。
3. **备份脚本语法检查**：`bash -n deploy/backup.sh`，成本很低。

### 测试的离线前提

自动测试只使用临时 Git 仓库、本地 HTTP 模型替身和子 JVM，不访问真实模型、不启动 Docker、不需要任何 API Key。测试本身离线意味着：CI 不需要配置密钥，也不会因为外部服务抖动而红；但 **Maven 依赖下载仍然需要网络**，由 setup-java 缓存与 Maven Central 提供。

### 为什么只在 Linux 跑

本项目测试会创建临时 Git 仓库、启动本地 HTTP 服务，并通过子 JVM（`CrashProbe`/`ProcessTreeProbe`）验证崩溃恢复和进程树终止。这些用例在 Windows 本机跑会遇到：

- Git for Windows 的路径/文件锁与命名管道语义和 Linux 不同（`git status`、索引刷新、子进程输出重定向到日志文件的时序不一致）；
- 符号链接测试在 Windows 普通账号下不可用，仓库测试改用目录联接（junction），与 Linux 路径行为不是同一套语义；
- 子 JVM 被 `Runtime.halt` 后的进程树回收、`/bin/sh` 与 PowerShell 命令语义不同；
- 文件属主、权限位（`chmod`）与 systemd/容器的验收都只在 Linux 成立。

因此 CI 是“Linux 门禁”，Windows 只适合本地快速迭代；发布前以 CI 绿为准。`ubuntu-latest` 自带 `git`，正好满足临时仓库需求。

## 发布流水线（release.yml）

- 触发：推送 `v*` 标签，或 `workflow_dispatch` 手动触发（手动触发需填 `tag`）。
- 门禁：标签名必须等于 `v` + `pom.xml` 版本（例如 `v0.1.0`），避免“标签是 0.2.0、jar 是 0.1.0”。
- 构建：`mvn -B -ntp -DskipTests package`（测试由 CI 门禁保证，发布只做确定性打包）。
- 产物：`target/superagent-java.jar` 与 `target/superagent-java.jar.sha256`，同时上传 workflow artifact 作为兜底。
- 发布：`softprops/action-gh-release@v2`，`generate_release_notes: true`，`fail_on_unmatched_files: true`。

运维侧验证下载产物：

```bash
sha256sum -c superagent-java.jar.sha256   # 输出 OK
java -jar superagent-java.jar --help
```

发布规范：先合并到 `main` 并让 CI 双 JDK 绿，再打标签；标签一旦推送即产生公开产物，打错需删除 Release 与标签后重发，不要在旧标签上覆盖上传（会破坏已下载用户的校验和信任）。

供应链建议：`actions/checkout`、`actions/setup-java`、`actions/upload-artifact`、`softprops/action-gh-release` 目前固定到主版本标签，便于升级；企业仓库可改为固定 commit SHA，并用 Dependabot/Renovate 受控升级。

## 容器化部署

### 镜像设计

| 项目 | 做法 | 理由 |
|---|---|---|
| 构建阶段 | `maven:3.9-eclipse-temurin-17` | 与 CI 相同 JDK 17，产物一致 |
| 运行阶段 | `eclipse-temurin:17-jre-jammy` | 只带 JRE，体积小 |
| 用户 | `uid/gid 10001`（`USER 10001:10001`） | 非 root |
| 内容 | 只复制 `superagent-java.jar` + `git`/`curl`/CA 证书 | 运行期依赖明确 |
| 内存 | `-XX:MaxRAMPercentage=75` | 按 cgroup 限制计算堆 |
| 探针 | `HEALTHCHECK` 请求 `/readyz` | 就绪判定基于状态库 |
| 默认入口 | `--serve-local --repo /app/source` | 容器内没有 rootless Docker |

### 为什么容器默认跑 `--serve-local`

`--serve`（GitHub 常驻服务）启动时会 `docker.verifyAndClean()`：检查 Docker context 是本机 Unix socket、要求 rootless + cgroup v2，并清理遗留沙箱容器。也就是说 **`--serve` 必须有宿主机的 rootless Docker CLI 和 socket**。

把宿主机 rootless Docker socket 挂进容器，等于让容器获得宿主用户创建容器的能力，`no-new-privileges`、`cap_drop: ALL`、只读根文件系统都挡不住这种越权；而且 rootless socket 属主是宿主用户，容器内 uid 10001 还需要额外的 uid/gid 对齐。因此本次交付的选择是：

- **容器镜像只承载服务本体（`--serve-local`，命令执行被禁用）**，适合联调、演示和自动化环境；
- **GitHub 常驻服务（`--serve`）继续用 systemd 部署**（`deploy/superagent.service`），让沙箱命令执行留在宿主机的隔离边界内；
- 若确实需要把 `--serve` 容器化，需要单独设计 Docker socket 委派或 DinD 方案并重新做安全评审，不在本次交付范围，也未验证。

### compose 加固清单

| 项 | 取值 | 说明 |
|---|---|---|
| 根文件系统 | `read_only: true` | 只有命名卷和 tmpfs 可写 |
| 临时目录 | `tmpfs: /tmp:rw,nosuid,nodev,size=256m,mode=1777` | **不能加 `noexec`**：sqlite-jdbc 会从 `java.io.tmpdir` 解压并 dlopen 原生库 |
| 提权 | `security_opt: [no-new-privileges:true]`、`cap_drop: [ALL]` | 与 systemd 的 `NoNewPrivileges` 对齐 |
| 用户 | `user: "10001:10001"` | 与镜像内非 root 账号一致 |
| 状态 | 命名卷 `superagent-state:/app/state` | 状态目录持久化 |
| 源码 | `./source:/app/source:ro` | 源仓库只读 |
| 资源 | `pids_limit: 256`、`mem_limit: 2g`、`cpus: 2.0` | 防止拖垮宿主机；`MaxRAMPercentage` 依赖 `mem_limit` |
| 网络 | `network_mode: host`，**不写 `ports`** | 应用硬编码监听 `127.0.0.1`，bridge 网络的端口发布到不了容器回环地址；host 网络下 8080 仍只监听宿主机 `127.0.0.1`，安全目标等价于 `127.0.0.1:8080:8080` |
| 健康检查 | `curl -fsS http://127.0.0.1:8080/readyz` | 与镜像内 HEALTHCHECK 一致 |
| 停止 | `stop_grace_period: 45s` | 与 systemd `TimeoutStopSec` 对齐 |

### 首次部署步骤

```bash
# 1. 秘密配置（该文件含模型 Key 与运维令牌，不要提交）
cp deploy/service.env.example deploy/service.env
chmod 600 deploy/service.env
# 编辑：填 SUPERAGENT_API_KEY、>=32 字符的 SUPERAGENT_OPERATOR_TOKEN 等

# 2. 目标仓库（容器内 uid 10001 必须能读；git 所有权检查无法通过配置绕过）
mkdir -p source
git clone <目标仓库> source
sudo chown -R 10001:10001 source

# 3. 构建并启动
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8080/readyz
docker compose logs -f
```

说明：应用给 git 子进程设置了 `GIT_CONFIG_NOSYSTEM=1` 和 `GIT_CONFIG_GLOBAL=/dev/null`，所以镜像里写 `safe.directory` 不会生效，必须让 `source` 的属主与容器 uid 一致。

### 验证状态（重要）

**容器镜像尚未在真机验证**：当前开发机没有安装 Docker，本次只做了 Dockerfile/compose 的逐行静态复核（字段合法、多阶段引用一致、路径与端口自洽），没有执行过 `docker build`、`docker run`、`docker compose up`，也没有验证过 HEALTHCHECK 的实际返回。

首次部署必须按 [service.md 的容器化验收清单](service.md#容器化部署未在真机验证) 逐项执行，包括：镜像构建成功、容器以非 root 运行、根文件系统只读、`/readyz` 健康、端口只监听 `127.0.0.1`、状态在重启后保留、备份脚本可恢复。在上述清单完成前，不要把镜像当作“已验证可交付”。

## 备份与恢复（deploy/backup.sh）

`backup.sh` 的行为：

1. 校验参数与状态目录（必须有 `state.db`），并用 `flock` 防止并行备份；
2. 停止 systemd 服务（`--scope user|system`；容器或离线场景用 `--scope none`），并在退出时自动恢复原来的运行状态；
3. 用 `fuser` 确认 `instance.lock` 没有被进程占用；
4. 有 `sqlite3` 时用 `.backup` 生成一致性数据库副本，并做 `PRAGMA integrity_check`；同时复制工作副本、交付物和日志（跳过原始 `state.db*`）。没有 `sqlite3` 时退化为停服后整目录打包；
5. `tar.gz` 打包（GNU tar 可用时用 `--sort=name --owner=0 --group=0 --numeric-owner` 生成可复现元数据）；
6. 校验：`gzip -t`、`tar -tzf` 可列举、归档内存在 `./state.db`、生成 `.sha256` 并用 `sha256sum -c` 自检；
7. 只保留最近 `SUPERAGENT_BACKUP_KEEP`（默认 7）份，连同 `.sha256` 一起清理。

首次使用前给执行权限：`chmod +x deploy/backup.sh`（本仓库在 Windows 上准备，Git 不会随提交固化可执行位；也可以用 `bash deploy/backup.sh` 调用）。

为什么不能直接热拷贝 SQLite：`Store` 启动时设置 `journal_mode=WAL`。WAL 模式下未 checkpoint 的事务只存在于 `state.db-wal`，热复制 `state.db` 会丢失最新数据甚至得到撕裂副本；`.backup` 走 SQLite 在线备份 API，能读到包含 WAL 的完整快照。没有 `sqlite3` 时之所以强调“先停服”，就是因为服务正常关闭会 checkpoint 并清理 WAL。

恢复（必须停服务，并保持原绝对路径；应用会核对仓库绑定）：

```bash
systemctl --user stop superagent.service
cd ~/superagent/backups                                  # 校验和文件与归档放在一起
sha256sum -c superagent-state-<时间>.tar.gz.sha256       # 输出 OK
tar -xzf superagent-state-<时间>.tar.gz -C ~/superagent/state   # 归档根是状态目录内容
systemctl --user start superagent.service
```

定时备份（用户级 timer，示例）：

```ini
# ~/.config/systemd/user/superagent-backup.service
[Unit]
Description=Super-agent backup
[Service]
Type=oneshot
# 用 /bin/bash 显式调用，避免依赖部署机上 backup.sh 的可执行位。
ExecStart=/bin/bash %h/superagent/deploy/backup.sh
```

```ini
# ~/.config/systemd/user/superagent-backup.timer
[Unit]
Description=Daily Super-agent backup
[Timer]
OnCalendar=*-*-* 03:30:00
Persistent=true
[Install]
WantedBy=timers.target
```

## 系统加固核对

`deploy/superagent.service` 的取舍已在文件中逐条用中文注释，部署后用下列命令核对“写了但没生效”的情况：

```bash
systemctl --user show superagent.service -p NoNewPrivileges -p ProtectSystem -p ProtectHome \
  -p ProtectKernelTunables -p RestrictAddressFamilies -p RestrictNamespaces -p MemoryMax \
  -p TasksMax -p SystemCallFilter -p UMask
systemd-analyze --user security superagent.service   # 查看暴露面评分与仍开放的项
journalctl --user -u superagent.service -o cat | grep -i -E 'seccomp|EPERM|denied'
```

三个容易被“通用加固模板”踩坏的点：

1. **`MemoryDenyWriteExecute` 不能开**：HotSpot JIT 需要同时可写可执行的内存页，开了 JVM 起不来；本单元刻意不设置。
2. **`/tmp` 不能只读**：sqlite-jdbc 会解压原生库到 `java.io.tmpdir`，`PrivateTmp=yes` 提供可写可执行的私有 tmpfs。
3. **`RestrictNamespaces=yes` 与 rootless Docker 的关系**：docker CLI 只连 socket，不创建命名空间，所以可以在本单元开启；只有把 rootless dockerd 也放进同一单元时才需要放宽为 `RestrictNamespaces=~user`，文件里已注明。

## 已验证与未验证

| 内容 | 状态 |
|---|---|
| CI/release 工作流 YAML 结构与字段 | 逐行人工复核；本机无 PyYAML/actionlint，未做解析器级校验，`on:`/`with:`/`uses:` 字段按 GitHub Actions 语法核对 |
| `deploy/backup.sh` 语法 | 已用 `bash -n deploy/backup.sh` 通过 |
| `deploy/backup.sh` 无 sqlite3 的整目录分支 | 已在 Git Bash 用临时状态目录端到端验证：打包、`gzip -t`/`tar -tzf` 列举、归档内含 `./state.db`、`sha256sum -c` 通过、保留份数删除最旧归档、临时目录清理、退出码 0 |
| `deploy/backup.sh` 的 systemd 停服/重启与 `sqlite3 .backup` 分支 | 未验证：本机无 systemd、无 sqlite3 |
| Dockerfile / docker-compose.yml | 仅静态复核；**未构建、未运行** |
| systemd 加固项实际生效 | 未验证：本机为 Windows，需在 Linux 真机用上面的 `systemd-analyze` 核对 |
| 真实模型、rootless Docker、GitHub PR | 未验证，见 [service.md](service.md) 验收清单 |
