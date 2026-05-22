package com.fairtix.holds.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.holds.application.WillCallService;
import com.fairtix.holds.dto.CompTicketResponse;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.tickets.domain.Ticket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "Will-Call", description = "Will-call attendee list, claim, batch print")
@RestController
@RequestMapping("/api/organizer/will-call")
@PreAuthorize("isAuthenticated()")
public class WillCallController {

  private static final DateTimeFormatter DTF =
      DateTimeFormatter.ofPattern("EEE, MMM d yyyy 'at' h:mm a").withZone(ZoneOffset.UTC);

  private final WillCallService service;

  public WillCallController(WillCallService service) {
    this.service = service;
  }

  @Operation(summary = "List or search will-call attendees for an event")
  @OrgScoped(OrgPermission.ATTENDEES_READ)
  @GetMapping
  public List<CompTicketResponse> list(@RequestParam UUID eventId,
                                       @RequestParam(required = false) String q) {
    return service.list(eventId, q).stream().map(CompTicketResponse::from).toList();
  }

  @Operation(summary = "Mark a will-call ticket as claimed at the door")
  @OrgScoped(OrgPermission.BOX_OFFICE_SELL)
  @PostMapping("/{ticketId}/claim")
  public CompTicketResponse claim(@AuthenticationPrincipal CustomUserPrincipal principal,
                                  @PathVariable UUID ticketId) {
    return CompTicketResponse.from(service.markClaimed(ticketId, principal.getUserId()));
  }

  /**
   * Returns one HTML page per will-call ticket with {@code page-break-after:
   * always}; browser print-to-PDF gives a usable batch print. A real PDF
   * pipeline (PDFBox or similar) would be cleaner but is deferred per the
   * "no new dependencies without approval" project rule.
   */
  /**
   * Sort order for the batch print queue. {@code lastName} is the default
   * because door staff typically call attendees alphabetically; {@code seat}
   * helps when seats are pre-assigned and pickup order matches the seating
   * chart; {@code recent} surfaces last-minute claims first.
   */
  public enum PrintSort { lastName, seat, recent }

  /** Filter on claimed status — most prints want only unclaimed tickets. */
  public enum PrintFilter { unclaimed, claimed, all }

  @Operation(summary = "Batch-print HTML for the will-call list (one ticket per page)")
  @OrgScoped(OrgPermission.ATTENDEES_READ)
  @GetMapping(value = "/print", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> print(@RequestParam UUID eventId,
                                      @RequestParam(required = false) PrintSort sort,
                                      @RequestParam(required = false) PrintFilter filter) {
    PrintSort effectiveSort = sort == null ? PrintSort.lastName : sort;
    PrintFilter effectiveFilter = filter == null ? PrintFilter.unclaimed : filter;

    List<Ticket> list = service.list(eventId, null).stream()
        .filter(t -> switch (effectiveFilter) {
          case unclaimed -> t.getWillCallClaimedAt() == null;
          case claimed   -> t.getWillCallClaimedAt() != null;
          case all       -> true;
        })
        .sorted(comparatorFor(effectiveSort))
        .toList();

    String pages = list.stream().map(WillCallController::ticketPage).collect(Collectors.joining());
    String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
        + "<title>Will-Call — " + (list.isEmpty() ? "no event" : escape(list.get(0).getEvent().getTitle())) + "</title>"
        + "<style>"
        + " @page { size: letter; margin: 0.5in; }"
        + " body { font-family: -apple-system, system-ui, sans-serif; margin: 0; }"
        + " .ticket { page-break-after: always; padding: 0.5in; height: 9.5in; box-sizing: border-box; }"
        + " .ticket:last-child { page-break-after: auto; }"
        + " h1 { font-size: 28pt; margin: 0 0 4pt 0; }"
        + " .meta { color: #444; font-size: 12pt; margin-bottom: 24pt; }"
        + " .recipient { font-size: 22pt; font-weight: bold; margin-top: 24pt; }"
        + " .seat { font-size: 18pt; margin-top: 8pt; }"
        + " .id { color: #777; font-size: 10pt; margin-top: 24pt; word-break: break-all; }"
        + " .kind-COMP { color: #b35900; font-weight: bold; }"
        + "</style></head><body>"
        + pages
        + "</body></html>";
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
  }

  private static Comparator<Ticket> comparatorFor(PrintSort sort) {
    return switch (sort) {
      case lastName -> Comparator.comparing(WillCallController::lastNameKey, String.CASE_INSENSITIVE_ORDER);
      case seat -> Comparator
          .comparing((Ticket t) -> nullToEmpty(t.getSeat().getSection()), String.CASE_INSENSITIVE_ORDER)
          .thenComparing(t -> nullToEmpty(t.getSeat().getRowLabel()), String.CASE_INSENSITIVE_ORDER)
          .thenComparing(t -> nullToEmpty(t.getSeat().getSeatNumber()), String.CASE_INSENSITIVE_ORDER);
      case recent -> Comparator
          .comparing((Ticket t) -> t.getWillCallClaimedAt(),
                     Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Ticket::getId);
    };
  }

  private static String lastNameKey(Ticket t) {
    String name = t.getRecipientName() != null && !t.getRecipientName().isBlank()
        ? t.getRecipientName()
        : (t.getUser() != null ? t.getUser().getEmail() : "");
    if (name == null || name.isBlank()) return "";
    String trimmed = name.trim();
    int space = trimmed.lastIndexOf(' ');
    return space < 0 ? trimmed : trimmed.substring(space + 1);
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String ticketPage(Ticket t) {
    var s = t.getSeat();
    var e = t.getEvent();
    String recipient = t.getRecipientName() != null && !t.getRecipientName().isBlank()
        ? t.getRecipientName() : t.getUser().getEmail();
    String venueName = e.getVenue() == null ? "" : e.getVenue().getName();
    return "<section class=\"ticket\">"
        + "<h1>" + escape(e.getTitle()) + "</h1>"
        + "<div class=\"meta\">" + escape(venueName) + " · " + DTF.format(e.getStartTime()) + "</div>"
        + "<div>Will-Call</div>"
        + "<div class=\"recipient\">" + escape(recipient) + "</div>"
        + "<div class=\"seat\">Seat: " + escape(s.getSection()) + " " + escape(s.getRowLabel()) + " " + escape(s.getSeatNumber()) + "</div>"
        + "<div class=\"kind-" + t.getKind() + "\">" + t.getKind() + "</div>"
        + (t.getKindReason() == null ? "" : "<div>Reason: " + escape(t.getKindReason()) + "</div>")
        + "<div class=\"id\">Ticket ID: " + t.getId() + "</div>"
        + "</section>";
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
  }
}
