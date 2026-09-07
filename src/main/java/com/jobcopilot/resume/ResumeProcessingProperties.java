package com.jobcopilot.resume;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties("resume.processing")
public record ResumeProcessingProperties(
        @DefaultValue("5242880") @Min(1) @jakarta.validation.constraints.Max(104857600) int maxBytes,
        @DefaultValue("25") @Min(1) int maxPages,
        @DefaultValue("200000") @Min(1) int maxCharacters,
        @DefaultValue("50") @Min(1) int minMeaningfulCharacters) {}
