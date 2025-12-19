package com.company.Verifier.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NominatimService {

    private final WebClient webClient = WebClient.builder().baseUrl("https://nominatim.openstreetmap.org").build();

    @Value("${app.nominatim.userAgent:your-email@example.com}")
    private String userAgent;

    public Map<String,Object> search(String query) {
        Map<String,Object> out = new HashMap<>();
        try {
            Mono<List> mono = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("limit", "3")
                            .build())
                    .header("User-Agent", userAgent)
                    .retrieve()
                    .bodyToMono(List.class);

            List results = mono.block();
            out.put("count", results == null ? 0 : results.size());
            if (results != null && !results.isEmpty()) out.put("top", results.get(0));
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }
}

