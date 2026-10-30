package com.company.Verifier.offer;

import java.util.List;

/**
 * Result returned by the offer letter analyzer. Serialised to JSON by Spring.
 */
public record OfferAnalysis(
        int riskScore,              // 0 - 100, higher = riskier
        String riskLevel,           // LOW | MEDIUM | HIGH
        String summary,             // human readable summary
        List<RedFlag> redFlags,     // everything suspicious we found
        List<String> positives,     // reassuring signals (weak evidence only)
        Extracted extracted         // facts pulled from the letter
) {

    /** Points per severity now live in offer-rules.json. */
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public record RedFlag(
            String code,            // stable id, e.g. CANDIDATE_PAYMENT
            String title,
            Severity severity,
            String evidence,        // exact sentence from the letter (may be empty)
            String advice
    ) {}

    public record Extracted(
            String companyName,         // best effort, may be null
            String jobTitle,            // best effort, may be null
            List<String> emails,
            List<String> companyDomains, // non free-mail domains found in the letter
            List<String> amounts        // money amounts mentioned
    ) {}
}
