package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.alibaba.fastjson2.JSONArray;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 凭证模板</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年09月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Entity
@Getter
@Setter
@DynamicUpdate
@DynamicInsert
@Comment("凭证模板")
public class VoucherTemplate extends AbsEntity {

    @Column(length = 64, nullable = false)
    @Comment("模板标题")
    private String title;

    @Comment("模板明细")
    @JdbcTypeCode(SqlTypes.JSON)
    private JSONArray details;
}
