package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminRepository extends JpaRepository<Admin, Long> {
}
