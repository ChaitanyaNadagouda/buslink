package com.buslink.controller;

import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.TicketDetailResponseDTO;
import com.buslink.dto.response.TransactionResponseDTO;
import com.buslink.dto.response.WalletBalanceResponseDTO;
import com.buslink.security.UserPrincipal;
import com.buslink.service.TicketService;
import com.buslink.service.WalletService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/passenger")
@RequiredArgsConstructor
public class PassengerController {

    private final TicketService ticketService;
    private final WalletService walletService;

    @GetMapping("/tickets")
    public ApiResponse<List<TicketDetailResponseDTO>> getTickets(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(ticketService.getPassengerTickets(principal.getUser().getUserId()));
    }

    @GetMapping("/tickets/{ticketId}")
    public ApiResponse<TicketDetailResponseDTO> getTicketById(
            @PathVariable UUID ticketId, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(ticketService.getTicketById(ticketId, principal.getUser().getUserId()));
    }

    @GetMapping("/wallet/balance")
    public ApiResponse<WalletBalanceResponseDTO> getWalletBalance(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(walletService.getWalletBalance(principal.getUser().getUserId()));
    }

    @GetMapping("/wallet/transactions")
    public ApiResponse<List<TransactionResponseDTO>> getTransactionHistory(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(walletService.getTransactionHistory(principal.getUser().getUserId()));
    }
}
