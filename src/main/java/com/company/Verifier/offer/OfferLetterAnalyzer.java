package com.company.Verifier.offer;

import com.company.Verifier.offer.OfferAnalysis.Extracted;
import com.company.Verifier.offer.OfferAnalysis.RedFlag;
import com.company.Verifier.offer.OfferAnalysis.Severity;
import com.company.Verifier.offer.OfferRules.CandidatePayment;
import com.company.Verifier.offer.OfferRules.Meta;
import com.company.Verifier.offer.OfferRules.PhraseRule;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule based offer-letter scam analyzer.
 *
 * What to scan for (phrases, severities, thresholds, wording) lives in
 * offer-rules.json. This class only holds the LOGIC plus the structural
 * patterns that extract data (money, emails, CIN, ...), which are not
 * something you would tune by editing a word list.
 *
 * Design notes:
 *  - Works sentence by sentence so we can show the exact evidence.
 *  - "Money" alone is NOT a red flag (every real letter mentions salary).
 *    We only flag money when the CANDIDATE is the one paying.
 *  - "No fee is charged" / "company will bear the cost" sentences are skipped,
 *    and bond / notice-period clauses are a softer warning.
 *  - Nothing is logged or stored: the letter contains personal data.
 */
@Service
public class OfferLetterAnalyzer {

    private static final int F = Pattern.CASE_INSENSITIVE;

    // ---- structural patterns (data extraction, not tunable word lists) ----

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private static final Pattern URL =
            Pattern.compile("\\b(?:https?://|www\\.)([a-z0-9.-]+\\.[a-z]{2,})", F);

    // Rs 5000, Rs. 5,000, INR 5000, ₹5,000, $200, 5000/-, 5000 rupees
    private static final Pattern MONEY = Pattern.compile(
            "(?:₹|\\brs\\.?|\\binr\\b|\\busd\\b|\\$)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)"
                    + "|([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:/-|\\brupees\\b)", F);

    private static final Pattern LPA = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?)\\s*(?:lpa|lakhs?\\s+per\\s+(?:annum|year))", F);

    private static final Pattern CIN =
            Pattern.compile("\\b[LU]\\d{5}[A-Z]{2}\\d{4}[A-Z]{3}\\d{6}\\b");
    private static final Pattern GSTIN =
            Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z]\\dZ[A-Z0-9]\\b");

    private static final Pattern COMPANY_NAME = Pattern.compile(
            "\\b((?:(?:[A-Z][A-Za-z0-9&'\\-]*|&)\\s+){1,5}"
                    + "(?i:Pvt\\s+Ltd|Private\\s+Limited|Limited|Ltd|LLP|Inc|Technologies|Solutions))\\b");

    private static final Pattern JOB_TITLE = Pattern.compile(
            "(?:position|role|designation|post)\\s+(?:of|as|:)\\s*"
                    + "([A-Za-z][A-Za-z &/\\-]{2,50}?)(?=[,.;]|\\s+(?:at|in|with|from|on|for)\\b|$)", F);

    // ---- compiled from offer-rules.json ----

    private record CompiledPhraseRule(PhraseRule rule, Pattern pattern) {}

    private final OfferRules rules;
    private final Pattern strongFee;
    private final Pattern paymentPhrase;
    private final Pattern candidatePays;
    private final Pattern paymentChannel;
    private final Pattern noFee;
    private final Pattern companyPays;
    private final Pattern bondClause;
    private final Pattern lowSkill;
    private final Pattern monthly;
    private final Set<String> freeEmail;
    private final Set<String> sketchyTlds;
    private final List<CompiledPhraseRule> phraseRules = new ArrayList<>();

    public OfferLetterAnalyzer(OfferRules rules) {
        this.rules = need(rules, "rules");
        try {
            CandidatePayment cp = need(rules.candidatePayment(), "candidatePayment");
            OfferRules.NoFee nf = need(cp.noFee(), "candidatePayment.noFee");

            strongFee = Pattern.compile(any(cp.feeKinds()) + "\\s+" + any(cp.feeWords()), F);
            paymentPhrase = Pattern.compile(any(cp.paymentPhrases()), F);
            candidatePays = Pattern.compile(any(cp.subjects()) + ".{0,80}?" + any(cp.verbs()), F);
            paymentChannel = Pattern.compile(any(cp.channels()), F);
            // "no fee", "no registration fee", "without any charges" ... or an exact phrase
            noFee = Pattern.compile(any(nf.negations()) + "\\s+(?:any\\s+)?(?:[a-z]+\\s+){0,2}"
                    + any(nf.nouns()) + "|" + any(nf.phrases()), F);
            companyPays = Pattern.compile(any(cp.companyPaysPhrases()), F);
            bondClause = Pattern.compile(any(need(rules.bondClause(), "bondClause").phrases()), F);

            OfferRules.Salary sal = need(rules.salary(), "salary");
            lowSkill = Pattern.compile(any(sal.lowSkillRoles()), F);
            monthly = Pattern.compile(any(sal.monthlyHints()), F);

            OfferRules.Email em = need(rules.email(), "email");
            freeEmail = lower(em.freeDomains());
            sketchyTlds = lower(em.sketchyTlds());

            for (PhraseRule pr : need(rules.phraseRules(), "phraseRules")) {
                phraseRules.add(new CompiledPhraseRule(pr, Pattern.compile(any(pr.phrases()), F)));
            }
            need(rules.points(), "points");
            need(rules.levels(), "levels");
            need(rules.noCompanyId(), "noCompanyId");
        } catch (RuntimeException e) {
            throw new IllegalStateException("offer-rules.json is invalid or incomplete: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ public API

    public OfferAnalysis analyze(String rawText) {
        String text = normalize(rawText == null ? "" : rawText);
        List<String> sentences = splitSentences(text);

        List<RedFlag> flags = new ArrayList<>();
        List<String> positives = new ArrayList<>();

        checkCandidatePayment(sentences, flags, positives);
        for (CompiledPhraseRule r : phraseRules) {
            firstMatch(sentences, r.pattern()).ifPresent(s -> flags.add(new RedFlag(
                    r.rule().code(), r.rule().title(), r.rule().severity(), shorten(s), r.rule().advice())));
        }
        checkUnrealisticSalary(sentences, text, flags);

        Extracted extracted = extract(text);
        checkEmails(extracted, flags, positives);
        checkCompanyIdentifiers(text, flags, positives);

        int score = score(flags);
        String level = level(score, flags);
        return new OfferAnalysis(score, level, buildSummary(extracted, flags, score, level),
                flags, positives, extracted);
    }

    // ------------------------------------------------------------------ rules (logic)

    private void checkCandidatePayment(List<String> sentences, List<RedFlag> flags,
                                       List<String> positives) {
        CandidatePayment cp = rules.candidatePayment();
        PhraseRule bond = rules.bondClause();
        int hits = 0;
        boolean bondAdded = false;
        String noFeeStatement = null;

        for (String s : sentences) {
            if (noFee.matcher(s).find()) {
                if (noFeeStatement == null) noFeeStatement = s;
                continue;
            }
            boolean pays = candidatePays.matcher(s).find();
            if (companyPays.matcher(s).find() && !pays) continue;

            boolean hasMoney = MONEY.matcher(s).find();
            boolean strongFeeHit = strongFee.matcher(s).find();
            boolean paysMoney = pays && hasMoney;
            boolean paysViaChannel = pays && paymentChannel.matcher(s).find();
            boolean paymentRequest = hasMoney && paymentPhrase.matcher(s).find();
            if (!(strongFeeHit || paysMoney || paysViaChannel || paymentRequest)) continue;

            // Money owed LATER (bond, notice-period buy-out) is not an upfront scam fee.
            if (!strongFeeHit && !paymentRequest && bondClause.matcher(s).find()) {
                if (!bondAdded) {
                    flags.add(new RedFlag(bond.code(), bond.title(), bond.severity(), shorten(s), bond.advice()));
                    bondAdded = true;
                }
                continue;
            }

            if (hits < 3) {
                flags.add(new RedFlag("CANDIDATE_PAYMENT", cp.title(), cp.severity(), shorten(s), cp.advice()));
                hits++;
            }
        }

        if (hits == 0 && noFeeStatement != null) {
            positives.add("The letter states that no fee is charged: \"" + shorten(noFeeStatement) + "\"");
        }
    }

    private void checkUnrealisticSalary(List<String> sentences, String text, List<RedFlag> flags) {
        OfferRules.Salary sal = rules.salary();
        if (!lowSkill.matcher(text).find()) return;
        for (String s : sentences) {
            Matcher lpa = LPA.matcher(s);
            boolean tooHigh = lpa.find() && parse(lpa.group(1)) >= sal.lpaThreshold();
            if (!tooHigh && monthly.matcher(s).find()) {
                Matcher m = MONEY.matcher(s);
                while (m.find()) {
                    if (parse(m.group(1) != null ? m.group(1) : m.group(2)) >= sal.monthlyThreshold()) {
                        tooHigh = true;
                        break;
                    }
                }
            }
            if (tooHigh) {
                flags.add(new RedFlag("UNREALISTIC_SALARY", sal.title(), sal.severity(), shorten(s), sal.advice()));
                return;
            }
        }
    }

    private void checkEmails(Extracted ex, List<RedFlag> flags, List<String> positives) {
        Meta free = rules.email().freeEmail();
        Meta sketchy = rules.email().sketchyDomain();
        for (String email : ex.emails()) {
            if (freeEmail.contains(email.substring(email.indexOf('@') + 1))) {
                flags.add(new RedFlag("FREE_EMAIL", free.title(), free.severity(), email, free.advice()));
                break;
            }
        }
        for (String domain : ex.companyDomains()) {
            String tld = domain.substring(domain.lastIndexOf('.') + 1);
            if (sketchyTlds.contains(tld)) {
                flags.add(new RedFlag("SKETCHY_DOMAIN", sketchy.title() + ": ." + tld, sketchy.severity(),
                        domain, sketchy.advice()));
                break;
            }
        }
        if (!ex.companyDomains().isEmpty()) {
            positives.add("Letter lists a company domain you can verify: " + ex.companyDomains().get(0));
        }
    }

    private void checkCompanyIdentifiers(String text, List<RedFlag> flags, List<String> positives) {
        if (CIN.matcher(text).find() || GSTIN.matcher(text).find()) {
            positives.add("Letter contains a CIN / GSTIN. Verify it on the MCA / GST portal, since scammers can copy these too.");
        } else {
            Meta m = rules.noCompanyId();
            flags.add(new RedFlag("NO_COMPANY_ID", m.title(), m.severity(), "", m.advice()));
        }
    }

    // ------------------------------------------------------------------ extraction

    private Extracted extract(String text) {
        Set<String> emails = new LinkedHashSet<>();
        Set<String> domains = new LinkedHashSet<>();
        Matcher em = EMAIL.matcher(text);
        while (em.find()) {
            emails.add(em.group().toLowerCase());
            String d = em.group(1).toLowerCase();
            if (!freeEmail.contains(d)) domains.add(d);
        }
        Matcher um = URL.matcher(text);
        while (um.find()) {
            String d = um.group(1).toLowerCase();
            if (!freeEmail.contains(d)) domains.add(d);
        }

        Set<String> amounts = new LinkedHashSet<>();
        Matcher mm = MONEY.matcher(text);
        while (mm.find() && amounts.size() < 10) amounts.add(mm.group().trim());

        Matcher cm = COMPANY_NAME.matcher(text);
        String company = cm.find() ? cm.group(1).trim() : null;
        Matcher jm = JOB_TITLE.matcher(text);
        String job = jm.find() ? jm.group(1).trim() : null;

        return new Extracted(company, job, new ArrayList<>(emails), new ArrayList<>(domains),
                new ArrayList<>(amounts));
    }

    // ------------------------------------------------------------------ scoring + summary

    private int score(List<RedFlag> flags) {
        Map<String, Integer> byCode = new HashMap<>();
        for (RedFlag f : flags) {
            byCode.merge(f.code(), rules.points().getOrDefault(f.severity(), 0), Math::max);
        }
        return Math.min(100, byCode.values().stream().mapToInt(Integer::intValue).sum());
    }

    private String level(int score, List<RedFlag> flags) {
        boolean critical = flags.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
        if (critical || score >= rules.levels().highFrom()) return "HIGH";
        if (score >= rules.levels().mediumFrom()) return "MEDIUM";
        return "LOW";
    }

    private String buildSummary(Extracted ex, List<RedFlag> flags, int score, String level) {
        StringBuilder sb = new StringBuilder();
        sb.append("Offer");
        if (ex.companyName() != null) sb.append(" from ").append(ex.companyName());
        if (ex.jobTitle() != null) sb.append(" for the role of ").append(ex.jobTitle());
        sb.append(". Risk level: ").append(level).append(" (").append(score).append("/100). ");

        boolean paymentAsked = flags.stream().anyMatch(f -> f.code().equals("CANDIDATE_PAYMENT"));
        if (paymentAsked) {
            sb.append("This letter asks you to pay money, which genuine employers do not do. ");
        } else {
            sb.append("No upfront payment demand was detected. ");
        }
        long others = flags.stream().filter(f -> !f.code().equals("CANDIDATE_PAYMENT")).count();
        if (others > 0) {
            sb.append(others).append(others == 1 ? " other warning sign was" : " other warning signs were")
              .append(" found. ");
        }
        sb.append("This is an automated check, not proof either way. Verify the company independently.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** Turns a phrase list into one regex: (?:\bfoo\s+bar\b|\bbaz\b). Whole-word, whitespace tolerant. */
    private static String any(List<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            throw new IllegalArgumentException("a phrase list is missing or empty");
        }
        StringJoiner alts = new StringJoiner("|", "(?:", ")");
        for (String phrase : phrases) {
            String p = phrase.trim();
            if (p.isEmpty()) continue;
            StringJoiner tokens = new StringJoiner("\\s+");
            for (String t : p.split("\\s+")) tokens.add(Pattern.quote(t));
            String lead = isWord(p.charAt(0)) ? "\\b" : "";
            String trail = isWord(p.charAt(p.length() - 1)) ? "\\b" : "";
            alts.add(lead + tokens + trail);
        }
        return alts.toString();
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static Set<String> lower(List<String> items) {
        Set<String> out = new HashSet<>();
        for (String s : items) out.add(s.toLowerCase().trim());
        return out;
    }

    private static <T> T need(T value, String name) {
        if (value == null) throw new IllegalArgumentException("section '" + name + "' is missing");
        return value;
    }

    private static String normalize(String raw) {
        String t = raw.replace('\u00a0', ' ').replace("\r", "");
        t = t.replaceAll("(?i)(?<![\\w.@/-])(pvt|ltd|inc|co|mr|ms|mrs|dr)\\.(?=\\s)", "$1");
        t = t.replaceAll("(?i)\\brs\\.\\s*", "Rs ");
        t = t.replaceAll("(?<=\\d)[`\u00b4](?=\\d)", ",");      // 24`800 -> 24,800
        t = t.replaceAll("\\n\\s*\\n+", "\u0001");   // keep paragraph breaks
        t = t.replaceAll("\\s*\\n\\s*", " ");         // PDF line wraps -> spaces
        t = t.replace('\u0001', '\n');
        return t.replaceAll("[ \\t]+", " ").trim();
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            for (String s : para.split("(?<=[.!?;])\\s+(?=[A-Z0-9])|\\s*[•●▪◦]\\s*")) {
                s = s.trim();
                if (s.length() >= 8) out.add(s);
            }
        }
        return out;
    }

    private static Optional<String> firstMatch(List<String> sentences, Pattern p) {
        return sentences.stream().filter(s -> p.matcher(s).find()).findFirst();
    }

    private static double parse(String num) {
        try {
            return Double.parseDouble(num.replace(",", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String shorten(String s) {
        return s.length() <= 220 ? s : s.substring(0, 217) + "...";
    }
}
