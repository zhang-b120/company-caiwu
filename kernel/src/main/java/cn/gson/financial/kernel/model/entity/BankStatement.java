package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.CosIdGenerator;
import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.*;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 银行对账单实体类
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@EqualsAndHashCode(callSuper=false)
@Comment("银行流水数据表")
public class BankStatement extends AbsEntity{


    @Comment("账套")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long accountSetsId;


    @Comment("创建时间")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /**
     * 交易类型
     */
    @Column(name = "create_id")
    @Comment("创建人")
    private Long createId;

    @Column(name = "is_delete")
    @Comment("是否删除 0-否 1-是")
    private int isDelete;

    @Column(name = "period", length = 7)
    @Comment("账期，格式 YYYY-MM，上传时由用户指定")
    private String period;

    @Column(name = "bank_name", length = 64)
    @Comment("银行名称（导入时识别或用户指定）")
    private String bankName;

    @Column(name = "cashier_account_id")
    @Comment("关联系统银行账户ID")
    private Long cashierAccountId;

    @Column(name = "file_name", length = 255)
    @Comment("原始上传文件名")
    private String fileName;

    @Column(name = "detail_count")
    @Comment("解析明细数")
    private Integer detailCount;
}
