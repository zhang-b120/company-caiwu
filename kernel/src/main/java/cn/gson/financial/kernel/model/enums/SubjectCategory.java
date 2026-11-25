package cn.gson.financial.kernel.model.enums;

import java.util.Arrays;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 科目类型</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum SubjectCategory {
    ASSET("资产"),
    LIABILITY("负债"),
    RIGHTS("权益"),
    COST("成本"),
    INCOME("损益"),
    COMMON("共同"),
    NET_ASSETS("净资产"),
    REVENUE("收入"),
    EXPENSES("费用");

    public final String display;

    SubjectCategory(String display) {
        this.display = display;
    }

    public static SubjectCategory conv(String display) {
        return Arrays.stream(SubjectCategory.values()).filter(s -> s.display.equals(display)).findFirst().orElse(null);
    }
}
