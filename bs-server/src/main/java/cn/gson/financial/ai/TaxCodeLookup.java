package cn.gson.financial.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * FN-AI-T2（185）— 税收分类码表查询缝（seam）。
 *
 * <p>权威源 {@code fxy_financial_tax_classification_code}（19 位 merged_code / goods_name /
 * short_name / parent_code / level），税率/纳税人字段经 LEFT JOIN 旧应用表
 * {@code fxy_financial_tax_classification} 兜衍生（稀疏，可空）。
 *
 * <p>抽成接口是为了 {@link TaxCodeMatcher} 可<b>纯单测</b>（喂少量种子行验证 codeProvided 补零、
 * keyword 命中中类自动下钻末级、候选切换），生产实现走 {@code JdbcTaxCodeLookup}。
 */
public interface TaxCodeLookup {

    /** 按 19 位 merged_code 精确取一行（含 leaf/税率）。 */
    Optional<TaxCodeRow> findByCode(String code19);

    /** keyword 对 goods_name / short_name 模糊匹配，按 serial_no 序返回前 limit 行。 */
    List<TaxCodeRow> searchByKeyword(String keyword, int limit);

    /** 取某节点的直接子代（含各自 leaf/税率），按 merged_code 升序。 */
    List<TaxCodeRow> findChildren(String parentCode);

    /**
     * 码表一行（投影）。
     *
     * @param code        19 位合并编码（开票实际填这个）
     * @param name        货物和劳务名称（goods_name）
     * @param shortName   简称（short_name，≈全名）
     * @param parentCode  父节点 merged_code（篇=null）
     * @param level       层级 1-10
     * @param leaf        是否末级（无子节点）
     * @param defaultTaxRate 旧应用表衍生默认税率（LEFT JOIN，稀疏可空）
     * @param applicableTaxpayerType 旧应用表衍生纳税人类型（GENERAL/SMALL_SCALE/BOTH，稀疏可空）
     */
    record TaxCodeRow(String code, String name, String shortName, String parentCode,
                      Integer level, boolean leaf, BigDecimal defaultTaxRate,
                      String applicableTaxpayerType) {
    }
}
