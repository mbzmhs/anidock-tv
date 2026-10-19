// TV 无头引擎:AniDock TV 客户端内嵌的下载核心。
//
// 复用 ZDownloader 的 engine/bt 包(纯 Go 零依赖),去掉桌面壳(Wails/托盘/自启/
// 文件关联/Web UI),只起 127.0.0.1 的 HTTP API,由安卓 App 作子进程拉起并
// 走 localhost 调用。交叉编译:GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0。
package main

import (
	"context"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"zdownloader/internal/conf"
	"zdownloader/internal/engine"
	"zdownloader/internal/hub"
	"zdownloader/internal/server"
)

// emptyFS 空文件系统:无头模式不托管任何 Web UI,UI 由安卓 App 承担。
type emptyFS struct{}

func (emptyFS) Open(string) (fs.File, error) { return nil, fs.ErrNotExist }
func (emptyFS) Stat(string) (fs.FileInfo, error) {
	return nil, &fs.PathError{Op: "stat", Path: "/", Err: fs.ErrNotExist}
}

func main() {
	var (
		flagPort = flag.Int("port", 0, "监听端口(0 = 配置默认 8620)")
		flagDir  = flag.String("dir", "", "下载目录(覆盖配置)")
		flagData = flag.String("data", "", "数据目录(config.json / tasks/)")
	)
	flag.Parse()

	if *flagData == "" {
		log.Fatal("必须通过 -data 指定数据目录(安卓下为应用私有目录)")
	}
	dataDir := *flagData
	cfgPath := filepath.Join(dataDir, "config.json")
	tasksDir := filepath.Join(dataDir, "tasks")
	downloadDir := *flagDir
	if downloadDir == "" {
		downloadDir = dataDir
	}
	if err := os.MkdirAll(downloadDir, 0o755); err != nil {
		log.Fatalf("下载目录不可用: %v", err)
	}

	cfg := conf.Load(cfgPath, downloadDir)
	// TV 端初始默认：BT peer 不代理、不自动封禁 PCDN（覆盖 zdl 桌面默认）。
	// 仅在首次运行（config.json 尚不存在）生效；用户经设置页改过后配置落盘，
	// 之后以落盘值为准，不再覆盖。
	if _, err := os.Stat(cfgPath); os.IsNotExist(err) {
		noProxy := false
		cfg.Proxy.ForPeer = &noProxy
		cfg.BT.AutoBanPoison = false
	}
	if *flagPort > 0 {
		cfg.Port = *flagPort
	}
	if downloadDir != "" {
		cfg.DownloadDir = downloadDir
	}

	h := hub.New()
	mgr := engine.NewManager(cfg, cfgPath, tasksDir)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	mgr.Run(ctx)

	svc := server.New(mgr, h, emptyFS{})
	srv := &http.Server{Handler: svc.Handler(), ReadHeaderTimeout: 10 * 1000000000}
	go svc.StartAggregator(ctx.Done())

	var ln net.Listener
	port := cfg.Port
	for i := 0; i < 10; i++ {
		l, e := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
		if e == nil {
			ln = l
			break
		}
		port++
	}
	if ln == nil {
		log.Fatalf("端口 %d..%d 均被占用", cfg.Port, cfg.Port+9)
	}
	log.Printf("AniDock TV 下载引擎启动: http://%s (下载目录 %s)", ln.Addr(), cfg.DownloadDir)
	if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
