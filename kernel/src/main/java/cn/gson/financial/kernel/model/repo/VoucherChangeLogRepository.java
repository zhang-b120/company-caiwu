package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.VoucherChangeLog;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherChangeLogRepository extends JpaRepository<VoucherChangeLog, PrimaryKey> {
}