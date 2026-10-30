package com.company.Verifier.offer;

import com.company.Verifier.offer.OfferAnalysis.Severity;

import java.util.List;
import java.util.Map;

/**
 * Mirrors src/main/resources/offer-rules.json field for field.
 * If you rename a field here, rename it in the JSON too (and vice versa).
 */
public record OfferRules(
        String readme,
        Map<Severity, Integer> points,
        Levels levels,
        CandidatePayment candidatePayment,
        PhraseRule bondClause,
        List<PhraseRule> phraseRules,
        Salary salary,
        Email email,
        Meta noCompanyId
) {
    public record Levels(int mediumFrom, int highFrom) {}

    /** Severity + wording shown to the user for one kind of finding. */
    public record Meta(Severity severity, String title, String advice) {}

    /** A rule that fires when any of its phrases appears in a sentence. */
    public record PhraseRule(String code, Severity severity, String title, String advice,
                             List<String> phrases) {}

    public record NoFee(List<String> negations, List<String> nouns, List<String> phrases) {}

    public record CandidatePayment(Severity severity, String title, String advice,
                                   List<String> feeKinds, List<String> feeWords,
                                   List<String> paymentPhrases,
                                   List<String> subjects, List<String> verbs,
                                   List<String> channels,
                                   NoFee noFee, List<String> companyPaysPhrases) {}

    public record Salary(Severity severity, String title, String advice,
                         List<String> lowSkillRoles, List<String> monthlyHints,
                         double monthlyThreshold, double lpaThreshold) {}

    public record Email(List<String> freeDomains, List<String> sketchyTlds,
                        Meta freeEmail, Meta sketchyDomain) {}
}
