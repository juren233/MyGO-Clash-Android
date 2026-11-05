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
)

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
	if err := Init(t.TempDir(), p); err != nil {
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
	if err := DeleteProfile(prof.UID); err != nil {
		t.Fatal(err)
	}
	t.Logf("%s; %d sockets protected", Version(), p.protected.Load())
}
