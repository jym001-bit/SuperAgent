# Super-agent Java 运行镜像（多阶段构建）。
#
# 需要 Docker 23+（默认 BuildKit），本文件用 RUN --mount=type=cache 缓存 Maven 仓库。
# 本镜像只承载服务本体：沙箱命令执行依赖宿主机的 rootless Docker，不在镜像内，
# 因此默认入口是 --serve-local；GitHub 常驻服务（--serve）仍按 docs/service.md 用 systemd 部署。
#
# 构建：
#   docker build -t superagent-java:0.1.0 .
#   docker run --rm superagent-java:0.1.0 --help

# ---------------------------------------------------------------- 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先只复制 pom.xml，让依赖解析成为可缓存层；源码变化时无需重新下载依赖。
# 依赖下载在构建镜像时联网完成，运行镜像本身不联网（离线测试与 CI 一致）。
COPY pom.xml ./
COPY src ./src

# 测试由 CI 门禁（.github/workflows/ci.yml）执行；镜像构建只做确定性打包。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# ---------------------------------------------------------------- 运行阶段
FROM eclipse-temurin:17-jre-jammy AS runtime

# 非 root 运行账号：固定 uid/gid，便于宿主机挂载卷的属主对齐。
ARG UID=10001
ARG GID=10001
ARG VERSION=0.1.0

LABEL org.opencontainers.image.title="superagent-java" \
      org.opencontainers.image.description="Super-agent Java 编码代理（本地 HTTP 入口）" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="UNLICENSED"

# 应用与 git 子进程只继承白名单环境变量，HOME 指向可写的私有 /tmp；
# 镜像内状态目录固定，compose 再用同名环境变量指向命名卷。
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=UTC \
    HOME=/tmp \
    SUPERAGENT_STATE_DIR=/app/state

# git：任务副本的 clone/commit/diff 全部通过 git 子进程完成，必须安装。
# curl：HEALTHCHECK 与运维探活使用（比装 JDK 内部 HTTP 客户端更简单可靠）。
# ca-certificates：访问模型 API 与 GitHub 需要 TLS 根证书。
# 不安装 docker CLI：容器内没有 rootless Docker，沙箱执行不可用，见 docs/service.md。
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git; \
    rm -rf /var/lib/apt/lists/*; \
    groupadd --system --gid "${GID}" superagent; \
    useradd --system --uid "${UID}" --gid "${GID}" \
        --home-dir /app --no-create-home --shell /usr/sbin/nologin superagent; \
    mkdir -p /app/state; \
    chown -R "${UID}:${GID}" /app

WORKDIR /app

# 只复制运行所需的 fat jar，构建工具链和源码不进入运行镜像。
# 命名卷首次挂载时会继承 /app/state 的属主，因此非 root 账号可直接写入。
COPY --from=build --chown=${UID}:${GID} /build/target/superagent-java.jar /app/superagent-java.jar

# 非 root（uid/gid 10001）。即使有人误删 USER，也不要以 root 启动。
USER 10001:10001

# 仅作说明：应用硬编码监听 127.0.0.1，bridge 网络的端口发布无法到达回环地址，
# 实际访问请使用 host 网络（docker-compose.yml）或同网络命名空间内的反向代理。
EXPOSE 8080

# 就绪探针读取 /readyz（会检查 SQLite 状态库），未就绪时容器显示 unhealthy。
# start-period 给 SQLite 迁移和 Git 仓库检查留时间。
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/readyz || exit 1

# 固定 JVM 容器内存策略：按 cgroup 上限的 75% 使用堆，避免与容器内存限制冲突。
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/superagent-java.jar"]

# 默认入口。--serve-local 需要 SUPERAGENT_API_KEY 与长度 >=32 的 SUPERAGENT_OPERATOR_TOKEN。
CMD ["--serve-local","--repo","/app/source"]
