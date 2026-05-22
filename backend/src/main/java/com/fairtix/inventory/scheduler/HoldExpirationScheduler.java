package com.fairtix.inventory.scheduler;

import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Periodically scans for active holds whose expiry has passed and marks them
 * EXPIRED, returning the corresponding seats to AVAILABLE.
 *
 * <p><b>Paging strategy:</b> each scheduler run processes at most
 * {@value #PAGE_SIZE} holds in a single transaction. Any remainder is handled
 * on the next scheduled tick ({@code holds.cleanup.interval-ms}, default 30 s).
 * This bounds both memory usage and transaction duration without requiring
 * self-invocation tricks or additional Spring beans.
 *
 * <p><b>Trade-off:</b> using one transaction per run means a failure
 * mid-batch leaves no partially-expired state — either the full page commits
 * or none of it does. If >500 holds expire between ticks they will be cleaned
 * over multiple consecutive runs; this is acceptable for the current scale.
 */
@Component
public class HoldExpirationScheduler {

  private static final Logger log = LoggerFactory.getLogger(HoldExpirationScheduler.class);
  private static final int PAGE_SIZE = 500;

  private final SeatHoldRepository seatHoldRepository;
  private final SeatRepository seatRepository;
  private final UserRepository userRepository;
  private final NotificationGate notificationGate;
  private final EmailTemplateService emailTemplateService;

  public HoldExpirationScheduler(SeatHoldRepository seatHoldRepository,
      SeatRepository seatRepository,
      UserRepository userRepository,
      NotificationGate notificationGate,
      EmailTemplateService emailTemplateService) {
    this.seatHoldRepository = seatHoldRepository;
    this.seatRepository = seatRepository;
    this.userRepository = userRepository;
    this.notificationGate = notificationGate;
    this.emailTemplateService = emailTemplateService;
  }

  @Scheduled(fixedDelayString = "${holds.cleanup.interval-ms:30000}")
  @Transactional
  public void expireHolds() {
    MDC.put("requestId", "sched-expireHolds-" + UUID.randomUUID());
    try {
      // Always query page 0: after mutation the ACTIVE items disappear from the
      // result set, so a subsequent run naturally picks up the next batch.
      Page<SeatHold> batch = seatHoldRepository.findAllByStatusAndExpiresAtBefore(
          HoldStatus.ACTIVE, Instant.now(), PageRequest.of(0, PAGE_SIZE));

      if (batch.isEmpty()) {
        return;
      }

      List<Seat> seatsToRelease = new ArrayList<>();
      List<SeatHold> expiredHolds = new ArrayList<>();
      for (SeatHold hold : batch.getContent()) {
        hold.setStatus(HoldStatus.EXPIRED);
        expiredHolds.add(hold);
        Seat seat = hold.getSeat();
        if (seat.getStatus() == SeatStatus.HELD) {
          seat.setStatus(SeatStatus.AVAILABLE);
          seatsToRelease.add(seat);
        }
      }

      seatHoldRepository.saveAll(expiredHolds);
      seatRepository.saveAll(seatsToRelease);

      List<SeatHold> toEmail = List.copyOf(expiredHolds);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          for (SeatHold hold : toEmail) {
            sendHoldExpiryEmail(hold);
          }
        }
      });
    } finally {
      MDC.remove("requestId");
    }
  }

  private void sendHoldExpiryEmail(SeatHold hold) {
    try {
      Optional<User> userOpt = userRepository.findById(hold.getOwnerId());
      if (userOpt.isEmpty()) return;

      User user = userOpt.get();
      Seat seat = hold.getSeat();
      if (seat == null || seat.getEvent() == null) {
        log.warn("Seat or event missing for hold {}; skipping expiry email", hold.getId());
        return;
      }
      String seatLine = seat.getSection() + " / Row " + seat.getRowLabel() + " / Seat " + seat.getSeatNumber();
      String eventTitle = seat.getEvent().getTitle();

      String body = emailTemplateService.buildHoldExpiryEmail(
          user.getEmail(), eventTitle, List.of(seatLine), hold.getId().toString());
      notificationGate.sendEmail(user.getId(), NotificationCategory.HOLD_EXPIRING,
          user.getEmail(), "Your held seat has been released — " + eventTitle, body);
    } catch (Exception ex) {
      log.warn("Failed to send hold expiry email for hold {}: {}", hold.getId(), ex.getMessage());
    }
  }
}
