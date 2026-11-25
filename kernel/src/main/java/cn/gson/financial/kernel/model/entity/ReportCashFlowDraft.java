package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <p>***************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : 现金流量表底稿</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年12月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>***************************************</p>
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@DynamicInsert
@Table
@Comment("现金流量表底稿")
public class ReportCashFlowDraft extends AbsEntity {

    @Comment("会计期间")
    private LocalDate period;

    @Comment("报表项ID")
    private Long parentId;

    @Comment("公式项关联的科目id")
    private String fromTag;

    @Comment("公式项取数规则")
    private String accessRules;

    @Comment("公式项计算方式")
    private String calculation;

    @Comment("底稿金额")
    private BigDecimal amount;

    @Comment("备注")
    private String remark;

    public void setPeriod(LocalDate period) {
        if (period != null) {
            this.period = period.withDayOfMonth(1);
        }
    }

    public boolean isEq(ReportItemsFormula formula) {
        return formula.getFromTag().equals(fromTag)
               && formula.getCalculation().equals(calculation)
               && formula.getAccessRules().name().equals(accessRules);
    }
}
