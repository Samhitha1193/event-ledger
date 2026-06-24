package com.eventledger.account.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDomainTest {

    @Test
    void account_getId_returnsSetId() {
        Account a = new Account();
        a.setId("domain-id-1");
        assertThat(a.getId()).isEqualTo("domain-id-1");
    }

    @Test
    void transaction_getAccount_returnsSetAccount() {
        Account a = new Account();
        a.setId("domain-id-2");
        Transaction t = new Transaction();
        t.setAccount(a);
        assertThat(t.getAccount()).isSameAs(a);
        assertThat(t.getAccount().getId()).isEqualTo("domain-id-2");
    }
}
