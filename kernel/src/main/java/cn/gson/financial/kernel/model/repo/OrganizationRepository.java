package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Organization;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, PrimaryKey> {
}
