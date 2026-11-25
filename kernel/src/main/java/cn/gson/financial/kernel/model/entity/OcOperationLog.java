package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * OC 运行日志
 * 记录 OpenClaw 每次操作，用于统计各省份在指定时间窗口内的操作次数，防止超限
 */
@Getter
@Setter
@Entity
@Table(
    name = "oc_operation_log",
    indexes = {
        @Index(name = "idx_oc_op_log_credential_province_time",
               columnList = "login_credential, province, operation_time")
    }
)
@Comment("OC运行日志")
public class OcOperationLog implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("省份")
    @Column(length = 32, nullable = false)
    private String province;

    @Comment("登录凭证")
    @Column(name = "login_credential", length = 255, nullable = false)
    private String loginCredential;

    @Comment("操作时间，自动取当前时间")
    @CreationTimestamp
    @Column(name = "operation_time", updatable = false, nullable = false)
    private LocalDateTime operationTime;

    @Comment("操作类型：login（登录）/ switch_identity（切换身份）")
    @Column(name = "operation_type", length = 32, nullable = false)
    private String operationType;
}
