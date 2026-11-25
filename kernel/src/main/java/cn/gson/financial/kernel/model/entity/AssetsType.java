package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;

/**
 * (固定资产类别)实体类
 *
 * @author 开发者
 * @since 2022-03-04 22:17:13
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@NoArgsConstructor
public class AssetsType extends AbsEntity {

    @Comment("编码")
    @Column(nullable = false, length = 32)
    private String code;

    /**
     * 名称
     */
    @Comment("名称")
    @Column(nullable = false)
    private String name;
    /**
     * 折旧方法
     */
    @Comment("折旧方法")
    @Enumerated(EnumType.STRING)
    private DepreciationMethod depreciationMethod;

    /**
     * 使用期限
     */
    @Comment("使用期限")
    private Integer useMonth;

    /**
     * 残值率
     */
    @Comment("残值率")
    private BigDecimal residualRate;
    /**
     * 费用科目 code
     */
    @Comment("费用科目 code")
    private Long costAccount;
    /**
     * 折旧科目  code
     */
    @Comment("折旧科目  code")
    private Long depreciationAccount;

    @Comment("备注")
    private String remark;

    public enum DepreciationMethod {
        平均年限法, 不折旧
    }
}

