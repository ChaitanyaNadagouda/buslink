package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.dto.request.RechargeInitiateRequestDTO;
import com.buslink.dto.request.TicketUpiPaymentInitiateRequestDTO;
import com.buslink.dto.response.RechargeInitiateResponseDTO;
import com.buslink.dto.response.TicketUpiPaymentInitiateResponseDTO;
import com.buslink.entity.Payment;
import com.buslink.entity.Ticket;
import com.buslink.entity.Wallet;
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
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private PaymentGatewayPort paymentGatewayPort;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private final UUID userId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();

    @Test
    void initiateRecharge_success() {
        RechargeInitiateRequestDTO request = new RechargeInitiateRequestDTO(new BigDecimal("200.00"));
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("50.00"))
                .status(WalletStatus.ACTIVE)
                .build();

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(paymentGatewayPort.createOrder(eq(request.amount()), eq("INR"), anyString()))
                .thenReturn(new GatewayOrder("order_recharge_123"));
        when(paymentGatewayPort.getPublicKeyId()).thenReturn("rzp_test_key");

        RechargeInitiateResponseDTO response = paymentService.initiateRecharge(request, userId);

        assertThat(response.razorpayOrderId()).isEqualTo("order_recharge_123");
        assertThat(response.amount()).isEqualByComparingTo("200.00");
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.razorpayKeyId()).isEqualTo("rzp_test_key");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment saved = captor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo(response.paymentId());
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAmount()).isEqualByComparingTo("200.00");
        assertThat(saved.getPurpose()).isEqualTo(PaymentPurpose.WALLET_TOPUP);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getGatewayReferenceId()).isEqualTo("order_recharge_123");
        assertThat(saved.getReferenceId()).isNull();
    }

    @Test
    void initiateRecharge_inactiveWallet() {
        RechargeInitiateRequestDTO request = new RechargeInitiateRequestDTO(new BigDecimal("200.00"));
        Wallet frozenWallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("50.00"))
                .status(WalletStatus.FROZEN)
                .build();

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(frozenWallet));

        assertThatThrownBy(() -> paymentService.initiateRecharge(request, userId))
                .isInstanceOf(ValidationException.class);

        verify(paymentGatewayPort, never()).createOrder(any(), any(), any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void initiateTicketUpiPayment_success() {
        TicketUpiPaymentInitiateRequestDTO request = new TicketUpiPaymentInitiateRequestDTO(ticketId);
        Ticket ticket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.ISSUED)
                .totalFare(new BigDecimal("90.00"))
                .build();

        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(paymentRepository.findByReferenceIdAndStatus(ticketId, PaymentStatus.PENDING))
                .thenReturn(Optional.empty());
        when(paymentGatewayPort.createOrder(eq(new BigDecimal("90.00")), eq("INR"), anyString()))
                .thenReturn(new GatewayOrder("order_ticket_456"));
        when(paymentGatewayPort.getPublicKeyId()).thenReturn("rzp_test_key");

        TicketUpiPaymentInitiateResponseDTO response = paymentService.initiateTicketUpiPayment(request, userId);

        assertThat(response.ticketId()).isEqualTo(ticketId);
        assertThat(response.razorpayOrderId()).isEqualTo("order_ticket_456");
        assertThat(response.amount()).isEqualByComparingTo("90.00");
        assertThat(response.razorpayKeyId()).isEqualTo("rzp_test_key");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment saved = captor.getValue();
        assertThat(saved.getPurpose()).isEqualTo(PaymentPurpose.TICKET_PAYMENT);
        assertThat(saved.getReferenceId()).isEqualTo(ticketId);
        assertThat(saved.getGatewayReferenceId()).isEqualTo("order_ticket_456");
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void initiateTicketUpiPayment_ticketNotFound() {
        TicketUpiPaymentInitiateRequestDTO request = new TicketUpiPaymentInitiateRequestDTO(ticketId);
        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.initiateTicketUpiPayment(request, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void initiateTicketUpiPayment_ticketAlreadyPaid() {
        TicketUpiPaymentInitiateRequestDTO request = new TicketUpiPaymentInitiateRequestDTO(ticketId);
        Ticket paidTicket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.PAID)
                .totalFare(new BigDecimal("90.00"))
                .build();

        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(paidTicket));

        assertThatThrownBy(() -> paymentService.initiateTicketUpiPayment(request, userId))
                .isInstanceOf(ValidationException.class);

        verify(paymentGatewayPort, never()).createOrder(any(), any(), any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void initiateTicketUpiPayment_existingPendingPayment() {
        TicketUpiPaymentInitiateRequestDTO request = new TicketUpiPaymentInitiateRequestDTO(ticketId);
        Ticket ticket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.ISSUED)
                .totalFare(new BigDecimal("90.00"))
                .build();
        Payment existingPending = Payment.builder()
                .paymentId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("90.00"))
                .purpose(PaymentPurpose.TICKET_PAYMENT)
                .status(PaymentStatus.PENDING)
                .gatewayReferenceId("order_existing_789")
                .referenceId(ticketId)
                .build();

        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(paymentRepository.findByReferenceIdAndStatus(ticketId, PaymentStatus.PENDING))
                .thenReturn(Optional.of(existingPending));
        when(paymentGatewayPort.getPublicKeyId()).thenReturn("rzp_test_key");

        TicketUpiPaymentInitiateResponseDTO response = paymentService.initiateTicketUpiPayment(request, userId);

        assertThat(response.paymentId()).isEqualTo(existingPending.getPaymentId());
        assertThat(response.razorpayOrderId()).isEqualTo("order_existing_789");
        assertThat(response.amount()).isEqualByComparingTo("90.00");

        verify(paymentGatewayPort, never()).createOrder(any(), any(), any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
