package com.jobcopilot.resume;

import com.jobcopilot.common.text.MatchingVocabulary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public final class DeterministicCandidateProfileExtractor implements CandidateProfileExtractor {
    private final DeterministicProfileParser parser;

    public DeterministicCandidateProfileExtractor(DeterministicProfileParser parser) {
        this.parser = parser;
    }

    @Override
    public CandidateProfileExtraction extract(String text, LocalDate assessedOn) {
        return new CandidateProfileExtraction(
                parser.parse(text, assessedOn),
                DeterministicProfileParser.VERSION,
                MatchingVocabulary.standard().version());
    }
}
