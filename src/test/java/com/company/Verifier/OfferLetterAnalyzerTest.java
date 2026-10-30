package com.company.Verifier;

import com.company.Verifier.offer.OfferAnalysis;
import com.company.Verifier.offer.OfferLetterAnalyzer;
import com.company.Verifier.offer.OfferRules;
import com.company.Verifier.offer.OfferRulesConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.*;

class OfferLetterAnalyzerTest {

    private final OfferLetterAnalyzer analyzer = new OfferLetterAnalyzer(loadRules());

    /** Uses the REAL src/main/resources/offer-rules.json, so editing the file is tested too. */
    private static OfferRules loadRules() {
        try (InputStream in = OfferLetterAnalyzerTest.class.getResourceAsStream("/offer-rules.json")) {
            assertNotNull(in, "offer-rules.json not found on the classpath");
            return OfferRulesConfig.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean has(OfferAnalysis a, String code) {
        return a.redFlags().stream().anyMatch(f -> f.code().equals(code));
    }

    @Test
    void rulesFileLoads() {
        OfferRules rules = loadRules();
        assertFalse(rules.phraseRules().isEmpty());
        assertTrue(rules.points().size() >= 4);
    }

    @Test
    void genuineLetterWithSalaryAndNoFeeDisclaimerIsLowRisk() {
        OfferAnalysis a = analyzer.analyze("""
            We are pleased to offer you the position of Software Engineer at Acme Technologies Pvt. Ltd.
            Your annual CTC will be Rs. 6,00,000. Your salary will be transferred to your bank account via NEFT.
            The company will bear the cost of your joining kit.
            Acme does not charge any fee at any stage of recruitment.
            Write to hr@acmetech.com. CIN: U72900KA2015PTC123456
            """);
        assertEquals("LOW", a.riskLevel());
        assertFalse(has(a, "CANDIDATE_PAYMENT"));
    }

    @Test
    void registrationFeeIsCritical() {
        OfferAnalysis a = analyzer.analyze(
            "To confirm your seat you need to pay a refundable registration fee of Rs 2,500 via UPI.");
        assertTrue(has(a, "CANDIDATE_PAYMENT"));
        assertEquals("HIGH", a.riskLevel());
    }

    @Test
    void securityDepositWithoutTheWordPayIsStillCaught() {
        OfferAnalysis a = analyzer.analyze(
            "A laptop security deposit of Rs 8,000 is required and will be refunded after 3 months.");
        assertTrue(has(a, "CANDIDATE_PAYMENT"));
    }

    @Test
    void rewordedPaymentRequestIsCaughtViaConfigPhrases() {
        OfferAnalysis a = analyzer.analyze(
            "Kindly arrange Rs 2,500 towards your seat blocking to proceed with joining formalities.");
        assertTrue(has(a, "CANDIDATE_PAYMENT"));
    }

    @Test
    void bondClauseIsOnlyAWarningNotAScamFlag() {
        OfferAnalysis a = analyzer.analyze(
            "If you leave before 18 months you must pay Rs 1,00,000 as liquidated damages.");
        assertTrue(has(a, "BOND_CLAUSE"));
        assertFalse(has(a, "CANDIDATE_PAYMENT"));
    }

    @Test
    void freeEmailAndWhatsAppAreFlagged() {
        OfferAnalysis a = analyzer.analyze(
            "Please contact HR on WhatsApp or at hrdesk@gmail.com to finish onboarding formalities.");
        assertTrue(has(a, "FREE_EMAIL"));
        assertTrue(has(a, "OFF_PLATFORM_CONTACT"));
    }

    @Test
    void emailDomainsAreNotMangledByAbbreviationCleanup() {
        OfferAnalysis a = analyzer.analyze("Write to careers@delta.co.in for any queries about joining.");
        assertTrue(a.extracted().companyDomains().contains("delta.co.in"));
    }

    @Test
    void emptyInputDoesNotCrash() {
        assertDoesNotThrow(() -> analyzer.analyze(""));
        assertDoesNotThrow(() -> analyzer.analyze(null));
    }

    @Test
    void fakeLarsenToubroDepositLetterIsHigh() {
        OfferAnalysis a = analyzer.analyze("""
            We are pleased to inform you that your profile has been selected for an Interview in Larsen & Toubro Group.
            Cost to Company: Salary - Min Rs.24`800/- to Rs.1`32`000/-+ incentives, per month. You are going to get employment in Larsen & Toubro
            Limited.
            Note - You have to deposit a refundable security Rs. 8,900/- by NEFT, RTGS, Online/ATM Transfer on
            30/12/2016 before 03 pm into the (State Bank of India ) . Your call letter will dispatch after receiving confirmation.
            Note- 30/12/2016 is the last date of security deposit in to the (State Bank of India).
            Without information to this email id - {apply@larsenntoubro.co.in}. Your letter will not be dispatched.
            Trainee Engineer openings are available.
            """);
        assertEquals("HIGH", a.riskLevel());
        assertTrue(has(a, "CANDIDATE_PAYMENT"));
        assertTrue(has(a, "UNREALISTIC_SALARY"));
        assertEquals("Larsen & Toubro Limited", a.extracted().companyName());
        assertTrue(a.extracted().companyDomains().contains("larsenntoubro.co.in"));
    }
}
