package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppConfigRepository extends JpaRepository<AppConfig, Long> {
}
