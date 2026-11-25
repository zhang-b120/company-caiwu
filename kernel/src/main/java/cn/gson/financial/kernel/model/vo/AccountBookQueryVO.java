package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 
 * <p><b>Copyright © 2010-2023开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年01月17日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class AccountBookQueryVO {

    private Long accountSetsId;
    private Long subjectId;
    private Long currencyId;
    private Long localCurrencyId;
    private LocalDate accountDate;
    private LocalDate start;
    private LocalDate end;
    private String subjectStart;
    private String subjectEnd;
    private Integer levelStart;
    private Integer levelEnd;
    private String subjectCode;
    private String summary;
    private Boolean showNumPrice = false;
    private AccountingCategoryType categoryType;
    private String categoryId;
    private Long detailId;
    private boolean auxiliary = false;
    private List<Long> detailIds;
    private List<Long> subjectIds;

    public boolean isSubjectBlock() {
        return StrUtil.isNotEmpty(this.subjectStart) && StrUtil.isNotEmpty(this.subjectEnd);
    }

    public void setSubjectEnd(String subjectEnd) {
        this.subjectEnd = subjectEnd;
    }

    public String getSubjectCode() {
        if (this.isSubjectBlock() || StrUtil.isNotEmpty(this.subjectStart)) {
            return subjectStart;
        } else if (StrUtil.isNotEmpty(this.subjectEnd)) {
            return subjectEnd;
        } else {
            return subjectCode;
        }
    }

    public void setDetailId(Long detailId) {
        this.detailId = detailId;
        if (CollUtil.isEmpty(detailIds)) {
            this.detailIds = new ArrayList<>();
            this.detailIds.add(detailId);
        }
    }

    public void setSubjectCode(String subjectCode) {
        if (StrUtil.isNotEmpty(subjectCode)) {
            this.subjectCode = subjectCode + "%";
        }
    }

    public LocalDate getEnd() {
        return end != null ? end.withDayOfMonth(end.lengthOfMonth()) : null;
    }

    public LocalDate getStart() {
        return start != null ? start.withDayOfMonth(1) : null;
    }

    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }

    public Long getCurrencyId() {
        if (currencyId != null) {
            if (currencyId == 0L) {
                return null;
            } else if (currencyId == 1L) {
                return localCurrencyId;
            }
            return currencyId;
        }
        return localCurrencyId;
    }
}
