package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.ReportItems;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReportItemsRepository extends JpaRepository<ReportItems, PrimaryKey> {
    @Query("select r from ReportItems r where r.accountSetsId = ?1 and r.templateId = ?2 order by r.pos,r.parentId asc ")
    List<ReportItems> list(Long accountSetsId, Long templateId);
}
