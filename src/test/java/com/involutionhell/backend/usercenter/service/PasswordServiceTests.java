package com.involutionhell.backend.usercenter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordServiceTests {

    private final PasswordService passwordService = new PasswordService();

    @Test
    void hashReturnsStableSha256Hex() {
        String hash = passwordService.hash("Admin@123456");

        assertThat(hash).hasSize(64);
        assertThat(hash).isEqualTo(passwordService.hash("Admin@123456"));
    }

    @Test
    void matchesReturnsTrueOnlyForSamePassword() {
        String hash = passwordService.hash("Alice@123456");

        assertThat(passwordService.matches("Alice@123456", hash)).isTrue();
        assertThat(passwordService.matches("wrong-password", hash)).isFalse();
    }
}
