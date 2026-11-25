package cn.gson.financial.kernel.model.dto;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-05 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-05</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
public class EmployeeDetailDto implements Serializable {
    private Long id;

    private Long provisionId;

    private Long employeeId;

    private LocalDate createDate;

    private String idCard;

    private String email;

    private String edu;

    private String bank;

    private String bankCard;

    private String openBank;

    private String status;

    private Boolean purchase;
}