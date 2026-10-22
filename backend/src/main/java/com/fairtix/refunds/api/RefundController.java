package com.fairtix.refunds.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.refunds.application.RefundExecutionService;
import com.fairtix.refunds.application.RefundService;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.dto.CreateRefundRequest;
import com.fairtix.refunds.dto.RefundApprovalRequest;
import com.fairtix.refunds.dto.RefundResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Refunds", description = "Refund request management")
@RestController
@PreAuthorize("isAuthenticated()")
public class RefundController {

  private final RefundService refundService;
  private final RefundExecutionService refundExecutionService;

  public RefundController(RefundService refundService,
      RefundExecutionService refundExecutionService) {
    this.refundService = refundService;
    this.refundExecutionService = refundExecutionService;
  }

  @Operation(summary = "Request a refund for a completed order")
  @PostMapping("/api/orders/{orderId}/refunds")
  @ResponseStatus(HttpStatus.CREATED)
  public RefundResponse requestRefund(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @PathVariable UUID orderId,
      @Valid @RequestBody CreateRefundRequest request) {
    return RefundResponse.from(
        refundExecutionService.request(principal.getUserId(), orderId, request.reason()));
  }

  @Operation(summary = "List current user's refund requests")
  @GetMapping("/api/refunds")
  public List<RefundResponse> listRefunds(@AuthenticationPrincipal CustomUserPrincipal principal) {
    return refundService.getUserRefunds(principal.getUserId())
        .stream().map(RefundResponse::from).toList();
  }

  @Operation(summary = "Get a specific refund request")
  @GetMapping("/api/refunds/{refundId}")
  public RefundResponse getRefund(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @PathVariable UUID refundId) {
    return RefundResponse.from(refundService.getRefund(refundId, principal.getUserId()));
  }

  // --- Admin endpoints ---

  @Operation(summary = "Admin: list all refund requests")
  @GetMapping("/api/admin/refunds")
  @PreAuthorize("hasRole('ADMIN')")
  public Page<RefundResponse> adminListRefunds(
      @RequestParam(required = false) RefundStatus status,
      @PageableDefault(size = 20) Pageable pageable) {
    return refundService.adminListRefunds(status, pageable).map(RefundResponse::from);
  }

  @Operation(summary = "Admin: approve or reject a refund request")
  @PostMapping("/api/admin/refunds/{refundId}/review")
  @PreAuthorize("hasRole('ADMIN')")
  public RefundResponse reviewRefund(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @PathVariable UUID refundId,
      @Valid @RequestBody RefundApprovalRequest request) {
    return RefundResponse.from(
        refundExecutionService.review(principal.getUserId(), refundId,
            request.approved(), request.adminNotes()));
  }

  @Operation(summary = "Admin: retry an approved refund whose payment-provider call or "
      + "bookkeeping failed. Safe to repeat: Stripe replays the original refund.")
  @PostMapping("/api/admin/refunds/{refundId}/retry")
  @PreAuthorize("hasRole('ADMIN')")
  public RefundResponse retryRefund(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @PathVariable UUID refundId) {
    return RefundResponse.from(refundExecutionService.retry(principal.getUserId(), refundId));
  }
}
