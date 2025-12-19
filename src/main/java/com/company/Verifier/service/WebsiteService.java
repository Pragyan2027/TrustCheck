package com.company.Verifier.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.net.ssl.HttpsURLConnection;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

@Service
public class WebsiteService {

    private final WebClient webClient = WebClient.builder().build();

    public Map<String,Object> analyzeWebsite(String website) {
        Map<String,Object> out = new HashMap<>();
        if (website == null || website.isBlank()) {
            out.put("error", "no website provided");
            return out;
        }

        try {
            if (!website.startsWith("http://") && !website.startsWith("https://")) website = "https://" + website;

            // reachability
            Mono<Integer> mono = webClient.get()
                    .uri(website)
                    .retrieve()
                    .toBodilessEntity()
                    .map(resp -> resp.getStatusCode().value())
                    .onErrorReturn(-1);
            int status = mono.block(Duration.ofSeconds(10));
            out.put("status", status);

            URL url = new URL(website);
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                try {
                    HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.connect();
                    java.security.cert.Certificate[] certs = conn.getServerCertificates();
                    out.put("tlsPresent", certs != null && certs.length > 0);
                    if (certs != null && certs.length > 0) {
                        out.put("certCount", certs.length);
                        out.put("certType", certs[0].getType());
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    out.put("tlsError", e.getMessage());
                    out.put("tlsPresent", false);
                }
            } else {
                out.put("tlsPresent", false);
            }

            // contact page heuristics
            String base = website.endsWith("/") ? website.substring(0, website.length()-1) : website;
            String[] candidates = { base, base + "/contact", base + "/contact-us", base + "/#contact" };
            boolean contactFound = false;
            for (String cand : candidates) {
                try {
                    int st = webClient.get().uri(cand).retrieve().toBodilessEntity()
                            .map(r->r.getStatusCode().value()).onErrorReturn(-1).block(Duration.ofSeconds(6));
                    if (st >= 200 && st < 400) { contactFound = true; break; }
                } catch (Exception ignored) {}
            }
            out.put("contactPageFound", contactFound);

        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    public Map<String,Object> dnsChecks(String domain) {
        Map<String,Object> out = new HashMap<>();
        if (domain == null || domain.isBlank()) {
            out.put("error", "no domain");
            return out;
        }
        try {
            String host = domain.replaceFirst("^https?://", "").split("/")[0];

            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            DirContext dirContext = new InitialDirContext(env);

            // MX
            try {
                Attributes attrs = dirContext.getAttributes(host, new String[]{"MX"});
                out.put("hasMX", attrs.get("MX") != null && attrs.get("MX").size() > 0);
            } catch (Exception ex) {
                out.put("mxError", ex.getMessage());
                out.put("hasMX", false);
            }

            // TXT (SPF)
            try {
                Attributes attrsTxt = dirContext.getAttributes(host, new String[]{"TXT"});
                var attr = attrsTxt.get("TXT");
                boolean hasSPF = false;
                if (attr != null) {
                    for (int i = 0; i < attr.size(); i++) {
                        String txt = (String) attr.get(i);
                        if (txt.toLowerCase().contains("v=spf1")) { hasSPF = true; break; }
                    }
                }
                out.put("hasSPF", hasSPF);
            } catch (Exception ex) {
                out.put("txtError", ex.getMessage());
                out.put("hasSPF", false);
            }

        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }
}

