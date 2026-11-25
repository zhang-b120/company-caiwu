package cn.gson.financial.kernel.model.ua.request;

import cn.gson.financial.kernel.model.dto.SalarySocialEmployeeDto;
import cn.gson.financial.kernel.model.entity.Employee;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;


/**
 * @Author zhb
 * @Date 2025-05-15
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class SaveEmployeeReq extends GetCommonReq{

    /**
     * 员工信息
     */
    private Employee employee;
    /**
     * 社保项目
     */
    private List<SalarySocialEmployeeDto> socials;
}
