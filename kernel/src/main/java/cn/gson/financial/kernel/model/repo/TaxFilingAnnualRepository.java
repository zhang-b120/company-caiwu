package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxFilingAnnual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaxFilingAnnualRepository extends JpaRepository<TaxFilingAnnual, Long> {

    Optional<TaxFilingAnnual> findByAccountSetIdAndTaxYear(Long accountSetId, Integer taxYear);
}
