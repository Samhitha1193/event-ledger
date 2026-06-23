package com.eventledger.account.service;

import com.eventledger.account.domain.Account;
import com.eventledger.account.domain.Transaction;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Returns true if the transaction was newly saved, false if it was a duplicate.
     */
    @Transactional
    public boolean process(Long accountId, TransactionRequest req) {
        if (transactionRepository.existsById(req.getEventId())) {
            return false;
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account not found: " + accountId));

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
        return true;
    }
}
