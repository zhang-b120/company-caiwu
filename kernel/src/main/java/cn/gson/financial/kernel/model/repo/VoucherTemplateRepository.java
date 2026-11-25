package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.VoucherTemplate;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherTemplateRepository extends JpaRepository<VoucherTemplate, PrimaryKey> {
}
