package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AutoBackupConfig;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoBackupConfigRepository extends JpaRepository<AutoBackupConfig, PrimaryKey> {
}