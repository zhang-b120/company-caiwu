package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Project;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, PrimaryKey> {
}
