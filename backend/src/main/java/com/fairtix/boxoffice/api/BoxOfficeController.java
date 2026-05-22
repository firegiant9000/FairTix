package com.fairtix.boxoffice.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.boxoffice.application.BoxOfficeService;
import com.fairtix.boxoffice.application.BoxOfficeService.CardPresentIntentResult;
import com.fairtix.boxoffice.domain.BoxOfficeSale;
import com.fairtix.boxoffice.domain.BoxOfficeSession;
import com.fairtix.boxoffice.domain.SaleMethod;
import com.fairtix.boxoffice.dto.CardPresentIntentRequest;
import com.fairtix.boxoffice.dto.CardPresentIntentResponse;
import com.fairtix.boxoffice.dto.CloseSessionRequest;
import com.fairtix.boxoffice.dto.CompSaleRequest;
import com.fairtix.boxoffice.dto.ConfirmCardSaleRequest;
import com.fairtix.boxoffice.dto.OpenSessionRequest;
import com.fairtix.boxoffice.dto.SaleResponse;
import com.fairtix.boxoffice.dto.SessionReportResponse;
import com.fairtix.boxoffice.dto.SessionResponse;
import com.fairtix.boxoffice.dto.TerminalConnectionTokenResponse;
import com.fairtix.boxoffice.dto.WalkUpSaleRequest;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Box-office API (Phase D — M2-09..M2-11). Every endpoint is org-scoped and
 * requires {@link OrgPermission#BOX_OFFICE_SELL}. Per the role model, OWNER and
 * BOX_OFFICE staff hold this permission; MANAGER does not — managers sign off
 * on the close-of-night flow via a separate permission check.
 */
@Tag(name = "Box Office", description = "Tablet box-office sales and reconciliation")
@RestController
@RequestMapping("/api/organizations/{orgId}/box-office")
public class BoxOfficeController {

  private final BoxOfficeService boxOfficeService;
  private final StripePaymentService stripePaymentService;
  private final OrganizationRepository organizationRepository;

  public BoxOfficeController(BoxOfficeService boxOfficeService,
                             StripePaymentService stripePaymentService,
                             OrganizationRepository organizationRepository) {
    this.boxOfficeService = boxOfficeService;
    this.stripePaymentService = stripePaymentService;
    this.organizationRepository = organizationRepository;
  }

  @Operation(summary = "Today's events for the org")
  @GetMapping("/events/today")
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public List<Map<String, Object>> todaysEvents(@PathVariable UUID orgId) {
    return boxOfficeService.todaysEvents(orgId).stream()
        .map(e -> Map.<String, Object>of(
            "id", e.getId(),
            "title", e.getTitle(),
            "startTime", e.getStartTime(),
            "venueName", e.getVenue() == null ? "" : e.getVenue().getName()))
        .toList();
  }

  @Operation(summary = "Open a box-office session (cash drawer)")
  @PostMapping("/sessions")
  @ResponseStatus(HttpStatus.CREATED)
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public SessionResponse openSession(@PathVariable UUID orgId,
                                     @Valid @RequestBody OpenSessionRequest req,
                                     @AuthenticationPrincipal CustomUserPrincipal principal) {
    BoxOfficeSession session = boxOfficeService.openSession(orgId, principal.getUserId(),
        req.openingCash());
    return SessionResponse.from(session);
  }

  @Operation(summary = "Active session for the calling staff")
  @GetMapping("/sessions/active")
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public SessionResponse activeSession(@PathVariable UUID orgId,
                                       @AuthenticationPrincipal CustomUserPrincipal principal) {
    return boxOfficeService.findActiveSession(orgId, principal.getUserId())
        .map(SessionResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No active session"));
  }

  @Operation(summary = "Session detail + reconciliation totals")
  @GetMapping("/sessions/{sessionId}")
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public SessionReportResponse sessionReport(@PathVariable UUID orgId,
                                             @PathVariable UUID sessionId) {
    BoxOfficeSession session = boxOfficeService.requireSession(sessionId, orgId);
    List<BoxOfficeSale> sales = boxOfficeService.listSales(sessionId).stream()
        .filter(s -> s.getOrderId() != null) // hide pending card holds
        .toList();
    BigDecimal cash = sumByMethod(sales, SaleMethod.CASH);
    BigDecimal card = sumByMethod(sales, SaleMethod.CARD);
    BigDecimal comp = sumByMethod(sales, SaleMethod.COMP);
    int cashCount = (int) sales.stream().filter(s -> s.getMethod() == SaleMethod.CASH).count();
    int cardCount = (int) sales.stream().filter(s -> s.getMethod() == SaleMethod.CARD).count();
    int compCount = (int) sales.stream().filter(s -> s.getMethod() == SaleMethod.COMP).count();
    int tickets = sales.stream().mapToInt(BoxOfficeSale::getSeatCount).sum();
    BigDecimal expectedCash = session.getOpeningCash().add(cash);
    return new SessionReportResponse(
        SessionResponse.from(session),
        cash, card, comp, cashCount, cardCount, compCount, tickets,
        expectedCash, sales.stream().map(SaleResponse::from).toList());
  }

  @Operation(summary = "Close box-office session (cash count + variance + sign-off)")
  @PostMapping("/sessions/{sessionId}/close")
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public SessionResponse closeSession(@PathVariable UUID orgId,
                                      @PathVariable UUID sessionId,
                                      @Valid @RequestBody CloseSessionRequest req,
                                      @AuthenticationPrincipal CustomUserPrincipal principal) {
    BoxOfficeSession session = boxOfficeService.closeSession(
        sessionId, orgId, req.closingCash(), req.varianceReason(), principal.getUserId());
    return SessionResponse.from(session);
  }

  @Operation(summary = "Cash walk-up sale")
  @PostMapping("/sessions/{sessionId}/sales/cash")
  @ResponseStatus(HttpStatus.CREATED)
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public SaleResponse cashSale(@PathVariable UUID orgId,
                               @PathVariable UUID sessionId,
                               @Valid @RequestBody WalkUpSaleRequest req,
                               @AuthenticationPrincipal CustomUserPrincipal principal) {
    return SaleResponse.from(boxOfficeService.recordCashSale(
        sessionId, orgId, principal.getUserId(),
        req.eventId(), req.seatIds(), req.customerEmail(), req.customerName()));
  }

  @Operation(summary = "Issue a comp (no payment, $0 ticket)")
  @PostMapping("/sessions/{sessionId}/sales/comp")
  @ResponseStatus(HttpStatus.CREATED)
  @OrgScoped(OrgPermission.COMPS_WRITE)
  public SaleResponse compSale(@PathVariable UUID orgId,
                               @PathVariable UUID sessionId,
                               @Valid @RequestBody CompSaleRequest req,
                               @AuthenticationPrincipal CustomUserPrincipal principal) {
    return SaleResponse.from(boxOfficeService.recordCompSale(
        sessionId, orgId, principal.getUserId(),
        req.eventId(), req.seatIds(), req.reason(),
        req.customerEmail(), req.customerName()));
  }

  @Operation(summary = "Create a card-present PaymentIntent for Stripe Terminal")
  @PostMapping("/sessions/{sessionId}/sales/card-present/intent")
  @ResponseStatus(HttpStatus.CREATED)
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public CardPresentIntentResponse createCardPresentIntent(
      @PathVariable UUID orgId,
      @PathVariable UUID sessionId,
      @Valid @RequestBody CardPresentIntentRequest req,
      @AuthenticationPrincipal CustomUserPrincipal principal) {
    CardPresentIntentResult r = boxOfficeService.createCardPresentIntent(
        sessionId, orgId, principal.getUserId(), req.eventId(), req.seatIds(),
        req.terminalReaderId());
    return new CardPresentIntentResponse(
        r.intent().getId(),
        r.intent().getClientSecret(),
        r.connectedAccountId(),
        r.intent().getAmount(),
        r.applicationFeeCents());
  }

  @Operation(summary = "Confirm a card-present sale after Terminal completes")
  @PostMapping("/sessions/{sessionId}/sales/card-present/confirm")
  @ResponseStatus(HttpStatus.CREATED)
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public SaleResponse confirmCardSale(@PathVariable UUID orgId,
                                      @PathVariable UUID sessionId,
                                      @Valid @RequestBody ConfirmCardSaleRequest req,
                                      @AuthenticationPrincipal CustomUserPrincipal principal) {
    return SaleResponse.from(boxOfficeService.confirmCardSale(
        sessionId, orgId, principal.getUserId(),
        req.paymentIntentId(), null, null));
  }

  @Operation(summary = "Stripe Terminal connection token (per-org Connect account)")
  @PostMapping("/terminal/connection-token")
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  public TerminalConnectionTokenResponse connectionToken(@PathVariable UUID orgId) {
    if (!stripePaymentService.isStripeEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
          "Stripe is not enabled in this environment");
    }
    Organization org = organizationRepository.findById(orgId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    if (org.getStripeConnectAccountId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Organization has not connected a Stripe account");
    }
    String secret = stripePaymentService.createTerminalConnectionToken(org.getStripeConnectAccountId());
    return new TerminalConnectionTokenResponse(secret);
  }

  private static BigDecimal sumByMethod(List<BoxOfficeSale> sales, SaleMethod method) {
    return sales.stream()
        .filter(s -> s.getMethod() == method)
        .map(BoxOfficeSale::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
