#!/bin/bash
# build-engine.sh - 编 TV 无头引擎。
# engine/ 是 zdl 下载引擎的 submodule（zdl 仓库零改动）；TV 入口 engine-tv/main.go
# 单独放本仓库，构建时临时注入 submodule 的 zdl 模块里编译，编完抹掉还原干净
# （TV 入口要用 zdl 的 internal 包，必须临时进同一模块才合法）。
# 用法: ./build-engine.sh [armv7|arm64|x86|x86_64]  (默认 armv7)
set -e
ARCH=${1:-armv7}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

case $ARCH in
  armv7)   export GOOS=linux GOARCH=arm GOARM=7 ;;
  arm64)   export GOOS=linux GOARCH=arm64 ;;
  x86)     export GOOS=linux GOARCH=386 ;;
  x86_64)  export GOOS=linux GOARCH=amd64 ;;
  *) echo "未知架构: $ARCH (用 armv7|arm64|x86|x86_64)"; exit 1 ;;
esac

[ -d engine/core ] || { echo "engine/ submodule 未初始化：先跑 git submodule update --init"; exit 1; }

# 动态 patch：注入 TV 入口 → 编译 → 抹掉还原
mkdir -p engine/core/cmd/tv
cp engine-tv/main.go engine/core/cmd/tv/main.go
( cd engine/core && CGO_ENABLED=0 go build -ldflags "-s -w" -o "$SCRIPT_DIR/build/zdownloader" ./cmd/tv )
rm -rf engine/core/cmd   # 还原 submodule，不留脏文件

mkdir -p app/src/main/assets/zdownloader
cp build/zdownloader app/src/main/assets/zdownloader/zdownloader
echo "已编 $ARCH TV 引擎，拷进 asset。"
