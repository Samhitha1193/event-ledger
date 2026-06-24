package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(accountRepository, transactionRepository, new SimpleMeterRegistry());
    }

    @Test
    void process_duplicateEventId_returnsExistingWithoutCreatingNewOne() {
        Transaction existing = new Transaction();
        existing.setEventId("dup-evt");
        existing.setType(TransactionType.CREDIT);
        when(transactionRepository.findById("dup-evt")).thenReturn(Optional.of(existing));

        TransactionService.ProcessResult result = transactionService.process(
                "acct-1", buildRequest("dup-evt", TransactionType.CREDIT, "100.00", "USD"));

        assertThat(result.isNew()).isFalse();
        assertThat(result.transaction()).isSameAs(existing);
        assertThat(result.accountId()).isEqualTo("acct-1");
    }

    @Test
    void process_newAccount_autoCreatesAccountThenSavesTransaction() {
        when(transactionRepository.findById("new-evt")).thenReturn(Optional.empty());
        when(accountRepository.findById("new-acct")).thenReturn(Optional.empty());
        Account savedAccount = buildAccount("new-acct", "USD");
        when(accountRepository.save(any())).thenReturn(savedAccount);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionService.ProcessResult result = transactionService.process(
                "new-acct", buildRequest("new-evt", TransactionType.CREDIT, "200.00", "USD"));

        assertThat(result.isNew()).isTrue();
        assertThat(result.accountId()).isEqualTo("new-acct");
        assertThat(result.transaction().getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(result.transaction().getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void process_existingAccount_savesTransactionWithoutCreatingAccount() {
        when(transactionRepository.findById("exist-evt")).thenReturn(Optional.empty());
        Account existing = buildAccount("exist-acct", "EUR");
        when(accountRepository.findById("exist-acct")).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionService.ProcessResult result = transactionService.process(
                "exist-acct", buildRequest("exist-evt", TransactionType.DEBIT, "50.00", "EUR"));

        assertThat(result.isNew()).isTrue();
        assertThat(result.transaction().getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(result.transaction().getAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void process_currencyMismatch_throwsUnprocessableEntity() {
        when(transactionRepository.findById("mismatch-evt")).thenReturn(Optional.empty());
        Account existing = buildAccount("mismatch-acct", "USD");
        when(accountRepository.findById("mismatch-acct")).thenReturn(Optional.of(existing));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                transactionService.process("mismatch-acct",
                        buildRequest("mismatch-evt", TransactionType.CREDIT, "100.00", "EUR")));

        assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(ex.getReason()).contains("Currency mismatch");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Account buildAccount(String id, String currency) {
        Account a = new Account();
        a.setId(id);
        a.setCurrency(currency);
        return a;
    }

    private TransactionRequest buildRequest(String eventId, TransactionType type,
            String amount, String currency) {
        TransactionRequest req = new TransactionRequest();
        req.setEventId(eventId);
        req.setType(type);
        req.setAmount(new BigDecimal(amount));
        req.setCurrency(currency);
        req.setEventTimestamp(Instant.now());
        return req;
    }
}
