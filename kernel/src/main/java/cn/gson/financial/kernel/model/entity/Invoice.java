package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.InvoiceCategory;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.hutool.core.util.StrUtil;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 *
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月19日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_invoice_invoice_number_unx", columnNames = {"accountSetsId", "invoiceNumber"})
}, indexes = {
        // 任务工作台按账套+开票日期范围聚合
        @Index(name = "ix_invoice_acct_trade_date", columnList = "accountSetsId, tradeDate"),
        // 任务工作台按账套+核定日期范围聚合（assignmentDate 为做账归属期）
        @Index(name = "ix_invoice_acct_assign_date", columnList = "accountSetsId, assignmentDate"),
        // 凭证电子册等按 voucherId 反查
        @Index(name = "ix_invoice_voucher_id", columnList = "voucherId")
})
@NoArgsConstructor
public class Invoice extends AbsEntity {

    @Comment("发票类别")
    @Enumerated
    private InvoiceType type;

    @Comment("发票类型")
    @Enumerated
    @Deprecated
    private InvoiceCategory category;

    @Comment("新发票类型")
    private String newCategory;

    @Comment("状态：正常, 作废")
    @Enumerated
    private InvoiceState state;
    /**
     * 开票日期
     */
    @Comment("开票日期")
    private LocalDate tradeDate;

    @Comment("核定日期")
    private LocalDate checkDate;

    @Comment("发票编码")
    private String invoiceCode;

    @Comment("发票号")
    private String invoiceNumber;
    @Comment("备用发票号")
    private String invoiceNumber1;

    @Comment("客户名称")
    private String customerName;

    @Comment("客户地址")
    private String address;

    @Comment("客户卡号")
    private String account;

    @Comment("客户备注")
    private String note;

    @Comment("客户信用代码")
    private String creditCode;

    @Comment("不含税额")
    @ColumnDefault("0")
    @Column(nullable = false)
    private BigDecimal taxAmountExcluded;

    @Comment("税额")
    @ColumnDefault("0")
    @Column(nullable = false)
    private BigDecimal taxAmount;

    @Comment("发票金额")
    @ColumnDefault("0")
    @Column(nullable = false)
    private BigDecimal amount;

    @Comment("是否有明细")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean haveDetail;

    @Comment("业务类型")
    private Long invoiceBusinessTypeId;

    @Comment("凭证ID")
    private Long voucherId;

    @Comment("备注")
    private String remark;

    @Comment("发票pdf文件路径")
    private String pdfPath;

    @Comment("勾选日期")
    private LocalDate checkRiQi;

    @Comment("被分配的日期")
    private LocalDate assignmentDate;

    @Comment("显示状态")
    private String showStatus;


    /**
     * 兼容旧类型获取
     * @return
     */
    public String getNewCategory() {
        return StrUtil.emptyToDefault(newCategory, category != null ? category.name() : null);
    }
}
