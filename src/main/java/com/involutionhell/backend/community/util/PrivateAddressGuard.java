package com.involutionhell.backend.community.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SSRF 防御：判定主机名解析后的 IP 是否属于不能被服务端主动访问的地址段。
 *
 * 命中任一地址段即视为“内网”，应立刻拒绝：
 * <ul>
 *   <li>IPv4: 127.0.0.0/8（loopback）、10.0.0.0/8、172.16.0.0/12、192.168.0.0/16、
 *       169.254.0.0/16（link-local / AWS / GCP metadata）、0.0.0.0/8、
 *       100.64.0.0/10（CGNAT）、224.0.0.0/4（multicast）</li>
 *   <li>IPv6: ::1（loopback）、fc00::/7（ULA）、fe80::/10（link-local）、
 *       以及被 {@link InetAddress} 直接归类为 anyLocal / siteLocal / mcast 的任何地址</li>
 * </ul>
 *
 * 只做“是否应拒绝”的判定，不负责发起 HTTP 请求。调用方需在发 HTTP 前对每一跳
 * （包括 302/301 redirect 的目标）都走一次 {@link #isBlockedHost(String)}。
 */
public final class PrivateAddressGuard {

    private PrivateAddressGuard() {}

    /**
     * 解析 host 对应的所有 IP，只要任意一个 IP 命中内网段就返回 true。
     * DNS 解析失败当作“不可达”处理，返回 true（等价于拒绝请求，fail-closed）。
     */
    public static boolean isBlockedHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // DNS 失败 → 不放行（避免被 DNS rebinding 绕过）
            return true;
        }
        if (addrs == null || addrs.length == 0) {
            return true;
        }
        for (InetAddress a : addrs) {
            if (isBlockedAddress(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单个 IP 的内网判定。拆出来方便单元测试。
     */
    public static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null) return true;

        // JDK 已经帮我们覆盖了绝大多数“不是公网”的情况
        if (addr.isLoopbackAddress()) return true;        // 127.0.0.0/8 / ::1
        if (addr.isAnyLocalAddress()) return true;        // 0.0.0.0 / ::
        if (addr.isLinkLocalAddress()) return true;       // 169.254/16 / fe80::/10
        if (addr.isSiteLocalAddress()) return true;       // 10/8, 172.16/12, 192.168/16
        if (addr.isMulticastAddress()) return true;       // 224.0.0.0/4 / ff00::/8

        if (addr instanceof Inet4Address v4) {
            byte[] b = v4.getAddress();
            int o1 = b[0] & 0xff;
            int o2 = b[1] & 0xff;

            // 0.0.0.0/8（“本网络”）JDK 的 isAnyLocalAddress 只判 0.0.0.0 精确值，
            // 这里扩到整段保险
            if (o1 == 0) return true;

            // CGNAT 100.64.0.0/10 不在 JDK 的 siteLocal 里
            if (o1 == 100 && (o2 & 0xc0) == 64) return true;

            // 防御性：JDK 某些版本 isSiteLocalAddress 的判定以 10/172.16/192.168 为准，
            // 再显式补一遍，避免 JDK 行为变化
            if (o1 == 10) return true;
            if (o1 == 172 && o2 >= 16 && o2 <= 31) return true;
            if (o1 == 192 && o2 == 168) return true;
            if (o1 == 169 && o2 == 254) return true;
        } else if (addr instanceof Inet6Address v6) {
            byte[] b = v6.getAddress();
            int first = b[0] & 0xff;

            // fc00::/7 — Unique Local Address，JDK 没有 isUniqueLocal()
            if ((first & 0xfe) == 0xfc) return true;

            // fe80::/10 — link-local；JDK 已判过，冗余一遍保险
            if (first == 0xfe && (b[1] & 0xc0) == 0x80) return true;
        }

        return false;
    }
}
