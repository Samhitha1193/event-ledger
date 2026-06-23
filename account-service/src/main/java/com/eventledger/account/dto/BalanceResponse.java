package com.eventledger.account.dto;

import java.math.BigDecimal;

public record BalanceResponse(
        Long accountId,
        String currency,
        BigDecimal balance
) {}
