package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.BankStatementDetail;
import cn.gson.financial.kernel.model.entity.QBankStatementDetail;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 银行流水（BankStatementDetail）
 * 路径前缀 /openapi/v1/bank
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/bank")
@SaIgnore
public class OpenApiBankController {

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QBankStatementDetail q = QBankStatementDetail.bankStatementDetail;

    /**
     * §2.4 银行流水
     * GET /openapi/v1/bank/statements?accountSetsId=88&period=2026-05&includeProcessed=false&page=1&size=200
     */
    @GetMapping("/statements")
    public JsonResult statements(@RequestParam Long accountSetsId,
                                 @RequestParam String period,
                                 @RequestParam(required = false, defaultValue = "false") Boolean includeProcessed,
                                 @RequestParam(required = false) Boolean voucherIdIsNull,
                                 @RequestParam(required = false, defaultValue = "1") Integer page,
                                 @RequestParam(required = false, defaultValue = "200") Integer size) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (Exception e) {
            return JsonResult.failure("period 格式错误，应为 YYYY-MM：" + period);
        }
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 200;
        if (size > 500) size = 500;

        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.atEndOfMonth().atTime(23, 59, 59);

        BooleanBuilder where = new BooleanBuilder();
        // 注意：BankStatementDetail 用的是 accountSetId（不带 s），而非 accountSetsId
        where.and(q.accountSetId.eq(accountSetsId));
        where.and(q.tradeDate.between(start, end));
        where.and(q.isDelete.eq(0));
        if (!Boolean.TRUE.equals(includeProcessed)) {
            where.and(q.cashierJournalId.isNull());
        }
        // voucherIdIsNull=true → 只返回还没有生成凭证的流水（未做账）
        if (Boolean.TRUE.equals(voucherIdIsNull)) {
            where.and(q.voucherId.isNull());
        }

        long total = bqf.selectFrom(q).where(where).fetchCount();

        List<BankStatementDetail> rows = bqf.selectFrom(q)
                .where(where)
                .orderBy(q.tradeDate.asc(), q.id.asc())
                .offset((long) (page - 1) * size)
                .limit(size)
                .fetch();

        List<Map<String, Object>> data = new ArrayList<>();
        for (BankStatementDetail r : rows) {
            data.add(toDto(r));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", data);
        // 用 int 包装绕过全局 Long→String 序列化器（WebMvcSupport.java:183），保证 total 是 number
        result.put("total", (int) total);
        result.put("page", page);
        result.put("size", size);
        return JsonResult.successful(result);
    }

    private Map<String, Object> toDto(BankStatementDetail r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("bankStatementId", r.getBankStatementId());
        m.put("serialNo", r.getSerialNo());
        m.put("tradeDate", r.getTradeDate());
        m.put("tradeAmount", r.getTradeAmount());
        m.put("balance", r.getBalance());

        // 推算方向：如果本方账号(accountNumber)出现在收款方账号 → 入账(in)；
        // 出现在付款方账号 → 出账(out)；都没匹配上时，用 receiveAccountNum 是否非空来兜底
        String direction = inferDirection(r);
        m.put("direction", direction);

        m.put("ownAccountNumber", r.getAccountNumber());
        m.put("bankName", r.getBankName());

        // 对方信息：根据方向取
        boolean isIn = "in".equals(direction);
        m.put("counterpartyName", isIn ? r.getPayName() : r.getReceiveName());
        m.put("counterpartyAccount", isIn ? r.getPayAccountNum() : r.getReceiveAccountNum());
        m.put("counterpartyBankName", isIn ? r.getPayBankName() : r.getReceiveBankName());

        m.put("transactionType", r.getTransactionType());
        m.put("businessType", r.getBusinessType());
        m.put("remarks", r.getRemarks());
        m.put("currencyType", r.getCurrencyType());
        m.put("cashierJournalId", r.getCashierJournalId());
        m.put("voucherId", r.getVoucherId());
        return m;
    }

    /**
     * 推算交易方向：
     *  - 本方账号(accountNumber) == receiveAccountNum → in（收款）
     *  - 本方账号 == payAccountNum → out（付款）
     *  - 否则用 receiveAccountNum 是否非空判断（有收款方信息认为是收款）
     */
    private String inferDirection(BankStatementDetail r) {
        String own = r.getAccountNumber();
        if (StrUtil.isNotBlank(own)) {
            if (own.equals(r.getReceiveAccountNum())) return "in";
            if (own.equals(r.getPayAccountNum())) return "out";
        }
        // 兜底：tradeAmount 正负判断（如果该字段有正负含义）
        BigDecimal amt = r.getTradeAmount();
        if (amt != null && amt.signum() < 0) return "out";
        // 默认按 payAccountNum 是否非空、且 receiveAccountNum 没填判断
        if (StrUtil.isNotBlank(r.getReceiveAccountNum()) && StrUtil.isBlank(r.getPayAccountNum())) {
            return "out";  // 我方付款给 receive 方
        }
        return "in";
    }
}
