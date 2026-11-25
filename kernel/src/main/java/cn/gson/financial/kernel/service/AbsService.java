package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.annotation.Resource;
import me.ahoo.cosid.snowflake.SnowflakeId;
import org.sagacity.sqltoy.dao.SqlToyLazyDao;
import org.springframework.context.annotation.Lazy;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : com.flyemu.share.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年03月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public abstract class AbsService {

    @Resource
    protected SqlToyLazyDao lazyDao;

    @Resource
    protected JPAQueryFactory jqf;

    @Resource
    @Lazy
    protected BlazeJPAQueryFactory bqf;

    /**
     * 雪花ID生成器
     */
    @Resource
    @Lazy
    protected SnowflakeId snowflakeId;
}
