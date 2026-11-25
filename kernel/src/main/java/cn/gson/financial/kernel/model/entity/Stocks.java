package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.StocksCategory;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助核算-存货</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("辅助项-存货")
public class Stocks extends AcDetails {

    @Comment("存货类别")
    @Enumerated(EnumType.STRING)
    private StocksCategory category;

    @Comment("规格型号")
    private String specification;

    @Comment("计量单位")
    private String unit;

    @Comment("启用日期")
    private LocalDate startDate;

    @Comment("停用日期")
    private LocalDate endDate;
}
