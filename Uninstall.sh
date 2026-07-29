#!/usr/bin/env bash
# ==============================================================================
# CilExec 卸载脚本 — 删除当前安装目录对应的 CilExec 实例
#
# 此脚本仅删除当前 Compose 项目的容器、卷和网络，以及当前安装目录内
# 生成的密码文件和默认导出目录；不会全局清理其他实例或 Docker 缓存。
#
# 用法: ./Uninstall.sh [--force]
#   --force  跳过确认提示，直接执行清理
# ==============================================================================
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd -P)"
cd "$project_dir"

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
# 使用与 install.sh 相同的项目名和卷名计算逻辑，
# 确保能正确找到并清理对应安装实例的资源。
project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
IMAGE_NAME="cilexec:${CILEXEC_IMAGE_TAG:-local}"
VOLUME_NAME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
SECRET_DIR="$project_dir/docker/secrets"
SECRET_FILES=(
    postgres-admin-password
    cilexec-migrator-password
    cilexec-runtime-password
    cilexec-effect-worker-password
    cilexec-readonly-password
)
DEFAULT_EXPORT_DIR="$project_dir/exports"
configured_export_dir="${CILEXEC_EXPORT_DIRECTORY:-$DEFAULT_EXPORT_DIR}"
if [[ "$configured_export_dir" == /* ]]; then
    EXPORT_DIR="${configured_export_dir%/}"
else
    EXPORT_DIR="${project_dir}/${configured_export_dir#./}"
    EXPORT_DIR="${EXPORT_DIR%/}"
fi

FORCE=false
IMAGE_REMOVED=false
if [[ "${1:-}" == "--force" ]]; then
    FORCE=true
fi

# ---------------------------------------------------------------------------
# 颜色输出
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m' # No Color

info()  { printf "  ${GREEN}✓${NC} %s\n" "$*"; }
warn()  { printf "  ${YELLOW}⚠${NC} %s\n" "$*"; }
error() { printf "  ${RED}✗${NC} %s\n" "$*"; }
header() {
    echo ""
    printf "${BOLD}%s${NC}\n" "$*"
}

# ---------------------------------------------------------------------------
# 确认
# ---------------------------------------------------------------------------
if [[ "$FORCE" != true ]]; then
    echo ""
    printf "${RED}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}\n"
    printf "${RED}${BOLD}║  警告：此操作将永久删除当前 CilExec 实例的数据！                    ║${NC}\n"
    printf "${RED}${BOLD}║  包括容器、数据库卷、密码文件和默认导出文件。                        ║${NC}\n"
    printf "${RED}${BOLD}║  此操作不可逆！                                                  ║${NC}\n"
    printf "${RED}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}\n"
    echo ""

    if command -v docker >/dev/null 2>&1; then
        echo "当前 CilExec 相关 Docker 资源："
        echo "──────────────────────────────────────────────"

        echo ""
        echo "【容器】"
        docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
            --format '  {{.Names}} ({{.Status}})' 2>/dev/null || echo "  (无)"

        echo ""
        echo "【镜像】"
        if docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
            docker image inspect "$IMAGE_NAME" --format='  {{.RepoTags}} ({{.Size}})' 2>/dev/null
        else
            echo "  (无)"
        fi

        echo ""
        echo "【数据卷】"
        if docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
            docker volume inspect "$VOLUME_NAME" --format='  {{.Name}} ({{.Mountpoint}})' 2>/dev/null
        else
            echo "  (无)"
        fi

        echo ""
        echo "【网络】"
        docker network ls --filter "name=cilexec" --format '  {{.Name}}' 2>/dev/null || echo "  (无)"
    fi

    echo ""
    echo "【密码文件】"
    if [[ -d "$SECRET_DIR" ]] && ls "$SECRET_DIR"/* 2>/dev/null | grep -q .; then
        ls -1 "$SECRET_DIR" 2>/dev/null | while read -r f; do
            echo "  $SECRET_DIR/$f"
        done
    else
        echo "  (无)"
    fi

    echo ""
    echo "【导出文件】"
    if [[ -d "$EXPORT_DIR" ]] && ls "$EXPORT_DIR"/* 2>/dev/null | grep -q .; then
        ls -1 "$EXPORT_DIR" 2>/dev/null | while read -r f; do
            echo "  $EXPORT_DIR/$f"
        done
    else
        echo "  (无)"
    fi

    echo ""
    echo "──────────────────────────────────────────────"
    read -r -p "确认要删除以上所有 CilExec 资源吗？输入 yes 继续: " confirm
    if [[ "$confirm" != "yes" ]]; then
        echo "已取消。"
        exit 0
    fi
fi

# ---------------------------------------------------------------------------
# 第 1 步：停止并删除所有 Compose 容器和网络
# ---------------------------------------------------------------------------
header "第 1 步：停止并删除 Compose 服务"

# 尝试主 Compose + 持久化变体组合（最常见的部署）
if [[ -f "$project_dir/compose.yml" ]]; then
    compose_files=(-f "$project_dir/compose.yml")

    persistent_compose="$project_dir/docker/compose/persistent.yml"
    if [[ -f "$persistent_compose" ]]; then
        compose_files+=(-f "$persistent_compose")
    fi

    echo "使用 compose 文件停止服务..."
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        docker compose "${compose_files[@]}" down --volumes --remove-orphans 2>/dev/null || true
        info "Compose 服务已停止并删除"
    else
        warn "Docker Compose 不可用，跳过"
    fi
fi

# ---------------------------------------------------------------------------
# 第 2 步：删除当前安装实例的残留容器
# ---------------------------------------------------------------------------
header "第 2 步：清理残留容器"

if command -v docker >/dev/null 2>&1; then
    containers=$(docker ps -a \
        --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
        -q 2>/dev/null || true)
    if [[ -n "$containers" ]]; then
        while read -r container; do
            [[ -z "$container" ]] || docker rm -f "$container" 2>/dev/null || true
        done <<< "$containers"
        info "已删除残留容器"
    else
        info "无残留容器"
    fi
else
    warn "Docker 不可用，跳过容器清理"
fi

# ---------------------------------------------------------------------------
# 第 3 步：删除 CilExec Docker 镜像
# ---------------------------------------------------------------------------
header "第 3 步：删除 Docker 镜像"

if command -v docker >/dev/null 2>&1; then
    if docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
        image_users=$(docker ps -a --filter "ancestor=$IMAGE_NAME" -q 2>/dev/null || true)
        if [[ -n "$image_users" ]]; then
            warn "镜像 $IMAGE_NAME 仍被其他容器使用，已保留"
        elif docker rmi "$IMAGE_NAME" >/dev/null 2>&1; then
            IMAGE_REMOVED=true
            info "已删除镜像 $IMAGE_NAME"
        else
            warn "无法安全删除镜像 $IMAGE_NAME，已保留"
        fi
    else
        info "镜像 $IMAGE_NAME 不存在"
    fi
else
    warn "Docker 不可用，跳过镜像删除"
fi

# ---------------------------------------------------------------------------
# 第 4 步：删除 CilExec 数据卷
# ---------------------------------------------------------------------------
header "第 4 步：删除数据卷"

if command -v docker >/dev/null 2>&1; then
    if docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
        docker volume rm "$VOLUME_NAME" 2>/dev/null || true
        info "已删除数据卷 $VOLUME_NAME"
    else
        info "数据卷 $VOLUME_NAME 不存在"
    fi
else
    warn "Docker 不可用，跳过卷删除"
fi

# ---------------------------------------------------------------------------
# 第 5 步：删除 Compose 网络
# ---------------------------------------------------------------------------
header "第 5 步：删除网络"

if command -v docker >/dev/null 2>&1; then
    networks=$(docker network ls \
        --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
        -q 2>/dev/null || true)
    if [[ -n "$networks" ]]; then
        while read -r network; do
            [[ -z "$network" ]] || docker network rm "$network" 2>/dev/null || true
        done <<< "$networks"
        info "已删除当前实例的 CilExec 网络"
    else
        info "无 CilExec 网络"
    fi
else
    warn "Docker 不可用，跳过网络删除"
fi

# ---------------------------------------------------------------------------
# 第 6 步：删除密码文件
# ---------------------------------------------------------------------------
header "第 6 步：删除密码文件"

if [[ -d "$SECRET_DIR" ]]; then
    for secret_file in "${SECRET_FILES[@]}"; do
        rm -f "$SECRET_DIR/$secret_file"
    done
    # Keep the directory inode stable. Docker Desktop for macOS may retain a
    # stale bind-mount view when a shared directory is removed and recreated,
    # causing an immediate reinstall to report randomly missing secret files.
    info "已删除密码文件（保留目录 $SECRET_DIR 以便重新安装）"
else
    info "密码目录 $SECRET_DIR 不存在"
fi

# ---------------------------------------------------------------------------
# 第 7 步：删除导出文件
# ---------------------------------------------------------------------------
header "第 7 步：删除导出文件"

if [[ "$EXPORT_DIR" != "$DEFAULT_EXPORT_DIR" ]]; then
    warn "自定义导出目录不属于安装实例，已保留：$EXPORT_DIR"
elif [[ -d "$DEFAULT_EXPORT_DIR" ]]; then
    rm -rf "$DEFAULT_EXPORT_DIR"
    info "已删除默认导出目录 $DEFAULT_EXPORT_DIR"
else
    info "默认导出目录 $DEFAULT_EXPORT_DIR 不存在"
fi

# ---------------------------------------------------------------------------
# 第 8 步：保留共享 Docker 构建缓存
# ---------------------------------------------------------------------------
header "第 8 步：保留共享构建缓存"
info "Docker 构建缓存可能被其他项目使用，未执行全局清理"

# ---------------------------------------------------------------------------
# 完成
# ---------------------------------------------------------------------------
echo ""
printf "${GREEN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}\n"
printf "${GREEN}${BOLD}║  当前 CilExec 安装实例已移除。                                     ║${NC}\n"
printf "${GREEN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}\n"
echo ""
echo "已删除的内容："
echo "  • 当前 Compose 实例的容器 ($COMPOSE_PROJECT_NAME)"
if [[ "$IMAGE_REMOVED" == true ]]; then
    echo "  • 未被其他容器使用的 Docker 镜像 ($IMAGE_NAME)"
fi
echo "  • 数据卷 ($VOLUME_NAME) — 所有数据库数据"
echo "  • 当前 Compose 实例的网络 ($COMPOSE_PROJECT_NAME)"
echo "  • 密码文件 ($SECRET_DIR)"
if [[ "$EXPORT_DIR" == "$DEFAULT_EXPORT_DIR" ]]; then
    echo "  • 默认导出文件 ($DEFAULT_EXPORT_DIR)"
fi
echo ""
echo "未受影响的内容："
echo "  • 源代码目录 ($project_dir)"
echo "  • Maven 本地缓存 (~/.m2)"
echo "  • Docker 本身和其他项目"
echo "  • Docker 全局构建缓存"
if [[ "$IMAGE_REMOVED" != true ]]; then
    echo "  • 共享或不存在的 Docker 镜像 ($IMAGE_NAME)"
fi
if [[ "$EXPORT_DIR" != "$DEFAULT_EXPORT_DIR" ]]; then
    echo "  • 自定义导出目录 ($EXPORT_DIR)"
fi
echo ""
