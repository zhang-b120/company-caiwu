package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.gson.financial.kernel.model.hibernate.FileInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年10月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Entity
@Getter
@Setter
@DynamicUpdate
@DynamicInsert
@Comment("凭证")
public class Voucher extends AbsEntity {
    /**
     * 凭证字
     */
    @Comment("凭证字")
    private String word;

    @Comment("凭证号")
    private Integer code;

    /**
     * 备注
     */
    @Comment("备注")
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    private String remark;

    /**
     * 原始单据引用（自由文本）
     * 例：'invoice:12001+12002 / bank:9001' 或 '出纳:JV-2026-05-088'
     * xiaowei 生成凭证时自标，BT 审核工作站直接显示。
     */
    @Comment("原始单据引用（自由文本，xiaowei 自标 / BT 显示）")
    @Column(length = 500)
    private String sourceRef;

    /**
     * 附单据数量
     */
    @Comment("附单据数量")
    private Integer receiptNum;

    /**
     * 制单人
     */
    @Comment("制单人")
    private Long createMember;

    /**
     * 制单人
     */
    @Comment("最后修改人")
    private Long lastUpdateMember;

    /**
     * 制单人
     */
    @Comment("最后修改时间")
    private LocalDateTime lastUpdateDate;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createDate;

    /**
     * 借方总金额
     */
    @Comment("借方总金额")
    private BigDecimal debitAmount;

    /**
     * 贷方总金额
     */
    @Comment("贷方总金额")
    private BigDecimal creditAmount;

    @Comment("期间年")
    private Integer year;

    @Comment("期间月")
    private Integer month;

    @Comment("期间年月")
    private LocalDate yearMonth;

    @Comment("凭证日期")
    private LocalDate voucherDate;

    @Comment("审核人 ID")
    private Long auditMemberId;

    @Comment("审核时间")
    private LocalDateTime auditDate;

    @Comment("附件")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<FileInfo> billList;

    @Comment("是否锁定")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean locked;

    @Comment("结转凭证模板")
    private Long checkTplId;

    @Comment("冲销凭证ID")
    private Long reverseVoucherId;

    @Comment("出纳")
    private Long cashierId;

    @Comment("是否是结转凭证")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean carryForward;

    @Comment("是否系统自动生成")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean isSysAuto;
}
