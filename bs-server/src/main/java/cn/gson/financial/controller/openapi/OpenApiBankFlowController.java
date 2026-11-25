package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.BankStatementDetail;
import cn.gson.financial.kernel.model.entity.QBankStatementDetail;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放 API — 单笔银行流水详情（BT 审核工作站"漏的银行流水"明细用）
 * <p>
 * GET /openapi/v1/bank-flow/{id}?accountSetsId=88
 * 返回 BankStatementDetail 完整字段 + voucherId / cashierJournalId 关联状态。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/bank-flow")
@SaIgnore
public class OpenApiBankFlowController {

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QBankStatementDetail qBsd = QBankStatementDetail.bankStatementDetail;

    @GetMapping("/{id}")
    public JsonResult detail(@PathVariable Long id,
                             @RequestParam Long accountSetsId) {
        if (id == null) return JsonResult.failure("id 不能为空");
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");

        BankStatementDetail r = bqf.selectFrom(qBsd)
                .where(qBsd.id.eq(id)
                        .and(qBsd.accountSetId.eq(accountSetsId))
                        .and(qBsd.isDelete.eq(0).or(qBsd.isDelete.isNull())))
                .fetchFirst();
        if (r == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "NOT_FOUND");
            return JsonResult.failure("流水不存在或不属于该账套：" + id).setData(data);
        }
        return JsonResult.successful(toDto(r));
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-OPENAPI-BANK-FLOW-LIST
    // GET /openapi/v1/bank-flow/list?accountSetsId=88&year=2025&month=12
    //
    // BT 工作台银行流水面板「查看」按钮用：列出某账套某期已入库的银行流水明细
    // + 入/出金额汇总。复用现有 toDto / inferDirection，不重写。
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/list")
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam Integer year,
                           @RequestParam Integer month) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (year == null || month == null) return JsonResult.failure("year/month 不能为空");
        if (month < 1 || month > 12) return JsonResult.failure("month 取值范围 1-12：" + month);

        List<BankStatementDetail> rows = fetchMonthlyDetails(accountSetsId, year, month);

        List<Map<String, Object>> result = new ArrayList<>();
        BigDecimal inSum = BigDecimal.ZERO;
        BigDecimal outSum = BigDecimal.ZERO;
        for (BankStatementDetail r : rows) {
            Map<String, Object> dto = toDto(r);
            result.add(dto);
            BigDecimal amt = r.getTradeAmount() != null ? r.getTradeAmount().abs() : BigDecimal.ZERO;
            if ("in".equals(dto.get("direction"))) {
                inSum = inSum.add(amt);
            } else {
                outSum = outSum.add(amt);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", result);
        data.put("total", result.size());
        data.put("inSum", inSum);
        data.put("outSum", outSum);
        return JsonResult.successful(data);
    }

    /**
     * 查某账套某期（trade_date 落在该月范围内）未删除的银行流水明细。
     * <p>trade_date 字段是 LocalDateTime（DB datetime），按月首 00:00:00 ~ 月末 23:59:59 between。
     * package-private 是为了单测能 override 规避 BlazeJPAQuery 难 mock 的问题。
     */
    List<BankStatementDetail> fetchMonthlyDetails(Long accountSetsId, Integer year, Integer month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return bqf.selectFrom(qBsd)
                .where(qBsd.accountSetId.eq(accountSetsId)
                        .and(qBsd.tradeDate.between(start.atStartOfDay(), end.atTime(23, 59, 59)))
                        .and(qBsd.isDelete.eq(0).or(qBsd.isDelete.isNull())))
                .orderBy(qBsd.tradeDate.asc(), qBsd.id.asc())
                .fetch();
    }

    private Map<String, Object> toDto(BankStatementDetail r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("accountSetsId", r.getAccountSetId());
        m.put("bankStatementId", r.getBankStatementId());

        // 基础信息
        m.put("serialNo", r.getSerialNo());
        m.put("tradeDate", r.getTradeDate());
        m.put("tradeAmount", r.getTradeAmount());
        m.put("balance", r.getBalance());
        m.put("currencyType", r.getCurrencyType());
        m.put("bankName", r.getBankName());
        m.put("ownAccountNumber", r.getAccountNumber());

        // 推算方向（与 OpenApiBankController 一致逻辑）
        String direction = inferDirection(r);
        m.put("direction", direction);

        // 对方 + 我方信息（按方向取）
        boolean isIn = "in".equals(direction);
        m.put("counterpartyName", isIn ? r.getPayName() : r.getReceiveName());
        m.put("counterpartyAccount", isIn ? r.getPayAccountNum() : r.getReceiveAccountNum());
        m.put("counterpartyBankName", isIn ? r.getPayBankName() : r.getReceiveBankName());

        // 原始 pay/receive 字段也一并暴露（审核场景需要看原始信息）
        m.put("payName", r.getPayName());
        m.put("payAccountNum", r.getPayAccountNum());
        m.put("payBankName", r.getPayBankName());
        m.put("payBankNum", r.getPayBankNum());
        m.put("receiveName", r.getReceiveName());
        m.put("receiveAccountNum", r.getReceiveAccountNum());
        m.put("receiveBankName", r.getReceiveBankName());
        m.put("receiveBankNum", r.getReceiveBankNum());

        // 业务字段
        m.put("transactionType", r.getTransactionType());
        m.put("businessType", r.getBusinessType());
        m.put("remarks", r.getRemarks());

        // 关联状态（BT 审核工作站重点关心）
        m.put("voucherId", r.getVoucherId());
        m.put("cashierJournalId", r.getCashierJournalId());
        m.put("cashierAccountId", r.getCashierAccountId());
        m.put("subjectId", r.getSubjectId());
        m.put("ioTypeId", r.getIoTypeId());

        // 派生状态：是否还需要做账
        boolean processed = r.getVoucherId() != null;
        m.put("processed", processed);
        m.put("processStatus", processed ? "VOUCHERED"
                : (r.getCashierJournalId() != null ? "JOURNALED" : "PENDING"));
        return m;
    }

    /**
     * 推算交易方向（与 OpenApiBankController.inferDirection 保持一致）：
     *  - 本方账号 == receiveAccountNum → in（收款）
     *  - 本方账号 == payAccountNum → out（付款）
     *  - 兜底：tradeAmount 负号 / receive 与 pay 谁有值
     */
    private String inferDirection(BankStatementDetail r) {
        String own = r.getAccountNumber();
        if (StrUtil.isNotBlank(own)) {
            if (own.equals(r.getReceiveAccountNum())) return "in";
            if (own.equals(r.getPayAccountNum())) return "out";
        }
        BigDecimal amt = r.getTradeAmount();
        if (amt != null && amt.signum() < 0) return "out";
        if (StrUtil.isNotBlank(r.getReceiveAccountNum()) && StrUtil.isBlank(r.getPayAccountNum())) {
            return "out";
        }
        return "in";
    }
}
