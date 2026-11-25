package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-09-03
 * @Description
 */
@DynamicInsert
@DynamicUpdate
@Entity
@Data
@Table(name = "sync_individual_tax_emplpoyee")
public class SyncIndividualTaxEmployee {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    /**
     * 账套ID
     */
    @Column(name = "accountSetsId")
    @Comment("账套ID")
    private Long accountSetsId;

    @Column(name = "period")
    @Comment("账期")
    private LocalDate period;

    /**
     * 结果 0-成功 1-失败
     */
    @Column(name = "result")
    @Comment("结果")
    private Integer result;

    @Comment("类型")
    @Column(name = "type")
    private String type;

    @Comment("类型")
    @Column(name = "source")
    private String source;

    @Comment("原因")
    @Column(name = "reason")
    private String reason;
}
