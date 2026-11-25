package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.Hibernate;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月01日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_subject_accountsetsid_code", columnNames = {"accountSetsId", "code"})
})
@DynamicInsert
@DynamicUpdate
@Comment("科目")
public class Subject extends AbsEntity {

    /**
     * 科目类型
     */
    @Comment("科目类型")
    private String type;

    /**
     * 科目编码
     */
    @Comment("科目编码")
    private String code;

    @Comment("查询码")
    private String queryCode;

    /**
     * 科目名称
     */
    @Comment("科目名称")
    private String name;

    /**
     * 助记码
     */
    @Comment("助记码")
    private String mnemonicCode;

    /**
     * 余额方向
     */
    @Comment("余额方向")
    private String balanceDirection;

    /**
     * 状态
     */
    @Comment("状态")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean status = true;

    /**
     * 上级科目
     */
    @Comment("上级科目")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long parentId = 0L;

    /**
     * 所在级别
     */
    @Comment("所在级别")
    @Column(nullable = false)
    @ColumnDefault("1")
    private Integer level = 1;

    /**
     * 是否为系统默认
     */
    @Comment("是否为系统默认")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean systemDefault = true;

    /**
     * 是否现金及现金等价物
     */
    @Comment("是否现金及现金等价物")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean cash = false;

    /**
     * 单位
     */
    @Comment("单位")
    private String unit;

    @Comment("科目全称")
    private String fullName;

    @Comment("是否是根节点")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean leaf = true;

    @Comment("期末调汇")
    @ColumnDefault("b'0'")
    @Column(nullable = false)
    private Boolean adjustments = false;

    /**
     * FN-NEW-OPENAPI-ENSURE-SUBJECTS：subjects/ensure 接口自动补建明细科目时打此标记，
     * 标识「自动创建、待会计核对编码/辅助核算」。标准科目补建时为 false（结构固定无需确认）。
     */
    @Comment("AI自动创建待确认")
    @ColumnDefault("b'0'")
    @Column(name = "auto_created", columnDefinition = "bit not null default b'0'")
    private Boolean autoCreated = false;

    /**
     * 辅助核算
     */
    @Comment("辅助核算")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> auxiliaryAccounting;

    /**
     * 非必填辅助核算
     */
    @Comment("非必填辅助核算")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> nonAuxiliaryAccounting;

    /**
     * 外币核算
     */
    @Comment("外币核算")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> currencyAccounting;

    public void setName(String name) {
        this.name = StrUtil.trimToEmpty(name);
    }

    public void setCode(String code) {
        this.code = StrUtil.trimToEmpty(code);
        // 标识为现金科目
        if (StrUtil.isNotEmpty(code) && code.startsWith("1001") || code.startsWith("1002") || code.startsWith("1012")) {
            this.setCash(true);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Subject subject = (Subject) o;
        return getPrimaryKey() != null && Objects.equals(getPrimaryKey(), subject.getPrimaryKey());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    public List<Long> getNonAuxiliaryAccounting() {
        return CollUtil.emptyIfNull(nonAuxiliaryAccounting);
    }

    public List<Long> getCurrencyAccounting() {
        return CollUtil.emptyIfNull(currencyAccounting);
    }

    /**
     * 必填辅助项
     *
     * @return
     */
    @JsonIgnore
    public Set<Long> getRequiredAuxiliaryAccounting() {
        if (CollUtil.isNotEmpty(this.auxiliaryAccounting)) {
            if (CollUtil.isEmpty(this.nonAuxiliaryAccounting)) {
                return new HashSet<>(this.auxiliaryAccounting);
            }
            return this.auxiliaryAccounting.stream().filter(id -> {
                if (CollUtil.isEmpty(this.nonAuxiliaryAccounting)) {
                    return true;
                }
                return !this.nonAuxiliaryAccounting.contains(id);
            }).collect(Collectors.toSet());
        }
        return new HashSet<>();
    }

    public List<Long> getAuxiliaryAccounting() {
        return CollUtil.emptyIfNull(auxiliaryAccounting);
    }
}
