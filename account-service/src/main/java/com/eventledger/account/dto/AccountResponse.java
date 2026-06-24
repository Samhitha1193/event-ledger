package com.eventledger.account.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountResponse(
        String accountId,
        String currency,
        BigDecimal balance,
        List<TransactionSummary> recentTransactions
) {}
