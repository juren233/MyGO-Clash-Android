package libmygo

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"io"
	"net/http"
	"net/url"
	"time"
)

func randRead(b []byte) (int, error) { return rand.Read(b) }

// readLines calls fn with each line of r that is not blank.
func readLines(r io.Reader, fn func(string)) error {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64<<10), 16<<20)
	for sc.Scan() {
		if line := bytes.TrimSpace(sc.Bytes()); len(line) > 0 {
			fn(string(line))
		}
	}
	return sc.Err()
}

// SelectProxy selects name in the group and remembers it with the
// profile, for the next start.
func SelectProxy(group, name string) error {
	body, _ := marshal(map[string]string{"name": name})
	if _, err := Request(http.MethodPut, "/proxies/"+url.PathEscape(group), body); err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if manager != nil {
		manager.RecordSelection(group, name)
	}
	return nil
}

// restoreSelections selects again what the current profile remembers.
// Called with mu held, after an apply.
func restoreSelections() {
	cur, ok := manager.Current()
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for _, s := range cur.Selected {
		_ = client.SelectProxy(ctx, s.Name, s.Now)
	}
}

// SetDNS gives the core the DNS servers of the network under the VPN,
// separated by commas, after the network changed.
func SetDNS(dns string) {
	setSystemDNS(splitList(dns))
}
