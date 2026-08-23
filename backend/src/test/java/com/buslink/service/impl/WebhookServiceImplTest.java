package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buslink.entity.Payment;
import com.buslink.entity.Ticket;
import com.buslink.entity.Transaction;
import com.buslink.entity.Wallet;
import com.buslink.enums.PaymentPurpose;
import com.buslink.enums.PaymentStatus;
import com.buslink.enums.TicketStatus;
import com.buslink.enums.TransactionType;
import com.buslink.exception.ValidationException;
import com.buslink.gateway.GatewayEventType;
import com.buslink.gateway.GatewayWebhookEvent;
import com.buslink.gateway.PaymentGatewayPort;
import com.buslink.repository.PaymentRepository;
import com.buslink.repository.TicketRepository;
import com.buslink.repository.TransactionRepository;
import com.buslink.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PaymentGatewayPort paymentGatewayPort;

    @InjectMocks
    private WebhookServiceImpl webhookService;

    private final UUID userId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final String rawPayload = "{\"event\":\"payment.captured\"}";
    private final String signature = "sig";

    @Test
    void handleWebhook_recharge_success_noOverdraft() {
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("200.00"))
                .purpose(PaymentPurpose.WALLET_TOPUP)
                .status(PaymentStatus.PENDING)
                .gatewayReferenceId("order_123")
                .build();
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("50.00"))
                .build();

        when(paymentGatewayPort.verifyWebhookSignature(rawPayload, signature)).thenReturn(true);
        when(paymentGatewayPort.parseWebhookEvent(rawPayload))
                .thenReturn(new GatewayWebhookEvent(GatewayEventType.SUCCESS, "order_123"));
        when(paymentRepository.findByGatewayReferenceId("order_123")).thenReturn(Optional.of(payment));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(wallet)).thenReturn(wallet);

        webhookService.handleRazorpayWebhook(rawPayload, signature);

        assertThat(wallet.getBalance()).isEqualByComparingTo("250.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(txCaptor.capture());
        Transaction credit = txCaptor.getValue();
        assertThat(credit.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(credit.getAmount()).isEqualByComparingTo("200.00");
        assertThat(credit.getReferenceId()).isEqualTo(payment.getPaymentId());

        verify(paymentRepository).save(payment);
    }

    @Test
    void handleWebhook_recharge_success_withOverdraft() {
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("200.00"))
                .purpose(PaymentPurpose.WALLET_TOPUP)
                .status(PaymentStatus.PENDING)
                .gatewayReferenceId("order_123")
                .build();
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("-40.00"))
                .build();

        when(paymentGatewayPort.verifyWebhookSignature(rawPayload, signature)).thenReturn(true);
        when(paymentGatewayPort.parseWebhookEvent(rawPayload))
                .thenReturn(new GatewayWebhookEvent(GatewayEventType.SUCCESS, "order_123"));
        when(paymentRepository.findByGatewayReferenceId("order_123")).thenReturn(Optional.of(payment));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(wallet)).thenReturn(wallet);

        webhookService.handleRazorpayWebhook(rawPayload, signature);

        assertThat(wallet.getBalance()).isEqualByComparingTo("160.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(txCaptor.capture());
        List<Transaction> saved = txCaptor.getAllValues();

        Transaction recovery = saved.get(0);
        assertThat(recovery.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(recovery.getAmount()).isEqualByComparingTo("40.00");
        assertThat(recovery.getReferenceId()).isEqualTo(payment.getPaymentId());

        Transaction credit = saved.get(1);
        assertThat(credit.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(credit.getAmount()).isEqualByComparingTo("200.00");
        assertThat(credit.getReferenceId()).isEqualTo(payment.getPaymentId());
    }

    @Test
    void handleWebhook_ticketPayment_success() {
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("90.00"))
                .purpose(PaymentPurpose.TICKET_PAYMENT)
                .status(PaymentStatus.PENDING)
                .gatewayReferenceId("order_456")
                .referenceId(ticketId)
                .build();
        Ticket ticket =
                Ticket.builder().ticketId(ticketId).status(TicketStatus.ISSUED).build();

        when(paymentGatewayPort.verifyWebhookSignature(rawPayload, signature)).thenReturn(true);
        when(paymentGatewayPort.parseWebhookEvent(rawPayload))
                .thenReturn(new GatewayWebhookEvent(GatewayEventType.SUCCESS, "order_456"));
        when(paymentRepository.findByGatewayReferenceId("order_456")).thenReturn(Optional.of(payment));
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        webhookService.handleRazorpayWebhook(rawPayload, signature);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.PAID);
        assertThat(ticket.getPaidAt()).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        verify(ticketRepository).save(ticket);
        verifyNoInteractions(walletRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void handleWebhook_invalidSignature() {
        when(paymentGatewayPort.verifyWebhookSignature(rawPayload, signature)).thenReturn(false);

        assertThatThrownBy(() -> webhookService.handleRazorpayWebhook(rawPayload, signature))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(walletRepository);
        verifyNoInteractions(ticketRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void handleWebhook_alreadyProcessed() {
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("200.00"))
                .purpose(PaymentPurpose.WALLET_TOPUP)
                .status(PaymentStatus.SUCCESS)
                .gatewayReferenceId("order_123")
                .build();

        when(paymentGatewayPort.verifyWebhookSignature(rawPayload, signature)).thenReturn(true);
        when(paymentGatewayPort.parseWebhookEvent(rawPayload))
                .thenReturn(new GatewayWebhookEvent(GatewayEventType.SUCCESS, "order_123"));
        when(paymentRepository.findByGatewayReferenceId("order_123")).thenReturn(Optional.of(payment));

        webhookService.handleRazorpayWebhook(rawPayload, signature);

        verify(paymentRepository, never()).save(any(Payment.class));
        verifyNoInteractions(walletRepository);
        verifyNoInteractions(ticketRepository);
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void handleWebhook_recharge_success_afterPriorFailedAttempt() {
        // Razorpay allows multiple payment attempts against the same order — an earlier
        // declined attempt already marked this payment FAILED before a later attempt
        // on the same order succeeded. FAILED must not be treated as terminal.
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("200.00"))
                .purpose(PaymentPurpose.WALLET_TOPUP)
                .status(PaymentStatus.FAILED)
                .gatewayReferenceId("order_123")
                .build();
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("50.00"))
                .build();

        when(paymentGatewayPort.verifyWebhookSignature(rawPayload, signature)).thenReturn(true);
        when(paymentGatewayPort.parseWebhookEvent(rawPayload))
                .thenReturn(new GatewayWebhookEvent(GatewayEventType.SUCCESS, "order_123"));
        when(paymentRepository.findByGatewayReferenceId("order_123")).thenReturn(Optional.of(payment));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(wallet)).thenReturn(wallet);

        webhookService.handleRazorpayWebhook(rawPayload, signature);

        assertThat(wallet.getBalance()).isEqualByComparingTo("250.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(paymentRepository).save(payment);
    }

    @Test
    void handleWebhook_paymentFailed() {
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("90.00"))
                .purpose(PaymentPurpose.TICKET_PAYMENT)
                .status(PaymentStatus.PENDING)
                .gatewayReferenceId("order_456")
                .referenceId(ticketId)
                .build();

        when(paymentGatewayPort.verifyWebhookSignature(rawPayload, signature)).thenReturn(true);
        when(paymentGatewayPort.parseWebhookEvent(rawPayload))
                .thenReturn(new GatewayWebhookEvent(GatewayEventType.FAILED, "order_456"));
        when(paymentRepository.findByGatewayReferenceId("order_456")).thenReturn(Optional.of(payment));

        webhookService.handleRazorpayWebhook(rawPayload, signature);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentRepository).save(payment);
        verifyNoInteractions(ticketRepository);
        verifyNoInteractions(walletRepository);
        verifyNoInteractions(transactionRepository);
    }
}
