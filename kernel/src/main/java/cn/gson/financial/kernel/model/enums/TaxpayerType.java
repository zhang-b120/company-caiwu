package cn.gson.financial.kernel.model.enums;

/**
 * FN-FIX-10：纳税人类型权威枚举
 * <p>
 * 用于跨团队（FN / WC / FM / XW / BT）对齐 customer/account_sets 的"纳税人类型"
 * 语义。**FN 是源真理**，所有团队按这 3 个枚举名对齐。
 * <p>
 * 修复前各方命名混乱：
 * <ul>
 *   <li>FN AccountSets.vatType：SMALL_SCALE / COMMONLY（VAT 类型，不是纳税人类型）</li>
 *   <li>WC：small_scale / general / individual（小写，individual 与 FN 没对应）</li>
 *   <li>FM：Boolean（仅区分是否一般纳税人）</li>
 *   <li>OB Vault tag：纳税人/小规模、纳税人/一般、纳税人/个体 中文</li>
 * </ul>
 * <p>
 * 注意：这里的 TaxpayerType **不等于 AccountSets.vatType**。
 *   - {@link #INDIVIDUAL}：派生自 AccountSets.individualBusiness=true（个体工商户，独立于 VAT 类型）
 *   - {@link #SMALL_SCALE}：individualBusiness=false 且 vatType=SMALL_SCALE
 *   - {@link #GENERAL}：individualBusiness=false 且 vatType=COMMONLY
 * <p>
 * 推导逻辑由 {@link #fromAccountSets} 提供，所有方都应用同一函数避免分歧。
 */
public enum TaxpayerType {

    /** 小规模纳税人（非个体）— 季报增值税，征收率 1%/3% */
    SMALL_SCALE("纳税人/小规模"),

    /** 一般纳税人 — 月报增值税，按销项-进项算 */
    GENERAL("纳税人/一般"),

    /** 个体工商户 — 个税征收，与公司不同 */
    INDIVIDUAL("纳税人/个体");

    /** 用于在 OB Vault 知识库 tag 检索的中文标签 */
    public final String vaultTag;

    TaxpayerType(String vaultTag) {
        this.vaultTag = vaultTag;
    }

    /**
     * 从 AccountSets entity 推导纳税人类型。
     * <p>
     * 优先级：individualBusiness=true → INDIVIDUAL；否则按 vatType 决定。
     *
     * @param individualBusiness AccountSets.individualBusiness（null 视为 false）
     * @param vatType AccountSets.vatType（null 视为 SMALL_SCALE 默认）
     */
    public static TaxpayerType fromAccountSets(Boolean individualBusiness, VatType vatType) {
        if (Boolean.TRUE.equals(individualBusiness)) {
            return INDIVIDUAL;
        }
        if (vatType == VatType.COMMONLY) {
            return GENERAL;
        }
        // 默认 / SMALL_SCALE
        return SMALL_SCALE;
    }
}
