package cn.gson.financial.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * FN-AI-T2（185）— {@link TaxCodeLookup} 的生产实现，查真 DB 码表
 * {@code fxy_financial_tax_classification_code}（不读 .sql）。
 *
 * <p>leaf 用 {@code NOT EXISTS(子节点)} 即时算（走 {@code idx_parent}）；税率/纳税人字段经
 * LEFT JOIN {@code fxy_financial_tax_classification} 兜（与现有 {@code /tax-classification} 端点同源）。
 */
@Component
@RequiredArgsConstructor
public class JdbcTaxCodeLookup implements TaxCodeLookup {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT =
            "SELECT c.merged_code AS code, c.goods_name AS name, c.short_name AS short_name, " +
            "c.parent_code AS parent_code, c.level AS level, " +
            "old.default_tax_rate AS default_tax_rate, " +
            "old.applicable_taxpayer_type AS applicable_taxpayer_type, " +
            "NOT EXISTS (SELECT 1 FROM fxy_financial_tax_classification_code k " +
            "            WHERE k.parent_code = c.merged_code) AS leaf " +
            "FROM fxy_financial_tax_classification_code c " +
            "LEFT JOIN fxy_financial_tax_classification old ON old.code = c.merged_code ";

    @Override
    public Optional<TaxCodeRow> findByCode(String code19) {
        if (code19 == null || code19.isBlank()) return Optional.empty();
        List<TaxCodeRow> rows = jdbcTemplate.query(
                SELECT + "WHERE c.merged_code = ?", this::mapRow, code19.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<TaxCodeRow> searchByKeyword(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String like = "%" + keyword.trim() + "%";
        return jdbcTemplate.query(
                SELECT + "WHERE (c.goods_name LIKE ? OR c.short_name LIKE ?) ORDER BY c.serial_no LIMIT ?",
                this::mapRow, like, like, Math.max(1, limit));
    }

    @Override
    public List<TaxCodeRow> findChildren(String parentCode) {
        if (parentCode == null || parentCode.isBlank()) return List.of();
        return jdbcTemplate.query(
                SELECT + "WHERE c.parent_code = ? ORDER BY c.merged_code",
                this::mapRow, parentCode.trim());
    }

    private TaxCodeRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        BigDecimal rate = rs.getBigDecimal("default_tax_rate");
        Integer level = (Integer) rs.getObject("level");
        return new TaxCodeRow(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("short_name"),
                rs.getString("parent_code"),
                level,
                rs.getBoolean("leaf"),
                rate,
                rs.getString("applicable_taxpayer_type"));
    }
}
