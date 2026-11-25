package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Department;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, PrimaryKey> {
}
