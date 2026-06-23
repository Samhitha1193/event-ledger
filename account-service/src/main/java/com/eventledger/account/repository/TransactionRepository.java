package com.eventledger.account.repository;

import com.eventledger.account.domain.Transaction;
import com.eventledger.account.domain.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.type = :type")
    BigDecimal sumAmountByAccountIdAndType(@Param("accountId") Long accountId, @Param("type") TransactionType type);

    List<Transaction> findByAccount_IdOrderByEventTimestampDesc(Long accountId, Pageable pageable);
}
