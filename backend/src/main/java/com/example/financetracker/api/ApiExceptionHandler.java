package com.example.financetracker.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.InvalidEntryException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error of the API into an RFC 7807 problem detail with a message that a user can read, and nothing of the
 * server's internals: no stack trace and no SQL.
 * <ul>
 * <li>400: a malformed request. {@code errors} lists each invalid field or parameter with what is wrong with it.
 * <li>404: an object the user doesn't have, whether it doesn't exist or belongs to someone else (rule 11).
 * <li>409: a stale version, or a change that the object's state rules out.
 * <li>422: an entry or setting that breaks the ledger's rules. {@code violations} lists every rule it breaks.
 * <li>500: anything else, which is logged.
 * </ul>
 * The {@code @ResponseStatus} of the handlers put these statuses into the OpenAPI description of every operation.
 * Spring MVC's own exceptions, such as 405 or 413, get their standard problem details from
 * {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** One invalid field of the request body, or one invalid request parameter. */
    record InvalidField(String field, String message) {
    }

    @ExceptionHandler(InvalidEntryException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ProblemDetail invalidEntry(InvalidEntryException e) {
        return ledgerRules(e.violations());
    }

    @ExceptionHandler(RuleViolationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ProblemDetail ruleViolation(RuleViolationException e) {
        return ledgerRules(e.violations());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage() + ".");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail staleVersion(OptimisticLockingFailureException e) {
        // EntryService's message says which entry and version, and what to do.
        return problem(HttpStatus.CONFLICT, "Changed in the meantime", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage() + ".");
    }

    /** A backstop: the services check what users can run into before the database does. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ProblemDetail dataIntegrity(DataIntegrityViolationException e) {
        log.warn("The database refused a change: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Conflict", "The change conflicts with other data.");
    }

    /** Spring Data JDBC wraps what the database refuses when it saves an aggregate. */
    @ExceptionHandler(DbActionExecutionException.class)
    ProblemDetail refusedSave(DbActionExecutionException e) throws Exception {
        if (e.getCause() instanceof DataIntegrityViolationException refused) {
            return dataIntegrity(refused);
        }
        return unexpected(e);
    }

    /** The services' own checks of their arguments, such as a date range that ends before it starts. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage() + ".");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ProblemDetail unexpected(Exception e) throws Exception {
        if (e instanceof AuthenticationException || e instanceof AccessDeniedException) {
            // Rethrown as it came, so Spring Security answers 401 or 403.
            throw e;
        }
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "Something went wrong on the server. Please try again later.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalid(ex, fieldErrors(ex.getBindingResult().getFieldErrors()), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors bean) {
                errors.addAll(fieldErrors(bean.getFieldErrors()));
            } else {
                String parameter = result.getMethodParameter().getParameterName();
                result.getResolvableErrors()
                        .forEach(error -> errors.add(new InvalidField(parameter, error.getDefaultMessage())));
            }
        });
        return invalid(ex, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return invalid(ex, List.of(new InvalidField(ex.getPropertyName(), expected(ex.getRequiredType()))), headers,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalid(ex, List.of(new InvalidField(ex.getParameterName(), "is required")), headers, request);
    }

    /**
     * A parameter value that no handler takes, where handlers on one path are told apart by it, such as a report's
     * {@code currency}: each parameter that a handler wants a value of, with the values that one would take.
     */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(ex instanceof UnsatisfiedServletRequestParameterException unsatisfied)) {
            return super.handleServletRequestBindingException(ex, headers, status, request);
        }
        Map<String, List<String>> values = new TreeMap<>();
        unsatisfied.getParamConditionGroups().forEach(group -> Arrays.stream(group)
                .filter(condition -> condition.contains("=") && !condition.contains("!="))
                .forEach(condition -> values.computeIfAbsent(condition.substring(0, condition.indexOf('=')),
                        name -> new ArrayList<>()).add(condition.substring(condition.indexOf('=') + 1))));
        if (values.isEmpty()) {
            return super.handleServletRequestBindingException(ex, headers, status, request);
        }
        List<InvalidField> errors = values.entrySet().stream()
                .map(e -> new InvalidField(e.getKey(), "must be " + String.join(" or ", e.getValue())
                        + ", or left out"))
                .toList();
        return invalid(ex, errors, headers, request);
    }

    /**
     * A body that can't be read into the request's type. An entry command whose own checks fail is a ledger rule
     * violation (422), like one the validator rejects later.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidEntryException invalid) {
                return handleExceptionInternal(ex, ledgerRules(invalid.violations()), headers,
                        HttpStatus.UNPROCESSABLE_ENTITY, request);
            }
        }
        return switch (ex.getCause()) {
            case InvalidTypeIdException typeId -> invalid(ex, List.of(new InvalidField("kind",
                    (typeId.getTypeId() == null ? "is missing" : "'%s' is unknown".formatted(typeId.getTypeId()))
                            + "; it must be one of " + names(EntryKind.values()))), headers, request);
            case MismatchedInputException mismatch when !mismatch.getPath().isEmpty() -> invalid(ex,
                    List.of(new InvalidField(path(mismatch), expected(mismatch.getTargetType()))), headers, request);
            case JsonMappingException mapping -> body(ex, "The request body doesn't have the expected form.", headers,
                    request);
            case JsonProcessingException parse -> body(ex, "The request body is not valid JSON.", headers, request);
            case null, default -> body(ex, "The request body is missing or can't be read.", headers, request);
        };
    }

    private ResponseEntity<Object> invalid(Exception ex, List<InvalidField> errors, HttpHeaders headers,
            WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", errors.stream()
                .map(error -> error.field() + " " + error.message())
                .collect(Collectors.joining("; ", "Invalid request: ", ".")));
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> body(Exception ex, String detail, HttpHeaders headers, WebRequest request) {
        return handleExceptionInternal(ex, problem(HttpStatus.BAD_REQUEST, "Invalid request", detail), headers,
                HttpStatus.BAD_REQUEST, request);
    }

    private static ProblemDetail ledgerRules(List<String> violations) {
        String detail = String.join("; ", violations);
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "The ledger's rules are broken",
                Character.toUpperCase(detail.charAt(0)) + detail.substring(1) + ".");
        problem.setProperty("violations", violations);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    private static List<InvalidField> fieldErrors(List<FieldError> errors) {
        return errors.stream()
                .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::message))
                .toList();
    }

    /** Where in the body the value is, such as {@code postings[1].amount}. */
    private static String path(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            } else {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /** What a value of the type looks like, for a value that isn't one. */
    private static String expected(Class<?> type) {
        if (type == null) {
            return "has an invalid value";
        } else if (type == BigDecimal.class) {
            return "must be a decimal number, such as \"12.50\"";
        } else if (type == LocalDate.class) {
            return "must be a date, such as \"2026-09-25\"";
        } else if (type.isEnum()) {
            return "must be one of " + names(type.getEnumConstants());
        } else if (List.of(Long.class, long.class, Integer.class, int.class).contains(type)) {
            return "must be a whole number";
        } else if (type == Boolean.class || type == boolean.class) {
            return "must be true or false";
        }
        return "has an invalid value";
    }

    private static String names(Object[] constants) {
        return Arrays.stream(constants).map(Object::toString).collect(Collectors.joining(", "));
    }
}
