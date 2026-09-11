package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static com.jobcopilot.intelligence.JobIntelligence.ExperienceClause;
import static com.jobcopilot.intelligence.JobIntelligence.Importance;
import static com.jobcopilot.intelligence.JobIntelligence.MinimumExperience;
import static com.jobcopilot.intelligence.JobIntelligence.MinimumStatus;
import static com.jobcopilot.intelligence.JobIntelligence.Scope;
import static com.jobcopilot.intelligence.JobIntelligence.Uncertainty;
import static com.jobcopilot.intelligence.JobIntelligence.UncertaintyCode;

/** Java-only aggregate minimum. Model output never supplies this field. */
final class MinimumExperienceDeriver {
    MinimumExperience derive(List<ExperienceClause> clauses, List<Uncertainty> uncertainties) {
        return derive(clauses, uncertainties, clauses.stream().map(c -> ExperienceForm.parse(c.text())).toList());
    }

    /** Runner path supplies independently validated source forms without rewriting accepted clause text. */
    MinimumExperience derive(List<ExperienceClause> clauses, List<Uncertainty> uncertainties, List<ExperienceForm> sourceForms) {
        if (uncertainties.stream().anyMatch(u -> u.code() == UncertaintyCode.AMBIGUOUS_EXPERIENCE)) {
            return new MinimumExperience(MinimumStatus.AMBIGUOUS, null);
        }
        List<Bound> bounds = new ArrayList<>();
        for (int i = 0; i < clauses.size(); i++) {
            ExperienceClause clause = clauses.get(i);
            if (clause.importance() != Importance.REQUIRED || clause.scope() != Scope.OVERALL) continue;
            if (clause.conditional()) return new MinimumExperience(MinimumStatus.AMBIGUOUS, null);
            ExperienceForm form = sourceForms.get(i);
            if (form == null) return new MinimumExperience(MinimumStatus.AMBIGUOUS, null);
            if (form.exclusive()) return new MinimumExperience(MinimumStatus.AMBIGUOUS, null);
            if (form.lower() == null) return new MinimumExperience(MinimumStatus.AMBIGUOUS, null);
            bounds.add(new Bound(JobIntelligenceValidator.months(form.lower(), form.unit()),
                    form.upper() == null ? null : JobIntelligenceValidator.months(form.upper(), form.unit())));
        }
        if (bounds.isEmpty()) return new MinimumExperience(MinimumStatus.NOT_STATED, null);
        BigDecimal lower = bounds.getFirst().lower();
        BigDecimal upper = bounds.getFirst().upper();
        for (Bound bound : bounds) {
            if (bound.lower().compareTo(lower) > 0) lower = bound.lower();
            if (bound.upper() != null) {
                upper = upper == null || bound.upper().compareTo(upper) < 0 ? bound.upper() : upper;
            }
        }
        if (upper != null && lower.compareTo(upper) > 0) return new MinimumExperience(MinimumStatus.AMBIGUOUS, null);
        if (lower.signum() < 0) return new MinimumExperience(MinimumStatus.AMBIGUOUS, null);
        return new MinimumExperience(MinimumStatus.KNOWN, lower);
    }

    private record Bound(BigDecimal lower, BigDecimal upper) {}
}
