package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public record ProcessResult(boolean isNew, Transaction transaction, String accountId) {}

    @Transactional
    public ProcessResult process(String accountId, TransactionRequest req) {
        Transaction existing = transactionRepository.findById(req.getEventId()).orElse(null);
        if (existing != null) {
            log.info("Duplicate event ignored eventId={} accountId={}", req.getEventId(), accountId);
            return new ProcessResult(false, existing, accountId);
        }

        Account account = accountRepository.findById(accountId).orElseGet(() -> {
            Account newAccount = new Account();
            newAccount.setId(accountId);
            newAccount.setCurrency(req.getCurrency());
            return accountRepository.save(newAccount);
        });

        if (!account.getCurrency().equals(req.getCurrency())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Currency mismatch: account uses " + account.getCurrency()
                            + " but request has " + req.getCurrency());
        }

        Transaction tx = new Transaction();
        tx.setEventId(req.getEventId());
        tx.setAccount(account);
        tx.setType(req.getType());
        tx.setAmount(req.getAmount());
        tx.setCurrency(req.getCurrency());
        tx.setEventTimestamp(req.getEventTimestamp());
        transactionRepository.save(tx);
        log.info("Transaction saved eventId={} accountId={} type={} amount={} currency={}",
                req.getEventId(), accountId, req.getType(), req.getAmount(), req.getCurrency());
        return new ProcessResult(true, tx, accountId);
    }
}
