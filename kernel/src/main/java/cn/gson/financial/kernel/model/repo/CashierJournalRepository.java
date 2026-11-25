package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.CashierJournal;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface CashierJournalRepository extends QuerydslPredicateExecutor<CashierJournal>, JpaRepository<CashierJournal, PrimaryKey> {

    List<CashierJournal> findByVoucherIdIn(List<Long> voucherIds);


    boolean existsByAccountSetsIdAndAccountIdAndBillDateAndDebitAndTransactionPostscript(
            Long accountSetsId, Long accountId, LocalDate billDate, BigDecimal debit, String transactionPostscript);

    boolean existsByAccountSetsIdAndAccountIdAndBillDateAndCreditAndTransactionPostscript(
            Long accountSetsId, Long accountId, LocalDate billDate, BigDecimal credit, String transactionPostscript);
}
