package com.eventledger.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false, length = 3)
    private String currency;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCurrency() { return currency; }

    public void setCurrency(String currency) { this.currency = currency; }
}
