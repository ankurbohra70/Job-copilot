package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import com.jobcopilot.resume.dto.ResumeRouteRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import java.util.List;
import java.util.regex.Pattern;
import static com.jobcopilot.resume.ResumeExceptions.*;

@Service
public class ResumeRouteService {
    private final CandidateProfileRepository profiles;
    private final ResumeRepository resumes;
    private final ResumeRouteRepository routes;
    ResumeRouteService(CandidateProfileRepository profiles, ResumeRepository resumes, ResumeRouteRepository routes) {
        this.profiles = profiles; this.resumes = resumes; this.routes = routes;
    }
    @Transactional
    public ResumeRouteSnapshot create(Long profileId, ResumeRouteRequest request) {
        CandidateProfile profile = profiles.findById(profileId).orElseThrow(() -> new CandidateProfileNotFoundException(profileId));
        Resume resume = resumes.findById(request.resumeId()).orElseThrow(() -> new ResumeNotFoundException(request.resumeId()));
        if (!resume.usableRouteSource()) throw new IllegalArgumentException("resume is not a usable validated PDF record");
        ResumeRoute route = new ResumeRoute(profile, resume, request.strategy(), request.roleFamily(),
                request.defaultRoute(), request.variantLabel(), request.approved());
        try {
            return routes.saveAndFlush(route).snapshot();
        } catch (DataIntegrityViolationException failure) {
            String constraint = constraintName(failure);
            if ("ux_resume_routes_default".equals(constraint) || "ux_resume_routes_role_family".equals(constraint))
                throw new ResumeRouteConflictException();
            throw failure;
        }
    }
    @Transactional(readOnly = true)
    public List<ResumeRouteSnapshot> list(Long profileId) {
        if (!profiles.existsById(profileId)) throw new CandidateProfileNotFoundException(profileId);
        return routes.findByCandidateProfileIdOrderById(profileId).stream().map(ResumeRoute::snapshot).toList();
    }
    @Transactional(readOnly = true)
    public ResumeRouteSnapshot resolve(Long profileId, ResumeStrategy strategy, String jobTitle) {
        OptionalRoute exact = exactRoute(profileId, strategy, jobTitle);
        if (exact.route() != null) return exact.route().snapshot();
        return routes.findFirstByCandidateProfileIdAndStrategyAndApprovedTrueAndIsDefaultTrue(profileId, strategy)
                .filter(ResumeRoute::usable).map(ResumeRoute::snapshot).orElse(null);
    }
    private OptionalRoute exactRoute(Long profileId, ResumeStrategy strategy, String jobTitle) {
        if (jobTitle == null) return new OptionalRoute(null);
        return routes.findByCandidateProfileIdOrderById(profileId).stream()
                .filter(route -> route.approved() && route.usable() && route.strategy() == strategy && !route.isDefault())
                .filter(route -> containsRoleFamily(jobTitle, route.roleFamily()))
                .findFirst().map(OptionalRoute::new).orElseGet(() -> new OptionalRoute(null));
    }
    private static boolean containsRoleFamily(String title, String roleFamily) {
        String expression = "(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(roleFamily.strip())
                + "(?![\\p{L}\\p{N}])";
        return Pattern.compile(expression).matcher(title).find();
    }
    private static String constraintName(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof ConstraintViolationException constraint) return constraint.getConstraintName();
        return null;
    }
    private record OptionalRoute(ResumeRoute route) {}
}
