package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.SalaryVoucherTemplate;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-11 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-11</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Data
public class SalaryVoucherTemplateDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String title;
    private Long voucherWord;
    private String word;
    private String status;
    private BigDecimal amount;
    private Boolean isAccrual;
    private Boolean def;
    private List<SalaryVoucherTemplate.VoucherItems> items;

    public void setItems(List<SalaryVoucherTemplate.VoucherItems> items) {
        if (CollUtil.isNotEmpty(items)) {
            this.items = items.stream().filter(it -> StrUtil.isNotEmpty(it.getSubjectId())).toList();
        } else {
            this.items = new ArrayList<>();
        }
    }

    public List<SalaryVoucherTemplate.VoucherItems> getItems() {
        if (CollUtil.isNotEmpty(items)) {
            return items.stream().filter(it -> StrUtil.isNotEmpty(it.getSubjectId())).toList();
        } else {
            return new ArrayList<>();
        }
    }
}
