package cn.gson.financial.kernel.model.hibernate;

import cn.hutool.db.sql.SqlUtil;
import org.sagacity.sqltoy.plugins.formater.SqlFormater;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.hibernate</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月13日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public class SqlFormat implements SqlFormater {
    @Override
    public String format(String sql, String dialect) {
        return SqlUtil.formatSql("\n" + sql);
    }
}
