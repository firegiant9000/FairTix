package com.fairtix.holds.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.holds.application.CompService;
import com.fairtix.holds.dto.CompTicketResponse;
import com.fairtix.holds.dto.IssueCompRequest;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketKind;
import com.fairtix.tickets.infrastructure.TicketRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Comps", description = "Issue and list complimentary tickets")
@RestController
@RequestMapping("/api/organizer/comps")
@PreAuthorize("isAuthenticated()")
public class CompController {

  private final CompService compService;
  private final TicketRepository ticketRepository;

  public CompController(CompService compService, TicketRepository ticketRepository) {
    this.compService = compService;
    this.ticketRepository = ticketRepository;
  }

  @Operation(summary = "Issue comp ticket(s) for an event")
  @OrgScoped(OrgPermission.COMPS_WRITE)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public List<CompTicketResponse> issue(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @Valid @RequestBody IssueCompRequest request) {
    List<Ticket> issued = compService.issueComp(request, principal.getUserId());
    return issued.stream().map(CompTicketResponse::from).toList();
  }

  @Operation(summary = "List comps issued for an event")
  @OrgScoped(OrgPermission.COMPS_WRITE)
  @GetMapping
  public List<CompTicketResponse> list(@RequestParam UUID eventId) {
    return ticketRepository.findAllByEvent_IdAndKindOrderByIssuedAtDesc(eventId, TicketKind.COMP)
        .stream().map(CompTicketResponse::from).toList();
  }
}
