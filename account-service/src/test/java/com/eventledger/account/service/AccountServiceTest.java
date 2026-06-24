package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionSummary;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks AccountService accountService;

    @Test
    void getBalance_existingAccount_returnsComputedBalance() {
        when(accountRepository.findById("acct-1")).thenReturn(Optional.of(buildAccount("acct-1", "USD")));
        when(transactionRepository.sumAmountByAccountIdAndType("acct-1", TransactionType.CREDIT))
                .thenReturn(new BigDecimal("500.00"));
        when(transactionRepository.sumAmountByAccountIdAndType("acct-1", TransactionType.DEBIT))
                .thenReturn(new BigDecimal("150.00"));

        BalanceResponse resp = accountService.getBalance("acct-1");

        assertThat(resp.accountId()).isEqualTo("acct-1");
        assertThat(resp.currency()).isEqualTo("USD");
        assertThat(resp.balance()).isEqualByComparingTo("350.00");
    }

    @Test
    void getBalance_unknownAccount_throwsNotFound() {
        when(accountRepository.findById("no-such")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> accountService.getBalance("no-such"));

        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void getAccount_existingAccount_returnsFullResponseWithTransactions() {
        Account account = buildAccount("acct-2", "EUR");
        Transaction tx = buildTransaction("evt-1", account, TransactionType.CREDIT,
                new BigDecimal("200.00"), Instant.parse("2024-06-01T10:00:00Z"));

        when(accountRepository.findById("acct-2")).thenReturn(Optional.of(account));
        when(transactionRepository.findByAccount_IdOrderByEventTimestampDesc(
                eq("acct-2"), any(PageRequest.class))).thenReturn(List.of(tx));
        when(transactionRepository.sumAmountByAccountIdAndType("acct-2", TransactionType.CREDIT))
                .thenReturn(new BigDecimal("200.00"));
        when(transactionRepository.sumAmountByAccountIdAndType("acct-2", TransactionType.DEBIT))
                .thenReturn(BigDecimal.ZERO);

        AccountResponse resp = accountService.getAccount("acct-2");

        assertThat(resp.accountId()).isEqualTo("acct-2");
        assertThat(resp.currency()).isEqualTo("EUR");
        assertThat(resp.balance()).isEqualByComparingTo("200.00");
        assertThat(resp.recentTransactions()).hasSize(1);
        TransactionSummary summary = resp.recentTransactions().get(0);
        assertThat(summary.eventId()).isEqualTo("evt-1");
        assertThat(summary.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(summary.amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void getAccount_unknownAccount_throwsNotFound() {
        when(accountRepository.findById("unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> accountService.getAccount("unknown"));

        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void getBalance_zeroTransactions_returnsZeroBalance() {
        when(accountRepository.findById("empty-acct")).thenReturn(Optional.of(buildAccount("empty-acct", "GBP")));
        when(transactionRepository.sumAmountByAccountIdAndType("empty-acct", TransactionType.CREDIT))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.sumAmountByAccountIdAndType("empty-acct", TransactionType.DEBIT))
                .thenReturn(BigDecimal.ZERO);

        BalanceResponse resp = accountService.getBalance("empty-acct");

        assertThat(resp.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Account buildAccount(String id, String currency) {
        Account a = new Account();
        a.setId(id);
        a.setCurrency(currency);
        return a;
    }

    private Transaction buildTransaction(String eventId, Account account,
            TransactionType type, BigDecimal amount, Instant timestamp) {
        Transaction t = new Transaction();
        t.setEventId(eventId);
        t.setAccount(account);
        t.setType(type);
        t.setAmount(amount);
        t.setCurrency(account.getCurrency());
        t.setEventTimestamp(timestamp);
        return t;
    }
}
