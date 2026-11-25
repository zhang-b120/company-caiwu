package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.TaxDeclare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaxDeclareRepository extends JpaRepository<TaxDeclare, Long> {

    List<TaxDeclare> findByAccountSetsId(Long accountSetsId);

    void deleteByAccountSetsId(Long accountSetsId);

    /** 该账套应申报税费种数据最近一次更新时间（供前端展示、判定是否重新抓取） */
    @Query("SELECT MAX(t.updateTime) FROM TaxDeclare t WHERE t.accountSetsId = :accountSetsId")
    Optional<LocalDateTime> findMaxUpdateTimeByAccountSetsId(@Param("accountSetsId") Long accountSetsId);
}
