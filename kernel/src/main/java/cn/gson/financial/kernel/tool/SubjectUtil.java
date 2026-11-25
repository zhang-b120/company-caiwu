package cn.gson.financial.kernel.tool;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.finance.kernel.util</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public final class SubjectUtil {
    /**
     * 判断科目编码是否符合账套编码规则（不抛异常）。
     *
     * @param code     科目编码
     * @param encoding 账套编码规则，如 [4,3,3,2]
     * @return 符合规则返回 true，否则 false
     */
    public static boolean isValidCode(String code, List<Integer> encoding) {
        if (StrUtil.isBlank(code) || encoding == null || encoding.isEmpty()) {
            return false;
        }
        int start = 0;
        for (Integer len : encoding) {
            if (start >= code.length()) {
                break;
            }
            String sub = code.substring(start, Math.min(len + start, code.length()));
            if (sub.isEmpty()) {
                break;
            }
            if (sub.length() != len) {
                return false;
            }
            try {
                if (NumberUtil.parseInt(sub) < 0) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
            start += len;
        }
        return start == code.length();
    }

    /**
     * 编码格式检查
     *
     * @param code
     */
    public static List<String> checkCode(@NonNull String code, List<Integer> encoding) {
        int start = 0;
        String msg = "编码：" + code + ",不符合格式：" + StrUtil.join("-", encoding);
        List<String> codePath = new ArrayList<>();
        for (Integer len : encoding) {
            String sub = code.substring(start, Math.min(len + start, code.length()));
            if (sub.isEmpty()) {
                break;
            }
            Assert.isTrue(sub.length() == len && NumberUtil.parseInt(sub) >= 0, msg);
            start += len;
            codePath.add(sub);
        }
        return codePath;
    }

    /**
     * 根据新编码规则，生成新编码
     *
     * @param code
     * @param oldModel
     * @param newModel
     * @return
     */
    public static String updateCode(String code, List<Integer> oldModel, List<Integer> newModel) {
        List<String> codeSp = checkCode(code, oldModel);
        List<String> newCodeSp = new ArrayList<>();

        for (int i = 0; i < codeSp.size(); i++) {
            String item = codeSp.get(i);
            int len = newModel.get(i);
            if (item != null) {
                if (item.length() < len) {
                    item = StrUtil.padPre(Integer.parseInt(item) + "", newModel.get(i), "0");
                } else if (item.length() > len) {
                    String substring = item.substring(item.length() - len);
                    Assert.isTrue(item.startsWith("0"), "编码：{}不能转换为{}，请人工处理～", item, substring);
                    item = substring;
                }
                newCodeSp.add(item);
            }
        }
        return StrUtil.join("", newCodeSp);
    }
}
