package com.buslink.service;

import com.buslink.dto.request.IssueTicketRequestDTO;
import com.buslink.dto.response.IssueTicketResponseDTO;
import com.buslink.dto.response.PendingTicketResponseDTO;
import com.buslink.dto.response.TicketDetailResponseDTO;
import java.util.List;
import java.util.UUID;

public interface TicketService {

    IssueTicketResponseDTO issueTicket(IssueTicketRequestDTO request, UUID conductorId, String idempotencyKey);

    List<PendingTicketResponseDTO> getPendingTickets(UUID conductorId);

    IssueTicketResponseDTO terminateTicket(UUID ticketId, UUID conductorId);

    List<TicketDetailResponseDTO> getPassengerTickets(UUID userId);

    TicketDetailResponseDTO getTicketById(UUID ticketId, UUID userId);
}
