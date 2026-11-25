package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.AssetsDetail;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetsDetailRepository extends JpaRepository<AssetsDetail, PrimaryKey> {
}
