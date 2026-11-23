#!/usr/bin/env bash
# 把各 buildable 目标的构建产物收进 dist/<mod_version>/（Release/部署以此目录为准）。
# 先自动跑一次守卫闸；构建请先跑 tools/build_all.sh。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MOD_VERSION=$(grep -E '^mod_version=' "$ROOT/gradle.properties" | cut -d= -f2 | tr -d '[:space:]')
DEST="$ROOT/dist/$MOD_VERSION"
mkdir -p "$DEST"

# tr -d '\r'：Windows python 的 print 往管道写 \r\n，$() 只截尾部换行，
# 中间行会带着 \r 混进路径 —— 已实锤踩过（forge 目标排第一行，路径悄悄失效）。
BUILDABLE=$(python tools/verify_targets.py --list-buildable | tr -d '\r')

copied=0
while IFS= read -r project; do
    [ -z "$project" ] && continue
    archive_base=$(grep -E '^archives_base_name=' "$ROOT/$project/gradle.properties" | cut -d= -f2 | tr -d '\r')
    jar_name="$archive_base-v$MOD_VERSION.jar"
    jar="$ROOT/$project/build/libs/$jar_name"
    if [ ! -f "$jar" ]; then
        echo "Missing build output $project/build/libs/$jar_name" >&2
        exit 1
    fi
    cp -f "$jar" "$DEST/"
    echo "collected $jar_name"
    copied=$((copied+1))
done <<< "$BUILDABLE"

echo "dist/$MOD_VERSION: $copied jar(s)"
