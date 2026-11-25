package cn.gson.financial.kernel.model.entity;

import cn.hutool.core.bean.BeanUtil;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDate;
import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-09-01
 * @Description
 */
@Data
@Entity
@Table(name = "individual_tax")
public class IndividualTax {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    /**
     * 账套ID
     */
    @Comment("账套ID")
    private Long accountSetsId;

    /**
     * 当前账期
     */
    @Comment("当前账期")
    private LocalDate period;

    /**
     * 结果 0 未执行 1 失败 2 成功
     */
    @Column(name = "result")
    @Comment("结果")
    private Integer result;

    /**
     * 失败原因
     */
    @Comment("失败原因")
    @Column(name = "failed_reason")
    private  String failedReason;

    /**
     * 截图名
     */
    @Column(name = "img_name")
    @Comment("截图名")
    private String imgName;

    /**
     * 截图路径
     */
    @Column(name = "img_url")
    @Comment("截图名")
    private String imgUrl;

    /**
     * 审核状态 0-未审核 1-审核通过 2-待更新
     */
    @Column(name = "audit_status", columnDefinition = "TINYINT DEFAULT 0")
    @Comment("审核状态: 0-未审核, 1-审核通过, 2-待更新")
    private Integer auditStatus;

    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}
