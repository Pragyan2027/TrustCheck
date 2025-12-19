package com.company.Verifier.model;

import java.util.Map;

public class Evidence {
    private String source;
    private Map<String, Object> details;
    private int score;

    public Evidence() {}

    public Evidence(String source, Map<String, Object> details, int score) {
        this.source = source;
        this.details = details;
        this.score = score;
    }

    public String getSource() {
        return source;
    }
    public void setSource(String source) {
        this.source = source;
    }
    public Map<String, Object> getDetails() {
        return details;
    }
    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
    public int getScore() {
        return score;
    }
    public void setScore(int score) {
        this.score = score;
    }
}

