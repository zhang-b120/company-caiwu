package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("币别")
public class Currency extends AbsEntity {
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

    /**
     * 汇率
     */
    @Comment("汇率")
    @ColumnDefault("0")
    @Column(scale = 6, precision = 19)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal exchangeRate;

    /**
     * 是否本位币
     */
    @Comment("是否本位币")
    @ColumnDefault("b'0'")
    @Column(updatable = false)
    private Boolean localCurrency;
}
