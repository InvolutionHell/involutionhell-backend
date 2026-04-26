package com.involutionhell.backend.community.site;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 串联所有 {@link SiteAdapter} 跑链式 normalize，直到稳定（没人再改）。
 *
 * 链式必要性：scholar_url 提出来的 url 可能又是 arxiv pdf，需要 arxiv adapter 再处理一次。
 * 防环：用 visited set 兜底，最多 5 跳。
 */
@Component
public class UrlNormalizer {

    private static final Logger log = LoggerFactory.getLogger(UrlNormalizer.class);
    private static final int MAX_HOPS = 5;

    private final List<SiteAdapter> adapters;

    public UrlNormalizer(List<SiteAdapter> adapters) {
        this.adapters = adapters;
    }

    public String normalize(String url) {
        if (url == null) return null;
        String current = url;
        Set<String> visited = new HashSet<>();
        visited.add(current);
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            String next = current;
            for (SiteAdapter adapter : adapters) {
                try {
                    next = adapter.normalize(next);
                } catch (Exception e) {
                    log.warn("url-normalize adapter 异常，跳过: adapter={} url={} error={}",
                            adapter.getClass().getSimpleName(), next, e.getMessage());
                }
            }
            if (next.equals(current)) {
                if (hop > 0) {
                    log.debug("url-normalize 完成: {} -> {} (hops={})", url, current, hop);
                }
                return current;
            }
            if (!visited.add(next)) {
                log.warn("url-normalize 检测到环路，停在: url={} loop={}", url, next);
                return current;
            }
            current = next;
        }
        log.warn("url-normalize 超过最大跳数 {}: original={} final={}", MAX_HOPS, url, current);
        return current;
    }
}
