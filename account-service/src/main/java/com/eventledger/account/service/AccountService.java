package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionSummary;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final int RECENT_TRANSACTION_LIMIT = 10;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        Account account = findOrThrow(accountId);
        return new BalanceResponse(accountId, account.getCurrency(), computeBalance(accountId));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        Account account = findOrThrow(accountId);

        List<TransactionSummary> recent = transactionRepository
                .findByAccount_IdOrderByEventTimestampDesc(
                        accountId, PageRequest.of(0, RECENT_TRANSACTION_LIMIT))
                .stream()
                .map(t -> new TransactionSummary(
                        t.getEventId(), t.getType(), t.getAmount(),
                        t.getCurrency(), t.getEventTimestamp()))
                .toList();

        return new AccountResponse(accountId, account.getCurrency(), computeBalance(accountId), recent);
    }

    private BigDecimal computeBalance(String accountId) {
        BigDecimal credits = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.CREDIT);
        BigDecimal debits  = transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.DEBIT);
        BigDecimal balance = credits.subtract(debits);
        log.info("Balance computed accountId={} credits={} debits={} balance={}", accountId, credits, debits, balance);
        return balance;
    }

    private Account findOrThrow(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account not found: " + accountId));
    }
}
