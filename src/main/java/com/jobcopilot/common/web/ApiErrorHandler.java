package com.jobcopilot.common.web;

import com.jobcopilot.job.InvalidJobQueryException;
import com.jobcopilot.job.JobNotFoundException;
import com.jobcopilot.job.JobRequirementExtractionException;
import com.jobcopilot.matching.InvalidJobRankingQueryException;
import com.jobcopilot.matching.JobRankingComputationException;
import com.jobcopilot.discovery.JobSourceApiExceptions.DuplicateSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.InvalidQuery;
import com.jobcopilot.discovery.JobSourceApiExceptions.InvalidSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.InvalidStoredSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.ListingNotFound;
import com.jobcopilot.discovery.JobSourceApiExceptions.PersistenceFailure;
import com.jobcopilot.discovery.JobSourceApiExceptions.RunNotFound;
import com.jobcopilot.discovery.JobSourceApiExceptions.SourceDisabled;
import com.jobcopilot.discovery.JobSourceApiExceptions.SourceNotFound;
import com.jobcopilot.discovery.JobSourceApiExceptions.SynchronizationFailure;
import com.jobcopilot.discovery.JobSourceApiExceptions.UnsupportedProvider;
import com.jobcopilot.discovery.JobSourceSyncAlreadyRunningException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import static com.jobcopilot.resume.ResumeExceptions.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler({InvalidQuery.class, InvalidSource.class, UnsupportedProvider.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidJobSource(RuntimeException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler({SourceNotFound.class, RunNotFound.class, ListingNotFound.class})
    public ResponseEntity<ApiErrorResponse> handleDiscoveryNotFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({DuplicateSource.class, SourceDisabled.class, InvalidStoredSource.class,
            JobSourceSyncAlreadyRunningException.class})
    public ResponseEntity<ApiErrorResponse> handleDiscoveryConflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({PersistenceFailure.class, SynchronizationFailure.class})
    public ResponseEntity<ApiErrorResponse> handleDiscoveryFailure(RuntimeException exception) {
        log.error("Unexpected discovery operation failure type={}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @ExceptionHandler(JobRankingComputationException.class)
    public ResponseEntity<ApiErrorResponse> handleRankingFailure(JobRankingComputationException exception) {
        log.error(exception.getMessage(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @ExceptionHandler(com.jobcopilot.job.InvalidJobRequirementsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequirements(com.jobcopilot.job.InvalidJobRequirementsException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(JobRequirementExtractionException.class)
    public ResponseEntity<ApiErrorResponse> handleRequirementExtraction(JobRequirementExtractionException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }
    @ExceptionHandler(com.jobcopilot.matching.MatchCannotBeComputedException.class)
    public ResponseEntity<ApiErrorResponse> handleCannotMatch(com.jobcopilot.matching.MatchCannotBeComputedException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }

    @ExceptionHandler({ResumeNotFoundException.class, CandidateProfileNotFoundException.class,
            PreferenceNotFoundException.class, ResumeRouteNotFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleResumeNotFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }
    @ExceptionHandler(ResumeRouteConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleResumeRouteConflict(ResumeRouteConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }
    @ExceptionHandler({CandidateProfileNotConfirmedException.class, CandidateProfileRevisionConflictException.class})
    public ResponseEntity<ApiErrorResponse> handleCandidateProfileConflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }
    @ExceptionHandler({InvalidResumeFileException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidResume(Exception exception) {
        return badRequest(exception instanceof InvalidResumeFileException ? exception.getMessage() : "A file part named file is required");
    }
    @ExceptionHandler({ResumeTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiErrorResponse> handleTooLarge(Exception exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds the configured file or request size limit");
    }
    @ExceptionHandler({UnsupportedResumeTypeException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiErrorResponse> handleUnsupported(Exception exception) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception instanceof UnsupportedResumeTypeException
                ? exception.getMessage() : "Unsupported request content type");
    }
    @ExceptionHandler({ResumeParsingException.class, EmptyResumeTextException.class, ResumeProcessingLimitException.class})
    public ResponseEntity<ApiErrorResponse> handleUnprocessableResume(RuntimeException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiErrorResponse> handleMultipart(MultipartException exception) {
        return badRequest("Malformed multipart upload");
    }
    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(status.value(), status.getReasonPhrase(), message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ValidationErrorResponse response = new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                fieldErrors
        );

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(JobNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                exception.getMessage()
        ));
    }

    @ExceptionHandler({InvalidJobQueryException.class, InvalidJobRankingQueryException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidJobQuery(RuntimeException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidDomainValue(IllegalArgumentException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return badRequest("Invalid value for " + exception.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody() {
        return badRequest("Request body is malformed or contains an invalid value");
    }

    private static ResponseEntity<ApiErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                message
        ));
    }
}
