package com.involutionhell.backend.common.security;

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
 *   <li>IPv4-mapped IPv6 (::ffff:0:0/96)：取出末 4 字节，按 IPv4 规则再判一次。
 *       否则攻击者用 [::ffff:127.0.0.1] / [::ffff:10.0.0.1] 这类字面量能绕过</li>
 * </ul>
 *
 * 只做“是否应拒绝”的判定，不负责发起 HTTP 请求。调用方需在发 HTTP 前对每一跳
 * （包括 302/301 redirect 的目标）都走一次 {@link #resolveAndCheck(String)}。
 *
 * <p>调用约定：
 * <ul>
 *   <li>{@link #resolveAndCheck(String)} 区分 OK / DNS_FAIL / BLOCKED，调用方可以
 *       给最终用户返回不同的错误信息（"DNS 查询失败" vs "拒绝内网地址"）。</li>
 *   <li>{@link #isBlockedHost(String)} 是 fail-closed 的布尔包装：DNS 失败也按
 *       BLOCKED 算。保留给只关心 boolean 的旧调用方（不区分原因即可）。</li>
 * </ul>
 */
public final class PrivateAddressGuard {

    private PrivateAddressGuard() {}

    /** {@link #resolveAndCheck(String)} 的三态结果。 */
    public enum CheckResult {
        /** 解析成功且所有 IP 都是公网。 */
        OK,
        /** DNS 解析失败 / 空 host —— 上层应给“DNS 查询失败”这种 user-facing 错误。 */
        DNS_FAIL,
        /** 解析成功但有 IP 命中内网/回环/link-local 等黑名单段。 */
        BLOCKED
    }

    /**
     * 解析 host，对每个 IP 跑黑名单。区分 DNS 失败和真实命中黑名单两种情况，
     * 让调用方可以给用户更准确的 error message。
     *
     * 仍然 fail-closed：{@link CheckResult#DNS_FAIL} 在调用方语义上和
     * {@link CheckResult#BLOCKED} 一样要拒绝请求，只是错误文案不一样。
     */
    public static CheckResult resolveAndCheck(String host) {
        if (host == null || host.isBlank()) {
            return CheckResult.DNS_FAIL;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return CheckResult.DNS_FAIL;
        }
        if (addrs == null || addrs.length == 0) {
            return CheckResult.DNS_FAIL;
        }
        for (InetAddress a : addrs) {
            if (isBlockedAddress(a)) {
                return CheckResult.BLOCKED;
            }
        }
        return CheckResult.OK;
    }

    /**
     * 解析 host 对应的所有 IP，只要任意一个 IP 命中内网段就返回 true。
     * DNS 解析失败当作“不可达”处理，返回 true（等价于拒绝请求，fail-closed）。
     *
     * 不区分 DNS 失败和黑名单命中的旧 API；新代码请用
     * {@link #resolveAndCheck(String)} 拿到三态枚举。
     */
    public static boolean isBlockedHost(String host) {
        return resolveAndCheck(host) != CheckResult.OK;
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

            // ::ffff:0:0/96 — IPv4-mapped IPv6。JDK 对这类地址的
            // isLoopbackAddress / isSiteLocalAddress 返回 false（它认为这就是
            // IPv6），导致攻击者写 [::ffff:127.0.0.1] 或 [::ffff:10.0.0.1]
            // 能绕过前面所有 IPv4 规则。这里取末 4 字节重建 Inet4Address
            // 再走一次 IPv4 黑名单，关掉这条路。
            if (isIPv4Mapped(b)) {
                try {
                    byte[] v4Bytes = new byte[] { b[12], b[13], b[14], b[15] };
                    InetAddress mapped = InetAddress.getByAddress(v4Bytes);
                    if (mapped instanceof Inet4Address && isBlockedAddress(mapped)) {
                        return true;
                    }
                } catch (UnknownHostException e) {
                    // getByAddress(byte[4]) 不会真去做 DNS 反查，理论上不抛；
                    // 真抛了视为可疑，按 BLOCKED 处理
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 判定 16 字节是否是 ::ffff:0:0/96（IPv4-mapped IPv6）：
     * 前 10 字节全 0，第 11、12 字节都是 0xff。
     */
    private static boolean isIPv4Mapped(byte[] b) {
        if (b.length != 16) return false;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
