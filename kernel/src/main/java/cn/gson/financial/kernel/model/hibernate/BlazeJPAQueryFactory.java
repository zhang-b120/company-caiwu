package cn.gson.financial.kernel.model.hibernate;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.types.EntityPath;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : com.flyemu.share.hibernate</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年03月08日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Component
@RequiredArgsConstructor
public class BlazeJPAQueryFactory {

    @Getter
    protected final EntityManager em;

    protected final CriteriaBuilderFactory cbf;

    public <T> BlazeJPAQuery<T> selectFrom(EntityPath<T> from) {
        BlazeJPAQuery<T> query = new BlazeJPAQuery<>(em, cbf);
        return query.select(from).from(from);
    }

    /**
     * 将实体从Hibernate的上下文中分离出来，这样它就不会被脏检查。
     * @param entity
     */
    public void detach(Object entity) {
        em.detach(entity);
    }
}
