//go:build !(android && cmfa)

package libmygo

// setSystemDNS does nothing where mihomo reads the system's DNS itself.
func setSystemDNS([]string) {}
