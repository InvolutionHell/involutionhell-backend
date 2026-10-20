package com.involutionhell.backend.community.job;

import com.involutionhell.backend.common.security.PrivateAddressGuard;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.repository.SharedLinkRepository;
import com.involutionhell.backend.community.repository.SharedLinkRepository.ProbeTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * M9 - 社区分享链接失效探活定时任务。
 *
 * 运行时机：每周日凌晨 3:00 UTC（国内是上午 11 点）跑一次。
 * 工作流程：
 *   1. 拉一批 APPROVED 状态的链接（一次最多 500 条，按 probe_last_at 轮询）
 *   2. 对每条发 HEAD 请求，15s 超时
 *   3. 2xx/3xx → resetProbeFail；4xx/5xx/异常 → incrementProbeFail
 *   4. probeFailCount 达 2（即本次失败后 >=2）→ 状态置 ARCHIVED，archived_reason=link_dead
 *
 * 设计要点：
 * - **不用 @Async**：@Scheduled 自己就在独立线程池跑，不会阻塞主请求
 * - **顺序扫描**：量小（预计千级）没必要并发，且避免并发打爆目标站点触发反爬
 * - **User-Agent** 和 OgFetchService 保持一致，方便目标站点识别白名单
 * - **"失效"不等于 4xx**：公众号文章违规被删返回 302 + 提示页；知乎删文 404。
 *   只用 HEAD 状态码判定就够，不做正文扫描——简单即是稳。
 * - 连续 2 次才 ARCHIVED：防止偶发网络抖动误杀
 *
 * 手动触发：通过写一个 admin-only 接口再调度 probeOnce()（未实现，按需加）。
 */
@Component
public class SharedLinkArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(SharedLinkArchiveJob.class);

    /** 每次扫描的链接数上限。单次跑完预计 500 × 500ms ≈ 4 分钟，在 weekly 任务里可接受。 */
    private static final int BATCH_SIZE = 500;

    /** 连续失败达到此次数 → 标记 ARCHIVED。 */
    private static final int ARCHIVE_THRESHOLD = 2;

    /** 手动跟随跳转的上限，和 JDK Redirect.NORMAL 的默认 5 跳一致。 */
    private static final int MAX_REDIRECTS = 5;

    /** 归档原因常量。 */
    private static final String ARCHIVED_REASON_LINK_DEAD = "link_dead";

    private final SharedLinkRepository linkRepo;
    private final HttpClient httpClient;

    /** 另有测试用 package-private ctor，必须显式 @Autowired，否则 Spring 不会自动选。 */
    @Autowired
    public SharedLinkArchiveJob(SharedLinkRepository linkRepo) {
        // NEVER：跳转由 isAlive 手动跟随，每跳都过 SSRF 守卫
        this(linkRepo, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** 测试注入用。 */
    SharedLinkArchiveJob(SharedLinkRepository linkRepo, HttpClient httpClient) {
        this.linkRepo = linkRepo;
        this.httpClient = httpClient;
    }

    /**
     * cron: 秒 分 时 日 月 周
     * "0 0 3 ? * SUN" = 每周日 03:00:00 UTC。
     *
     * 首次部署后给个 warmup：7 天才跑第一次。想手动测试可直接注入调 {@link #probeOnce()}.
     */
    @Scheduled(cron = "0 0 3 ? * SUN", zone = "UTC")
    public void probeOnce() {
        List<ProbeTarget> batch = linkRepo.findApprovedForProbe(BATCH_SIZE);
        if (batch.isEmpty()) {
            log.info("archive-job: nothing to probe");
            return;
        }
        log.info("archive-job: probing {} approved links", batch.size());

        int okCount = 0;
        int failCount = 0;
        int archivedCount = 0;

        for (ProbeTarget target : batch) {
            try {
                boolean alive = isAlive(target.url());
                if (alive) {
                    linkRepo.resetProbeFail(target.id());
                    okCount++;
                } else {
                    int newFails = linkRepo.incrementProbeFail(target.id());
                    failCount++;
                    if (newFails >= ARCHIVE_THRESHOLD) {
                        linkRepo.archive(target.id(), ARCHIVED_REASON_LINK_DEAD);
                        archivedCount++;
                        log.info("archive-job: archived id={} after {} consecutive fails",
                                target.id(), newFails);
                    }
                }
            } catch (Exception e) {
                // 捕一切异常保证循环不中断：单条错不影响整个 batch
                log.warn("archive-job: probe error id={} url={} err={}",
                        target.id(), target.url(), e.getMessage());
                // 异常也算失败一次
                try {
                    int newFails = linkRepo.incrementProbeFail(target.id());
                    failCount++;
                    if (newFails >= ARCHIVE_THRESHOLD) {
                        linkRepo.archive(target.id(), ARCHIVED_REASON_LINK_DEAD);
                        archivedCount++;
                    }
                } catch (Exception inner) {
                    log.error("archive-job: failed to record probe fail id={}",
                            target.id(), inner);
                }
            }
        }

        log.info("archive-job done: ok={} fail={} archived={}",
                okCount, failCount, archivedCount);
    }

    /**
     * 发 HEAD 请求判断链接是否存活。
     * 微信公众号不支持 HEAD 会返回 405，降级成 GET 不读 body。
     *
     * SSRF：和 {@link com.involutionhell.backend.community.service.OgFetchService} 同一套防御——
     * 不让 HttpClient 自己跟随跳转，每一跳（含 302 目标）都先过 {@link PrivateAddressGuard}。
     * 投稿时验过的公网 URL 之后可能被改成 302 → 169.254.169.254，或 DNS 改指内网；
     * 命中内网 / DNS 失败一律按"失效"算。DNS rebinding 残留窗口同 OgFetchService 注释。
     */
    boolean isAlive(String url) {
        try {
            URI uri = URI.create(url);
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                if (PrivateAddressGuard.isBlockedHost(uri.getHost())) {
                    log.warn("archive-job: blocked internal/unresolvable host url={}", uri);
                    return false;
                }
                HttpResponse<Void> resp = send(uri, "HEAD");
                if (resp.statusCode() == 405 || resp.statusCode() == 501) {
                    // 某些站点不支持 HEAD，退回到 GET（只读状态码不消费 body）
                    resp = send(uri, "GET");
                }
                int sc = resp.statusCode();
                if (sc >= 300 && sc < 400) {
                    Optional<String> location = resp.headers().firstValue("Location");
                    if (location.isEmpty() || location.get().isBlank()) {
                        return true; // 3xx 无 Location：站点还活着，沿用旧判定
                    }
                    uri = uri.resolve(location.get().trim());
                    continue;
                }
                return sc >= 200 && sc < 300;
            }
            return false; // 跳转超过上限
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpResponse<Void> send(URI uri, String method) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "Mozilla/5.0 (compatible; InvolutionHellBot/1.0)")
                .timeout(Duration.ofSeconds(15))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
