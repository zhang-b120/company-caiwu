package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxFilingInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaxFilingInfoRepository extends JpaRepository<TaxFilingInfo, Long> {

    Optional<TaxFilingInfo> findByAccountSetId(Long accountSetId);
}
