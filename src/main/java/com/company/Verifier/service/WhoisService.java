
package com.company.Verifier.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WhoisService {

    @Value("${app.whois.timeout:8000}")
    private int timeoutMs;

    public Map<String, Object> lookup(String domain) {

        Map<String, Object> out = new HashMap<>();

        if (domain == null || domain.isBlank()) {
            out.put("status", "UNAVAILABLE");
            out.put("reason", "No domain provided.");
            return out;
        }

        String domainClean = domain.trim()
                .replaceFirst("(?i)^https?://", "")
                .split("[/?#]", 2)[0]
                .replaceFirst("^www\\.", "")
                .toLowerCase(Locale.ROOT);

        if (domainClean.isBlank() || !domainClean.contains(".")) {
            out.put("status", "UNAVAILABLE");
            out.put("reason", "Invalid domain format.");
            return out;
        }

        String tld = domainClean.substring(
                domainClean.lastIndexOf('.') + 1
        );

        String whoisHost = chooseWhoisServer(tld);

        // One overall time budget for connecting and reading.
        long deadline = System.nanoTime()
                + Duration.ofMillis(Math.max(1, timeoutMs)).toNanos();

        try (Socket socket = new Socket()) {

            int connectTimeout = remainingMillis(deadline);

            socket.connect(
                    new InetSocketAddress(whoisHost, 43),
                    connectTimeout
            );

            socket.getOutputStream().write(
                    (domainClean + "\r\n")
                            .getBytes(StandardCharsets.UTF_8)
            );
            socket.getOutputStream().flush();

            StringBuilder response = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    ))) {

                String line;

                while (true) {
                    // Recalculate the remaining total time before each read.
                    socket.setSoTimeout(remainingMillis(deadline));

                    line = reader.readLine();

                    if (line == null) {
                        break;
                    }

                    response.append(line).append('\n');
                }
            }

            String resp = response.toString();
            out.put("raw", resp);

            String creation = extractCreationDate(resp);

            if (creation != null) {
                out.put("creationDateRaw", creation);

                LocalDate parsed = tryParseDate(creation);

                if (parsed != null && !parsed.isAfter(LocalDate.now())) {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(
                            parsed,
                            LocalDate.now()
                    );

                    out.put("ageDays", days);
                }
            }

            out.put("status", "SUCCESS");
            out.put(
                    "reason",
                    out.containsKey("ageDays")
                            ? "WHOIS response received and domain age determined."
                            : "WHOIS response received, but domain age could not be determined."
            );

        } catch (SocketTimeoutException e) {
            out.clear();
            out.put("status", "UNAVAILABLE");
            out.put(
                    "reason",
                    "WHOIS lookup timed out. This is not evidence of fraud."
            );

        } catch (Exception e) {
            out.clear();
            out.put("status", "UNAVAILABLE");
            out.put(
                    "reason",
                    "WHOIS lookup failed. This is not evidence of fraud."
            );
        }

        return out;
    }

    private int remainingMillis(long deadline) throws SocketTimeoutException {
        long remainingNanos = deadline - System.nanoTime();

        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("WHOIS time budget exceeded");
        }

        // Round up so a positive fraction of a millisecond is not treated as zero.
        long millis = (remainingNanos + 999_999L) / 1_000_000L;

        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private String chooseWhoisServer(String tld) {
        return switch (tld.toLowerCase(Locale.ROOT)) {
            case "com", "net" -> "whois.verisign-grs.com";
            case "org" -> "whois.pir.org";
            case "in" -> "whois.registry.in";
            case "io" -> "whois.nic.io";
            default -> "whois.iana.org";
        };
    }

    private String extractCreationDate(String text) {
        Pattern pattern = Pattern.compile(
                "(?im)^\\s*(?:Creation Date|created|Registered on):\\s*(.+)$"
        );

        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        Pattern fallback = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
        Matcher fallbackMatcher = fallback.matcher(text);

        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1);
        }

        return null;
    }

    private LocalDate tryParseDate(String raw) {
        String value = raw.trim();

        try {
            return LocalDate.parse(
                    value.substring(0, 10),
                    DateTimeFormatter.ISO_LOCAL_DATE
            );
        } catch (Exception ignored) {
            // Try another supported representation.
        }

        try {
            return LocalDate.parse(
                    value,
                    DateTimeFormatter.ISO_LOCAL_DATE
            );
        } catch (Exception ignored) {
            // Try extracting a date from the response value.
        }

        Pattern pattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher matcher = pattern.matcher(value);

        if (matcher.find()) {
            try {
                return LocalDate.parse(matcher.group(1));
            } catch (Exception ignored) {
                // The extracted date was not valid.
            }
        }

        return null;
    }
}
