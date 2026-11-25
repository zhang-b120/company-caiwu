package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.SmsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsConfigRepository extends JpaRepository<SmsConfig, Long> {
}