package cn.gson.financial.ai;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.enums.TaxpayerType;

/**
 * FN-AI-T1（185）— 卖方上下文，按 {@code accountSetsId} 自动从 {@link AccountSets} 装配，
 * 用户不填。喂给 Qwen 的【卖方上下文】块据此渲染。
 *
 * <p>纳税人类型用权威派生 {@link TaxpayerType#fromAccountSets}（个体→INDIVIDUAL、一般→GENERAL、
 * 其余→SMALL_SCALE），约束后续税率纠偏与普/专票推断。
 */
public class AiSellerContext {

    public final Long accountSetsId;
    public final String companyName;
    public final String creditCode;
    public final TaxpayerType taxpayerType;
    public final String businessScope;
    public final String industryName;
    public final String companyOrgType;
    public final String aiProfile;

    private AiSellerContext(Long accountSetsId, String companyName, String creditCode,
                           TaxpayerType taxpayerType, String businessScope, String industryName,
                           String companyOrgType, String aiProfile) {
        this.accountSetsId = accountSetsId;
        this.companyName = companyName;
        this.creditCode = creditCode;
        this.taxpayerType = taxpayerType;
        this.businessScope = businessScope;
        this.industryName = industryName;
        this.companyOrgType = companyOrgType;
        this.aiProfile = aiProfile;
    }

    public static AiSellerContext fromAccountSets(AccountSets as) {
        TaxpayerType tt = TaxpayerType.fromAccountSets(as.getIndividualBusiness(), as.getVatType());
        return new AiSellerContext(
                as.getId(),
                as.getCompanyName(),
                as.getCreditCode(),
                tt,
                as.getBusinessScope(),
                as.getIndustryName(),
                as.getCompanyOrgType(),
                as.getAiProfile());
    }

    /** 渲染进 user 提示词的【卖方上下文】文本块（只读，喂模型用）。 */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        // 242：销方名称/税号是用户选定账套的确定值，模型抽购方根本不需要它们——源头不写进 prompt，
        // 釜底抽薪杜绝"模型把卖方上下文抄成购方"（购方=销方）。纳税人类型/企业类型/行业/经营范围/简介保留（模型定税率/编码仍需）。
        sb.append("纳税人类型：").append(taxpayerType.name())
                .append('（').append(taxpayerType.vaultTag).append('）').append('\n');
        if (notBlank(companyOrgType)) sb.append("企业类型：").append(companyOrgType).append('\n');
        if (notBlank(industryName)) sb.append("行业：").append(industryName).append('\n');
        if (notBlank(businessScope)) sb.append("经营范围：").append(trunc(businessScope, 500)).append('\n');
        if (notBlank(aiProfile)) sb.append("企业简介：").append(trunc(aiProfile, 500)).append('\n');
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trunc(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
