package cn.gson.financial.kernel.model.ua.request;

import cn.gson.financial.kernel.model.entity.SalaryField;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-05-16
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class SaveSalaryFiledReq extends GetCommonReq{

    /**
     * 字段信息
     */
    private List<SalaryField> salaryFields;
}
