package com.eventledger.gateway.dto;

import java.math.BigDecimal;

public record BalanceResponse(
        Long accountId,
        String currency,
        BigDecimal balance
) {}
