package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.annotation.ColName;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月23日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class InvoiceImportVo {
    @ColName("发票票种")
    private String newCategory;
    @ColName("开票日期")
    private LocalDate tradeDate;
    @ColName("发票代码")
    private String invoiceCode;
    @ColName("发票号码")
    private String invoiceNumber;
    @ColName("数电发票号码")
    private String netNumber;
    @ColName("销方识别号")
    private String pinNumber;
    @ColName("销方名称")
    private String pinName;
    @ColName("购方识别号")
    private String buyerNumber;
    @ColName("购买方名称")
    private String buyerName;
    @ColName("备注")
    private String remark;
    @ColName("发票状态")
    private String state;
    @ColName("金额")
    private BigDecimal taxAmountExcluded;
    @ColName("税额")
    private BigDecimal taxAmount;
    @ColName("价税合计")
    private BigDecimal amount;

    public InvoiceState getState() {
        if (StrUtil.isNotEmpty(state)) {
            return switch (state) {
                case "正常" -> InvoiceState.正常;
                case "已作废" -> InvoiceState.作废;
                case "已红冲-全额" -> InvoiceState.全额红冲;
                case "已红冲-部分" -> InvoiceState.部分红冲;
                default -> throw new IllegalStateException(StrUtil.emptyToDefault(invoiceNumber, netNumber) + "错误的发票状态: " + state);
            };
        }
        throw new IllegalStateException(StrUtil.emptyToDefault(invoiceNumber, netNumber) + "发票状态为空请检查~");
    }

    /**
     * 根据纳税人识别号在发票中的销方和购方判断发票类型
     * <p>
     * 说明：
     * - 若纳税人识别号匹配销方或购方，则返回对应的发票类型；
     * - 若不匹配，则返回 {@code null}，交由上层逻辑决定是否跳过该发票；
     * - 若传入的纳税人识别号为空，则视为配置错误，仍然抛出异常。
     *
     * @param creditCode 纳税人识别号（企业本身识别号）
     * @return 发票类型，若不匹配返回 {@code null}
     */
    public InvoiceType getInvoiceType(String creditCode) {
        Assert.notEmpty(creditCode, "纳税人识别号不能为空");
        // 销方和购方识别号都为空，无法判断发票类型，直接返回 null
        if (StrUtil.isEmpty(pinNumber) && StrUtil.isEmpty(buyerNumber)) {
            return null;
        }
        // 匹配销方识别号：视为销项发票
        if (StrUtil.isNotEmpty(pinNumber) && creditCode.equals(pinNumber)) {
            return InvoiceType.OUT;
        }
        // 匹配购方识别号：视为进项发票
        if (StrUtil.isNotEmpty(buyerNumber) && creditCode.equals(buyerNumber)) {
            return InvoiceType.IN;
        }
        // 不匹配时不再抛异常，返回 null，让调用方自行决定是否跳过该发票
        return null;
    }
}
