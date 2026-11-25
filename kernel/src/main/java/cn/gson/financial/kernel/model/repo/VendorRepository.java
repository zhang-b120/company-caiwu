package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.Vendor;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorRepository extends JpaRepository<Vendor, PrimaryKey> {
}
