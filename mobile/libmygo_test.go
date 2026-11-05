package libmygo

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type lines struct{ got chan string }

func (l *lines) OnLine(line string) {
	select {
	case l.got <- line:
	default:
	}
}

func (l *lines) OnClose(string) {}

type fakePlatform struct{ protected atomic.Int32 }

func (f *fakePlatform) Protect(fd int32) bool { f.protected.Add(1); return true }

func (f *fakePlatform) PackageOf(network, srcIP string, srcPort int32, dstIP string, dstPort int32) string {
	return ""
}

const profile = `proxies:
  - {name: a, type: socks5, server: 127.0.0.1, port: 1}
  - {name: b, type: http, server: 127.0.0.1, port: 2}
proxy-groups:
  - {name: Proxy, type: select, proxies: [a, b, DIRECT]}
rules:
  - MATCH,Proxy
`

// TestLifecycle runs the Go side as the app does, without TUN: the core
// starts, a subscription is added and applied, a proxy is selected and
// remembered, and the core stops.
func TestLifecycle(t *testing.T) {
	sub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Subscription-Userinfo", "upload=1; download=2; total=100; expire=0")
		_, _ = w.Write([]byte(profile))
	}))
	defer sub.Close()

	p := &fakePlatform{}
	if err := Init(t.TempDir(), nil, p); err != nil {
		t.Fatal(err)
	}
	// A free port for the proxy.
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	_ = l.Close()

	o := NewOptions()
	o.MixedPort = int32(port)
	if err := Start(-1, "1.1.1.1", o); err != nil {
		t.Fatal(err)
	}
	if !IsRunning() {
		t.Fatal("not running after Start")
	}

	added, err := AddProfile(sub.URL, "Test")
	if err != nil {
		t.Fatal(err)
	}
	var prof struct {
		UID     string `json:"uid"`
		Proxies int    `json:"proxies"`
	}
	if err := json.Unmarshal([]byte(added), &prof); err != nil || prof.Proxies != 2 {
		t.Fatalf("added %s: %v", added, err)
	}

	body, err := Request("GET", "/proxies/Proxy", "")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(body, `"now":"a"`) {
		t.Fatalf("the group before selecting: %s", body)
	}
	// The logs stream while the core applies a configuration.
	logs := &lines{got: make(chan string, 64)}
	stream, err := Subscribe("/logs?level=info", logs)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond) // the stream connects
	if err := Reload(); err != nil {
		t.Fatal(err)
	}
	select {
	case l := <-logs.got:
		if !strings.Contains(l, `"payload"`) {
			t.Fatalf("a log line: %s", l)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no logs")
	}
	stream.Close()
	conns, err := Request("GET", "/connections", "")
	if err != nil || !strings.Contains(conns, `"connections"`) {
		t.Fatalf("connections: %s %v", conns, err)
	}

	if err := SelectProxy("Proxy", "b"); err != nil {
		t.Fatal(err)
	}
	// Applying again keeps the selection.
	if err := Reload(); err != nil {
		t.Fatal(err)
	}
	body, _ = Request("GET", "/proxies/Proxy", "")
	if !strings.Contains(body, `"now":"b"`) {
		t.Fatalf("the selection was lost: %s", body)
	}
	cfg, _ := Request("GET", "/configs", "")
	if !strings.Contains(cfg, `"mixed-port":`) {
		t.Fatalf("configs: %s", cfg)
	}

	// A connection through the proxy port, direct: the core's socket to
	// the destination is protected, as VpnService needs.
	if err := SelectProxy("Proxy", "DIRECT"); err != nil {
		t.Fatal(err)
	}
	proxyURL, _ := url.Parse("http://" + l.Addr().String())
	hc := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
	resp, err := hc.Get(sub.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if p.protected.Load() == 0 {
		t.Fatal("the core dialed without protecting its socket")
	}

	list, _ := Profiles()
	if !strings.Contains(list, `"current":"`+prof.UID+`"`) {
		t.Fatalf("profiles: %s", list)
	}

	if err := Stop(); err != nil {
		t.Fatal(err)
	}
	if IsRunning() {
		t.Fatal("running after Stop")
	}
	if c, err := net.Dial("tcp", l.Addr().String()); err == nil {
		c.Close()
		t.Fatal("the proxy port is still open after Stop")
	}
	patched, err := PatchProfile(prof.UID, `{"name":"Renamed","updateInterval":-1,"userAgent":"ua/1"}`)
	if err != nil || !strings.Contains(patched, `"name":"Renamed"`) || !strings.Contains(patched, `"noAutoUpdate":true`) || !strings.Contains(patched, `"userAgent":"ua/1"`) {
		t.Fatalf("patched: %s %v", patched, err)
	}
	if !strings.Contains(UnlockList(), `"status":"pending"`) {
		t.Fatal("no services to check")
	}
	if _, err := ExitIP("global"); err == nil {
		t.Fatal("an exit IP without the proxy")
	}
	// A merge that adds a proxy reaches the running core.
	ext, err := ExtensionOf(prof.UID, "merge")
	if err != nil {
		t.Fatal(err)
	}
	if err := SetContent(ext, "proxies: [\n"); err == nil {
		t.Fatal("invalid YAML was saved")
	}
	if err := Start(-1, "", o); err != nil { // running again, after Stop
		t.Fatal(err)
	}
	if err := SetContent(ext, "prepend-proxies:\n  - {name: merged, type: http, server: 127.0.0.1, port: 3}\n"); err != nil {
		t.Fatal(err)
	}
	if body, _ := Request("GET", "/proxies/merged", ""); !strings.Contains(body, `"name":"merged"`) {
		t.Fatalf("the merge did not apply: %s", body)
	}
	if got, _ := Content(ext); !strings.Contains(got, "merged") {
		t.Fatalf("content: %s", got)
	}
	if _, err := Content(GlobalScript); err != nil {
		t.Fatal(err)
	}
	_ = Stop()
	if err := DeleteProfile(prof.UID); err != nil {
		t.Fatal(err)
	}
	t.Logf("%s; %d sockets protected", Version(), p.protected.Load())
}

func TestSealerOfKey(t *testing.T) {
	key := make([]byte, 32)
	key[0] = 7
	s, err := openSealer(t.TempDir(), key)
	if err != nil {
		t.Fatal(err)
	}
	sealed := s.Seal([]byte("secret"), "x")
	if out, err := s.Unseal(sealed, "x"); err != nil || string(out) != "secret" {
		t.Fatalf("unseal: %q %v", out, err)
	}
	if s.Keyring().Name() != "android-keystore" {
		t.Fatal(s.Keyring().Name())
	}
	if _, err := openSealer(t.TempDir(), []byte("short")); err == nil {
		t.Fatal("a short key was taken")
	}
}
