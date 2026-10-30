
package com.company.Verifier.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NominatimService {

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://nominatim.openstreetmap.org")
            .build();

    @Value("${app.nominatim.userAgent:TrustCheck/1.0 (contact: configure-me@example.com)}")
    private String userAgent;

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final long MIN_REQUEST_INTERVAL_MS = 1100L;

    private final Map<String, CacheEntry> cache =
            new ConcurrentHashMap<>();

    private long lastRequestTime = 0;

    private static class CacheEntry {
        private final Map<String, Object> result;
        private final long createdAt;

        private CacheEntry(Map<String, Object> result) {
            this.result = new HashMap<>(result);
            this.createdAt = System.currentTimeMillis();
        }
    }

    public synchronized Map<String, Object> search(String query) {

        Map<String, Object> output = new HashMap<>();

        if (query == null || query.isBlank()) {
            output.put("count", 0);
            output.put("status", "INVALID_QUERY");
            output.put("reason", "A company name or location is required.");
            return output;
        }

        String normalizedQuery = query.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        // 1. Return cached result when it is still fresh.
        CacheEntry cached = cache.get(normalizedQuery);

        if (cached != null) {
            long age = System.currentTimeMillis() - cached.createdAt;

            if (age < CACHE_TTL_MS) {
                Map<String, Object> result =
                        new HashMap<>(cached.result);

                result.put("cached", true);
                return result;
            }

            cache.remove(normalizedQuery);
        }

        // 2. Respect the request interval.
        try {
            long elapsed =
                    System.currentTimeMillis() - lastRequestTime;

            long waitTime = MIN_REQUEST_INTERVAL_MS - elapsed;

            if (waitTime > 0) {
                Thread.sleep(waitTime);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            output.put("count", 0);
            output.put("status", "UNAVAILABLE");
            output.put("reason", "Location lookup was interrupted.");
            return output;
        }

        // 3. Make the external request with a timeout.
        try {
            lastRequestTime = System.currentTimeMillis();

            List<Map<String, Object>> results = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("q", query.trim())
                            .queryParam("format", "json")
                            .queryParam("limit", 3)
                            .build())
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .bodyToMono(
                            new ParameterizedTypeReference<
                                    List<Map<String, Object>>>() {}
                    )
                    .block(Duration.ofSeconds(8));

            output.put("count", results == null ? 0 : results.size());
            output.put("cached", false);
            output.put("status",
                    results != null && !results.isEmpty()
                            ? "FOUND"
                            : "NO_MATCH");

            if (results != null && !results.isEmpty()) {
                output.put("top", results.get(0));
            }

            // Cache successful responses, including a valid empty result.
            cache.put(normalizedQuery, new CacheEntry(output));

        } catch (Exception e) {
            output.put("count", 0);
            output.put("status", "UNAVAILABLE");
            output.put(
                    "reason",
                    "The location service could not be reached. This is not evidence of fraud."
            );
        }

        return output;
    }
}
