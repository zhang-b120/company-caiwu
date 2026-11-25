package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_voucherword_accountsetsid", columnNames = {"accountSetsId", "word"})
})
@DynamicUpdate
@DynamicInsert
@Comment("凭证字")
public class VoucherWord extends AbsEntity {
    @Comment("凭证字")
    @Column(nullable = false, length = 32)
    private String word;

    @Comment("打印标题")
    @Column(nullable = false, length = 32)
    private String printTitle;

    @Comment("是否默认")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean isDefault;
}
