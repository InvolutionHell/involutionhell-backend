package com.involutionhell.backend.usercenter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordServiceTests {

    private final PasswordService passwordService = new PasswordService();

    @Test
    void hashGeneratesBcryptOutput() {
        String hash = passwordService.hash("Admin@123456");

        // bcrypt 输出形如 $2b$10$<22 字符 salt><31 字符 hash>，总长 60
        assertThat(hash).startsWith("$2");
        assertThat(hash).hasSize(60);
    }

    /**
     * 同一明文哈希两次必须输出不同结果——salt 随机的关键证据。
     * 之前裸 SHA-256 的实现里这条会失败，正是漏洞特征。
     */
    @Test
    void hashSamePasswordTwiceProducesDifferentOutput() {
        String first = passwordService.hash("Admin@123456");
        String second = passwordService.hash("Admin@123456");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void matchesReturnsTrueOnlyForSamePassword() {
        String hash = passwordService.hash("Alice@123456");

        assertThat(passwordService.matches("Alice@123456", hash)).isTrue();
        assertThat(passwordService.matches("wrong-password", hash)).isFalse();
    }

    /**
     * INV-003 兼容路径：旧 SHA-256 hash 仍能让历史用户登录。
     * 这条 hash 是 sha256("Admin@123456") 裸十六进制——和老 schema.sql / test-schema.sql
     * 之前 seed 的内容一致，确保 dual-mode 保留向后兼容。
     */
    @Test
    void matchesAcceptsLegacySha256Hash() {
        String legacyHash = "ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d";

        assertThat(passwordService.matches("Admin@123456", legacyHash)).isTrue();
        assertThat(passwordService.matches("wrong-password", legacyHash)).isFalse();
    }

    @Test
    void isLegacyHashIdentifiesPlainSha256() {
        String legacyHash = "ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d";
        String bcryptHash = passwordService.hash("any");

        assertThat(passwordService.isLegacyHash(legacyHash)).isTrue();
        assertThat(passwordService.isLegacyHash(bcryptHash)).isFalse();
    }

    @Test
    void matchesReturnsFalseOnNullInputs() {
        assertThat(passwordService.matches(null, "$2b$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")).isFalse();
        assertThat(passwordService.matches("Admin@123456", null)).isFalse();
    }
}
