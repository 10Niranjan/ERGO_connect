package com.ergo.connect.common.security;

/**
 * Strips a proxy-appended port from an address (ARCHITECTURE.md §8, Phase 8d) so a per-IP rate limiter can't be
 * bypassed by a new TCP connection per attempt - some proxies append a port to X-Forwarded-For ("ip:port" or
 * "[ipv6]:port"). A bare, bracket-less IPv6 address is left untouched: it always has 2+ colons, so it's never
 * mistaken for "ip:port" (exactly one colon) the way a naive regex could misread "::1". Shared by every
 * per-IP {@code RedisFixedWindowLimiter} caller.
 */
public final class IpKeys {

	private IpKeys() {
	}

	public static String normalize(String ip) {
		if (ip.startsWith("[")) {
			int closingBracket = ip.indexOf(']');
			if (closingBracket > 0) {
				return ip.substring(1, closingBracket);
			}
		}
		else if (ip.indexOf(':') == ip.lastIndexOf(':') && ip.indexOf(':') > 0) {
			return ip.substring(0, ip.indexOf(':'));
		}
		return ip;
	}

}
