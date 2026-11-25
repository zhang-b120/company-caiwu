package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.alibaba.fastjson.JSONArray;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

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
@NoArgsConstructor
public class InvoiceBusinessType extends AbsEntity {

    @Comment("发票类别")
    @Enumerated
    private InvoiceType invoiceType;

    @Comment("业务标题")
    private String title;

    @Comment("凭证字")
    private Long voucherWord;

    @Comment("智能匹配商品关键字")
    @JdbcTypeCode(SqlTypes.JSON)
    private JSONArray matchWords;

    @Comment("模板明细")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<InvoiceVoucherTemplateItems> items;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class InvoiceVoucherTemplateItems {

        private String subjectTitle;

        private String subjectId;

        private String direction;

        private String valueField;
    }
}
