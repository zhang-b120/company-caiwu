package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FN-09 客户群路由表
 * <p>
 * 维护"企微客户群 → 4 个员工"的映射，供企微客服服务把 AI 草稿
 * 按问题类型（销售/客服/会计/工商）推给正确的员工。
 * <p>
 * 不属于账套数据，不继承 AbsEntity，仅一个简单 PK。ID 由 cosid 雪花生成。
 */
@Getter
@Setter
@Entity
@Table(name = "customer_group")
@Comment("企微客户群路由表（FN-09）")
public class CustomerGroup implements Serializable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("企微外部群 chat_id")
    @Column(name = "wecom_chat_id", length = 64, nullable = false, unique = true)
    private String wecomChatId;

    @Comment("群名")
    @Column(name = "group_name", length = 255)
    private String groupName;

    @Comment("客户 ID")
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Comment("专属销售员工 ID")
    @Column(name = "sales_employee_id")
    private Long salesEmployeeId;

    @Comment("专属客服员工 ID")
    @Column(name = "cs_employee_id")
    private Long csEmployeeId;

    @Comment("专属会计员工 ID")
    @Column(name = "accountant_employee_id")
    private Long accountantEmployeeId;

    @Comment("专属工商员工 ID")
    @Column(name = "gov_employee_id")
    private Long govEmployeeId;

    @Comment("是否启用")
    @Column(name = "is_active")
    @ColumnDefault("1")
    private Boolean isActive = Boolean.TRUE;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
