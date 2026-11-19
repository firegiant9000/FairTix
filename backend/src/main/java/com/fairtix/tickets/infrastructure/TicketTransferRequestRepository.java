package com.fairtix.tickets.infrastructure;

import com.fairtix.tickets.domain.TicketTransferRequest;
import com.fairtix.tickets.domain.TransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketTransferRequestRepository extends JpaRepository<TicketTransferRequest, UUID> {

  List<TicketTransferRequest> findByToUser_IdAndStatus(UUID userId, TransferStatus status);

  List<TicketTransferRequest> findByFromUser_Id(UUID userId);

  Optional<TicketTransferRequest> findByTicket_IdAndStatus(UUID ticketId, TransferStatus status);

  List<TicketTransferRequest> findByTicket_IdInAndStatus(java.util.Collection<UUID> ticketIds,
      TransferStatus status);

  List<TicketTransferRequest> findByStatusAndExpiresAtBefore(TransferStatus status, Instant now);
}
