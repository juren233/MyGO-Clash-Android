//go:build android && cmfa

package libmygo

import "github.com/metacubex/mihomo/dns"

// setSystemDNS gives mihomo the system's DNS servers: built for Android
// with the cmfa tag, it cannot read them itself.
func setSystemDNS(servers []string) {
	if len(servers) > 0 {
		dns.UpdateSystemDNS(servers)
		dns.FlushCacheWithDefaultResolver()
	}
}
