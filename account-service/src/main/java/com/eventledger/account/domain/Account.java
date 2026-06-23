package com.eventledger.account.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3)
    private String currency;

    public Long getId() { return id; }

    public String getCurrency() { return currency; }

    public void setCurrency(String currency) { this.currency = currency; }
}
