package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年07月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class FeeDetailDto {
    private Long id;
    private String code;
    private String name;
    private Long parentId;
    private String fullName;
    List<FeeDetailBalanceDto> details = new ArrayList<>();
}
