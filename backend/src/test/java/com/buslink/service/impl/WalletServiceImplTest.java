package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.config.WalletProperties;
import com.buslink.dto.request.WalletPaymentRequestDTO;
import com.buslink.dto.response.WalletBalanceResponseDTO;
import com.buslink.dto.response.WalletPaymentResponseDTO;
import com.buslink.entity.Ticket;
import com.buslink.entity.Transaction;
import com.buslink.entity.Wallet;
import com.buslink.enums.TicketStatus;
import com.buslink.enums.TransactionStatus;
import com.buslink.enums.TransactionType;
import com.buslink.enums.WalletStatus;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.TicketRepository;
import com.buslink.repository.TransactionRepository;
import com.buslink.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private WalletProperties walletProperties;

    @InjectMocks
    private WalletServiceImpl walletService;

    private final UUID userId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final WalletPaymentRequestDTO request = new WalletPaymentRequestDTO(ticketId);

    @Test
    void payViaWallet_success_sufficientBalance() {
        Ticket ticket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.ISSUED)
                .totalFare(new BigDecimal("75.00"))
                .build();
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("150.00"))
                .status(WalletStatus.ACTIVE)
                .build();

        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));
        when(walletRepository.save(wallet)).thenReturn(wallet);
        when(ticketRepository.save(ticket)).thenReturn(ticket);

        WalletPaymentResponseDTO response = walletService.payViaWallet(request, userId);

        assertThat(response.ticketId()).isEqualTo(ticketId);
        assertThat(response.amountDeducted()).isEqualByComparingTo("75.00");
        assertThat(response.walletBalanceAfter()).isEqualByComparingTo("75.00");
        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.PAID);
        assertThat(ticket.getPaidAt()).isNotNull();

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        Transaction transaction = txCaptor.getValue();
        assertThat(transaction.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(transaction.getAmount()).isEqualByComparingTo("75.00");
        assertThat(transaction.getReferenceId()).isEqualTo(ticketId);
    }

    @Test
    void payViaWallet_success_overdraftAllowed() {
        Ticket ticket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.ISSUED)
                .totalFare(new BigDecimal("90.00"))
                .build();
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("20.00"))
                .status(WalletStatus.ACTIVE)
                .build();

        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));
        when(walletRepository.save(wallet)).thenReturn(wallet);
        when(ticketRepository.save(ticket)).thenReturn(ticket);

        WalletPaymentResponseDTO response = walletService.payViaWallet(request, userId);

        assertThat(response.walletBalanceAfter()).isEqualByComparingTo("-70.00");
        assertThat(response.ticketStatus()).isEqualTo(TicketStatus.PAID);
    }

    @Test
    void payViaWallet_rejected_exceedsOverdraft() {
        Ticket ticket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.ISSUED)
                .totalFare(new BigDecimal("110.00"))
                .build();
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("0.00"))
                .status(WalletStatus.ACTIVE)
                .build();

        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));

        assertThatThrownBy(() -> walletService.payViaWallet(request, userId))
                .isInstanceOf(ValidationException.class);

        verify(walletRepository, never()).save(any(Wallet.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    @Test
    void payViaWallet_ticketNotFound() {
        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.payViaWallet(request, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void payViaWallet_ticketAlreadyPaid() {
        Ticket paidTicket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.PAID)
                .totalFare(new BigDecimal("75.00"))
                .build();
        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(paidTicket));

        assertThatThrownBy(() -> walletService.payViaWallet(request, userId))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void payViaWallet_walletInactive() {
        Ticket ticket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .status(TicketStatus.ISSUED)
                .totalFare(new BigDecimal("75.00"))
                .build();
        Wallet frozenWallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("150.00"))
                .status(WalletStatus.FROZEN)
                .build();

        when(ticketRepository.findByTicketIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(frozenWallet));

        assertThatThrownBy(() -> walletService.payViaWallet(request, userId))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getWalletBalance_success() {
        Instant updatedAt = Instant.now();
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(new BigDecimal("50.00"))
                .status(WalletStatus.ACTIVE)
                .build();
        wallet.setUpdatedAt(updatedAt);

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        WalletBalanceResponseDTO response = walletService.getWalletBalance(userId);

        assertThat(response.balance()).isEqualByComparingTo("50.00");
        assertThat(response.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(response.lastUpdated()).isEqualTo(updatedAt);
    }
}
