package com.eventledger.account.controller;

import com.eventledger.account.dto.AccountResponse;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.service.AccountService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse getBalance(@PathVariable String id) {
        return accountService.getBalance(id);
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable String id) {
        return accountService.getAccount(id);
    }
}
