package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 现金账户</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_cashieraccount_type_name", columnNames = {"type", "name", "accountSetsId"}),
        @UniqueConstraint(name = "uc_cashieraccount_type_code", columnNames = {"type", "code", "accountSetsId"}),
        @UniqueConstraint(name = "uc_cashieraccount_type", columnNames = {"type", "cardNumber", "accountSetsId"})
})
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("资金-账户")
public class CashierAccount extends AbsEntity {
    /**
     * 账户类型
     */
    @Comment("账户类型")
    @Enumerated(EnumType.STRING)
    private CashierAccountType type;

    /**
     * 账户名称
     */
    @Comment("账户名称")
    private String name;

    /**
     * 银行卡号
     */
    @Comment("银行卡号")
    private String cardNumber;

    /**
     * 账户编码
     */
    @Comment("账户编码")
    private String code;

    @Comment("关联科目")
    private Long subjectId;

    @Comment("科目关联的辅助信息")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<Long, AcDetails> categoryVal;

    @Comment("关联币别")
    private Long currencyId;

    /**
     * 余额
     */
    @Comment("余额")
    @Column(columnDefinition = "double not null default 0")
    private BigDecimal balance;

    /**
     * FN-NEW-AUTOCREATE-BANK-ACCOUNT：cashier-journal/batch 匹配不到账户时由系统自动创建，
     * 打此标记表示「待会计确认」（核对关联科目 + 期初余额后手动清除）。
     */
    @Comment("AI自动创建待确认")
    @ColumnDefault("b'0'")
    @Column(name = "auto_created", columnDefinition = "bit not null default b'0'")
    private Boolean autoCreated = false;

    public enum CashierAccountType {
        现金, 银行存款
    }
}
