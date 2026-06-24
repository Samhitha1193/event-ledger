package com.eventledger.gateway.dto;

import java.math.BigDecimal;

public record BalanceResponse(
        String accountId,
        String currency,
        BigDecimal balance
) {}
