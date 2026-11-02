#!/usr/bin/env bash
# HOScrcpy 启动脚本
#  1) 定位 hdc（工具本身与 SDK 都依赖裸命令 hdc）
#  2) 启动 jar：源码构建产物 target/HOScrcpy.jar，或与本脚本同目录的 HOScrcpy-<版本>-<平台>.jar
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ---------- 1) 定位 hdc ----------
HDC_BIN=""
if command -v hdc >/dev/null 2>&1; then
  HDC_BIN="$(command -v hdc)"                                   # (1) PATH 里已有
elif [ -n "${HDC_DIR:-}" ] && [ -x "${HDC_DIR}/hdc" ]; then
  HDC_BIN="${HDC_DIR}/hdc"                                      # (2) 用 HDC_DIR 指定
else
  for root in "$HOME/openHarmony/command-line-tools" "$HOME/command-line-tools" /opt/command-line-tools /usr/local/command-line-tools; do
    [ -d "$root" ] || continue
    found="$(find "$root" -type f -name hdc 2>/dev/null | head -n1 || true)"
    if [ -n "$found" ]; then HDC_BIN="$found"; break; fi         # (3) 常见位置搜索
  done
fi

if [ -z "$HDC_BIN" ]; then
  echo "错误：找不到 hdc。任选一种方式：" >&2
  echo "   1) 把 OpenHarmony command-line-tools 的 toolchains 目录加入 PATH；或" >&2
  echo "   2) HDC_DIR=/path/to/toolchains bash run.sh" >&2
  exit 1
fi
export PATH="$(dirname "$HDC_BIN"):$PATH"
echo "hdc: $HDC_BIN  ($(hdc -v 2>/dev/null || true))"

# ---------- 2) 定位 jar ----------
JAR="$SCRIPT_DIR/target/HOScrcpy.jar"
if [ ! -f "$JAR" ]; then
  JAR="$(ls -1 "$SCRIPT_DIR"/HOScrcpy-*.jar 2>/dev/null | head -n1 || true)"
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "错误：找不到 HOScrcpy.jar" >&2
  echo "   源码构建：先执行  mvn clean package -DskipTests" >&2
  echo "   Release  ：把本脚本与 HOScrcpy-<版本>-<平台>.jar 放在同一目录" >&2
  exit 1
fi
echo "启动投屏工具：$JAR"

# ---------- 3) 图形环境 ----------
export DISPLAY="${DISPLAY:-:0}"

exec java -jar "$JAR" "$@"
