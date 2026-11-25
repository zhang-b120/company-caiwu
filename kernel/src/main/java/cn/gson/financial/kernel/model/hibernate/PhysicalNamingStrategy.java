package cn.gson.financial.kernel.model.hibernate;

import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * 
 * <p><b>Copyright © 2010-2022 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 物理表名前缀处理</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月19日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public class PhysicalNamingStrategy extends CamelCaseToUnderscoresNamingStrategy {

    @Override
    public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment jdbcEnvironment) {
        Identifier identifier = super.toPhysicalTableName(name, jdbcEnvironment);
        return new Identifier("fxy_financial_" + identifier.getText(), true);
    }

    @Override
    public Identifier toPhysicalColumnName(Identifier logicalName, JdbcEnvironment jdbcEnvironment) {
        Identifier identifier = super.toPhysicalColumnName(logicalName, jdbcEnvironment);
        return new Identifier(identifier.getText(), true);
    }
}
