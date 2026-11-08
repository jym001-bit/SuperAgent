#!/usr/bin/env bash
# Super-agent 状态目录备份。
#
# 为什么必须先停服务、为什么用 sqlite3 .backup：
#   状态目录里除任务工作副本、交付物和日志外还有 SQLite（Store 启动时设置 journal_mode=WAL）。
#   WAL 模式下热复制 state.db 只能拿到已 checkpoint 的部分，还会漏掉 state.db-wal 里的最新事务，
#   直接整目录 tar 也可能复制到不一致的中间状态。正确做法是：
#     1) 停止服务，保证没有写入者；
#     2) 用 sqlite3 的 .backup（即 SQLite 在线备份 API）生成一致性数据库副本 —— 它通过
#        SQLite 自己读取 WAL，得到的是完整且可校验的快照；
#     3) 其余文件（work/、deliveries/、logs/）在停服后直接复制。
#   若机器上没有 sqlite3，则退化为“停服 + 整目录打包”：服务正常关闭时最后一个连接会
#   checkpoint 并删除 -wal/-shm，此时整目录复制是一致的；脚本会同时保留 WAL 文件以便恢复。
#
# 恢复（必须停服务，保持原绝对路径，应用会核对仓库与绑定）：
#   cd <备份目录> && sha256sum -c superagent-state-<时间>.tar.gz.sha256 \
#     && tar -xzf superagent-state-<时间>.tar.gz -C <状态目录>
#
# 用法：
#   deploy/backup.sh                                  # 读取环境变量默认值
#   deploy/backup.sh --keep 14                        # 保留最近 14 份
#   deploy/backup.sh --state /srv/sa/state --backup-dir /srv/backup --scope system
#   deploy/backup.sh --scope none                     # 容器/离线场景：已确认服务停止
#
# 环境变量：
#   SUPERAGENT_STATE_DIR      状态目录（默认 ~/superagent/state，应与 service.env 一致）
#   SUPERAGENT_BACKUP_DIR     备份目录（默认状态目录同级的 backups/）
#   SUPERAGENT_BACKUP_KEEP    保留份数（默认 7）
#   SUPERAGENT_SERVICE        systemd 单元名（默认 superagent.service）
#   SUPERAGENT_SERVICE_SCOPE  user|system|none（默认 user，即 systemctl --user）
set -euo pipefail

log() { printf '[backup %s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { printf '[backup %s] 错误: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
用法: deploy/backup.sh [选项]

  --state <目录>        状态目录（默认 $SUPERAGENT_STATE_DIR 或 ~/superagent/state）
  --backup-dir <目录>   备份目录（默认状态目录同级的 backups/）
  --keep <N>            保留最近 N 份归档（默认 7）
  --service <单元名>    systemd 单元（默认 superagent.service）
  --scope <user|system|none>
                        停服方式：systemctl --user / systemctl / 不停服（默认 user）
  -h, --help            显示本帮助

备份流程：停服 → sqlite3 .backup（没有 sqlite3 则停服整目录）→ 打包 →
gzip/tar/SHA-256 校验 → 只保留最近 N 份，并在退出时恢复服务运行状态。
EOF
}

STATE_DIR="${SUPERAGENT_STATE_DIR:-$HOME/superagent/state}"
KEEP="${SUPERAGENT_BACKUP_KEEP:-7}"
SERVICE="${SUPERAGENT_SERVICE:-superagent.service}"
SCOPE="${SUPERAGENT_SERVICE_SCOPE:-user}"
BACKUP_DIR="${SUPERAGENT_BACKUP_DIR:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --state)       [ "$#" -ge 2 ] || die "--state 缺少参数";        STATE_DIR="$2"; shift 2 ;;
    --backup-dir)  [ "$#" -ge 2 ] || die "--backup-dir 缺少参数";   BACKUP_DIR="$2"; shift 2 ;;
    --keep)        [ "$#" -ge 2 ] || die "--keep 缺少参数";         KEEP="$2"; shift 2 ;;
    --service)     [ "$#" -ge 2 ] || die "--service 缺少参数";      SERVICE="$2"; shift 2 ;;
    --scope)       [ "$#" -ge 2 ] || die "--scope 缺少参数";        SCOPE="$2"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
    *)             die "未知参数: $1（--help 查看用法）" ;;
  esac
done

[ -n "$BACKUP_DIR" ] || BACKUP_DIR="$(dirname "$STATE_DIR")/backups"

# ---- 参数校验 -------------------------------------------------------------
case "$SCOPE" in user|system|none) ;; *) die "SUPERAGENT_SERVICE_SCOPE 只能是 user/system/none" ;; esac
case "$KEEP" in ''|*[!0-9]*) die "保留份数必须是正整数: $KEEP" ;; esac
KEEP=$((10#$KEEP))
[ "$KEEP" -ge 1 ] || die "保留份数必须 >= 1"
command -v tar >/dev/null 2>&1 || die "需要 tar"
command -v gzip >/dev/null 2>&1 || die "需要 gzip"
command -v sha256sum >/dev/null 2>&1 || die "需要 sha256sum（coreutils）"
[ -d "$STATE_DIR" ] || die "状态目录不存在: $STATE_DIR"
[ -f "$STATE_DIR/state.db" ] || die "状态目录缺少 state.db: $STATE_DIR"
if [ "$SCOPE" != "none" ] && ! command -v systemctl >/dev/null 2>&1; then
  die "未找到 systemctl；无 systemd 环境请用 --scope none 并自行确认服务已停止"
fi

mkdir -p "$BACKUP_DIR"
STATE_REAL="$(cd "$STATE_DIR" && pwd -P)"
BACKUP_REAL="$(cd "$BACKUP_DIR" && pwd -P)"
case "$BACKUP_REAL/" in
  "$STATE_REAL"/*) die "备份目录不能位于状态目录内: $BACKUP_REAL" ;;
esac

# ---- 并发保护 -------------------------------------------------------------
if command -v flock >/dev/null 2>&1; then
  exec 9>"$BACKUP_DIR/.backup.lock"
  flock -n 9 || die "已有另一个备份任务在运行"
else
  log "警告: 未找到 flock，跳过并发保护（请自行确认没有并行备份）"
fi

# ---- 停服务并在退出时恢复 ------------------------------------------------
WAS_RUNNING=0
STAGE_DIR=""

service_active() {
  case "$SCOPE" in
    user)   systemctl --user is-active --quiet "$SERVICE" ;;
    system) systemctl is-active --quiet "$SERVICE" ;;
    none)   return 1 ;;
  esac
}

service_ctl() {
  case "$SCOPE" in
    user)   systemctl --user "$1" "$SERVICE" ;;
    system) systemctl "$1" "$SERVICE" ;;
    *)      die "内部错误: 无法在 scope=$SCOPE 下控制服务" ;;
  esac
}

restart_service() {
  if [ "$WAS_RUNNING" -eq 1 ]; then
    log "重新启动 $SERVICE"
    if ! service_ctl start; then
      log "警告: 服务未能自动恢复，请手工执行 systemctl ${SCOPE:+--$SCOPE }start $SERVICE 检查"
    fi
  fi
}

on_exit() {
  local status=$?
  trap - EXIT
  if [ -n "$STAGE_DIR" ] && [ -d "$STAGE_DIR" ]; then
    rm -rf "$STAGE_DIR"
  fi
  restart_service
  exit "$status"
}
trap on_exit EXIT
# 收到中断信号时主动 exit，让 EXIT trap 有机会把服务重新拉起来；
# 否则 bash 被信号直接终止时不会执行 EXIT trap，服务会停在停止状态。
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$SCOPE" = "none" ]; then
  log "按 --scope none 跳过停服；请确认服务已停止"
else
  if service_active; then
    WAS_RUNNING=1
    log "停止 $SERVICE 以获得一致快照"
    service_ctl stop
    # systemctl stop 默认同步等待进程退出，再给 SQLite 关闭连接留一点余量。
    sleep 1
  else
    log "服务未运行，按离线备份处理"
  fi
fi

if command -v fuser >/dev/null 2>&1; then
  if fuser -s "$STATE_DIR/instance.lock" 2>/dev/null; then
    die "状态目录仍被进程占用（instance.lock），请先停止服务再备份"
  fi
fi

# ---- 生成一致性副本 -------------------------------------------------------
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARCHIVE="$BACKUP_DIR/superagent-state-${TIMESTAMP}.tar.gz"
[ ! -e "$ARCHIVE" ] || die "备份文件已存在: $ARCHIVE"
STAGE_DIR="$(mktemp -d "$BACKUP_DIR/.staging.XXXXXX")"

SQLITE3="$(command -v sqlite3 || true)"
if [ -n "$SQLITE3" ]; then
  log "使用 sqlite3 .backup 生成一致性数据库副本"
  ( cd "$STAGE_DIR" && "$SQLITE3" "$STATE_DIR/state.db" ".backup state.db" )
  INTEGRITY="$("$SQLITE3" "$STAGE_DIR/state.db" 'PRAGMA integrity_check;')"
  [ "$INTEGRITY" = "ok" ] || die "备份库完整性检查失败: $INTEGRITY"
  log "复制状态目录其余内容（跳过原始 db/wal/shm）"
  tar -C "$STATE_DIR" \
      --exclude=./state.db \
      --exclude=./state.db-wal \
      --exclude=./state.db-shm \
      -cf - . | tar -C "$STAGE_DIR" -xf -
else
  log "警告: 未找到 sqlite3，退化为停服后整目录打包（保留 WAL 文件）"
  tar -C "$STATE_DIR" -cf - . | tar -C "$STAGE_DIR" -xf -
fi

# ---- 打包（可用时生成可复现的归档元数据） ---------------------------------
# 注意不要写成 `tar --help | grep -q`：pipefail 下 grep 提前退出会让 tar 收到
# SIGPIPE，条件判断会错误地变成 false。
TAR_OPTS=()
TAR_HELP="$(tar --help 2>/dev/null || true)"
case "$TAR_HELP" in
  *--sort=name*) TAR_OPTS=(--sort=name --owner=0 --group=0 --numeric-owner) ;;
esac
log "打包 $ARCHIVE"
# --force-local：归档路径始终是本机路径。GNU tar 默认把 "host:path" 当远程归档，
# 路径里偶然出现冒号（或 Windows 盘符）时会误判；显式声明本地语义更安全。
tar --force-local -C "$STAGE_DIR" -czf "$ARCHIVE" ${TAR_OPTS[@]+"${TAR_OPTS[@]}"} .

# ---- 校验：gzip 完整性、可列举、数据库在归档内、SHA-256 自检 ----------------
log "校验归档"
gzip -t "$ARCHIVE"
tar --force-local -tzf "$ARCHIVE" > "$STAGE_DIR/.archive-listing"
grep -qx './state.db' "$STAGE_DIR/.archive-listing" || die "归档缺少 state.db"
( cd "$BACKUP_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256" )
( cd "$BACKUP_DIR" && sha256sum -c --status "$(basename "$ARCHIVE").sha256" )

# ---- 保留最近 N 份 --------------------------------------------------------
log "清理旧备份（保留最近 $KEEP 份）"
mapfile -t ARCHIVES < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'superagent-state-*.tar.gz' -printf '%f\n' | sort -r)
for ((i = KEEP; i < ${#ARCHIVES[@]}; i++)); do
  log "删除 ${ARCHIVES[$i]}"
  rm -f "$BACKUP_DIR/${ARCHIVES[$i]}" "$BACKUP_DIR/${ARCHIVES[$i]}.sha256"
done

log "完成: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
log "校验和: $ARCHIVE.sha256"
log "服务状态: $([ "$WAS_RUNNING" -eq 1 ] && echo '备份后重新启动' || echo '备份期间未运行')"
