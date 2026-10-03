package com.jobcopilot.application;

import org.springframework.stereotype.Component;

@Component
public class CandidateFactResolutionPolicy {
    public CandidateFactResolution resolve(CandidateFactType type, boolean valueKnown) {
        if (type == CandidateFactType.LEGAL_DECLARATION || type == CandidateFactType.VOLUNTARY_SELF_IDENTIFICATION)
            return CandidateFactResolution.PROHIBITED_AUTOFILL;
        if (!valueKnown) return CandidateFactResolution.REQUIRES_USER;
        return switch (type) {
            case NAME, EMAIL, PHONE, LOCATION -> CandidateFactResolution.CAN_AUTOFILL;
            case WORK_AUTHORIZATION, SPONSORSHIP, COMPENSATION, NOTICE_PERIOD, RELOCATION ->
                    CandidateFactResolution.REQUIRES_CONFIRMATION;
            default -> throw new IllegalStateException("Unhandled candidate fact type " + type);
        };
    }
}
