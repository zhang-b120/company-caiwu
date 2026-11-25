package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 现金记账结算方式</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Comment("结算方式")
public class SettlementType extends AbsEntity {

    @Comment("结算方式")
    private String title;
}
