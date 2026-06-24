package com.eventledger.account.dto;

import java.math.BigDecimal;

public record BalanceResponse(
        String accountId,
        String currency,
        BigDecimal balance
) {}
