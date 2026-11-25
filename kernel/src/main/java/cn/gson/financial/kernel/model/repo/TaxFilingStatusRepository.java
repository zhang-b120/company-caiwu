package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxFilingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaxFilingStatusRepository extends JpaRepository<TaxFilingStatus, Long> {

    Optional<TaxFilingStatus> findByAccountSetIdAndTaxPeriod(Long accountSetId, String taxPeriod);

    void deleteByAccountSetIdAndTaxPeriod(Long accountSetId, String taxPeriod);
}
