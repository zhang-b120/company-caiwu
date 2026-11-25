package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxClassification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxClassificationRepository extends JpaRepository<TaxClassification, String> {
}
