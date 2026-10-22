package com.fairtix.tickets.api;

import com.fairtix.auth.application.VerifiedEmailGuard;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.tickets.application.TransferService;
import com.fairtix.tickets.dto.TransferRequestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Transfers", description = "Ticket transfer flow")
@RestController
@PreAuthorize("isAuthenticated()")
public class TransferController {

    private final TransferService transferService;
    private final VerifiedEmailGuard verifiedEmailGuard;

    public TransferController(TransferService transferService, VerifiedEmailGuard verifiedEmailGuard) {
        this.transferService = transferService;
        this.verifiedEmailGuard = verifiedEmailGuard;
    }

    public record InitiateTransferRequest(
            @NotBlank @Email String toEmail) {}

    @Operation(summary = "Initiate a ticket transfer")
    @PostMapping("/api/tickets/{ticketId}/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferRequestResponse initiateTransfer(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID ticketId,
            @Valid @RequestBody InitiateTransferRequest body) {
        verifiedEmailGuard.requireVerified(principal.getUserId(), "transferring tickets");
        return TransferRequestResponse.from(
                transferService.createTransferRequest(ticketId, principal.getUserId(), body.toEmail()));
    }

    @Operation(summary = "List pending incoming transfer requests")
    @GetMapping("/api/transfers/incoming")
    public List<TransferRequestResponse> listIncoming(
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return transferService.listIncoming(principal.getUserId()).stream()
                .map(TransferRequestResponse::from)
                .toList();
    }

    @Operation(summary = "List outgoing transfer requests (all statuses)")
    @GetMapping("/api/transfers/outgoing")
    public List<TransferRequestResponse> listOutgoing(
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return transferService.listOutgoing(principal.getUserId()).stream()
                .map(TransferRequestResponse::from)
                .toList();
    }

    @Operation(summary = "Accept a transfer request")
    @PostMapping("/api/transfers/{requestId}/accept")
    public TransferRequestResponse acceptTransfer(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID requestId) {
        return TransferRequestResponse.from(
                transferService.acceptTransfer(requestId, principal.getUserId()));
    }

    @Operation(summary = "Reject a transfer request")
    @PostMapping("/api/transfers/{requestId}/reject")
    public TransferRequestResponse rejectTransfer(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID requestId) {
        return TransferRequestResponse.from(
                transferService.rejectTransfer(requestId, principal.getUserId()));
    }

    @Operation(summary = "Cancel an outgoing transfer request")
    @PostMapping("/api/transfers/{requestId}/cancel")
    public TransferRequestResponse cancelTransfer(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID requestId) {
        return TransferRequestResponse.from(
                transferService.cancelTransfer(requestId, principal.getUserId()));
    }
}
