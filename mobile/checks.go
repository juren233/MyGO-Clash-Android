package libmygo

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/mygo-clash/mygo-clash/internal/profiles"
	"github.com/mygo-clash/mygo-clash/internal/tools"
)

// proxyURL is the core's HTTP proxy, which the checks go through: the
// app's own traffic goes around the VPN.
func proxyURL() (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return "", err
	}
	if !running {
		return "", errors.New("the proxy is off")
	}
	if lastOpts.MixedPort <= 0 {
		return "", errors.New("the checks need the proxy port, which is off")
	}
	return fmt.Sprintf("http://127.0.0.1:%d", lastOpts.MixedPort), nil
}

// ExitIP returns, as JSON, the address and place that a kind of site
// sees through the proxy: view is domestic, global or cloudflare.
func ExitIP(view string) (string, error) {
	proxy, err := proxyURL()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	info, err := tools.ExitIP(ctx, proxy, view)
	if err != nil {
		return "", err
	}
	return marshal(info)
}

// UnlockList returns, as JSON, the services CheckUnlock can check.
func UnlockList() string {
	s, _ := marshal(tools.UnlockList())
	return s
}

// CheckUnlock checks the streaming and AI services named by ids
// (separated by commas; all when empty) through the proxy. l receives a
// JSON line per result, then OnClose once all are done.
func CheckUnlock(ids string, l LineListener) error {
	proxy, err := proxyURL()
	if err != nil {
		return err
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		tools.CheckUnlock(ctx, proxy, splitList(ids), func(u tools.Unlock) {
			if s, err := marshal(u); err == nil {
				l.OnLine(s)
			}
		})
		l.OnClose("")
	}()
	return nil
}

// PatchProfile changes a profile: name, url, userAgent and updateInterval
// (minutes; 0 for the provider's suggestion, -1 for never), as a JSON
// object of the fields to change. It returns the profile as JSON.
func PatchProfile(uid, patch string) (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return "", err
	}
	var in struct {
		Name           *string `json:"name"`
		URL            *string `json:"url"`
		UserAgent      *string `json:"userAgent"`
		UpdateInterval *int    `json:"updateInterval"`
	}
	if err := unmarshal(patch, &in); err != nil {
		return "", err
	}
	cur, err := manager.Get(uid)
	if err != nil {
		return "", err
	}
	p := profiles.Patch{Name: in.Name}
	if in.URL != nil {
		u := strings.TrimSpace(*in.URL)
		p.URL = &u
	}
	if in.UserAgent != nil || in.UpdateInterval != nil {
		o := cur.Option
		if in.UserAgent != nil {
			o.UserAgent = strings.TrimSpace(*in.UserAgent)
		}
		if in.UpdateInterval != nil {
			o.UpdateInterval = *in.UpdateInterval
			o.NoAutoUpdate = *in.UpdateInterval < 0
		}
		p.Option = &o
	}
	out, err := manager.Patch(uid, p)
	if err != nil {
		return "", err
	}
	return marshal(out)
}

// GlobalMerge and GlobalScript are the UIDs of the extensions of every
// profile.
const (
	GlobalMerge  = profiles.GlobalMerge
	GlobalScript = profiles.GlobalScript
)

// ExtensionOf returns the UID of a profile's extension of a kind (merge
// or script), creating it from its template the first time.
func ExtensionOf(uid, kind string) (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return "", err
	}
	return manager.Extension(uid, kind)
}

// Content returns what an item holds: a profile's YAML, or an
// extension's YAML or script.
func Content(uid string) (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return "", err
	}
	data, err := manager.Content(uid)
	return string(data), err
}

// SetContent checks and saves what an item holds, and applies the
// configuration again when the core runs.
func SetContent(uid, content string) error {
	mu.Lock()
	defer mu.Unlock()
	if err := ready(); err != nil {
		return err
	}
	item, err := manager.Get(uid)
	if err != nil {
		return err
	}
	if err := profiles.Check(item.Type, []byte(content)); err != nil {
		return err
	}
	if err := manager.SetContent(uid, []byte(content)); err != nil {
		return err
	}
	if running {
		return apply(-1, lastOpts)
	}
	return nil
}
