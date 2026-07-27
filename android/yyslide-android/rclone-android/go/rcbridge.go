// Package rcbridge provides a minimal Go bridge to librclone for use via
// gomobile on Android.
package rcbridge

import (
	"context"
	"fmt"
	"mime"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/rclone/rclone/fs"
	"github.com/rclone/rclone/fs/config"
	"github.com/rclone/rclone/fs/config/configfile"
	"github.com/rclone/rclone/librclone/librclone"
	"github.com/rclone/rclone/vfs"
	"github.com/rclone/rclone/vfs/vfscommon"

	// Import rclone backends and operations so their RC methods get registered.
	_ "github.com/rclone/rclone/backend/all"
	_ "github.com/rclone/rclone/cmd/serve/dlna"
	_ "github.com/rclone/rclone/fs/operations"
	_ "github.com/rclone/rclone/fs/sync"
)

func init() {
	// Set a fallback HOME on Android to suppress "getent not found" errors
	// from rclone's config directory detection. RbInitialize overrides this
	// with the real app-specific path.
	if os.Getenv("HOME") == "" {
		os.Setenv("HOME", "/tmp")
	}
}

// RbResult holds the output of an RC method call.
type RbResult struct {
	Status int64
	Output string
}

// RbSetProxy sets HTTPS_PROXY / HTTP_PROXY for the embedded rclone
// process. rclone's HTTP transports honour these natively via Go's
// http.ProxyFromEnvironment, which understands `socks5://host:port` and
// `http://host:port` URLs.
//
// Pass empty string to clear both and route direct again.
//
// Used by Haven (#149) to pipe rclone's outgoing connections through
// a per-profile WireGuard / Tailscale tunnel via the localhost SOCKS5
// listener that wgbridge / tsbridge expose. Caveat: rclone caches HTTP
// clients per-fs, so changing the proxy mid-session may not take
// effect until a fresh fs is created. For the per-profile case the
// Kotlin caller sets this before the first fs use, which is fresh.
func RbSetProxy(proxyURL string) {
	if proxyURL == "" {
		os.Unsetenv("HTTPS_PROXY")
		os.Unsetenv("HTTP_PROXY")
	} else {
		os.Setenv("HTTPS_PROXY", proxyURL)
		os.Setenv("HTTP_PROXY", proxyURL)
	}
}

// RbInitialize initialises the rclone library.
//
// configPath is the absolute path to the rclone config file.
func RbInitialize(configPath string) {
	if configPath != "" {
		dir := filepath.Dir(configPath)
		os.MkdirAll(dir, 0700)
		// Set the config path BEFORE Initialize so rclone uses it.
		config.SetConfigPath(configPath)
		os.Setenv("HOME", dir)
	}
	os.Setenv("RCLONE_LOG_LEVEL", "NOTICE")
	librclone.Initialize()
	// Install the file-based config backend so config is read/written to disk.
	configfile.Install()
}

// RbFinalize tears down the rclone library.
func RbFinalize() {
	librclone.Finalize()
}

// RbRPC calls an rclone RC method with a JSON input string and returns
// the result.
func RbRPC(method, input string) *RbResult {
	output, status := librclone.RPC(method, input)
	return &RbResult{
		Status: int64(status),
		Output: output,
	}
}

// ---------------------------------------------------------------------------
// Media HTTP server
// ---------------------------------------------------------------------------

var (
	mediaServer     *http.Server
	mediaVFS        *vfs.VFS
	mediaListener   net.Listener
	mediaServerMu   sync.Mutex
	mediaRemoteName string // remote this server is serving
	mediaServerPort int    // port the server is listening on
)

// stopMediaServerLocked shuts down the running media server.
// Caller must hold mediaServerMu.
func stopMediaServerLocked() {
	if mediaServer != nil {
		mediaServer.Close()
		mediaServer = nil
	}
	if mediaVFS != nil {
		mediaVFS.Shutdown()
		mediaVFS = nil
	}
	if mediaListener != nil {
		mediaListener.Close()
		mediaListener = nil
	}
	mediaRemoteName = ""
	mediaServerPort = 0
}

// RbStartMediaServer starts a local HTTP server that streams files from the
// given rclone remote via VFS. The server binds to 127.0.0.1.
//
// remoteName is the rclone remote name (without trailing colon), e.g. "gdrive".
// preferredPort is the port to try first (e.g. from a previous session). Pass 0
// to auto-assign. If the preferred port is unavailable, falls back to auto-assign.
//
// Returns an RbResult with JSON {"port": N} on success or {"error": "..."} on
// failure.
func RbStartMediaServer(remoteName string, preferredPort int64) *RbResult {
	mediaServerMu.Lock()
	defer mediaServerMu.Unlock()

	// Reuse existing server if it's already serving this remote.
	if mediaServer != nil && mediaRemoteName == remoteName && mediaServerPort != 0 {
		return &RbResult{
			Status: 200,
			Output: fmt.Sprintf(`{"port":%d}`, mediaServerPort),
		}
	}

	// Stop any previously running server (different remote).
	stopMediaServerLocked()

	ctx := context.Background()
	f, err := fs.NewFs(ctx, remoteName+":")
	if err != nil {
		return &RbResult{
			Status: 500,
			Output: fmt.Sprintf(`{"error":%q}`, err.Error()),
		}
	}

	opt := vfscommon.Opt
	opt.ReadOnly = true
	opt.NoChecksum = true
	opt.CacheMode = vfscommon.CacheModeFull
	opt.ChunkSize = 16 * fs.Mebi       // 16 MB initial chunks
	opt.ChunkSizeLimit = 64 * fs.Mebi  // grow up to 64 MB per chunk
	opt.ReadAhead = 32 * fs.Mebi       // 32 MB read-ahead buffer
	opt.CacheMaxSize = 512 * fs.Mebi   // cap disk cache at 512 MB
	v := vfs.New(f, &opt)

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		filePath := strings.TrimPrefix(r.URL.Path, "/")
		if filePath == "" {
			http.NotFound(w, r)
			return
		}

		node, err := v.Stat(filePath)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		if node.IsDir() {
			http.NotFound(w, r)
			return
		}

		file, ok := node.(*vfs.File)
		if !ok {
			http.Error(w, "not a file", http.StatusInternalServerError)
			return
		}

		handle, err := file.Open(os.O_RDONLY)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer handle.Close()

		// Set Content-Type from extension if possible.
		ext := filepath.Ext(file.Name())
		if ct := mime.TypeByExtension(ext); ct != "" {
			w.Header().Set("Content-Type", ct)
		}

		// http.ServeContent handles Range requests for seeking.
		rs, ok := handle.(ReadSeekCloser)
		if !ok {
			http.Error(w, "file does not support seeking", http.StatusInternalServerError)
			return
		}
		http.ServeContent(w, r, file.Name(), file.ModTime(), rs)
	})

	// Try preferred port first (allows VLC to reconnect after app restart),
	// fall back to auto-assigned port if unavailable.
	var listener net.Listener
	if preferredPort > 0 {
		listener, _ = net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", preferredPort))
	}
	if listener == nil {
		var err error
		listener, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			v.Shutdown()
			return &RbResult{
				Status: 500,
				Output: fmt.Sprintf(`{"error":%q}`, err.Error()),
			}
		}
	}

	port := listener.Addr().(*net.TCPAddr).Port
	server := &http.Server{Handler: mux}

	mediaServer = server
	mediaVFS = v
	mediaListener = listener
	mediaRemoteName = remoteName
	mediaServerPort = port

	go server.Serve(listener)

	return &RbResult{
		Status: 200,
		Output: fmt.Sprintf(`{"port":%d}`, port),
	}
}

// ReadSeekCloser combines io.ReadSeeker with io.Closer.
type ReadSeekCloser interface {
	Read(p []byte) (n int, err error)
	Seek(offset int64, whence int) (int64, error)
	Close() error
}

// RbMediaServerStatus returns the current media server state as JSON:
// {"port": N, "remote": "name"} if running, or {"port": 0} if not.
func RbMediaServerStatus() *RbResult {
	mediaServerMu.Lock()
	defer mediaServerMu.Unlock()
	if mediaServer != nil && mediaServerPort != 0 {
		return &RbResult{
			Status: 200,
			Output: fmt.Sprintf(`{"port":%d,"remote":%q}`, mediaServerPort, mediaRemoteName),
		}
	}
	return &RbResult{
		Status: 200,
		Output: `{"port":0}`,
	}
}

// RbStopMediaServer stops the media streaming HTTP server if running.
func RbStopMediaServer() *RbResult {
	mediaServerMu.Lock()
	defer mediaServerMu.Unlock()
	stopMediaServerLocked()
	return &RbResult{
		Status: 200,
		Output: `{}`,
	}
}
