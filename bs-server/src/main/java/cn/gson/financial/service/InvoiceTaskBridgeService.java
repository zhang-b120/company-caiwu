package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Customer;
import cn.gson.financial.kernel.model.entity.InvoiceCollectRequest;
import cn.gson.financial.kernel.model.entity.InvoiceTask;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.CustomerRepository;
import cn.gson.financial.kernel.model.repo.InvoiceTaskRepository;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * FN-INV-T2（132）+ FN2-T2/T3（135）扫码直开搭桥 collect → invoice_task。
 * <p>
 * 状态机：建任务直接 APPROVED（跳过 CUSTOMER_REQUESTED/CS_REVIEWING）；BT 拉 → /start → EXECUTING → /result。
 * <p>
 * 幂等：同 {@code order_no} 已有任务直接返已存（{@code uk_task_order_no} UNIQUE 兜底）。
 * <p>
 * <b>135 改造</b>：
 * <ul>
 *   <li>{@code account_sets_id} 用 collect 真值（去 132 占位 1）</li>
 *   <li>{@code region_code} = {@code AccountSets.province}（BT 据此选省）</li>
 *   <li>{@code customer_id} 按购方税号 match {@code Customer.creditCode}（同账套内），不到则 NULL</li>
 *   <li>{@code source = "scan-direct"}（强类型，替代 132 借 request_remark）</li>
 *   <li>校验失败抛 {@link IllegalArgumentException} / {@link IllegalStateException}，
 *       controller 包络成 {@code errorType=VALIDATION/TASK_CREATE_FAILED}（FN2-T4）</li>
 * </ul>
 */
/**
 * @deprecated FN-U-T5（140）：collect 单表统一开票任务后，<b>不再搭桥建 invoice_task</b>。
 * 本类已无任何控制器调用，保留作历史/回滚，不删。invoice_task 表保留只读、不再写入。
 */
@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceTaskBridgeService {

    private final InvoiceTaskRepository invoiceTaskRepository;
    private final AccountSetsRepository accountSetsRepository;
    private final CustomerRepository customerRepository;

    /**
     * 校验扫码直开 collect 能否升任务（FN2-T4：controller 先调，失败 → errorType=VALIDATION）。
     * 不动 DB；纯 check。
     */
    public void validateForBridge(InvoiceCollectRequest collect) {
        if (collect == null) throw new IllegalArgumentException("collect 不能为空");
        if (isBlank(collect.getOrderNo())) throw new IllegalArgumentException("orderNo 必填");
        if (collect.getAccountSetsId() == null) {
            throw new IllegalArgumentException("accountSetsId 必填（扫码直开必须带商家账套）");
        }
        // 抬头四项 collect addHeader 已强制；bridge 这里兜底
        if (isBlank(collect.getBuyerCompany())) throw new IllegalArgumentException("抬头 companyName 必填");
        if (isBlank(collect.getBuyerTaxNo()))   throw new IllegalArgumentException("抬头 taxNo 必填");
        if (isBlank(collect.getBuyerPhone()))   throw new IllegalArgumentException("抬头 phone 必填");
        if (isBlank(collect.getBuyerEmail()))   throw new IllegalArgumentException("抬头 email 必填");
        // 明细每项必填：name/qty/price/amount/taxRate；code/spec/unit/discount/policy/coal 可空
        parseAndValidateItems(collect.getItemsJson());
    }

    /**
     * collect=COLLECTED 后调用：按 order_no 幂等建/取扫码直开任务。
     * <p>
     * REQUIRED 传播：与 collect 同事务；DB 异常自动回滚 collect。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public InvoiceTask createFromCollect(InvoiceCollectRequest collect) {
        validateForBridge(collect);
        String orderNo = collect.getOrderNo().trim();

        // 幂等：已有任务直接返
        Optional<InvoiceTask> exist = invoiceTaskRepository.findByOrderNo(orderNo);
        if (exist.isPresent()) {
            log.info("[FN-INV-T2] orderNo={} 任务已存在 id={} status={}，幂等返已存",
                    orderNo, exist.get().getId(), exist.get().getStatus());
            return exist.get();
        }

        // FN2-T2：从账套解析省份
        AccountSets account = accountSetsRepository.findById(collect.getAccountSetsId())
                .orElseThrow(() -> new IllegalStateException(
                        "账套不存在 accountSetsId=" + collect.getAccountSetsId() + "，无法建扫码直开任务"));
        if (isBlank(account.getProvince())) {
            throw new IllegalStateException("账套 " + account.getId() + "(" + account.getCompanyName()
                    + ") 未配置省份(province)，BT 无法选电税局；请运营在账套维护页补省份后重试");
        }

        // FN2-T2：customer_id match
        Long customerId = resolveCustomerId(collect, account.getId());

        JSONArray items = parseAndValidateItems(collect.getItemsJson());
        BigDecimal[] sums = sumItems(items);

        InvoiceTask t = new InvoiceTask();
        t.setOrderNo(orderNo);
        t.setItemsJson(collect.getItemsJson());
        t.setPurchaserName(collect.getBuyerCompany());
        t.setPurchaserCreditCode(collect.getBuyerTaxNo());
        t.setApplicantPhone(collect.getBuyerPhone());
        t.setApplicantEmail(collect.getBuyerEmail());
        t.setAccountSetsId(account.getId());                       // ← FN2-T2 真账套
        t.setRegionCode(account.getProvince().trim());             // ← FN2-T2 真省份
        t.setCustomerId(customerId);                                // ← FN2-T2 match 或 NULL（已放宽 NOT NULL）
        t.setSource(InvoiceTask.SOURCE_SCAN_DIRECT);                // ← FN2-T3 强类型 source
        t.setInvoiceType("NORMAL");
        t.setIsRedInvoice(Boolean.FALSE);
        t.setIsRedInvoiceRequested(Boolean.FALSE);
        t.setStatus(InvoiceTask.STATUS_APPROVED);
        t.setRequestAmount(sums[0]);
        t.setRequestTaxAmount(sums[1]);

        try {
            t = invoiceTaskRepository.save(t);
        } catch (DataIntegrityViolationException dup) {
            log.warn("[FN-INV-T2] orderNo={} 并发建任务冲突，回退查已存", orderNo);
            return invoiceTaskRepository.findByOrderNo(orderNo)
                    .orElseThrow(() -> new IllegalStateException(
                            "DataIntegrityViolation 但 findByOrderNo 也空，orderNo=" + orderNo));
        }
        log.info("[FN-INV-T2/T3] 建扫码直开任务 ok：id={}, orderNo={}, accountSetsId={}, regionCode={}, customerId={}, source={}",
                t.getId(), t.getOrderNo(), t.getAccountSetsId(), t.getRegionCode(),
                t.getCustomerId(), t.getSource());
        return t;
    }

    /**
     * FN-SLIM：collect.customer_id 已删；仅按购方税号在账套内 match Customer.creditCode。
     */
    private Long resolveCustomerId(InvoiceCollectRequest collect, Long accountSetsId) {
        String taxNo = collect.getBuyerTaxNo();
        if (isBlank(taxNo)) return null;
        return customerRepository.findByAccountSetsIdAndCreditCode(accountSetsId, taxNo.trim())
                .map(Customer::getId)
                .orElse(null);
    }

    private static JSONArray parseAndValidateItems(String itemsJson) {
        JSONArray arr;
        try { arr = JSON.parseArray(itemsJson); }
        catch (Exception e) { throw new IllegalArgumentException("items_json 解析失败: " + e.getMessage()); }
        if (arr == null || arr.isEmpty()) throw new IllegalArgumentException("items 至少 1 条");
        for (int i = 0; i < arr.size(); i++) {
            var it = arr.getJSONObject(i);
            if (isBlank(it.getString("name")))    throw new IllegalArgumentException("items[" + i + "].name 必填");
            if (it.get("qty") == null)            throw new IllegalArgumentException("items[" + i + "].qty 必填");
            if (isBlank(it.getString("price")))   throw new IllegalArgumentException("items[" + i + "].price 必填");
            if (isBlank(it.getString("amount")))  throw new IllegalArgumentException("items[" + i + "].amount 必填");
            if (isBlank(it.getString("taxRate"))) throw new IllegalArgumentException("items[" + i + "].taxRate 必填");
        }
        return arr;
    }

    /** items 合计 → request_amount(含税合计) + request_tax_amount = amount*rate/(1+rate) 累加 */
    private static BigDecimal[] sumItems(JSONArray items) {
        BigDecimal amount = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            var it = items.getJSONObject(i);
            BigDecimal a = new BigDecimal(it.getString("amount"));
            BigDecimal rate = new BigDecimal(it.getString("taxRate"));
            amount = amount.add(a);
            tax = tax.add(a.multiply(rate).divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP));
        }
        return new BigDecimal[]{amount.setScale(2, RoundingMode.HALF_UP), tax.setScale(2, RoundingMode.HALF_UP)};
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
