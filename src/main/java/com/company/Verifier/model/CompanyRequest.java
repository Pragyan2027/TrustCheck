
package com.company.Verifier.model;

public class CompanyRequest {
    private String name;
    private String city;
    private String website;
    private String domain;
    private String recruiterEmail;

    public CompanyRequest() {}

    public CompanyRequest(
            String name,
            String city,
            String website,
            String domain
    ) {
        this.name = name;
        this.city = city;
        this.website = website;
        this.domain = domain;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getRecruiterEmail() {
        return recruiterEmail;
    }

    public void setRecruiterEmail(String recruiterEmail) {
        this.recruiterEmail = recruiterEmail;
    }
}
