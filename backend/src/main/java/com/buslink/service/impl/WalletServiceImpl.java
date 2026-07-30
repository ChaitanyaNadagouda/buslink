package com.buslink.service.impl;

import com.buslink.config.WalletProperties;
import com.buslink.dto.request.WalletPaymentRequestDTO;
import com.buslink.dto.response.TransactionResponseDTO;
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
import com.buslink.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final TicketRepository ticketRepository;
    private final TransactionRepository transactionRepository;
    private final WalletProperties walletProperties;

    @Override
    @Transactional
    public WalletPaymentResponseDTO payViaWallet(WalletPaymentRequestDTO request, UUID userId) {
        Ticket ticket = ticketRepository
                .findByTicketIdAndUserId(request.ticketId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "ticketId", request.ticketId()));

        if (ticket.getStatus() != TicketStatus.ISSUED) {
            throw new ValidationException("Ticket is not awaiting payment");
        }

        Wallet wallet = walletRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", "userId", userId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new ValidationException("Wallet is not active");
        }

        BigDecimal overdraftLimit = walletProperties.overdraftLimit();
        BigDecimal effectiveBalance = wallet.getBalance().add(overdraftLimit);
        if (effectiveBalance.compareTo(ticket.getTotalFare()) < 0) {
            throw new ValidationException("Insufficient balance. Available: ₹" + effectiveBalance
                    + ", Required: ₹" + ticket.getTotalFare());
        }

        wallet.setBalance(wallet.getBalance().subtract(ticket.getTotalFare()));
        wallet = walletRepository.save(wallet);

        Transaction transaction = Transaction.builder()
                .userId(userId)
                .amount(ticket.getTotalFare())
                .type(TransactionType.DEBIT)
                .status(TransactionStatus.SUCCESS)
                .referenceId(ticket.getTicketId())
                .build();
        transactionRepository.save(transaction);

        Instant paidAt = Instant.now();
        ticket.setStatus(TicketStatus.PAID);
        ticket.setPaidAt(paidAt);
        ticket = ticketRepository.save(ticket);

        return new WalletPaymentResponseDTO(
                ticket.getTicketId(), ticket.getTotalFare(), wallet.getBalance(), ticket.getStatus(), paidAt);
    }

    @Override
    public WalletBalanceResponseDTO getWalletBalance(UUID userId) {
        Wallet wallet = walletRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", "userId", userId));
        return new WalletBalanceResponseDTO(wallet.getBalance(), wallet.getStatus(), wallet.getUpdatedAt());
    }

    @Override
    public List<TransactionResponseDTO> getTransactionHistory(UUID userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponseDTO)
                .toList();
    }

    private TransactionResponseDTO toResponseDTO(Transaction transaction) {
        return new TransactionResponseDTO(
                transaction.getTransactionId(),
                transaction.getAmount(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getReferenceId(),
                transaction.getCreatedAt());
    }
}
