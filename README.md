# AniDock TV

> anidock 的 Android TV 客户端：在电视上追番、下载、播放。

AniDock TV 是 [anidock](https://github.com/mbzmhs/anidock) 聚合站的 **Android TV 客户端**。番剧列表与剧集信息来自 anidock 服务器 API，下载直接在**电视本机**完成——App 内嵌一个纯 Go 的无头 BT 引擎子进程，播放使用**内置 libVLC 播放器**（本地成片与边下边播同一入口）。所有数据都存本地（Room），不经过任何云端。

**技术栈**：Kotlin + Jetpack Compose（focus-as-state TV 焦点导航）· libVLC（内置播放器）· Go（内嵌无头 BT 引擎）· Room（本地存储）· OkHttp · Coil

## ✨ 特性

### 追番与下载

- **电视端追番** — 浏览 anidock 聚合站的番剧，本地关注、筛选、搜索。
- **本机 BT 下载** — 磁力 / .torrent 直接在电视上下载，不依赖外部服务器。
- **每集去重 + 进度** — 下载进度、剩余集数、出错/暂停状态实时显示，出错集可单击续传、长按暂停/取消。
- **合集（多文件种子）** — 文件列表勾选下载（目录整组切换 / 只选视频 / 全选），下载中随时改选；集数区间解析展示（"1~14"）。
- **本地优先** — 全部数据存电视本地 Room，断网也能看已下载的片。

### 内置播放器（libVLC）

- **边下边播** — 下载中剧集直接点播，引擎按播放头重排 piece 优先级；合集可指定文件播，已下完的子文件自动走本地播放。
- **续播** — 记住每集退出位置，再次点开弹「继续观看？」（从上次位置继续 / 从头开始 / 合集从上次文件头部）。
- **选集 + 自动连播** — 播放器菜单内选集（单集列表 / 合集文件表，未下载灰显）；播完自动接下一集（可关），接近尾声预热下一文件元数据。
- **字幕** — 内嵌轨自动选择（简 > 繁 > 日 > 英），外挂字幕可浏览下载目录加载（支持进子目录），延迟微调。
- **音轨 / 视频轨 / 章节 / 倍速 / 画面比例** — 齐全的菜单调节；倍速保音调。
- **解码三档** — 硬件直渲 / 硬件无直渲 / 软件，兼容各家电视芯片的解码差异；黑屏看门狗自愈。
- **TV 焦点导航** — 完整 D-pad 方向键操作，专为电视遥控器设计。

## 🚀 快速开始

### 构建内嵌引擎（Go）

引擎用 [zdl](https://github.com/mbzmhs/zdl) 下载引擎，以 **git submodule** 挂在 `engine/`（zdl 仓库零改动）。TV 入口 `engine-tv/main.go` 单独放本仓库——它要用 zdl 的 internal 包，而 internal 只能被同一模块引用，所以 `build-engine.sh` 会在构建时把它**临时注入** submodule 的 zdl 模块里编译，编完抹掉、还原干净：

```bash
./build-engine.sh            # 默认 armv7；其它架构 ./build-engine.sh arm64 等
```

`build-engine.sh` 内部做的事：注入 `engine-tv/main.go` → `go build ./cmd/tv`（交叉编译 ARM，CGO 关闭）→ 抹掉注入文件 → 拷进 APK 资源。

### 构建 APK

```bash
gradle assembleDebug        # 或 Android Studio 直接打开
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装后在「设置」里填 anidock 服务器地址与访客口令，即可追番下载。

## 📦 依赖

需要一个运行中的 [anidock](https://github.com/mbzmhs/anidock) 服务器（聚合站本体），提供番剧与剧集 API。本 App 只读其公开接口 + 访客登录，不写任何数据到服务器。

## 📁 目录结构

```
anidock-tv/
├─ app/            # Kotlin / Compose TV 客户端
│  └─ src/main/java/com/mbzmhs/anidocktv/
│     ├─ ui/       # 首页 / 详情 / 设置 / 侧边栏 / 播放器 UI / 焦点组件
│     ├─ player/   # 内置播放器（libVLC 控制器 / 播放源 / 选集上下文,独立组件）
│     ├─ data/     # Room 数据库 / API 客户端 / 下载状态机 / 引擎服务
│     └─ EngineWatchdogService.kt   # 引擎看门狗前台服务
├─ engine/         # zdl 下载引擎（git submodule，zdl 仓库零改动）
├─ engine-tv/      # TV 入口（构建时由 build-engine.sh 注入 engine/ 再编）
│  └─ main.go
├─ build-engine.sh # 引擎交叉编译 + 动态注入脚本
└─ app/build.gradle.kts
```

## 📄 License

[MIT](./LICENSE)

## ⚖️ 免责声明

本项目仅为客户端工具，聚合与下载行为基于用户自行配置的 anidock 服务器。所有内容版权均归原作者与字幕组所有，请支持正版、合理使用。
