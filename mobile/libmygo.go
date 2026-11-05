// Package libmygo is the Go side of MyGO-Clash for Android, bound with
// gomobile into an AAR (see android/README.md).
//
// It reuses the desktop app's packages as they are: the core (corehost:
// mihomo embedded, serving its REST API on a Unix socket), the profiles
// and their extensions. The core runs in the process of the app's
// VpnService, with the TUN file descriptor that VpnService opened; the
// sockets of its outbound connections are protected through the
// Platform, so they do not loop back into the VPN.
//
// Every function is safe to call from any thread.
package libmygo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	C "github.com/metacubex/mihomo/constant"

	"github.com/mygo-clash/mygo-clash/internal/coreapi"
	"github.com/mygo-clash/mygo-clash/internal/corehost"
	"github.com/mygo-clash/mygo-clash/internal/enhance"
	"github.com/mygo-clash/mygo-clash/internal/profiles"
	"github.com/mygo-clash/mygo-clash/internal/secure"
	"github.com/mygo-clash/mygo-clash/internal/yamlx"
)

// Platform is what the Android side does for the core.
type Platform interface {
	// Protect keeps the socket fd out of the VPN (VpnService.protect).
	Protect(fd int32) bool
	// PackageOf returns the package name of the app that owns a
	// connection, or "" when it is unknown. network is "tcp" or "udp".
	PackageOf(network, srcIP string, srcPort int32, dstIP string, dstPort int32) string
}

// Options are the settings of a start.
type Options struct {
	// Mode is rule, global or direct.
	Mode string
	// Stack is the TUN stack: gvisor, system or mixed.
	Stack string
	// MixedPort is the HTTP and SOCKS port on 127.0.0.1; 0 turns it off.
	MixedPort int32
	// IPv6 routes IPv6 too.
	IPv6 bool
	// LogLevel is mihomo's: debug, info, warning, error or silent.
	LogLevel string
}

// NewOptions returns the default options.
func NewOptions() *Options {
	return &Options{Mode: "rule", Stack: "gvisor", MixedPort: 7897, LogLevel: "info"}
}

var (
	mu       sync.Mutex
	home     string
	manager  *profiles.Manager
	client   *coreapi.Client
	hostErr  error
	running  bool
	lastOpts = NewOptions()

	// platform is read by the socket hook while mu may be held: mihomo
	// dials (providers, DNS) while it applies a configuration.
	platform atomic.Pointer[platformBox]
)

type platformBox struct{ Platform }

func currentPlatform() Platform {
	if b := platform.Load(); b != nil {
		return b.Platform
	}
	return nil
}

// Version returns the versions of the app's Go side and of mihomo.
func Version() string { return corehost.AppVersion + " (mihomo " + corehost.MihomoVersion() + ")" }

// Init opens the app's data in dir (the app's files directory) with the
// master key that seals it (32 bytes, kept by the app in the Android
// Keystore; empty keeps a key in a file of dir, for tests), and starts
// the core, which serves without a configuration until Start. It is
// called once per process; later calls only replace the platform.
func Init(dir string, key []byte, p Platform) error {
	mu.Lock()
	defer mu.Unlock()
	platform.Store(&platformBox{p})
	if manager != nil {
		return nil
	}
	if dir == "" {
		return errors.New("no data directory")
	}
	home = dir
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	installHooks()

	sealer, err := openSealer(filepath.Join(dir, "secure"), key)
	if err != nil {
		return fmt.Errorf("open the key: %w", err)
	}
	m, err := profiles.Open(filepath.Join(dir, "profiles"), sealer)
	if err != nil {
		return err
	}
	manager = m

	sock := filepath.Join(dir, "core.sock")
	_ = os.Remove(sock)
	boot := coreapi.Bootstrap{Home: filepath.Join(dir, "core"), Socket: sock, Mode: "android", OwnerUID: -1, OwnerGID: -1}
	go func() {
		err := corehost.Serve(boot, nil)
		mu.Lock()
		hostErr = fmt.Errorf("the core stopped: %v", err)
		mu.Unlock()
	}()
	client = coreapi.New(sock)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for {
		if _, err := client.Health(ctx); err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return errors.New("the core did not start")
		case <-time.After(50 * time.Millisecond):
		}
	}
}

func openSealer(dir string, key []byte) (*secure.Sealer, error) {
	if len(key) > 0 {
		return secure.NewSealer(key, keystore{})
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	kr := secure.FileKeyring(dir)
	key, err := kr.Get("master-key")
	if errors.Is(err, secure.ErrNotFound) {
		key = make([]byte, 32)
		if _, err := randRead(key); err != nil {
			return nil, err
		}
		if err := kr.Set("master-key", key); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	return secure.NewSealer(key, kr)
}

// keystore stands for the Android Keystore, which holds the master key on
// the app's side: the sealer only names it.
type keystore struct{}

func (keystore) Get(string) ([]byte, error) { return nil, secure.ErrNotFound }
func (keystore) Set(string, []byte) error   { return errors.ErrUnsupported }
func (keystore) Delete(string) error        { return nil }
func (keystore) Name() string               { return "android-keystore" }
func (keystore) Secure() bool               { return true }

// installHooks protects the core's sockets and names the apps of
// connections, through the platform.
func installHooks() {
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		p := currentPlatform()
		if p == nil {
			return nil
		}
		var ok bool
		if err := conn.Control(func(fd uintptr) { ok = p.Protect(int32(fd)) }); err != nil {
			return err
		}
		if !ok {
			return errors.New("the VPN refused to protect a socket")
		}
		return nil
	}
	process.DefaultPackageNameResolver = func(m *C.Metadata) (string, error) {
		p := currentPlatform()
		if p == nil || !m.SrcIP.IsValid() {
			return "", errors.New("no platform")
		}
		network := "tcp"
		if m.NetWork == C.UDP {
			network = "udp"
		}
		pkg := p.PackageOf(network, m.SrcIP.String(), int32(m.SrcPort), m.DstIP.String(), int32(m.DstPort))
		if pkg == "" {
			return "", errors.New("unknown package")
		}
		return pkg, nil
	}
}

func ready() error {
	if manager == nil {
		return errors.New("Init was not called")
	}
	return hostErr
}

// Start applies the current profile with TUN on the file descriptor fd,
// which the core takes over (ParcelFileDescriptor.detachFd), and dns, the
// system's DNS servers separated by commas, for "system" in profiles.
func Start(fd int32, dns string, o *Options) error {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return err
	}
	if o == nil {
		o = NewOptions()
	}
	lastOpts = o
	setSystemDNS(splitList(dns))
	if err := apply(fd, o); err != nil {
		return err
	}
	running = true
	return nil
}

// Reload applies the current profile again, after it changed, keeping
// the TUN of Start.
func Reload() error {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return err
	}
	if !running {
		return nil
	}
	return apply(-1, lastOpts)
}

// currentFD is the TUN of the running configuration.
var currentFD int32 = -1

func apply(fd int32, o *Options) error {
	if fd < 0 {
		fd = currentFD
	}
	base := enhance.Base{
		Mode: o.Mode, MixedPort: int(o.MixedPort), IPv6: o.IPv6, LogLevel: o.LogLevel,
		UnifiedDelay: true, TCPConcurrent: true,
		// mihomo finds the app of a connection through the platform.
		FindProcessMode: "strict",
		Tun: enhance.Tun{
			Enable: fd > 0, Stack: o.Stack,
			// VpnService owns the routes and the interface.
			AutoRoute: false, AutoDetectInterface: false,
			DNSHijack: []string{"any:53"}, MTU: 9000,
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	rt, err := manager.Generate(ctx, profiles.Inputs{Base: base, Builtin: true})
	if err != nil {
		return err
	}
	cfg := rt.Core
	if fd > 0 {
		// The gvisor and mixed stacks take the packets of any address, so
		// the addresses of VpnService need not match mihomo's.
		cfg.Map("tun").Set("file-descriptor", int64(fd))
	}
	data, err := yamlx.Marshal(cfg)
	if err != nil {
		return err
	}
	if _, err := client.Apply(ctx, coreapi.ApplyRequest{Config: string(data)}); err != nil {
		return err
	}
	currentFD = fd
	restoreSelections()
	return nil
}

// stopping is the configuration of a stopped core: no listeners.
const (
	stopping = "proxies: []\nproxy-groups: []\nrules:\n  - MATCH,DIRECT\ntun:\n  enable: false\nmixed-port: 0\n"
)

// Stop turns TUN and the proxy port off: the core closes the descriptor
// of Start. The core itself keeps running, without listeners.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return err
	}
	running, currentFD = false, -1
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, err := client.Apply(ctx, coreapi.ApplyRequest{Config: stopping})
	return err
}

// IsRunning reports whether Start applied a configuration and Stop has
// not been called since.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// Request sends a request to the core's REST API (the mihomo API, and the
// app's under /mygo) and returns the body of the response. A status of
// 400 or more is an error with the body as its message.
func Request(method, path, body string) (string, error) {
	mu.Lock()
	err := ready()
	c := client
	mu.Unlock()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	resp, err := do(ctx, c, method, path, body)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}
	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("%s %s: %d %s", method, path, resp.StatusCode, strings.TrimSpace(string(data)))
	}
	return string(data), nil
}

// LineListener receives the lines of a stream.
type LineListener interface {
	OnLine(line string)
	// OnClose is called once the stream ends; err is "" when it was closed.
	OnClose(err string)
}

// Stream is a stream of lines from the core, such as /traffic or /logs.
type Stream struct{ cancel context.CancelFunc }

// Close stops the stream.
func (s *Stream) Close() { s.cancel() }

// Subscribe streams the lines of a GET request (/traffic, /memory,
// /logs?level=info, /connections) to l until the stream is closed.
func Subscribe(path string, l LineListener) (*Stream, error) {
	mu.Lock()
	err := ready()
	c := client
	mu.Unlock()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		resp, err := do(ctx, c, http.MethodGet, path, "")
		if err != nil {
			l.OnClose(closeReason(ctx, err))
			return
		}
		defer resp.Body.Close()
		err = readLines(resp.Body, l.OnLine)
		l.OnClose(closeReason(ctx, err))
	}()
	return &Stream{cancel: cancel}, nil
}

func closeReason(ctx context.Context, err error) string {
	if ctx.Err() != nil || err == nil || errors.Is(err, io.EOF) {
		return ""
	}
	return err.Error()
}

func do(ctx context.Context, c *coreapi.Client, method, path, body string) (*http.Response, error) {
	var rd io.Reader
	if body != "" {
		rd = strings.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, "http://core"+path, rd)
	if err != nil {
		return nil, err
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	return rawClient(c).Do(req)
}

var (
	rawOnce sync.Once
	raw     *http.Client
)

// rawClient is an HTTP client of the core's socket without a timeout, for
// streams; requests set their own deadline.
func rawClient(c *coreapi.Client) *http.Client {
	rawOnce.Do(func() {
		addr := c.Addr()
		raw = &http.Client{Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return coreapi.Dial(ctx, addr)
			},
			DisableCompression: true,
		}}
	})
	return raw
}

func splitList(s string) []string {
	var out []string
	for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' }) {
		out = append(out, f)
	}
	return out
}

// Profiles returns the profiles as JSON: {"items": [...], "current": uid}.
func Profiles() (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return "", err
	}
	return marshal(manager.List())
}

// AddProfile downloads the subscription at url, makes it the current
// profile and returns it as JSON.
func AddProfile(url, name string) (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	p, err := manager.Create(ctx, profiles.NewProfile{Type: profiles.TypeRemote, URL: strings.TrimSpace(url), Name: name})
	if err != nil {
		return "", err
	}
	if err := manager.SetCurrent(p.UID); err != nil {
		return "", err
	}
	if running {
		if err := apply(-1, lastOpts); err != nil {
			return "", err
		}
	}
	return marshal(p)
}

// UpdateProfile downloads a remote profile again.
func UpdateProfile(uid string) error {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if _, err := manager.Update(ctx, uid, nil); err != nil {
		return err
	}
	if cur, ok := manager.Current(); running && ok && cur.UID == uid {
		return apply(-1, lastOpts)
	}
	return nil
}

// SelectProfile makes a profile the current one, and applies it when the
// core runs.
func SelectProfile(uid string) error {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return err
	}
	if err := manager.SetCurrent(uid); err != nil {
		return err
	}
	if running {
		return apply(-1, lastOpts)
	}
	return nil
}

// DeleteProfile deletes a profile.
func DeleteProfile(uid string) error {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return err
	}
	return manager.Delete(uid)
}

func marshal(v any) (string, error) {
	data, err := json.Marshal(v)
	return string(data), err
}
