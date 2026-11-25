package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Permissions;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionsRepository extends JpaRepository<Permissions, Long> {
}
