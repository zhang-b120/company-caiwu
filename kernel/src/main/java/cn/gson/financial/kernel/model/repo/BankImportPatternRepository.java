package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.BankImportPattern;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankImportPatternRepository extends JpaRepository<BankImportPattern, PrimaryKey> {

    List<BankImportPattern> findByAccountSetsIdOrderByMatchCountDesc(Long accountSetsId);

    Optional<BankImportPattern> findByAccountSetsIdAndCounterparty(Long accountSetsId, String counterparty);
}
