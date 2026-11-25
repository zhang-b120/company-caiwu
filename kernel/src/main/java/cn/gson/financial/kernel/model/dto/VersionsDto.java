package cn.gson.financial.kernel.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-01 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-01</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
public class VersionsDto implements Serializable {
    private Long id;

    private Long adminId;

    private String adminName;

    private LocalDate createDate;

    private LocalDate publishDate;

    private String versionName;

    private String content;

}