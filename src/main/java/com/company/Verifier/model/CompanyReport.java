package com.company.Verifier.model;

import java.util.List;

public class CompanyReport {
    private String name;
    private int trustScore;
    private String verdict;
    private List<Evidence> evidences;

    public CompanyReport() {}

    public CompanyReport(String name, int trustScore, String verdict, List<Evidence> evidences) {
        this.name = name;
        this.trustScore = trustScore;
        this.verdict = verdict;
        this.evidences = evidences;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public int getTrustScore() {
        return trustScore;
    }
    public void setTrustScore(int trustScore) {
        this.trustScore = trustScore;
    }
    public String getVerdict() {
        return verdict;
    }
    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }
    public List<Evidence> getEvidences() {
        return evidences;
    }
    public void setEvidences(List<Evidence> evidences) {
        this.evidences = evidences;
    }
}
