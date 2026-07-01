package anet

// Patched for the Beeline build. Upstream anet's Android implementation reads
// interfaces via raw netlink and syncs them into net.zoneCache /
// x/net socket.zoneCache through //go:linkname. On this Go toolchain that
// struct layout no longer matches, so the writes corrupt the net package and
// break UDP/DNS (TCP survives). The stock xray-core that ships in this app has
// no anet dependency and uses the standard library directly, which works.
//
// This file makes the Android build delegate to the standard library too,
// removing all netlink/zoneCache machinery while keeping anet's public API for
// pion/transport (the only consumer, which calls Interfaces/InterfaceAddrs).

import "net"

func Interfaces() ([]net.Interface, error) {
	return net.Interfaces()
}

func InterfaceAddrs() ([]net.Addr, error) {
	return net.InterfaceAddrs()
}

func InterfaceByIndex(index int) (*net.Interface, error) {
	return net.InterfaceByIndex(index)
}

func InterfaceByName(name string) (*net.Interface, error) {
	return net.InterfaceByName(name)
}

func InterfaceAddrsByInterface(ifi *net.Interface) ([]net.Addr, error) {
	return ifi.Addrs()
}

func SetAndroidVersion(version uint) {}
