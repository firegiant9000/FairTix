package com.fairtix.support.application;

import com.fairtix.support.domain.TicketCategory;
import com.fairtix.support.domain.TicketPriority;
import com.fairtix.support.domain.TicketStatus;
import com.fairtix.support.dto.AdminUpdateTicketRequest;
import com.fairtix.support.dto.SupportTicketResponse;
import com.fairtix.support.dto.TicketMessageResponse;
import com.fairtix.support.infrastructure.SupportTicketRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class SupportTicketServiceTest {

    @Autowired private SupportTicketService supportTicketService;
    @Autowired private UserRepository userRepository;
    @Autowired private SupportTicketRepository ticketRepository;

    private User user;
    private User admin;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("support_user_" + UUID.randomUUID() + "@test.com");
        user.setPassword("$2a$10$dummyhashfortest");
        user.setEmailVerified(true);
        user = userRepository.save(user);

        admin = new User();
        admin.setEmail("support_admin_" + UUID.randomUUID() + "@test.com");
        admin.setPassword("$2a$10$dummyhashfortest");
        admin.setEmailVerified(true);
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
    }

    @Test
    void createTicket_savesTicketAndInitialMessage() {
        SupportTicketResponse response = supportTicketService.createTicket(
                user.getId(), "Missing ticket", TicketCategory.ORDER_ISSUE,
                "I never received my ticket.", null, null);

        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(response.subject()).isEqualTo("Missing ticket");
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.messages()).hasSize(1);
        assertThat(response.messages().get(0).message()).isEqualTo("I never received my ticket.");
    }

    /** The subject is the user's own text: it must not reach the log (audit details are logged). */
    @Test
    void createTicket_subjectNeverLogged(CapturedOutput output) {
        supportTicketService.createTicket(user.getId(), "Call me at subject.private@example.com",
                TicketCategory.ORDER_ISSUE, "body", null, null);

        assertThat(output.getAll())
                .contains("SUPPORT_TICKET")
                .doesNotContain("subject.private@example.com");
    }

    @Test
    void createTicket_unknownUser_throwsIllegalArgument() {
        assertThatThrownBy(() -> supportTicketService.createTicket(
                UUID.randomUUID(), "Subject", TicketCategory.ACCOUNT,
                "message", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getUserTickets_returnsPaginatedResultsForOwner() {
        supportTicketService.createTicket(user.getId(), "Ticket A", TicketCategory.OTHER, "body", null, null);
        supportTicketService.createTicket(user.getId(), "Ticket B", TicketCategory.OTHER, "body", null, null);

        Page<SupportTicketResponse> page = supportTicketService.getUserTickets(user.getId(), 0);

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void getTicket_owner_succeeds() {
        SupportTicketResponse created = supportTicketService.createTicket(
                user.getId(), "Subject", TicketCategory.REFUND, "body", null, null);

        SupportTicketResponse fetched = supportTicketService.getTicket(
                created.id(), user.getId(), false);

        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.messages()).hasSize(1);
    }

    @Test
    void getTicket_nonOwnerNonAdmin_throwsAccessDenied() {
        SupportTicketResponse created = supportTicketService.createTicket(
                user.getId(), "Subject", TicketCategory.REFUND, "body", null, null);

        User other = new User();
        other.setEmail("other_" + UUID.randomUUID() + "@test.com");
        other.setPassword("$2a$10$dummyhashfortest");
        other = userRepository.save(other);
        UUID otherId = other.getId();
        UUID ticketId = created.id();

        assertThatThrownBy(() -> supportTicketService.getTicket(ticketId, otherId, false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getTicket_admin_canFetchAnyTicket() {
        SupportTicketResponse created = supportTicketService.createTicket(
                user.getId(), "Subject", TicketCategory.TECHNICAL, "body", null, null);

        SupportTicketResponse fetched = supportTicketService.getTicket(
                created.id(), admin.getId(), true);

        assertThat(fetched.id()).isEqualTo(created.id());
    }

    @Test
    void addMessage_adminReply_transitionsOpenToInProgress() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Help", TicketCategory.EVENT, "Please help.", null, null);

        supportTicketService.addMessage(ticket.id(), admin.getId(), "We're looking into it.");

        SupportTicketResponse updated = supportTicketService.getTicket(ticket.id(), admin.getId(), true);
        assertThat(updated.status()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void addMessage_userReply_whenWaitingOnUser_transitionsToInProgress() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Help", TicketCategory.ACCOUNT, "Please help.", null, null);

        // Admin sets status to WAITING_ON_USER
        supportTicketService.adminUpdateTicket(ticket.id(), admin.getId(),
                new AdminUpdateTicketRequest(TicketStatus.WAITING_ON_USER, null, null));

        // User replies
        supportTicketService.addMessage(ticket.id(), user.getId(), "Here is the info.");

        SupportTicketResponse updated = supportTicketService.getTicket(ticket.id(), admin.getId(), true);
        assertThat(updated.status()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void addMessage_closedTicket_throwsIllegalState() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Help", TicketCategory.OTHER, "body", null, null);
        supportTicketService.closeTicket(ticket.id(), user.getId());
        UUID ticketId = ticket.id();

        assertThatThrownBy(() -> supportTicketService.addMessage(ticketId, user.getId(), "reply"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void addMessage_nonOwnerNonAdmin_throwsAccessDenied() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Help", TicketCategory.OTHER, "body", null, null);

        User other = new User();
        other.setEmail("intruder_" + UUID.randomUUID() + "@test.com");
        other.setPassword("$2a$10$dummyhashfortest");
        other = userRepository.save(other);
        UUID otherId = other.getId();
        UUID ticketId = ticket.id();

        assertThatThrownBy(() -> supportTicketService.addMessage(ticketId, otherId, "intrusion"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void closeTicket_owner_setsClosedStatus() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Help", TicketCategory.ORDER_ISSUE, "body", null, null);

        SupportTicketResponse closed = supportTicketService.closeTicket(ticket.id(), user.getId());

        assertThat(closed.status()).isEqualTo(TicketStatus.CLOSED);
    }

    @Test
    void closeTicket_nonOwnerNonAdmin_throwsAccessDenied() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Help", TicketCategory.ORDER_ISSUE, "body", null, null);

        User other = new User();
        other.setEmail("intruder2_" + UUID.randomUUID() + "@test.com");
        other.setPassword("$2a$10$dummyhashfortest");
        other = userRepository.save(other);
        UUID otherId = other.getId();
        UUID ticketId = ticket.id();

        assertThatThrownBy(() -> supportTicketService.closeTicket(ticketId, otherId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminUpdateTicket_updatesStatusAndPriority() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Help", TicketCategory.TECHNICAL, "body", null, null);

        AdminUpdateTicketRequest request = new AdminUpdateTicketRequest(
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH, null);
        SupportTicketResponse updated = supportTicketService.adminUpdateTicket(
                ticket.id(), admin.getId(), request);

        assertThat(updated.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(updated.priority()).isEqualTo(TicketPriority.HIGH);
    }

    @Test
    void adminUpdateTicket_assignsTo() {
        SupportTicketResponse ticket = supportTicketService.createTicket(
                user.getId(), "Assign me", TicketCategory.OTHER, "body", null, null);

        AdminUpdateTicketRequest request = new AdminUpdateTicketRequest(null, null, admin.getId());
        SupportTicketResponse updated = supportTicketService.adminUpdateTicket(
                ticket.id(), admin.getId(), request);

        assertThat(updated.assignedTo()).isEqualTo(admin.getId());
    }

    @Test
    void getAdminTickets_filterByStatus_returnsMatchingOnly() {
        supportTicketService.createTicket(user.getId(), "Open one", TicketCategory.OTHER, "body", null, null);
        SupportTicketResponse ticket2 = supportTicketService.createTicket(
                user.getId(), "To close", TicketCategory.OTHER, "body", null, null);
        supportTicketService.closeTicket(ticket2.id(), user.getId());

        Page<SupportTicketResponse> openPage = supportTicketService.getAdminTickets(
                TicketStatus.OPEN, null, 0);

        assertThat(openPage.getContent())
                .allMatch(t -> t.status() == TicketStatus.OPEN);
    }
}
