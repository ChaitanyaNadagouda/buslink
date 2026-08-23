package com.buslink.service;

import com.buslink.dto.request.RechargeInitiateRequestDTO;
import com.buslink.dto.request.TicketUpiPaymentInitiateRequestDTO;
import com.buslink.dto.response.RechargeInitiateResponseDTO;
import com.buslink.dto.response.TicketUpiPaymentInitiateResponseDTO;
import java.util.UUID;

public interface PaymentService {

    RechargeInitiateResponseDTO initiateRecharge(RechargeInitiateRequestDTO request, UUID userId);

    TicketUpiPaymentInitiateResponseDTO initiateTicketUpiPayment(
            TicketUpiPaymentInitiateRequestDTO request, UUID userId);
}
