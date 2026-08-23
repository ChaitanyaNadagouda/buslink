package com.buslink.controller;

import com.buslink.dto.request.RechargeInitiateRequestDTO;
import com.buslink.dto.request.TicketUpiPaymentInitiateRequestDTO;
import com.buslink.dto.request.WalletPaymentRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.RechargeInitiateResponseDTO;
import com.buslink.dto.response.TicketUpiPaymentInitiateResponseDTO;
import com.buslink.dto.response.WalletPaymentResponseDTO;
import com.buslink.security.UserPrincipal;
import com.buslink.service.PaymentService;
import com.buslink.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final WalletService walletService;
    private final PaymentService paymentService;

    @PostMapping("/wallet")
    public ApiResponse<WalletPaymentResponseDTO> payViaWallet(
            @Valid @RequestBody WalletPaymentRequestDTO request, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(walletService.payViaWallet(request, principal.getUser().getUserId()));
    }

    @PostMapping("/recharge/initiate")
    public ApiResponse<RechargeInitiateResponseDTO> initiateRecharge(
            @Valid @RequestBody RechargeInitiateRequestDTO request, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(paymentService.initiateRecharge(request, principal.getUser().getUserId()));
    }

    @PostMapping("/ticket/upi/initiate")
    public ApiResponse<TicketUpiPaymentInitiateResponseDTO> initiateTicketUpiPayment(
            @Valid @RequestBody TicketUpiPaymentInitiateRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(
                paymentService.initiateTicketUpiPayment(request, principal.getUser().getUserId()));
    }
}
