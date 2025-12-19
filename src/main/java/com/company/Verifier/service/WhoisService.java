package com.company.Verifier.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WhoisService {

    @Value("${app.whois.timeout:8000}")
    private int timeoutMs;

    public Map<String,Object> lookup(String domain) {
        Map<String,Object> out = new HashMap<>();
        if (domain == null || domain.isBlank()) {
            out.put("error", "no domain provided");
            return out;
        }
        String domainClean = domain.replaceFirst("^https?://", "").split("/")[0];
        String tld = domainClean.contains(".") ? domainClean.substring(domainClean.lastIndexOf('.') + 1) : "";
        String whoisHost = chooseWhoisServer(tld);

        try (Socket socket = new Socket(whoisHost, 43)) {
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write((domainClean + "\r\n").getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            String resp = sb.toString();
            out.put("raw", resp);

            String creation = extractCreationDate(resp);
            if (creation != null) {
                out.put("creationDateRaw", creation);
                LocalDate parsed = tryParseDate(creation);
                if (parsed != null) {
                    long days = java.time.Duration.between(parsed.atStartOfDay(), LocalDate.now().atStartOfDay()).toDays();
                    out.put("ageDays", days);
                }
            }
            return out;
        } catch (Exception e) {
            out.put("error", e.getMessage());
            return out;
        }
    }

    private String chooseWhoisServer(String tld) {
        return switch (tld.toLowerCase()) {
            case "com", "net" -> "whois.verisign-grs.com";
            case "org" -> "whois.pir.org";
            case "in" -> "whois.registry.in";
            case "io" -> "whois.nic.io";
            default -> "whois.iana.org";
        };
    }

    private String extractCreationDate(String text) {
        Pattern p = Pattern.compile("(?i)Creation Date:\\s*(.+)|(?i)created:\\s*(.+)|(?i)Registered on:\\s*(.+)");
        Matcher m = p.matcher(text);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null && !g.isBlank()) return g.trim();
            }
        }
        Pattern p2 = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher m2 = p2.matcher(text);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private LocalDate tryParseDate(String raw) {
        String r = raw.trim();
        // try common formats
        try { return LocalDate.parse(r.substring(0,10), DateTimeFormatter.ISO_LOCAL_DATE); } catch (Exception ignored) {}
        try { return LocalDate.parse(r, DateTimeFormatter.ISO_LOCAL_DATE); } catch (Exception ignored) {}
        // fallback: extract yyyy-mm-dd
        Pattern p = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher m = p.matcher(r);
        if (m.find()) {
            try { return LocalDate.parse(m.group(1)); } catch (Exception ignored) {}
        }
        return null;
    }
}
