package cn.gson.financial.kernel.model.hibernate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.util.Objects;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 非账套数据实体基类</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月11日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@MappedSuperclass
@IdClass(PrimaryKey.class)
public abstract class AbsEntity implements Serializable {
    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Id
    @Comment("账套")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long accountSetsId;

    @JsonIgnore
    public PrimaryKey getPrimaryKey() {
        if (id != null && accountSetsId != null) {
            return new PrimaryKey(id, accountSetsId);
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbsEntity absEntity = (AbsEntity) o;
        return Objects.equals(id, absEntity.id) && Objects.equals(accountSetsId, absEntity.accountSetsId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, accountSetsId);
    }
}
