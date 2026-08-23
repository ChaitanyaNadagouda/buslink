package com.buslink.service.impl;

import com.buslink.dto.response.RechargeConfirmResponseDTO;
import com.buslink.entity.Payment;
import com.buslink.entity.Ticket;
import com.buslink.entity.Transaction;
import com.buslink.entity.Wallet;
import com.buslink.enums.PaymentPurpose;
import com.buslink.enums.PaymentStatus;
import com.buslink.enums.TicketStatus;
import com.buslink.enums.TransactionStatus;
import com.buslink.enums.TransactionType;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.gateway.GatewayEventType;
import com.buslink.gateway.GatewayWebhookEvent;
import com.buslink.gateway.PaymentGatewayPort;
import com.buslink.repository.PaymentRepository;
import com.buslink.repository.TicketRepository;
import com.buslink.repository.TransactionRepository;
import com.buslink.repository.WalletRepository;
import com.buslink.service.WebhookService;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final TicketRepository ticketRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentGatewayPort paymentGatewayPort;

    @Override
    @Transactional
    public void handleRazorpayWebhook(String rawPayload, String signatureHeader) {
        if (!paymentGatewayPort.verifyWebhookSignature(rawPayload, signatureHeader)) {
            throw new ValidationException("Invalid webhook signature");
        }

        GatewayWebhookEvent event = paymentGatewayPort.parseWebhookEvent(rawPayload);

        Payment payment = paymentRepository
                .findByGatewayReferenceId(event.gatewayOrderId())
                .orElseThrow(
                        () -> new ResourceNotFoundException("Payment", "gatewayReferenceId", event.gatewayOrderId()));

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Webhook for payment {} already succeeded, skipping", payment.getPaymentId());
            return;
        }

        if (event.type() == GatewayEventType.SUCCESS) {
            if (payment.getPurpose() == PaymentPurpose.WALLET_TOPUP) {
                RechargeConfirmResponseDTO confirmation = processRecharge(payment);
                log.info("Wallet recharge confirmed: {}", confirmation);
            } else {
                processTicketPayment(payment);
            }
            payment.setStatus(PaymentStatus.SUCCESS);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            log.info(
                    "Payment {} failed — would notify userId={}: \"Payment failed — please try again\"",
                    payment.getPaymentId(),
                    payment.getUserId());
        }
        paymentRepository.save(payment);
    }

    private RechargeConfirmResponseDTO processRecharge(Payment payment) {
        Wallet wallet = walletRepository
                .findByUserId(payment.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", "userId", payment.getUserId()));

        BigDecimal currentBalance = wallet.getBalance();
        BigDecimal overdraftRecovered = BigDecimal.ZERO;

        if (currentBalance.compareTo(BigDecimal.ZERO) < 0) {
            overdraftRecovered = currentBalance.abs();

            Transaction recovery = Transaction.builder()
                    .userId(payment.getUserId())
                    .amount(overdraftRecovered)
                    .type(TransactionType.DEBIT)
                    .status(TransactionStatus.SUCCESS)
                    .referenceId(payment.getPaymentId())
                    .build();
            transactionRepository.save(recovery);
        }

        wallet.setBalance(currentBalance.add(payment.getAmount()));
        wallet = walletRepository.save(wallet);

        Transaction credit = Transaction.builder()
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .type(TransactionType.CREDIT)
                .status(TransactionStatus.SUCCESS)
                .referenceId(payment.getPaymentId())
                .build();
        transactionRepository.save(credit);

        return new RechargeConfirmResponseDTO(
                payment.getPaymentId(),
                payment.getAmount(),
                wallet.getBalance(),
                overdraftRecovered,
                PaymentStatus.SUCCESS);
    }

    private void processTicketPayment(Payment payment) {
        Ticket ticket = ticketRepository
                .findById(payment.getReferenceId())
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "ticketId", payment.getReferenceId()));

        if (ticket.getStatus() != TicketStatus.ISSUED) {
            throw new ValidationException("Ticket is not awaiting payment");
        }

        ticket.setStatus(TicketStatus.PAID);
        ticket.setPaidAt(Instant.now());
        ticketRepository.save(ticket);
    }
}
