package com.fairtix.config;

import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.inventory.application.DuplicateSeatException;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.application.SeatHoldNotFoundException;
import com.fairtix.orders.application.OrderNotFoundException;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.organizations.application.SalesCapExceededException;
import com.fairtix.auth.application.AccountLockedException;
import com.fairtix.boxoffice.application.BoxOfficeStateException;
import com.fairtix.holds.application.CompLimitExceededException;
import com.fairtix.auth.application.CaptchaRequiredException;
import com.fairtix.auth.application.InvalidCaptchaException;
import com.fairtix.auth.application.RecaptchaUnavailableException;
import com.fairtix.auth.application.WeakPasswordException;
import com.fairtix.payments.api.PaymentProcessingException;
import com.fairtix.payments.application.PaymentDeclinedException;
import com.fairtix.payments.application.PaymentFailedException;
import com.fairtix.payments.application.PaymentRetryConflictException;
import com.fairtix.refunds.application.RefundProviderException;
import com.fairtix.fraud.application.StepUpUnavailableException;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.queue.application.QueueConflictException;
import com.fairtix.refunds.application.RefundNotEligibleException;
import com.fairtix.tickets.application.TransferVelocityExceededException;
import com.fairtix.support.application.SupportTicketNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Translates domain and validation exceptions into a consistent JSON error
 * body:
 * 
 * <pre>
 * {
 *   "status":    409,
 *   "code":      "HOLD_CONFLICT",
 *   "message":   "Seat ... is not available",
 *   "path":      "/api/holds/abc/confirm",
 *   "timestamp": "2026-02-25T12:00:00Z"
 * }
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final AuthenticationTrustResolver TRUST_RESOLVER =
      new AuthenticationTrustResolverImpl();

  @ExceptionHandler(SeatHoldNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleNotFound(
      SeatHoldNotFoundException ex, HttpServletRequest req) {
    return error(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", ex.getMessage(), req);
  }

  @ExceptionHandler(SeatHoldConflictException.class)
  public ResponseEntity<Map<String, Object>> handleConflict(
      SeatHoldConflictException ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, "HOLD_CONFLICT", ex.getMessage(), req);
  }

  @ExceptionHandler(UserFlaggedForAbuseException.class)
  public ResponseEntity<Map<String, Object>> handleUserFlagged(
      UserFlaggedForAbuseException ex, HttpServletRequest req) {
    return error(HttpStatus.FORBIDDEN, "USER_FLAGGED_FOR_ABUSE", ex.getMessage(), req);
  }

  @ExceptionHandler(QueueConflictException.class)
  public ResponseEntity<Map<String, Object>> handleQueueConflict(
      QueueConflictException ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, "QUEUE_CONFLICT", ex.getMessage(), req);
  }

  @ExceptionHandler(DuplicateSeatException.class)
  public ResponseEntity<Map<String, Object>> handleDuplicateSeat(
      DuplicateSeatException ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, "DUPLICATE_SEAT", ex.getMessage(), req);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleBadRequest(
      IllegalArgumentException ex, HttpServletRequest req) {
    return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), req);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidStateTransition(
      IllegalStateException ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage(), req);
  }

  /**
   * Handles @Valid failures on request bodies.
   * Reports the first field error so clients know exactly what to fix.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    String firstError = ex.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
        .orElse("Validation failed");
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", firstError, req);
  }

  /**
   * Handles constraint failures on controller method parameters (for example
   * {@code @Valid List<...>} bodies and constrained {@code @RequestParam}s).
   * It extends {@link ResponseStatusException}, so without this closer match that
   * handler would answer with code {@code BAD_REQUEST}.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Map<String, Object>> handleMethodValidation(
      HandlerMethodValidationException ex, HttpServletRequest req) {
    logClientError(ex, req);
    String firstError = ex.getParameterValidationResults().stream()
        .flatMap(result -> result.getResolvableErrors().stream()
            .map(err -> (err instanceof FieldError fe ? fe.getField()
                : result.getMethodParameter().getParameterName())
                + ": " + err.getDefaultMessage()))
        .findFirst()
        .orElse("Validation failed");
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", firstError, req);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Map<String, Object>> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest req) {
    logClientError(ex, req);
    String firstError = ex.getConstraintViolations().stream()
        .findFirst()
        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
        .orElse("Validation failed");
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", firstError, req);
  }

  /** The parser's message can quote the body (a password, say), so it is never echoed or logged. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleNotReadable(
      HttpMessageNotReadableException ex, HttpServletRequest req) {
    logClientError(ex, req);
    return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
        "The request body is missing or is not valid JSON", req);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParameter(
      MissingServletRequestParameterException ex, HttpServletRequest req) {
    logClientError(ex, req);
    return error(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
        "Required parameter '" + ex.getParameterName() + "' is missing", req);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
    logClientError(ex, req);
    return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
        "Invalid value for parameter '" + ex.getName() + "'", req);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
    logClientError(ex, req);
    ResponseEntity<Map<String, Object>> body = error(HttpStatus.METHOD_NOT_ALLOWED,
        "METHOD_NOT_ALLOWED", "Method " + ex.getMethod() + " is not supported here", req);
    Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
    if (allowed == null || allowed.isEmpty()) {
      return body;
    }
    return ResponseEntity.status(body.getStatusCode()).allow(allowed.toArray(HttpMethod[]::new))
        .body(body.getBody());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
    logClientError(ex, req);
    return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
        "Content type is not supported here", req);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest req) {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    String message = ex.getReason();
    if (message == null || message.isBlank()) {
      message = ex.getMessage();
      if (message == null || message.isBlank()) {
        message = status.getReasonPhrase();
      }
    }
    return error(status, status.name(), message, req);
  }

  /**
   * {@code @PreAuthorize} on a permitAll path (such as {@code /auth/me}) denies an
   * anonymous caller here, before Spring Security's entry point can answer, so an
   * unauthenticated caller is mapped to the entry point's 401 rather than 403.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, Object>> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest req) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!TRUST_RESOLVER.isAuthenticated(auth)) {
      return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", req);
    }
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", req);
  }

  @ExceptionHandler(CompLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handleCompLimit(
      CompLimitExceededException ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, "COMP_LIMIT_EXCEEDED", ex.getMessage(), req);
  }

  @ExceptionHandler(BoxOfficeStateException.class)
  public ResponseEntity<Map<String, Object>> handleBoxOfficeState(
      BoxOfficeStateException ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, "BOX_OFFICE_STATE", ex.getMessage(), req);
  }

  @ExceptionHandler(PurchaseCapExceededException.class)
  public ResponseEntity<Map<String, Object>> handlePurchaseCap(
      PurchaseCapExceededException ex, HttpServletRequest req) {
    return error(HttpStatus.CONFLICT, "PURCHASE_CAP_EXCEEDED", ex.getMessage(), req);
  }

  @ExceptionHandler(SalesCapExceededException.class)
  public ResponseEntity<Map<String, Object>> handleSalesCap(
      SalesCapExceededException ex, HttpServletRequest req) {
    Map<String, Object> body = Map.of(
        "status", HttpStatus.TOO_MANY_REQUESTS.value(),
        "code", "SALES_CAP_EXCEEDED",
        "message", ex.getMessage(),
        "capCents", ex.getCapCents(),
        "usedCents", ex.getUsedCents(),
        "requestedCents", ex.getRequestedCents(),
        "path", req.getRequestURI(),
        "timestamp", Instant.now().toString());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
  }

  @ExceptionHandler(OrderNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleOrderNotFound(
      OrderNotFoundException ex, HttpServletRequest req) {
    return error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ex.getMessage(), req);
  }

  @ExceptionHandler(SupportTicketNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleSupportTicketNotFound(
      SupportTicketNotFoundException ex, HttpServletRequest req) {
    return error(HttpStatus.NOT_FOUND, "SUPPORT_TICKET_NOT_FOUND", ex.getMessage(), req);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleResourceNotFound(
      ResourceNotFoundException ex, HttpServletRequest req) {
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage(), req);
  }

  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<Map<String, Object>> handleAccountLocked(
      AccountLockedException ex, HttpServletRequest req) {
    Map<String, Object> body = Map.of(
        "status", HttpStatus.TOO_MANY_REQUESTS.value(),
        "code", "ACCOUNT_LOCKED",
        "message", ex.getMessage(),
        "remainingSeconds", ex.getRemainingSeconds(),
        "path", req.getRequestURI(),
        "timestamp", Instant.now().toString());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
  }

  @ExceptionHandler(CaptchaRequiredException.class)
  public ResponseEntity<Map<String, Object>> handleCaptchaRequired(
      CaptchaRequiredException ex, HttpServletRequest req) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, "CAPTCHA_REQUIRED", ex.getMessage(), req);
  }

  @ExceptionHandler(InvalidCaptchaException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidCaptcha(
      InvalidCaptchaException ex, HttpServletRequest req) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CAPTCHA", ex.getMessage(), req);
  }

  @ExceptionHandler(RecaptchaUnavailableException.class)
  public ResponseEntity<Map<String, Object>> handleRecaptchaUnavailable(
      RecaptchaUnavailableException ex, HttpServletRequest req) {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "CAPTCHA_UNAVAILABLE", ex.getMessage(), req);
  }

  @ExceptionHandler(StepUpUnavailableException.class)
  public ResponseEntity<Map<String, Object>> handleStepUpUnavailable(
      StepUpUnavailableException ex, HttpServletRequest req) {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "STEP_UP_UNAVAILABLE", ex.getMessage(), req);
  }

  @ExceptionHandler(WeakPasswordException.class)
  public ResponseEntity<Map<String, Object>> handleWeakPassword(
      WeakPasswordException ex, HttpServletRequest req) {
    return error(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", ex.getMessage(), req);
  }

  @ExceptionHandler(PaymentProcessingException.class)
  public ResponseEntity<Object> handlePaymentProcessing(
      PaymentProcessingException ex, HttpServletRequest req) {
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ex.getPaymentResponse());
  }

  @ExceptionHandler(TransferVelocityExceededException.class)
  public ResponseEntity<Map<String, Object>> handleTransferVelocity(
      TransferVelocityExceededException ex, HttpServletRequest req) {
    return error(HttpStatus.TOO_MANY_REQUESTS, "TRANSFER_VELOCITY_EXCEEDED", ex.getMessage(), req);
  }

  @ExceptionHandler(RefundNotEligibleException.class)
  public ResponseEntity<Map<String, Object>> handleRefundNotEligible(
      RefundNotEligibleException ex, HttpServletRequest req) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_NOT_ELIGIBLE", ex.getMessage(), req);
  }

  @ExceptionHandler(PaymentFailedException.class)
  public ResponseEntity<Map<String, Object>> handlePaymentFailed(
      PaymentFailedException ex, HttpServletRequest req) {
    return error(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", ex.getMessage(), req);
  }

  @ExceptionHandler(PaymentRetryConflictException.class)
  public ResponseEntity<Map<String, Object>> handlePaymentRetryConflict(
      PaymentRetryConflictException ex, HttpServletRequest req) {
    log.warn("Stripe idempotency conflict: {}", ex.getMessage());
    return error(HttpStatus.CONFLICT, "PAYMENT_RETRY_CONFLICT",
        "This payment changed since the last attempt. Start again.", req);
  }

  @ExceptionHandler(RefundProviderException.class)
  public ResponseEntity<Map<String, Object>> handleRefundProvider(
      RefundProviderException ex, HttpServletRequest req) {
    return error(HttpStatus.BAD_GATEWAY, "REFUND_PROVIDER_ERROR", ex.getMessage(), req);
  }

  @ExceptionHandler(PaymentDeclinedException.class)
  public ResponseEntity<Map<String, Object>> handleCardDeclined(
      PaymentDeclinedException ex, HttpServletRequest req) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, "CARD_DECLINED", ex.getMessage(), req);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<Map<String, Object>> handleOptimisticLock(
      OptimisticLockingFailureException ex, HttpServletRequest req) {
    log.warn("Optimistic lock conflict on {}: {}", req.getRequestURI(), ex.getMessage());
    return error(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
        "The resource was modified by another request; reload and try again", req);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, Object>> handleDataIntegrity(
      DataIntegrityViolationException ex, HttpServletRequest req) {
    log.error("Data integrity violation on {}: {}", req.getRequestURI(), ex.getMessage());
    return error(HttpStatus.CONFLICT, "DATA_CONFLICT",
        "The request conflicts with the current state of the data", req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnexpected(
      Exception ex, HttpServletRequest req) {
    log.error("Unhandled exception on {}", req.getRequestURI(), ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
        "An unexpected error occurred", req);
  }

  // -------------------------------------------------------------------------

  /** Client mistakes are warn, not error, and never log the exception message. */
  private void logClientError(Exception ex, HttpServletRequest req) {
    log.warn("{} on {} {}", ex.getClass().getSimpleName(), req.getMethod(), req.getRequestURI());
  }

  private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message,
      HttpServletRequest req) {
    return ResponseEntity.status(status).body(Map.of(
        "status", status.value(),
        "code", code,
        "message", message != null ? message : "",
        "path", req.getRequestURI(),
        "timestamp", Instant.now().toString()));
  }
}
