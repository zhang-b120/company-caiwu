package cn.gson.financial.kernel.model.ua.request;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.entity.Salary;
import cn.gson.financial.kernel.model.entity.SalaryField;
import cn.gson.financial.kernel.model.vo.SalaryDetailVo;
import cn.gson.financial.kernel.service.SalaryEmployeeService;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;

/**
 * @Author zhb
 * @Date 2025-05-16
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class SaveSalaryReq extends GetCommonReq{

    /**
     * 日期
     */
    private LocalDate period;

    /**
     *
     */
    private SalaryDetailVo detailVo;
}
