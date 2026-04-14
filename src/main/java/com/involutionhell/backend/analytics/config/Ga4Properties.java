package com.involutionhell.backend.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ga4")
public class Ga4Properties {

    private String propertyId;
    private String credentialsPath;

    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }

    public String getCredentialsPath() { return credentialsPath; }
    public void setCredentialsPath(String credentialsPath) { this.credentialsPath = credentialsPath; }
}
