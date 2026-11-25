package cn.gson.financial.kernel.model.enums;

/**
 * FN-INVOICE-COLLECT-MODE-CONFIG：发票采集/归属期模式（账套级配置）。
 * <ul>
 *   <li>{@link #QUARTERLY} 季度（默认）：小规模整季票归属期落季末月、按季采集（saveImportInvoiceSeason）。</li>
 *   <li>{@link #MONTHLY} 月度：归属期=真实开票月、按月做账（saveImportInvoice）。</li>
 * </ul>
 * 仅对<b>小规模</b>有意义；一般纳税人恒按月（与本配置无关，见 AccountSets#isQuarterlyInvoiceCollect）。
 */
public enum InvoiceCollectMode {
    QUARTERLY("季度"), MONTHLY("月度");

    public final String display;

    InvoiceCollectMode(String display) {
        this.display = display;
    }
}
