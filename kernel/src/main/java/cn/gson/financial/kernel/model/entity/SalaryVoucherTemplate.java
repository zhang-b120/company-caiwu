package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.*;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-11 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-11</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@NoArgsConstructor
@Comment("薪资凭证模板")
public class SalaryVoucherTemplate extends AbsEntity {

    @Comment("业务标题")
    private String title;

    @Comment("凭证字")
    private Long voucherWord;

    @Comment("是否计提")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean isAccrual;

    @Comment("是否是默认模板")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean def;

    @Comment("模板明细")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<VoucherItems> items;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class VoucherItems {

        private String summary;

        private String subjectTitle;

        private String subjectId;

        private String direction;

        private String salaryFieldId;

        private String salaryFieldTitle;

        private BigDecimal debitAmount = BigDecimal.ZERO;

        private BigDecimal creditAmount = BigDecimal.ZERO;

        public void setAmount(BigDecimal amount) {
            if (StrUtil.isNotEmpty(direction)) {
                switch (direction) {
                    case "借" -> this.debitAmount = amount;
                    case "贷" -> this.creditAmount = amount;
                }
            }
        }

        public BigDecimal getAmount() {
            return switch (direction) {
                case "借" -> this.debitAmount;
                case "贷" -> this.creditAmount;
                default -> throw new IllegalStateException("Unexpected value: " + direction);
            };
        }
    }

    public void setItems(List<VoucherItems> items) {
        if (CollUtil.isNotEmpty(items)) {
            this.items = items.stream().filter(it -> StrUtil.isNotEmpty(it.subjectId)).toList();
        } else {
            this.items = new ArrayList<>();
        }
    }

    public List<VoucherItems> getItems() {
        if (CollUtil.isNotEmpty(items)) {
            return items.stream().filter(it -> StrUtil.isNotEmpty(it.subjectId)).toList();
        } else {
            return new ArrayList<>();
        }
    }
}
