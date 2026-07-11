package com.example.httpreading.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WebSearchMcpService {
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int MAX_CONTENT_CHARS = 5000;

    private final ObjectMapper objectMapper;
    private final String searchEndpoint;
    private final int timeoutMillis;

    public WebSearchMcpService(ObjectMapper objectMapper,
                               @Value("${web-search.duckduckgo.endpoint:https://duckduckgo.com/html/}") String searchEndpoint,
                               @Value("${web-search.timeout-seconds:10}") int timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.searchEndpoint = searchEndpoint;
        this.timeoutMillis = (int) Duration.ofSeconds(Math.max(1, timeoutSeconds)).toMillis();
    }

    public Map<String, Object> search(String query, int topK, String lang, String timeRange) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        int limit = Math.max(1, Math.min(topK <= 0 ? DEFAULT_TOP_K : topK, MAX_TOP_K));
        try {
            String url = UriComponentsBuilder.fromUriString(searchEndpoint)
                .queryParam("q", query)
                .queryParam("kl", duckDuckGoRegion(lang))
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();
            Document document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 http-reading-mcp/0.0.1")
                .timeout(timeoutMillis)
                .get();

            List<Map<String, Object>> results = new ArrayList<>();
            for (Element item : document.select(".result")) {
                if (results.size() >= limit) {
                    break;
                }
                Element title = item.selectFirst(".result__a");
                if (title == null) {
                    continue;
                }
                String resultUrl = normalizeDuckDuckGoUrl(title.attr("href"));
                if (resultUrl.isBlank()) {
                    continue;
                }
                String snippet = text(item.selectFirst(".result__snippet"));
                String source = sourceHost(resultUrl);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("title", title.text());
                result.put("url", resultUrl);
                result.put("snippet", snippet);
                result.put("publishedAt", "");
                result.put("source", source);
                result.put("summary", snippet);
                results.add(result);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            data.put("provider", "duckduckgo-html");
            data.put("lang", lang == null ? "" : lang);
            data.put("timeRange", timeRange == null ? "" : timeRange);
            data.put("items", results);
            return data;
        } catch (IOException exception) {
            throw new IllegalStateException("网页搜索请求失败: " + exception.getMessage(), exception);
        }
    }

    public Map<String, Object> fetch(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        try {
            Document document = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 http-reading-mcp/0.0.1")
                .timeout(timeoutMillis)
                .get();
            String content = document.body() == null ? "" : document.body().text();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", document.title());
            data.put("url", url);
            data.put("snippet", truncate(content, 600));
            data.put("publishedAt", firstMetaContent(document,
                "meta[property=article:published_time]",
                "meta[name=date]",
                "meta[name=pubdate]"));
            data.put("source", sourceHost(url));
            data.put("content", truncate(content, MAX_CONTENT_CHARS));
            data.put("summary", truncate(content, 1200));
            return data;
        } catch (IOException exception) {
            throw new IllegalStateException("网页抓取请求失败: " + exception.getMessage(), exception);
        }
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String duckDuckGoRegion(String lang) {
        String normalized = lang == null ? "" : lang.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("zh")) {
            return "cn-zh";
        }
        if (normalized.startsWith("en")) {
            return "us-en";
        }
        return "wt-wt";
    }

    private String normalizeDuckDuckGoUrl(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        String value = href.trim();
        try {
            if (value.startsWith("//")) {
                value = "https:" + value;
            }
            URI uri = URI.create(value);
            String query = uri.getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    int index = part.indexOf('=');
                    if (index > 0 && "uddg".equals(part.substring(0, index))) {
                        return URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8);
                    }
                }
            }
            return value;
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private String sourceHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String firstMetaContent(Document document, String... selectors) {
        for (String selector : selectors) {
            Element element = document.selectFirst(selector);
            String content = element == null ? "" : element.attr("content");
            if (!content.isBlank()) {
                return content;
            }
        }
        return "";
    }

    private String text(Element element) {
        return element == null ? "" : element.text();
    }

    private String truncate(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }
}
