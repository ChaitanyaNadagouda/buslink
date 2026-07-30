package com.buslink.controller;

import com.buslink.dto.request.IssueTicketRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.IssueTicketResponseDTO;
import com.buslink.dto.response.PendingTicketResponseDTO;
import com.buslink.exception.ValidationException;
import com.buslink.security.ConductorPrincipal;
import com.buslink.service.TicketService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping("/tickets/issue")
    public ApiResponse<IssueTicketResponseDTO> issueTicket(
            @Valid @RequestBody IssueTicketRequestDTO request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal ConductorPrincipal principal) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ValidationException("X-Idempotency-Key header is required");
        }
        return ApiResponse.success(
                ticketService.issueTicket(request, principal.getConductor().getConductorId(), idempotencyKey));
    }

    @GetMapping("/conductor/tickets/pending")
    public ApiResponse<List<PendingTicketResponseDTO>> getPendingTickets(
            @AuthenticationPrincipal ConductorPrincipal principal) {
        return ApiResponse.success(ticketService.getPendingTickets(principal.getConductor().getConductorId()));
    }

    @PutMapping("/tickets/{ticketId}/terminate")
    public ApiResponse<IssueTicketResponseDTO> terminateTicket(
            @PathVariable UUID ticketId, @AuthenticationPrincipal ConductorPrincipal principal) {
        return ApiResponse.success(
                ticketService.terminateTicket(ticketId, principal.getConductor().getConductorId()));
    }
}
