package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品税收分类编码（FN-INVOICE-03）。
 * <p>表名由 PhysicalNamingStrategy 自动转为 fxy_financial_tax_classification。
 * <p>code 为主键（GB/T 分类编码，9-19 位），无自增，手动维护 + seed data。
 */
@Getter
@Setter
@Entity
@Comment("商品税收分类编码（FN-INVOICE-03）")
public class TaxClassification implements Serializable {

    @Id
    @Column(length = 20)
    @Comment("分类编码（9-19 位）")
    private String code;

    @Column(length = 200, nullable = false)
    @Comment("商品/服务名称")
    private String name;

    @Column(length = 100)
    @Comment("大类（如：信息技术服务）")
    private String category;

    @Column(precision = 5, scale = 4)
    @Comment("默认适用税率/征收率（0.13/0.09/0.06/0.03）")
    private BigDecimal defaultTaxRate;

    @Column(length = 20)
    @Comment("适用纳税人类型 GENERAL/SMALL_SCALE/BOTH")
    private String applicableTaxpayerType;

    @Column(nullable = false)
    @Comment("是否备注必填（不动产/建筑服务必须在备注填写地址等信息）")
    private Boolean remarkRequired = false;

    @Column(length = 20)
    @Comment("上级编码（层级关系）")
    private String parentCode;
}
