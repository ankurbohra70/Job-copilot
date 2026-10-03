package com.jobcopilot.resume;

public final class ResumeExceptions {
    private ResumeExceptions() {}
    public static class InvalidResumeFileException extends RuntimeException { public InvalidResumeFileException(String message) { super(message); } }
    public static class UnsupportedResumeTypeException extends RuntimeException { public UnsupportedResumeTypeException() { super("Upload a PDF file with a .pdf extension and an acceptable PDF content type"); } }
    public static class ResumeTooLargeException extends RuntimeException { public ResumeTooLargeException() { super("Resume exceeds the configured upload size limit"); } }
    public static class ResumeProcessingLimitException extends RuntimeException { public ResumeProcessingLimitException(String message) { super(message); } }
    public static class ResumeParsingException extends RuntimeException {
        public ResumeParsingException(String message) { super(message); }
        public ResumeParsingException(String message, Throwable cause) { super(message, cause); }
    }
    public static class EmptyResumeTextException extends RuntimeException { public EmptyResumeTextException() { super("No meaningful text could be extracted; upload a text-based PDF meeting the configured minimum text length"); } }
    public static class ResumeNotFoundException extends RuntimeException { public ResumeNotFoundException(Long id) { super("Resume " + id + " was not found"); } }
    public static class CandidateProfileNotFoundException extends RuntimeException { public CandidateProfileNotFoundException(Long id) { super("Candidate profile " + id + " was not found"); } }
    public static class CandidateProfileNotConfirmedException extends RuntimeException {
        public CandidateProfileNotConfirmedException(Long id) { super("Candidate profile " + id + " is not confirmed"); }
    }
    public static class CandidateProfileRevisionConflictException extends RuntimeException {
        public CandidateProfileRevisionConflictException(Long id) { super("Candidate profile " + id + " revision does not match"); }
    }
    public static class PreferenceNotFoundException extends RuntimeException { public PreferenceNotFoundException(Long id) { super("Candidate profile " + id + " has no job search preference"); } }
    public static class ResumeRouteNotFoundException extends RuntimeException { public ResumeRouteNotFoundException(Long id) { super("Resume route " + id + " was not found"); } }
    public static class ResumeRouteConflictException extends RuntimeException {
        public ResumeRouteConflictException() { super("A resume route already exists for this profile, strategy, and route selector"); }
    }
}
