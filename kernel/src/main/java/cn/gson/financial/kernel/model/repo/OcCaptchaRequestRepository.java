package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.OcCaptchaRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OcCaptchaRequestRepository extends JpaRepository<OcCaptchaRequest, Long> {

    /** 查询所有待填写的请求（前端轮询用） */
    List<OcCaptchaRequest> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * 该 oc_task 在 since 之后是否有过验证码/二维码交互请求。
     * 用于 running 超时回收的防误杀：近期有验证码交互 ⇒ OC 还活着、正等人工填码，本轮不回收。
     */
    boolean existsByTaskIdAndCreatedAtAfter(Long taskId, LocalDateTime since);

    /** 将超时的 pending 记录标记为 expired */
    @Modifying
    @Query("UPDATE OcCaptchaRequest r SET r.status = 'expired' WHERE r.status = 'pending' AND r.expiresAt < :now")
    int expireOverdue(@Param("now") LocalDateTime now);
}
