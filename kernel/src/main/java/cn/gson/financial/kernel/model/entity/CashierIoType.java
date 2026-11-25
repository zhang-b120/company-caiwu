package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.alibaba.fastjson.JSONArray;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 资金收支类别</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月26日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_cashieraccount_type_name", columnNames = {"type", "name", "accountSetsId"}),
        @UniqueConstraint(name = "uc_cashieraccount_type_code", columnNames = {"type", "code", "accountSetsId"}),
})
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("资金-收支类型")
public class CashierIoType extends AbsEntity {
    /**
     * 类型
     */
    @Enumerated(EnumType.STRING)
    @Comment("收支类型")
    private Type type;

    /**
     * 名称
     */
    @Comment("名称")
    private String name;

    @Comment("编码")
    private String code;

    @Comment("智能匹配摘要关键字")
    @JdbcTypeCode(SqlTypes.JSON)
    private JSONArray matchWords;

    public enum Type {
        收, 支
    }
}
