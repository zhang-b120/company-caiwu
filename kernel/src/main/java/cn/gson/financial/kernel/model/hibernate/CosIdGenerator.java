package cn.gson.financial.kernel.model.hibernate;

import cn.hutool.core.util.ReflectUtil;
import jakarta.annotation.Resource;
import me.ahoo.cosid.snowflake.SnowflakeId;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;
import org.springframework.context.annotation.Lazy;

import java.io.Serializable;

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
public class CosIdGenerator implements IdentifierGenerator {
    @Resource
    @Lazy
    private SnowflakeId provider;

    @Override
    public Serializable generate(SharedSessionContractImplementor session, Object object) throws HibernateException {
        Object id = ReflectUtil.getFieldValue(object, "id");
        if (id != null) {
            return (Serializable) id;
        }
        return provider.generate();
    }
}
