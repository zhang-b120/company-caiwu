package cn.gson.financial.kernel.model.hibernate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.study</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月11日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@NoArgsConstructor
public class PrimaryKey implements Serializable {
    private Long id;
    private Long accountSetsId;

    public PrimaryKey(Long id, Long accountSetsId) {
        this.id = id;
        this.accountSetsId = accountSetsId;
    }

    public static PrimaryKey of(Long id, Long accountSetsId) {
        return new PrimaryKey(id, accountSetsId);
    }

    @Override
    public String toString() {
        return "id:" + this.id + ",accountSetsId:" + this.accountSetsId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrimaryKey that = (PrimaryKey) o;
        return Objects.equals(id, that.id) && Objects.equals(accountSetsId, that.accountSetsId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, accountSetsId);
    }
}
