package com.buslink.service.impl;

import com.buslink.dto.request.RechargeInitiateRequestDTO;
import com.buslink.dto.request.TicketUpiPaymentInitiateRequestDTO;
import com.buslink.dto.response.RechargeInitiateResponseDTO;
import com.buslink.dto.response.TicketUpiPaymentInitiateResponseDTO;
import com.buslink.entity.Payment;
import com.buslink.entity.Ticket;
import com.buslink.entity.Wallet;
import com.buslink.enums.PaymentMode;
import com.buslink.enums.PaymentPurpose;
import com.buslink.enums.PaymentStatus;
import com.buslink.enums.TicketStatus;
import com.buslink.enums.WalletStatus;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.gateway.GatewayOrder;
import com.buslink.gateway.PaymentGatewayPort;
import com.buslink.repository.PaymentRepository;
import com.buslink.repository.TicketRepository;
import com.buslink.repository.WalletRepository;
import com.buslink.service.PaymentService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final String CURRENCY_INR = "INR";

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final TicketRepository ticketRepository;
    private final PaymentGatewayPort paymentGatewayPort;

    @Override
    @Transactional
    public RechargeInitiateResponseDTO initiateRecharge(RechargeInitiateRequestDTO request, UUID userId) {
        Wallet wallet = walletRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", "userId", userId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ValidationException("Wallet is not active");
        }

        UUID paymentId = UUID.randomUUID();
        GatewayOrder order = paymentGatewayPort.createOrder(request.amount(), CURRENCY_INR, paymentId.toString());

        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .userId(userId)
                .amount(request.amount())
                .mode(PaymentMode.UPI)
                .purpose(PaymentPurpose.WALLET_TOPUP)
                .status(PaymentStatus.PENDING)
                .gatewayReferenceId(order.gatewayOrderId())
                .referenceId(null)
                .build();
        paymentRepository.save(payment);

        return new RechargeInitiateResponseDTO(
                paymentId, order.gatewayOrderId(), request.amount(), CURRENCY_INR, paymentGatewayPort.getPublicKeyId());
    }

    @Override
    @Transactional
    public TicketUpiPaymentInitiateResponseDTO initiateTicketUpiPayment(
            TicketUpiPaymentInitiateRequestDTO request, UUID userId) {
        Ticket ticket = ticketRepository
                .findByTicketIdAndUserId(request.ticketId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "ticketId", request.ticketId()));

        if (ticket.getStatus() != TicketStatus.ISSUED) {
            throw new ValidationException("Ticket is not awaiting payment");
        }

        Payment existingPendingPayment = paymentRepository
                .findByReferenceIdAndStatus(ticket.getTicketId(), PaymentStatus.PENDING)
                .orElse(null);
        if (existingPendingPayment != null) {
            return new TicketUpiPaymentInitiateResponseDTO(
                    existingPendingPayment.getPaymentId(),
                    existingPendingPayment.getGatewayReferenceId(),
                    existingPendingPayment.getAmount(),
                    CURRENCY_INR,
                    paymentGatewayPort.getPublicKeyId(),
                    ticket.getTicketId());
        }

        UUID paymentId = UUID.randomUUID();
        GatewayOrder order =
                paymentGatewayPort.createOrder(ticket.getTotalFare(), CURRENCY_INR, paymentId.toString());

        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .userId(userId)
                .amount(ticket.getTotalFare())
                .mode(PaymentMode.UPI)
                .purpose(PaymentPurpose.TICKET_PAYMENT)
                .status(PaymentStatus.PENDING)
                .gatewayReferenceId(order.gatewayOrderId())
                .referenceId(ticket.getTicketId())
                .build();
        paymentRepository.save(payment);

        return new TicketUpiPaymentInitiateResponseDTO(
                paymentId, order.gatewayOrderId(), ticket.getTotalFare(), CURRENCY_INR,
                paymentGatewayPort.getPublicKeyId(), ticket.getTicketId());
    }
}
