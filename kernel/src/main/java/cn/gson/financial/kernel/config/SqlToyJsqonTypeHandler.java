package cn.gson.financial.kernel.config;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import org.sagacity.sqltoy.plugins.TypeHandler;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年07月01日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public class SqlToyJsqonTypeHandler extends TypeHandler {
    // 根据情况对null进行设置,postgresql这个环节是必须要的
    @Override
    public boolean setNull(Integer dbType, PreparedStatement pst, int paramIndex, int jdbcType) throws SQLException {
        return false;
    }

    /**
     * <li>返回true表示类型匹配上，并完成了setValue赋值</li>
     * <li>返回false 表示常规类型,交回框架自行处理</li>
     */
    @Override
    public boolean setValue(Integer dbType, PreparedStatement pst, int paramIndex, int jdbcType, Object value)
            throws SQLException {
        // 通过quickvo.xml 中jdbc-type 里面直接设置int数字,来区分特定的类型
        if (jdbcType == 1021) {
            pst.setString(paramIndex, JSONUtil.toJsonStr(value));
            return true;
        }
        return false;
    }

    /*
     * <li>1、返回null表示属于常规类型，交回框架完成处理</li>
     * <li>2、返回非null,表示特殊类型，完成了类型转换可直接映射到VO属性</li>
     */
    @Override
    public Object toJavaType(String javaTypeName, Class genericType, Object jdbcValue) throws Exception {
        if (javaTypeName.equalsIgnoreCase("java.lang.string")) {
            return jdbcValue.toString();
        }
        // 是一个VO对象
        if (javaTypeName.contains("com.sqltoy")) {
            return JSON.parseObject(jdbcValue.toString()).toJavaObject(Class.forName(javaTypeName));
        }
        if (javaTypeName.toLowerCase().contains("jsonobject")) {
            return JSON.parse(jdbcValue.toString());
        } else if (javaTypeName.toLowerCase().contains("jsonarray")) {
            return JSON.parseArray(jdbcValue.toString());
        }
        // List<VO>形式
        if (javaTypeName.equalsIgnoreCase("java.util.List") && genericType != null) {
            return JSON.parseArray(jdbcValue.toString()).toJavaList(genericType);
        }
        // 其他场景表示非json返回null交框架自行处理
        return null;
    }
}
