
package com.company.Verifier.offer;

import com.company.Verifier.model.CompanyRequest;
import com.company.Verifier.service.VerificationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/offer-letter")
public class OfferLetterController {

    private static final int MAX_CHARS = 100_000;

    private final OfferLetterAnalyzer analyzer;
    private final PdfTextExtractor pdfExtractor;
    private final VerificationService verificationService;

    public OfferLetterController(
            OfferLetterAnalyzer analyzer,
            PdfTextExtractor pdfExtractor,
            VerificationService verificationService) {

        this.analyzer = analyzer;
        this.pdfExtractor = pdfExtractor;
        this.verificationService = verificationService;
    }

    public record TextRequest(String text) {}

    // Keeps the original offer-analysis fields and adds company verification.
    public record CombinedResponse(
            int riskScore,
            String riskLevel,
            String summary,
            List<OfferAnalysis.RedFlag> redFlags,
            List<String> positives,
            OfferAnalysis.Extracted extracted,
            Object companyVerification,
            String verificationNote
    ) {}

    @PostMapping(
            value = "/analyze-text",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> analyzeText(@RequestBody TextRequest request) {
        return respond(request == null ? null : request.text());
    }

    @PostMapping(
            value = "/analyze-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> analyzeFile(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "The uploaded file is empty.");
        }

        String name = file.getOriginalFilename() == null
                ? ""
                : file.getOriginalFilename().toLowerCase();

        try {
            String text;

            if (name.endsWith(".pdf")) {
                text = pdfExtractor.extract(file.getBytes());
            } else if (name.endsWith(".txt")) {
                text = new String(file.getBytes(), StandardCharsets.UTF_8);
            } else {
                return error(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Please upload a PDF or TXT file, or paste the text."
                );
            }

            return respond(text);

        } catch (IOException e) {
            return error(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not read this file. It may be password-protected or corrupted. Try pasting the text instead."
            );
        }
    }

    private ResponseEntity<?> respond(String text) {

        if (text == null || text.isBlank()) {
            return error(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "No readable text found. If this is a scanned image or photo, paste the text instead."
            );
        }

        if (text.length() > MAX_CHARS) {
            return error(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Text is too long for an offer letter."
            );
        }

        // 1. Analyze the offer letter.
        OfferAnalysis offerAnalysis = analyzer.analyze(text);

        // 2. Extract company details.
        OfferAnalysis.Extracted extracted = offerAnalysis.extracted();

        String companyName = extracted.companyName();

        List<String> domains = extracted.companyDomains();
        String companyDomain = (domains == null || domains.isEmpty())
                ? null
                : domains.get(0);

        // 3. Prepare the combined response.
        Object companyReport = null;
        String note;

        if ((companyName == null || companyName.isBlank())
                && (companyDomain == null || companyDomain.isBlank())) {

            note = "Company verification was skipped because no company name or domain could be extracted.";

        } else {

            try {
                CompanyRequest request = new CompanyRequest();

                request.setName(
                        companyName != null && !companyName.isBlank()
                                ? companyName
                                : companyDomain
                );

                request.setDomain(companyDomain);

                if (companyDomain != null) {
                    request.setWebsite("https://" + companyDomain);
                }

                // Only use an email whose domain matches an extracted
                // company domain. Do not assume every email is the recruiter.
                String matchingEmail = findMatchingEmail(
                        extracted.emails(),
                        domains
                );

                request.setRecruiterEmail(matchingEmail);

                // 4. Run the existing company verification service.
                companyReport = verificationService.verify(request);

                note = "Company verification completed using details extracted from the offer letter.";

            } catch (Exception e) {
                // Offer-letter analysis should still succeed if company
                // verification encounters an error.
                note = "Offer analysis completed, but company verification could not be completed.";
            }
        }

        // 5. Return both results.
        CombinedResponse response = new CombinedResponse(
                offerAnalysis.riskScore(),
                offerAnalysis.riskLevel(),
                offerAnalysis.summary(),
                offerAnalysis.redFlags(),
                offerAnalysis.positives(),
                offerAnalysis.extracted(),
                companyReport,
                note
        );

        return ResponseEntity.ok(response);
    }

    private String findMatchingEmail(
            List<String> emails,
            List<String> companyDomains) {

        if (emails == null || companyDomains == null) {
            return null;
        }

        for (String email : emails) {
            if (email == null || !email.contains("@")) {
                continue;
            }

            String emailDomain = email.substring(
                    email.lastIndexOf("@") + 1
            ).toLowerCase();

            for (String domain : companyDomains) {
                if (domain != null
                        && emailDomain.equals(domain.toLowerCase())) {
                    return email;
                }
            }
        }

        return null;
    }

    private ResponseEntity<Map<String, String>> error(
            HttpStatus status,
            String message) {

        return ResponseEntity.status(status)
                .body(Map.of("error", message));
    }
}
