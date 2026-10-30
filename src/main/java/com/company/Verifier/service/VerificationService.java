
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

    // Small, explainable score contributions
    private static final int NOMINATIM_WEIGHT = 5;
    private static final int WHOIS_OLD_DOMAIN_POINTS = 10;
    private static final int WHOIS_MATURE_DOMAIN_POINTS = 5;
    private static final int WEBSITE_CONTACT_POINTS = 5;
    private static final int TLS_POINTS = 3;
    private static final int MX_POINTS = 3;
    private static final int SPF_POINTS = 2;

    private static final int BLACKLIST_PENALTY = -40;
    private static final int FREE_EMAIL_PENALTY = -5;
    private static final int DOMAIN_MISMATCH_PENALTY = -10;
    private static final int VERY_NEW_DOMAIN_PENALTY = -10;

    private static final Set<String> FREE_EMAIL_PROVIDERS = Set.of(
            "gmail.com",
            "yahoo.com",
            "yahoo.in",
            "outlook.com",
            "hotmail.com",
            "live.com",
            "rediffmail.com",
            "proton.me",
            "protonmail.com"
    );

    public CompanyReport verify(CompanyRequest req) {

        String canonical = req.getName() == null
                ? ""
                : req.getName().trim();

        List<Evidence> evidences = new ArrayList<>();

        // Resolve the company domain.
        String domain = normalizeDomain(req.getDomain());

        if (domain == null) {
            domain = normalizeDomain(req.getWebsite());
        }

        // ---------------- BLACKLIST CHECK ----------------

        boolean isBlacklisted =
                !canonical.isBlank()
                        && blacklistService.isBlacklisted(canonical);

        if (isBlacklisted) {
            evidences.add(new Evidence(
                    "blacklist",
                    Map.of(
                            "status", "WARNING",
                            "reason", "Company matched the existing seed blacklist."
                    ),
                    BLACKLIST_PENALTY
            ));
        } else {
            evidences.add(new Evidence(
                    "blacklist",
                    Map.of(
                            "status", "NO_MATCH",
                            "reason", "No match found in the current blacklist. This does not prove legitimacy."
                    ),
                    0
            ));
        }

        // ---------------- NOMINATIM CHECK ----------------

        int nomScore = 0;

        Map<String, Object> nomDetails = new LinkedHashMap<>();

        try {
            String query = canonical;

            if (req.getCity() != null && !req.getCity().isBlank()) {
                query += " " + req.getCity().trim();
            }

            Map<String, Object> result = nominatimService.search(query);

            if (result != null) {
                nomDetails.putAll(result);
            }

            Object countValue = nomDetails.get("count");

            if (countValue instanceof Number
                    && ((Number) countValue).intValue() > 0) {

                nomScore = NOMINATIM_WEIGHT;

                nomDetails.put("status", "WEAK_POSITIVE");
                nomDetails.put(
                        "reason",
                        "A location listing was found. Map listings are only weak supporting evidence."
                );

            } else {
                nomDetails.put("status", "NEUTRAL");
                nomDetails.put(
                        "reason",
                        "No matching map listing was found. No points deducted."
                );
            }

        } catch (Exception ex) {
            nomDetails.put("status", "UNAVAILABLE");
            nomDetails.put(
                    "reason",
                    "The location lookup failed. No points deducted."
            );
        }

        evidences.add(new Evidence(
                "nominatim",
                nomDetails,
                nomScore
        ));

        // ---------------- WHOIS CHECK ----------------

        int whoisScore = 0;
        long domainAgeDays = -1;

        Map<String, Object> whoisDetails = new LinkedHashMap<>();

        if (domain != null) {
            try {
                Map<String, Object> result = whoisService.lookup(domain);

                if (result != null) {
                    whoisDetails.putAll(result);
                }

                Object ageValue = whoisDetails.get("ageDays");

                if (ageValue instanceof Number) {
                    domainAgeDays = ((Number) ageValue).longValue();

                    if (domainAgeDays >= 365) {
                        whoisScore = WHOIS_OLD_DOMAIN_POINTS;
                        whoisDetails.put("status", "POSITIVE");
                        whoisDetails.put(
                                "reason",
                                "The domain is at least one year old. Domain age alone does not prove legitimacy."
                        );

                    } else if (domainAgeDays >= 90) {
                        whoisScore = WHOIS_MATURE_DOMAIN_POINTS;
                        whoisDetails.put("status", "WEAK_POSITIVE");
                        whoisDetails.put(
                                "reason",
                                "The domain is at least 90 days old."
                        );

                    } else if (domainAgeDays >= 0) {
                        whoisScore = VERY_NEW_DOMAIN_PENALTY;
                        whoisDetails.put("status", "WARNING");
                        whoisDetails.put(
                                "reason",
                                "The domain is relatively new. New domains can also belong to legitimate businesses."
                        );
                    }

                } else {
                    whoisDetails.put("status", "UNAVAILABLE");
                    whoisDetails.put(
                            "reason",
                            "Domain age could not be determined. No points deducted."
                    );
                }

            } catch (Exception ex) {
                whoisDetails.put("status", "UNAVAILABLE");
                whoisDetails.put(
                        "reason",
                        "WHOIS lookup failed. No points deducted."
                );
            }
        } else {
            whoisDetails.put("status", "UNAVAILABLE");
            whoisDetails.put(
                    "reason",
                    "No company domain was supplied."
            );
        }

        evidences.add(new Evidence(
                "whois",
                whoisDetails,
                whoisScore
        ));

        // ---------------- WEBSITE ANALYSIS ----------------

        int siteScore = 0;
        Map<String, Object> siteDetails = new LinkedHashMap<>();

        String websiteUrl = req.getWebsite();

        if ((websiteUrl == null || websiteUrl.isBlank()) && domain != null) {
            websiteUrl = "https://" + domain;
        }

        if (websiteUrl != null && !websiteUrl.isBlank()) {
            try {
                Map<String, Object> result =
                        websiteService.analyzeWebsite(websiteUrl);

                if (result != null) {
                    siteDetails.putAll(result);
                }

                boolean tlsOk = Boolean.TRUE.equals(
                        siteDetails.get("tlsPresent")
                );

                boolean contactOk = Boolean.TRUE.equals(
                        siteDetails.get("contactPageFound")
                );

                if (contactOk) {
                    siteScore += WEBSITE_CONTACT_POINTS;
                }

                if (tlsOk) {
                    siteScore += TLS_POINTS;
                }

                siteDetails.put(
                        "status",
                        (tlsOk || contactOk) ? "POSITIVE" : "NEUTRAL"
                );

                siteDetails.put(
                        "reason",
                        "Website signals provide supporting evidence only. Missing features do not automatically indicate fraud."
                );

            } catch (Exception ex) {
                siteDetails.put("status", "UNAVAILABLE");
                siteDetails.put(
                        "reason",
                        "Website analysis failed. No points deducted."
                );
            }

        } else {
            siteDetails.put("status", "UNAVAILABLE");
            siteDetails.put(
                    "reason",
                    "No website was supplied."
            );
        }

        evidences.add(new Evidence(
                "website_analysis",
                siteDetails,
                siteScore
        ));

        // ---------------- DNS CHECKS ----------------

        int dnsScore = 0;
        Map<String, Object> dnsDetails = new LinkedHashMap<>();

        if (domain != null) {
            try {
                Map<String, Object> result =
                        websiteService.dnsChecks(domain);

                if (result != null) {
                    dnsDetails.putAll(result);
                }

                boolean hasMX = Boolean.TRUE.equals(
                        dnsDetails.get("hasMX")
                );

                boolean hasSPF = Boolean.TRUE.equals(
                        dnsDetails.get("hasSPF")
                );

                if (hasMX) {
                    dnsScore += MX_POINTS;
                }

                if (hasSPF) {
                    dnsScore += SPF_POINTS;
                }

                dnsDetails.put(
                        "status",
                        (hasMX || hasSPF) ? "POSITIVE" : "NEUTRAL"
                );

                dnsDetails.put(
                        "reason",
                        "Email-related DNS records are supporting signals, not proof that a recruiter is genuine."
                );

            } catch (Exception ex) {
                dnsDetails.put("status", "UNAVAILABLE");
                dnsDetails.put(
                        "reason",
                        "DNS lookup failed. No points deducted."
                );
            }

        } else {
            dnsDetails.put("status", "UNAVAILABLE");
            dnsDetails.put(
                    "reason",
                    "No company domain was supplied."
            );
        }

        evidences.add(new Evidence(
                "dns_checks",
                dnsDetails,
                dnsScore
        ));

        // ---------------- RECRUITER EMAIL CHECK ----------------

        String email = req.getRecruiterEmail();

        if (email != null && !email.isBlank()) {

            email = email.trim().toLowerCase(Locale.ROOT);

            int emailScore = 0;

            Map<String, Object> emailDetails = new LinkedHashMap<>();
            emailDetails.put("email", email);

            boolean validBasicFormat =
                    email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

            if (!validBasicFormat) {
                emailDetails.put("status", "WARNING");
                emailDetails.put(
                        "reason",
                        "The recruiter email does not have a valid basic format."
                );
                emailScore = -5;

            } else {
                String emailDomain = normalizeDomain(
                        email.substring(email.lastIndexOf('@') + 1)
                );

                boolean freeProvider =
                        FREE_EMAIL_PROVIDERS.contains(emailDomain);

                boolean domainMismatch =
                        domain != null
                                && emailDomain != null
                                && !emailDomain.equals(domain)
                                && !emailDomain.endsWith("." + domain);

                if (freeProvider) {
                    emailScore += FREE_EMAIL_PENALTY;
                    emailDetails.put("freeEmailProvider", true);
                }

                if (domainMismatch) {
                    emailScore += DOMAIN_MISMATCH_PENALTY;
                    emailDetails.put("domainMismatch", true);
                }

                if (!freeProvider && !domainMismatch) {
                    emailDetails.put("status", "NEUTRAL");
                    emailDetails.put(
                            "reason",
                            "No obvious email-domain warning was found. This does not authenticate the recruiter."
                    );
                } else {
                    emailDetails.put("status", "WARNING");

                    List<String> reasons = new ArrayList<>();

                    if (freeProvider) {
                        reasons.add(
                                "The recruiter uses a free email provider."
                        );
                    }

                    if (domainMismatch) {
                        reasons.add(
                                "The recruiter email domain differs from the company website domain."
                        );
                    }

                    emailDetails.put("reason", String.join(" ", reasons));
                }
            }


            evidences.add(new Evidence(
                    "recruiter_email",
                    emailDetails,
                    emailScore
            ));

// Add a separate, prominent identity warning when
// the recruiter email domain differs from the company domain.
            if (Boolean.TRUE.equals(emailDetails.get("domainMismatch"))) {
                Map<String, Object> identityWarning = new LinkedHashMap<>();

                identityWarning.put("status", "WARNING");
                identityWarning.put(
                        "reason",
                        "The recruiter email domain differs from the company website domain. Verify the recruiter through an independently obtained official company contact."
                );
                identityWarning.put("companyDomain", domain);
                identityWarning.put(
                        "recruiterEmail",
                        emailDetails.get("email")
                );

                evidences.add(new Evidence(
                        "identity_warning",
                        identityWarning,
                        0
                ));
            }

        }

        // ---------------- REGISTRY PLACEHOLDER ----------------

        evidences.add(new Evidence(
                "registry",
                Map.of(
                        "status", "NOT_IMPLEMENTED",
                        "reason", "Company registry verification is not yet implemented."
                ),
                0
        ));


        boolean reservedTestDomain = isReservedTestDomain(domain);

        if (reservedTestDomain) {
            evidences.add(new Evidence(
                    "reserved_domain",
                    Map.of(
                            "status", "TEST_DOMAIN",
                            "reason",
                            "This is a reserved example domain. Its technical signals cannot establish the legitimacy of a real company."
                    ),
                    0
            ));
        }

        // ---------------- TOTAL SCORE ----------------

        // Start from a neutral baseline; evidence can increase or decrease it.
        int total = 50;

        for (Evidence evidence : evidences) {
            total += evidence.getScore();
        }

        total = Math.max(0, Math.min(100, total));

        // ---------------- VERDICT ----------------


        String verdict = verdictFromScore(total);

        if (reservedTestDomain) {
            verdict = "Test domain / Unable to assess";
        }


        // A blacklist match remains a strong warning.
        // Never override it to a low-risk verdict just because other checks passed.
        if (isBlacklisted && total >= 40) {
            verdict = "Needs review";
        } else if (isBlacklisted) {
            verdict = "High risk indicators";
        }

        return new CompanyReport(
                canonical,
                total,
                verdict,
                evidences
        );
    }

    private String verdictFromScore(int score) {
        if (score >= 65) {
            return "Low risk indicators";
        }

        if (score >= 40) {
            return "Needs review";
        }

        return "High risk indicators";
    }

    private String normalizeDomain(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String domain = value.trim().toLowerCase(Locale.ROOT);

        domain = domain.replaceFirst("^https?://", "");
        domain = domain.split("[/?#]", 2)[0];
        domain = domain.replaceFirst("^www\\.", "");
        domain = domain.replaceAll(":\\d+$", "");

        if (domain.isBlank() || !domain.contains(".")) {
            return null;
        }

        return domain;
    }

    private boolean isReservedTestDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }

        String normalized = domain.toLowerCase(Locale.ROOT);

        return normalized.equals("example.com")
                || normalized.equals("example.org")
                || normalized.equals("example.net")
                || normalized.equals("example.edu")
                || normalized.equals("example")
                || normalized.endsWith(".example");
    }

}
