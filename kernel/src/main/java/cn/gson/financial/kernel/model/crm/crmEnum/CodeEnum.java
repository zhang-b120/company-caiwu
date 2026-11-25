package cn.gson.financial.kernel.model.crm.crmEnum;

/**
 * @Author zhb
 * @Date 2025-07-06
 * @Description
 */
public enum CodeEnum {

    CODE_200("200","成功"),
    CODE_201("201","当前账号的办税员身份与公司不匹配，切换登录账号或者联系管理员进行维护"),
    CODE_202("202","页面加载超时，请稍后重试"),
    CODE_203("203","未获取的数据");

    private String code;
    private String msg;

    public String getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    CodeEnum(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static String getMsgByCode(String code) {
        for (CodeEnum c : CodeEnum.values()) {
            if (code.equals(c.code)) {
                return c.msg;
            }
        }
        return null;
    }
}