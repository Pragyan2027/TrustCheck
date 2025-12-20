**Fake Company Verifier**

**Overview**

Fake job postings and scam companies have become increasingly common on job portals and social media platforms. Many of these entities operate without a real office location, registered domain history, or proper corporate infrastructure, making it difficult for students and job seekers to verify their authenticity.
This project aims to automatically verify the legitimacy of a company using publicly available and free data sources. It combines blacklist data, physical office verification, domain intelligence, website analysis, and DNS infrastructure checks to produce a trust score and verdict for a given company.


**Problem Statement**

Manual verification of companies is time-consuming and error-prone. Users often have to:

-**Search for office addresses**
-**Verify websites**
-**Check domain age**
-**Look for blacklist warnings**
-**There is no single platform that consolidates all these checks into a unified verification system, especially using free and open services.**

## Solution Approach

The Fake Company Verifier performs multi-source verification by collecting independent evidence from various free data sources and aggregating them into a trust score. Each verification step contributes positively, negatively, or neutrally to the final result.

## System Architecture

The application follows a layered Spring Boot architecture:

## Frontend: Static HTML + JavaScript
## Backend: Spring Boot REST API
## Data Sources: CSV blacklist + external public services
## Deployment: Executable JAR

The frontend is served directly by the backend and communicates via REST APIs.

## Verification Components Used & Explained

**1. Blacklist Check (Seed Data)**

A CSV file containing known fake or blacklisted companies is loaded at application startup.
**Source:** Publicly available verified lists
**Purpose:** Acts as an early warning signal
**Behavior:** A blacklist match does not immediately mark a company as fake but lowers trust

This ensures fairness and avoids hard-coded assumptions.

## 2. Nominatim (OpenStreetMap) Office Verification

Nominatim is a free geocoding service provided by OpenStreetMap that converts place names into geographic coordinates and structured location data.

## How it is used here:
The company name (and optional city) is queried against Nominatim. If a physical office location is found, it is considered strong legitimacy evidence.

## Why it matters:
Fake companies often do not have verifiable physical offices.

## API used:

- OpenStreetMap Nominatim Search API
- Free and open-source

## 3. WHOIS Domain Analysis

WHOIS is a protocol that provides registration details of internet domains, such as:
Creation date , Registrar , Expiry date

## How it is used:
The company’s domain is extracted from the provided website
The domain’s age is calculated in days

## Why it matters:

Scam companies often use recently registered domains
Older domains indicate long-term legitimacy
No paid WHOIS APIs are used; raw WHOIS lookup is performed.

## 4. Website Analysis

The website analysis module checks:
- ## HTTPS availability
- ## TLS certificate presence
- ## Availability of a contact page

## Important design note:
TLS failures can occur due to Java truststore or certificate chain limitations. Therefore, TLS failures do not immediately penalize the company and are handled using fallback logic.
This prevents false positives for legitimate companies.

## 5. DNS Infrastructure Checks

DNS checks analyze whether a domain has proper email and sender validation records.

## Checks performed:

- ## MX records (mail servers)
- ## SPF records (sender policy framework)

## Why it matters?
Real companies typically have configured corporate email infrastructure, while scam entities often do not.
