package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.Subject;
import cn.hutool.core.collection.CollUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class SubjectVo extends Subject {

    private List<SubjectVo> children = new ArrayList<>();

    private List<String> auxiliaryAccountingNames;

    private List<String> currencyAccountingNames;

    private String auxiliaryAccountingStr;

    private String currencyAccountingStr;

    private String aan;

    private String can;

    private String typeAlias;

    public String getAan() {
        return CollUtil.join(auxiliaryAccountingNames, "/");
    }

    public String getCan() {
        return CollUtil.join(currencyAccountingNames, "/");
    }

    /**
     * 设置科目类型(导出使用)
     *
     * @param type 科目类型
     */
    @Override
    public void setType(String type) {
        super.setType(type);
        this.typeAlias = type + "类";
    }

    @Override
    public void setCash(Boolean cash) {
        super.setCash(Optional.ofNullable(cash).orElse(false));
    }

    @Override
    public Boolean getCash() {
        return Optional.ofNullable(super.getCash()).orElse(false);
    }
}
