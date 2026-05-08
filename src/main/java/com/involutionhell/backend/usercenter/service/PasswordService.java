package com.involutionhell.backend.usercenter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户密码哈希与校验服务。
 *
 * 安全不变量 INV-003：新生成的口令哈希必须是 bcrypt 形式（带 salt + 高成本因子），
 * 永远不再产生裸 SHA-256 哈希。matches 路径仍兼容老 SHA-256 hash 让历史用户能登
 * 录，配合 AuthService 的 lazy upgrade 在登录成功后把老哈希就地迁移成 bcrypt。
 *
 * 历史背景：原实现用单轮 SHA-256，无 salt 无 cost factor，rainbow table 秒破。
 * 2026-05-07 三方 CR attack chain B 起点（参见 SECURITY.md / 内部报告）。
 */
@Service
public class PasswordService {

    /**
     * BCrypt 输出格式：`$2[aby]$NN$` 后跟 53 字符 base64-ish 数据。
     * 用 Pattern.matches 严格判断而不是 startsWith，防止 legacy hash 凑巧
     * 以 `$2` 开头被误识别为 bcrypt（虽然概率极低，但白名单更稳）。
     */
    private static final Pattern BCRYPT_PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$.{53}$");

    /**
     * cost factor 10：每次 hash 约 ~80ms（普通 x86_64 server），
     * 抗 GPU 暴破足够；同时保证登录路径 RT < 100ms 不影响 UX。
     * 升级到 12 会让单次 hash ~300ms，登录峰值场景慎用。
     */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    /**
     * 始终生成 bcrypt 哈希（带 salt + cost）。
     * 即便同一明文调用两次，输出也不同（salt 随机）。
     */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验明文与哈希是否匹配。
     *
     * 双路径：
     *   - bcrypt 格式：BCryptPasswordEncoder 内部已是 constant-time
     *   - 其他（legacy SHA-256）：用 MessageDigest.isEqual 做 constant-time 比较
     *
     * 校验通过且原 hash 是 legacy 格式时，调用方应触发 lazy upgrade（见 AuthService）。
     */
    public boolean matches(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null) {
            return false;
        }
        if (BCRYPT_PATTERN.matcher(hashedPassword).matches()) {
            return encoder.matches(rawPassword, hashedPassword);
        }
        // Legacy 路径：以前的裸 SHA-256 哈希仍允许登录，但应被 lazy upgrade。
        return constantTimeEquals(legacySha256Hex(rawPassword), hashedPassword);
    }

    /**
     * 判断给定哈希是否为 legacy 格式（非 bcrypt）。
     * AuthService 在登录成功后用此判断决定是否就地升级哈希。
     */
    public boolean isLegacyHash(String hashedPassword) {
        return hashedPassword != null
                && !BCRYPT_PATTERN.matcher(hashedPassword).matches();
    }

    /**
     * 仅用于 legacy 兼容：复刻原裸 SHA-256 哈希算法。
     * 不对外暴露——所有新写入路径必须走 hash()。
     */
    private static String legacySha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 常量时间字符串比较，防止 legacy 路径上的 timing attack。
     * 长度不一致也走同一时间分支（MessageDigest.isEqual 实现保证）。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
