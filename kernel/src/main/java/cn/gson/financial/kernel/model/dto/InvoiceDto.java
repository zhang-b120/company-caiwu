package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.annotation.ColName;
import cn.gson.financial.kernel.model.enums.InvoiceCategory;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.Invoice} entity
 */
@Getter
@Setter
public class InvoiceDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    @ColName(value = "发票种类")
    private InvoiceCategory category;
    @ColName(value = "开票日期")
    private LocalDate tradeDate;
    @ColName(value = "发票代码")
    private String invoiceCode;
    @ColName(value = "发票号码")
    private String invoiceNumber;
    @ColName("备用发票号")
    private String invoiceNumber1;
    @ColName(value = "发票状态")
    private InvoiceState state;
    private InvoiceType type;
    private LocalDate checkDate;
    @ColName(value = "客户/供应商名称")
    private String customerName;
    @ColName(value = "纳税人识别号")
    private String creditCode;
    @ColName(value = "地址及电话")
    private String address;
    @ColName(value = "开户行及账号")
    private String account;
    @ColName(value = "业务类型")
    private String businessType;
    @ColName(value = "合计金额")
    private BigDecimal taxAmountExcluded;
    private BigDecimal rate;
    @ColName(value = "税额")
    private String rateStr;
    @ColName(value = "合计税额")
    private BigDecimal taxAmount;
    @ColName(value = "价税合计")
    private BigDecimal amount;
    private Boolean haveDetail;
    private Long invoiceBusinessTypeId;
    private Long voucherId;
    @ColName(value = "关联凭证")
    private String voucher;
    @ColName(value = "备注")
    private String note;
    private List<InvoiceItemsDto> items;
    private String remark;
    private String pdfPath;
    private String newCategory;
    private String goods;
    private LocalDate checkRiQi;
    //指定日期
    private LocalDate assignmentDate;
    //显示状态
    private String showStatus;
    public void setItems(List<InvoiceItemsDto> items) {
        this.items = items;
        if (CollUtil.isNotEmpty(items)) {
            this.goods = items.stream().map(InvoiceItemsDto::getGoodsName).filter(StrUtil::isNotEmpty).collect(Collectors.joining("，"));
        }
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
        if (rate != null) {
            this.rateStr = NumberUtil.round(rate, 2) + "%";
        }
    }
}
