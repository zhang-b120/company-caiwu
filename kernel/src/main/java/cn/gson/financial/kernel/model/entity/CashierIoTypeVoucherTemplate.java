package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 凭证模板</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月02日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"accountSetsId", "journalType", "ioTypeId", "direction"})
})
@DynamicInsert
@DynamicUpdate
@Comment("资金-凭证模板")
public class CashierIoTypeVoucherTemplate extends AbsEntity {

    @Comment("收支类型")
    private Long ioTypeId;

    @Comment("凭证字")
    private Long wordId;

    @Comment("科目")
    private Long subjectId;

    @Comment("收支类型")
    private CashierIoType.Type type;

    @Comment("借贷方向")
    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private Direction direction;

    @Comment("账户类型")
    private CashierAccount.CashierAccountType journalType;

    public enum Direction {
        借, 贷
    }
}
