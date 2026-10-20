package com.involutionhell.backend.community.job;

import com.involutionhell.backend.community.repository.SharedLinkRepository;
import com.involutionhell.backend.community.repository.SharedLinkRepository.ProbeTarget;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档探活的 SSRF 防御：每一跳都过 PrivateAddressGuard，命中内网按"失效"计数。
 * 公网 host 用 1.1.1.1 字面量，guard 不走 DNS，离线 CI 也能跑。
 */
class SharedLinkArchiveJobTests {

    private final SharedLinkRepository repo = mock(SharedLinkRepository.class);
    private final HttpClient client = mock(HttpClient.class);
    private final SharedLinkArchiveJob job = new SharedLinkArchiveJob(repo, client);

    @Test
    void loopbackUrl_neverRequested_countedAsFail() throws Exception {
        when(repo.findApprovedForProbe(any(Integer.class)))
                .thenReturn(List.of(new ProbeTarget(1L, "http://127.0.0.1:8080/actuator", 0)));

        job.probeOnce();

        verify(client, never()).send(any(), any());
        verify(repo).incrementProbeFail(1L);
        verify(repo, never()).resetProbeFail(any());
    }

    @Test
    void redirectToMetadataEndpoint_blockedOnSecondHop() throws Exception {
        stubResponses(response(302, "http://169.254.169.254/latest/meta-data/"));

        assertThat(job.isAlive("https://1.1.1.1/post")).isFalse();
        verify(client, times(1)).send(any(), any());
    }

    @Test
    void publicHost200_alive() throws Exception {
        stubResponses(response(200, null));

        assertThat(job.isAlive("https://1.1.1.1/post")).isTrue();
    }

    @Test
    void publicRedirectChain_followedToFinalStatus() throws Exception {
        stubResponses(response(301, "/moved"), response(404, null));

        assertThat(job.isAlive("https://1.1.1.1/post")).isFalse();
        verify(client, times(2)).send(any(), any());
    }

    @SafeVarargs
    private void stubResponses(HttpResponse<Object>... responses) throws Exception {
        var stub = when(client.send(any(HttpRequest.class), any()));
        for (HttpResponse<Object> r : responses) {
            stub = stub.thenReturn(r);
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<Object> response(int status, String location) {
        HttpResponse<Object> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        Map<String, List<String>> headers = location == null ? Map.of() : Map.of("Location", List.of(location));
        when(resp.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
        return resp;
    }
}
