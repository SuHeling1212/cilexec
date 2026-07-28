#!/usr/bin/env bash
# ==============================================================================
# CilExec 恢复脚本 — 将系统恢复到安装 CilExec 之前的状态
#
# 此脚本会删除 CilExec 创建的所有 Docker 资源（容器、镜像、卷、网络）
# 以及本地生成的密码文件和导出文件。
#
# 用法: ./cilexec-restore.sh [--force]
#   --force  跳过确认提示，直接执行清理
# ==============================================================================
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$project_dir"

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
# 使用与 install.sh 相同的项目名和卷名计算逻辑，
# 确保能正确找到并清理对应安装实例的资源。
project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="cilexec-${project_hash}"
export CILEXEC_POSTGRES_VOLUME="cilexec-pgdata-${project_hash}"
IMAGE_NAME="${CILEXEC_IMAGE_TAG:-cilexec:local}"
VOLUME_NAME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
SECRET_DIR="$project_dir/docker/secrets"
EXPORT_DIR="${CILEXEC_EXPORT_DIRECTORY:-$project_dir/exports}"

FORCE=false
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
    printf "${RED}${BOLD}║  警告：此操作将永久删除 CilExec 的所有数据！                       ║${NC}\n"
    printf "${RED}${BOLD}║  包括 Docker 容器、镜像、数据库卷、密码文件和导出文件。              ║${NC}\n"
    printf "${RED}${BOLD}║  此操作不可逆！                                                 ß║${NC}\n"
    printf "${RED}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}\n"
    echo ""

    if command -v docker >/dev/null 2>&1; then
        echo "当前 CilExec 相关 Docker 资源："
        echo "──────────────────────────────────────────────"

        echo ""
        echo "【容器】"
        docker ps -a --filter "name=cilexec" --format '  {{.Names}} ({{.Status}})' 2>/dev/null || echo "  (无)"

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

# 尝试 compose.yml + compose.persistent.yml 组合（最常见的部署）
if [[ -f "$project_dir/compose.yml" ]]; then
    compose_files=(-f "$project_dir/compose.yml")

    if [[ -f "$project_dir/compose.persistent.yml" ]]; then
        compose_files+=(-f "$project_dir/compose.persistent.yml")
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
# 第 2 步：强制删除任何残留的 CilExec 容器
# ---------------------------------------------------------------------------
header "第 2 步：清理残留容器"

if command -v docker >/dev/null 2>&1; then
    containers=$(docker ps -a --filter "name=cilexec" -q 2>/dev/null || true)
    if [[ -n "$containers" ]]; then
        # shellcheck disable=SC2086
        docker rm -f $containers 2>/dev/null || true
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
        docker rmi -f "$IMAGE_NAME" 2>/dev/null || true
        info "已删除镜像 $IMAGE_NAME"
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
    networks=$(docker network ls --filter "name=cilexec" -q 2>/dev/null || true)
    if [[ -n "$networks" ]]; then
        # shellcheck disable=SC2086
        docker network rm $networks 2>/dev/null || true
        info "已删除 CilExec 网络"
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
    rm -rf "$SECRET_DIR"
    info "已删除密码目录 $SECRET_DIR"
else
    info "密码目录 $SECRET_DIR 不存在"
fi

# ---------------------------------------------------------------------------
# 第 7 步：删除导出文件
# ---------------------------------------------------------------------------
header "第 7 步：删除导出文件"

if [[ -d "$EXPORT_DIR" ]]; then
    rm -rf "$EXPORT_DIR"
    info "已删除导出目录 $EXPORT_DIR"
else
    info "导出目录 $EXPORT_DIR 不存在"
fi

# ---------------------------------------------------------------------------
# 第 8 步：清理 Docker 构建缓存（可选）
# ---------------------------------------------------------------------------
header "第 8 步：清理 Docker 构建缓存"

if command -v docker >/dev/null 2>&1; then
    docker builder prune --force 2>/dev/null || true
    info "已清理构建缓存"
else
    warn "Docker 不可用，跳过构建缓存清理"
fi

# ---------------------------------------------------------------------------
# 完成
# ---------------------------------------------------------------------------
echo ""
printf "${GREEN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}\n"
printf "${GREEN}${BOLD}║  CilExec 已完全从本机移除，系统已恢复到安装前状态。                 ║${NC}\n"
printf "${GREEN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}\n"
echo ""
echo "已删除的内容："
echo "  • Docker 容器 (cilexec, postgres, migrate)"
echo "  • Docker 镜像 ($IMAGE_NAME)"
echo "  • 数据卷 ($VOLUME_NAME) — 所有数据库数据"
echo "  • Docker 网络 (cilexec_*)"
echo "  • 密码文件 ($SECRET_DIR)"
echo "  • 导出文件 ($EXPORT_DIR)"
echo ""
echo "未受影响的内容："
echo "  • 源代码目录 ($project_dir)"
echo "  • Maven 本地缓存 (~/.m2)"
echo "  • Docker 本身和其他项目"
echo ""
