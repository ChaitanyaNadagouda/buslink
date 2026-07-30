package com.buslink.service;

import com.buslink.dto.request.WalletPaymentRequestDTO;
import com.buslink.dto.response.TransactionResponseDTO;
import com.buslink.dto.response.WalletBalanceResponseDTO;
import com.buslink.dto.response.WalletPaymentResponseDTO;
import java.util.List;
import java.util.UUID;

public interface WalletService {

    WalletPaymentResponseDTO payViaWallet(WalletPaymentRequestDTO request, UUID userId);

    WalletBalanceResponseDTO getWalletBalance(UUID userId);

    List<TransactionResponseDTO> getTransactionHistory(UUID userId);
}
