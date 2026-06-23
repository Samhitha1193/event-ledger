package com.eventledger.account.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountResponse(
        Long accountId,
        String currency,
        BigDecimal balance,
        List<TransactionSummary> recentTransactions
) {}
