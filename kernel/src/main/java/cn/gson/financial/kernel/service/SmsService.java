package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.SmsConfig;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.sms4j.aliyun.config.AlibabaConfig;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.dromara.sms4j.comm.constant.SupplierConstant;
import org.dromara.sms4j.core.factory.SmsFactory;
import org.dromara.sms4j.huawei.config.HuaweiConfig;
import org.dromara.sms4j.provider.config.BaseConfig;
import org.dromara.sms4j.provider.factory.BaseProviderFactory;
import org.dromara.sms4j.provider.factory.ProviderFactoryHolder;
import org.dromara.sms4j.tencent.config.TencentConfig;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年05月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmsService {

    private final SmsConfigService smsConfigService;

    /**
     * 发送验证码
     *
     * @param phone
     * @param code
     * @return
     */
    public SmsResponse sendCaptcha(String phone, String code) {
        SmsConfig smsConfig = getSmsConfig();
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        return SmsFactory.getSmsBlend(smsConfig.getVendor()).sendMessage(phone, smsConfig.getCaptchaTemplateId(), params);
    }

    /**
     * FN-T4 发送"发票已开具"短信。
     * <p>
     * 模板 ID 走 {@link SmsConfig#getInvoiceDeliveredTemplateId()}（运营在管理端配）。
     * 模板未配置时抛 {@link IllegalStateException}，由调用方决定是否进 DLQ / 跳过。
     * 模板参数仅传 {@code invoiceNumber}（短信模板一般包含 ${invoiceNumber}）。
     *
     * @param phone         收件人手机号
     * @param invoiceNumber 发票号码
     * @return sms4j 通用响应（含 success / message / bizId）
     */
    public SmsResponse sendInvoiceDelivered(String phone, String invoiceNumber) {
        SmsConfig smsConfig = getSmsConfig();
        String templateId = smsConfig.getInvoiceDeliveredTemplateId();
        Assert.notBlank(templateId, "发票送达短信模板未配置（sms_config.invoice_delivered_template_id）");
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("invoiceNumber", invoiceNumber);
        return SmsFactory.getSmsBlend(smsConfig.getVendor()).sendMessage(phone, templateId, params);
    }

    /**
     * FN6-T1 发送"开票被驳回"短信。
     * <p>
     * 模板 ID 走 {@link SmsConfig#getInvoiceRejectedTemplateId()}。模板参数仅传 {@code comment}
     * 透传驳回原因（短信模板一般含 ${comment}）。驳回原因可空，空时用占位 "未填写"。
     *
     * @param phone   收件人手机号
     * @param comment 驳回原因（可空）
     * @return sms4j 通用响应
     */
    public SmsResponse sendInvoiceRejected(String phone, String comment) {
        SmsConfig smsConfig = getSmsConfig();
        String templateId = smsConfig.getInvoiceRejectedTemplateId();
        Assert.notBlank(templateId, "开票被驳回短信模板未配置（sms_config.invoice_rejected_template_id）");
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("comment", comment == null || comment.isBlank() ? "未填写" : comment);
        return SmsFactory.getSmsBlend(smsConfig.getVendor()).sendMessage(phone, templateId, params);
    }

    /**
     * FN8-T2 发送"发票已红冲"短信。
     * <p>
     * 模板参数：{@code originalInvoiceNumber}（原蓝字发票号）、{@code redInvoiceNumber}（红字发票号）。
     *
     * @param phone                 收件人手机号
     * @param originalInvoiceNumber 原蓝字发票号（被红冲的那张）
     * @param redInvoiceNumber      红字发票号
     */
    public SmsResponse sendRedLetterIssued(String phone, String originalInvoiceNumber, String redInvoiceNumber) {
        SmsConfig smsConfig = getSmsConfig();
        String templateId = smsConfig.getRedLetterTemplateId();
        Assert.notBlank(templateId, "发票红冲短信模板未配置（sms_config.red_letter_template_id）");
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("originalInvoiceNumber", originalInvoiceNumber == null ? "" : originalInvoiceNumber);
        params.put("redInvoiceNumber", redInvoiceNumber == null ? "" : redInvoiceNumber);
        return SmsFactory.getSmsBlend(smsConfig.getVendor()).sendMessage(phone, templateId, params);
    }

    private SmsConfig getSmsConfig() {
        SmsConfig smsConfig = smsConfigService.load();
        Assert.notNull(smsConfig, "短信参数未配置～");
        BaseConfig supplierConfig = switch (smsConfig.getVendor()) {
            case SupplierConstant.ALIBABA -> BeanUtil.toBean(smsConfig, AlibabaConfig.class);
            case SupplierConstant.TENCENT -> BeanUtil.toBean(smsConfig, TencentConfig.class);
            case SupplierConstant.HUAWEI -> BeanUtil.toBean(smsConfig, HuaweiConfig.class);
            default -> throw new IllegalStateException("不支持的短信厂商: " + smsConfig.getVendor());
        };
        BaseProviderFactory factory = ProviderFactoryHolder.requireForSupplier(smsConfig.getVendor());
        supplierConfig.setConfigId(smsConfig.getVendor());
        SmsFactory.register(factory.createSms(supplierConfig));
        return smsConfig;
    }
}
