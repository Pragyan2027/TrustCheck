package com.company.Verifier.service;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Service
public class BlacklistService {

    private final Set<String> blacklist = new HashSet<>();

    @PostConstruct
    public void load() {
        try {
            var resource = new ClassPathResource("Blackseed.csv");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = br.readLine()) != null) {
                    if (first) { first = false; continue; }
                    String[] cols = line.split(",", 2);
                    if (cols.length > 0) {
                        String name = cols[0].trim().replaceAll("^\"|\"$", "");
                        if (!name.isBlank()) blacklist.add(normalize(name));
                    }
                }
            }
            System.out.println("Loaded blacklist entries: " + blacklist.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
    }

    public boolean isBlacklisted(String companyName) {
        if (companyName == null) return false;
        return blacklist.contains(normalize(companyName));
    }
}
