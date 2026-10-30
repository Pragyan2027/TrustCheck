package com.company.Verifier.offer;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads offer-rules.json once at startup.
 *
 * Default location is src/main/resources/offer-rules.json (classpath).
 * To use a file outside the jar instead, set in application.properties:
 *     offer.rules.location=file:/path/to/offer-rules.json
 *
 * A typo in the JSON (unknown field, wrong severity name) stops the app at
 * startup with a clear error, rather than silently disabling a rule.
 */
@Configuration
public class OfferRulesConfig {

    @Bean
    public OfferRules offerRules(
            @Value("${offer.rules.location:classpath:offer-rules.json}") Resource location)
            throws IOException {
        try (InputStream in = location.getInputStream()) {
            return load(in);
        }
    }

    /** Also used by unit tests. */
    public static OfferRules load(InputStream in) throws IOException {
        return new ObjectMapper().readValue(in, OfferRules.class);
    }
}
