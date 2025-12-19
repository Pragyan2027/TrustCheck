package com.company.Verifier.service;

import com.company.Verifier.model.CompanyReport;
import com.company.Verifier.model.CompanyRequest;
import com.company.Verifier.model.Evidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class VerificationService {

    @Autowired
    private NominatimService nominatimService;

    @Autowired
    private WhoisService whoisService;

    @Autowired
    private WebsiteService websiteService;

    @Autowired
    private BlacklistService blacklistService;

    // Weights
    private final int NOMINATIM_WEIGHT = 20;
    private final int WHOIS_AGE_WEIGHT = 10;
    private final int WEBSITE_CONTACT_WEIGHT = 15;
    private final int TLS_WEIGHT = 5;
    private final int DNS_WEIGHT = 5;

    public CompanyReport verify(CompanyRequest req) {

        String canonical = req.getName();
        List<Evidence> evidences = new ArrayList<>();

        // ---------------- BLACKLIST CHECK ----------------
        boolean isBlacklisted = blacklistService.isBlacklisted(canonical);
        if (isBlacklisted) {
            Map<String, Object> info = Map.of("note", "matched seed blacklist");
            evidences.add(new Evidence("Blackseed", info, 0));
        }

        // ---------------- NOMINATIM CHECK ----------------
        Map<String, Object> nom = nominatimService.search(
                canonical + (req.getCity() != null ? " " + req.getCity() : "")
        );

        int nomScore = 0;
        boolean hasOffice = false;

        if (nom.containsKey("count")) {
            try {
                int count = (int) nom.get("count");
                if (count > 0) {
                    nomScore = NOMINATIM_WEIGHT;
                    hasOffice = true;
                }
            } catch (Exception ignored) {}
        }

        evidences.add(new Evidence("nominatim", nom, nomScore));

        // ---------------- WHOIS CHECK ----------------
        String domain = req.getDomain();
        if (domain == null && req.getWebsite() != null) {
            domain = req.getWebsite().replaceFirst("^https?://", "").split("/")[0];
        }

        Map<String, Object> whois = whoisService.lookup(domain);

        int whoisScore = 0;
        long domainAgeDays = 0;

        if (whois.containsKey("ageDays") && whois.get("ageDays") instanceof Number) {
            domainAgeDays = ((Number) whois.get("ageDays")).longValue();
            if (domainAgeDays >= 90) {
                whoisScore = WHOIS_AGE_WEIGHT;
            }
        }

        evidences.add(new Evidence("whois", whois, whoisScore));

        // ---------------- WEBSITE ANALYSIS ----------------
        Map<String, Object> site = websiteService.analyzeWebsite(
                req.getWebsite() != null
                        ? req.getWebsite()
                        : (domain != null ? "https://" + domain : null)
        );

        int siteScore = 0;

        boolean tlsOk = site.containsKey("tlsPresent")
                && Boolean.TRUE.equals(site.get("tlsPresent"));

        boolean contactOk = site.containsKey("contactPageFound")
                && Boolean.TRUE.equals(site.get("contactPageFound"));

        if (contactOk) {
            siteScore += WEBSITE_CONTACT_WEIGHT;
        }

        // TLS fallback logic (prevents false positives)
        if (tlsOk) {
            siteScore += TLS_WEIGHT;
        } else {
            if (domainAgeDays > 365) {
                siteScore += 3; // partial trust
            }
        }

        evidences.add(new Evidence("website_analysis", site, siteScore));

        // ---------------- DNS CHECKS ----------------
        Map<String, Object> dns = websiteService.dnsChecks(domain);

        int dnsScore = 0;
        boolean hasMX = dns.containsKey("hasMX") && Boolean.TRUE.equals(dns.get("hasMX"));
        boolean hasSPF = dns.containsKey("hasSPF") && Boolean.TRUE.equals(dns.get("hasSPF"));

        if (hasMX) dnsScore += DNS_WEIGHT / 2;
        if (hasSPF) dnsScore += DNS_WEIGHT / 2;

        evidences.add(new Evidence("dns_checks", dns, dnsScore));

        // ---------------- REGISTRY PLACEHOLDER ----------------
        Map<String, Object> registry = Map.of("info", "registry checks not yet implemented");
        evidences.add(new Evidence("registry", registry, 0));

        // ---------------- TOTAL SCORE ----------------
        int total = 0;
        for (Evidence e : evidences) {
            total += e.getScore();
        }

        if (total > 100) total = 100;

        String verdict = verdictFromScore(total);

        // ---------------- STRONG LEGITIMACY OVERRIDE ----------------
        boolean oldDomain = domainAgeDays > (365 * 5); // >5 years
        boolean corporateEmail = hasMX;

        if (hasOffice && oldDomain && corporateEmail) {
            verdict = "Likely Real";
            if (total < 60) {
                total = 60;
            }
        }

        // ---------------- BLACKLIST SAFETY ----------------
        if (isBlacklisted && total >= 40) {
            verdict = "Uncertain / Needs Review";
        }

        return new CompanyReport(canonical, total, verdict, evidences);
    }

    private String verdictFromScore(int score) {
        if (score >= 70) return "Likely Real";
        if (score >= 40) return "Uncertain / Needs Review";
        return "Likely Fake";
    }
}
