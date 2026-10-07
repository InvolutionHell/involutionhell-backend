package com.involutionhell.backend.sso.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.involutionhell.backend.sso.config.SsoProperties;
import com.involutionhell.backend.sso.dto.SsoCodeRequest;
import com.involutionhell.backend.sso.dto.SsoTokenRequest;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * SsoService 里 HTTP 层测不到的部分：码 60 秒过期（假时钟）、client 配置的启用条件。
 * 端到端行为见 SsoControllerIntegrationTests。
 */
class SsoServiceTests {

    private static final class FakeTicker implements Ticker {
        private long nanos = 0;
        @Override public long read() { return nanos; }
        void advance(Duration d) { nanos += d.toNanos(); }
    }

    private static final String REDIRECT = "https://holocard.longsizhuo.com/auth/callback";
    private static final String VERIFIER = "v".repeat(43);

    private final FakeTicker ticker = new FakeTicker();
    private final UserAccountRepository users = mock(UserAccountRepository.class);

    private SsoService service(SsoProperties.Client holocard) {
        when(users.findById(7L)).thenReturn(Optional.of(new UserAccount(
                7L, "alice", "!", "Alice", true, Set.of("user"), Set.of(), null, null, null, null)));
        return new SsoService(new SsoProperties(Map.of("holocard", holocard)), users, ticker);
    }

    @Test
    void codeExpiresAfter60Seconds() throws Exception {
        SsoService sso = service(new SsoProperties.Client("s3cret", REDIRECT));

        String fresh = issue(sso);
        ticker.advance(Duration.ofSeconds(59));
        assertThat(sso.redeem(redeemRequest(fresh))).isPresent();

        String stale = issue(sso);
        ticker.advance(Duration.ofSeconds(61));
        assertThat(sso.redeem(redeemRequest(stale))).isEmpty();
    }

    @Test
    void clientNeedsBothSecretAndRedirectUriToBeEnabled() {
        assertThat(service(new SsoProperties.Client("s3cret", REDIRECT)).authenticateClient("holocard", "s3cret"))
                .isTrue();
        assertThat(service(new SsoProperties.Client("", REDIRECT)).authenticateClient("holocard", "")).isFalse();
        assertThat(service(new SsoProperties.Client("  ", REDIRECT)).authenticateClient("holocard", "  ")).isFalse();
        assertThat(service(new SsoProperties.Client(null, REDIRECT)).authenticateClient("holocard", null)).isFalse();
        // 登记地址为空时，请求里传空串就能"逐字相等"，所以也算未启用
        assertThat(service(new SsoProperties.Client("s3cret", "")).authenticateClient("holocard", "s3cret"))
                .isFalse();
    }

    private static String issue(SsoService sso) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        String redirect = sso.issueCode(7L, new SsoCodeRequest("holocard", REDIRECT, "st", challenge, "S256"));
        return UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("code");
    }

    private static SsoTokenRequest redeemRequest(String code) {
        return new SsoTokenRequest("holocard", "s3cret", code, VERIFIER, REDIRECT);
    }
}
