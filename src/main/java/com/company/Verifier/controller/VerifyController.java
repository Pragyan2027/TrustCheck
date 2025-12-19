package com.company.Verifier.controller;
import com.company.Verifier.model.CompanyReport;
import com.company.Verifier.model.CompanyRequest;
import com.company.Verifier.service.VerificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class VerifyController {

    @Autowired
    private VerificationService verificationService;

    @PostMapping("/verify")
    public CompanyReport verify(@RequestBody CompanyRequest req) {
        return verificationService.verify(req);
    }

    @GetMapping("/ping")
    public String ping() { return "Company Verifier OK"; }
}
