package com.involutionhell.backend.common.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元覆盖 PrivateAddressGuard 的逐段判定 + IPv4-mapped IPv6 防绕过。
 *
 * 用 {@code InetAddress.getByName(literal)} 喂 IP 字面量进来，不依赖外部 DNS
 * （字面量解析走本地 parsing，不查询任何 resolver）。
 */
class PrivateAddressGuardTest {

    @Test
    void isBlockedAddress_ipv4Loopback() throws UnknownHostException {
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("127.255.255.254"))).isTrue();
    }

    @Test
    void isBlockedAddress_ipv4PrivateAndCgnatAndLinkLocal() throws UnknownHostException {
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("172.16.0.1"))).isTrue();
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("192.168.1.1"))).isTrue();
        // CGNAT 100.64.0.0/10
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("100.64.0.1"))).isTrue();
        // AWS / GCP metadata 169.254.169.254
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("169.254.169.254"))).isTrue();
        // 0.0.0.0/8
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("0.0.0.0"))).isTrue();
        // 224.0.0.0/4 multicast
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("224.0.0.1"))).isTrue();
    }

    @Test
    void isBlockedAddress_ipv4PublicAllowed() throws UnknownHostException {
        // example.com 的 IP（公网），不应被挡
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("93.184.216.34"))).isFalse();
        // Cloudflare 1.1.1.1（公网 DNS）
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("1.1.1.1"))).isFalse();
        // Google 8.8.8.8
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void isBlockedAddress_ipv6LoopbackAndULAAndLinkLocal() throws UnknownHostException {
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("::1"))).isTrue();
        // ULA fc00::/7
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("fd12:3456:789a::1"))).isTrue();
        // link-local fe80::/10
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("fe80::1"))).isTrue();
    }

    /**
     * IPv4-mapped IPv6 是真正的 P1：原代码只判 ULA 和 link-local，
     * ::ffff:127.0.0.1 这种字面量直接绕过整个 IPv4 黑名单。
     */
    @Test
    void isBlockedAddress_ipv4MappedIpv6_loopback() throws UnknownHostException {
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        assertThat(PrivateAddressGuard.isBlockedAddress(mapped))
                .as("::ffff:127.0.0.1 必须按 IPv4 loopback 拒绝")
                .isTrue();
    }

    @Test
    void isBlockedAddress_ipv4MappedIpv6_rfc1918() throws UnknownHostException {
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("::ffff:10.0.0.1"))).isTrue();
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("::ffff:192.168.1.1"))).isTrue();
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("::ffff:172.16.0.1"))).isTrue();
    }

    @Test
    void isBlockedAddress_ipv4MappedIpv6_metadataEndpoint() throws UnknownHostException {
        // ::ffff:169.254.169.254 —— AWS / GCP metadata via IPv4-mapped IPv6
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("::ffff:169.254.169.254")))
                .isTrue();
    }

    @Test
    void isBlockedAddress_ipv4MappedIpv6_publicIpAllowed() throws UnknownHostException {
        // ::ffff:1.1.1.1 是公网，应放行
        assertThat(PrivateAddressGuard.isBlockedAddress(InetAddress.getByName("::ffff:1.1.1.1"))).isFalse();
    }

    @Test
    void resolveAndCheck_dnsFailVsBlocked() {
        // 字面量 IP 不走 DNS：私网命中按 BLOCKED
        assertThat(PrivateAddressGuard.resolveAndCheck("127.0.0.1"))
                .isEqualTo(PrivateAddressGuard.CheckResult.BLOCKED);
        assertThat(PrivateAddressGuard.resolveAndCheck("10.0.0.1"))
                .isEqualTo(PrivateAddressGuard.CheckResult.BLOCKED);
        // 字面量公网 IP：OK
        assertThat(PrivateAddressGuard.resolveAndCheck("1.1.1.1"))
                .isEqualTo(PrivateAddressGuard.CheckResult.OK);
        // 空 host：DNS_FAIL
        assertThat(PrivateAddressGuard.resolveAndCheck(""))
                .isEqualTo(PrivateAddressGuard.CheckResult.DNS_FAIL);
        assertThat(PrivateAddressGuard.resolveAndCheck(null))
                .isEqualTo(PrivateAddressGuard.CheckResult.DNS_FAIL);
    }

    @Test
    void isBlockedHost_nullAndBlank() {
        assertThat(PrivateAddressGuard.isBlockedHost(null)).isTrue();
        assertThat(PrivateAddressGuard.isBlockedHost("")).isTrue();
        assertThat(PrivateAddressGuard.isBlockedHost("   ")).isTrue();
    }
}
