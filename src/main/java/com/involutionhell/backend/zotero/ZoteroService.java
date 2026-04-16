package com.involutionhell.backend.zotero;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 itemKey 批量从 Zotero group 拉 item 元信息。
 *
 * - 对应社区共享的 Zotero group（默认 6053219）
 * - 支持一次传多个 key，用逗号拼接走 ?itemKey=A,B,C 端点
 * - Caffeine 缓存 1h（用户 pinned papers 很少换，1h 足够）
 * - Zotero API 匿名访问公共 group 是允许的，无需 API key
 */
@Service
public class ZoteroService {

    private static final Logger log = LoggerFactory.getLogger(ZoteroService.class);
    private static final int MAX_KEYS_PER_REQUEST = 50;

    private final RestClient zotero;
    private final ObjectMapper mapper;
    private final long defaultGroupId;

    public ZoteroService(
            @Value("${ZOTERO_GROUP_ID:6053219}") long defaultGroupId,
            ObjectMapper mapper
    ) {
        this.defaultGroupId = defaultGroupId;
        this.mapper = mapper;
        this.zotero = RestClient.builder()
                .baseUrl("https://api.zotero.org")
                .defaultHeader("User-Agent", "involutionhell-backend")
                .build();
    }

    /**
     * 按 itemKey 列表批量取。返回顺序和输入顺序对齐；找不到的 key 直接丢弃。
     */
    @Cacheable(value = "zoteroItems", key = "#groupId + ':' + T(java.lang.String).join(',', #keys)", unless = "#result.isEmpty()")
    public List<ZoteroItemDto> getItems(long groupId, List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        long gid = groupId > 0 ? groupId : defaultGroupId;

        // 按 50 个一批分页请求（Zotero 单次上限）
        Map<String, ZoteroItemDto> byKey = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i += MAX_KEYS_PER_REQUEST) {
            List<String> batch = keys.subList(
                    i, Math.min(i + MAX_KEYS_PER_REQUEST, keys.size()));
            String csv = String.join(",", batch);
            try {
                String body = zotero.get()
                        .uri(uri -> uri
                                .path("/groups/{groupId}/items")
                                .queryParam("itemKey", csv)
                                .queryParam("format", "json")
                                .build(gid)
                        )
                        .retrieve()
                        .body(String.class);
                parseBatch(body, byKey);
            } catch (HttpClientErrorException e) {
                log.warn("[ZoteroService] Zotero API 返回 {}: {}, keys={}",
                        e.getStatusCode(), e.getStatusText(), csv);
            } catch (RestClientException e) {
                log.warn("[ZoteroService] Zotero API 网络异常: {}, keys={}",
                        e.getMessage(), csv);
            }
        }

        // 按输入顺序输出
        List<ZoteroItemDto> out = new ArrayList<>(byKey.size());
        for (String k : keys) {
            ZoteroItemDto dto = byKey.get(k);
            if (dto != null) out.add(dto);
        }
        return out;
    }

    /**
     * 解析 Zotero /items 批量响应，把每条塞进 map。
     */
    private void parseBatch(String body, Map<String, ZoteroItemDto> byKey) {
        if (body == null || body.isBlank()) return;
        try {
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return;
            for (JsonNode it : arr) {
                String key = text(it, "key");
                if (key.isEmpty()) continue;
                JsonNode data = it.get("data");
                if (data == null) continue;

                String title = text(data, "title");
                String date = text(data, "date");
                String year = extractYear(date);
                String url = text(data, "url");
                if (url.isEmpty()) {
                    // 没 url 时退回 Zotero 详情页
                    JsonNode links = it.get("links");
                    JsonNode alt = links != null ? links.get("alternate") : null;
                    if (alt != null) url = text(alt, "href");
                }
                String abstractNote = text(data, "abstractNote");
                String publicationTitle = text(data, "publicationTitle");
                String authors = extractAuthors(data.get("creators"));

                byKey.put(key, new ZoteroItemDto(
                        key, title, authors, year, url, abstractNote, publicationTitle));
            }
        } catch (Exception e) {
            log.warn("[ZoteroService] 解析 Zotero 响应失败: {}", e.getMessage());
        }
    }

    /**
     * 把 creators 数组拼成 "Last1, First1; Last2, First2" 格式。
     */
    private static String extractAuthors(JsonNode creators) {
        if (creators == null || !creators.isArray() || creators.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : creators) {
            if (sb.length() > 0) sb.append("; ");
            String name = text(c, "name"); // organization / single-name creator
            if (!name.isEmpty()) {
                sb.append(name);
                continue;
            }
            String last = text(c, "lastName");
            String first = text(c, "firstName");
            if (!last.isEmpty() && !first.isEmpty()) {
                sb.append(last).append(", ").append(first);
            } else if (!last.isEmpty()) {
                sb.append(last);
            } else if (!first.isEmpty()) {
                sb.append(first);
            }
        }
        return sb.toString();
    }

    /**
     * 从 "2024-03-15" / "2024" / "March 2024" 等格式里抓 4 位年份。
     */
    private static String extractYear(String date) {
        if (date == null || date.isBlank()) return "";
        var m = java.util.regex.Pattern.compile("(\\d{4})").matcher(date);
        return m.find() ? m.group(1) : "";
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }
}
