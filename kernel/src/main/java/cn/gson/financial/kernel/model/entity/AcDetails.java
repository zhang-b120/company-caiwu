package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@MappedSuperclass
@NoArgsConstructor
public class AcDetails extends AbsEntity {
    @Comment("所属辅助类别")
    private Long categoryId;

    @Transient
    private String categoryName;

    @Comment("所属辅助类类型")
    private AccountingCategoryType categoryType;
    /**
     * 编码
     */
    @Comment("编码")
    private String code;
    /**
     * 名称
     */
    @Comment("名称")
    private String name;

    @Comment("助记码")
    private String mnemonics;
    /**
     * 备注
     */
    @Comment("备注")
    private String remark;
    /**
     * 是否启用
     */
    @Comment("是否启用")
    @ColumnDefault("1")
    private Boolean enable;

    @CreationTimestamp
    private LocalDateTime createTime;

    @UpdateTimestamp
    private LocalDateTime updateTime;

    public AcDetails(String name, AccountingCategoryType categoryType) {
        this.name = name;
        this.categoryType = categoryType;
    }

    public AcDetails(String name, String code, AccountingCategoryType categoryType) {
        this.code = code;
        this.name = name;
        this.categoryType = categoryType;
    }
}
