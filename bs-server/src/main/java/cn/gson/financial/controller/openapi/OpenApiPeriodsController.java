package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.Checkout;
import cn.gson.financial.kernel.model.entity.QCheckout;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 开放 API — 会计期间批量查询
 * 路径前缀 /openapi/v1/periods
 *
 * 与 §9 单期查询 /checkout 配套，用于一次性看"哪些月已结账、哪些没结账"。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/periods")
@SaIgnore
public class OpenApiPeriodsController {

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QCheckout qCheckout = QCheckout.checkout;

    /**
     * P2-1 会计期间批量查询
     * GET /openapi/v1/periods?accountSetsId=88&yearStart=2025&yearEnd=2026
     */
    @GetMapping
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam(required = false) Integer yearStart,
                           @RequestParam(required = false) Integer yearEnd) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        BooleanBuilder where = new BooleanBuilder();
        where.and(qCheckout.accountSetsId.eq(accountSetsId));
        if (yearStart != null) where.and(qCheckout.checkYear.goe(yearStart));
        if (yearEnd != null) where.and(qCheckout.checkYear.loe(yearEnd));

        List<Checkout> rows = bqf.selectFrom(qCheckout)
                .where(where)
                .orderBy(qCheckout.checkYear.asc(), qCheckout.checkMonth.asc())
                .fetch();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Checkout c : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", c.getCheckYear());
            m.put("month", c.getCheckMonth());
            m.put("status", c.getStatus());           // 0 / 1 / 2
            m.put("checkedOut", Integer.valueOf(2).equals(c.getStatus()));
            m.put("checkoutDate", Integer.valueOf(2).equals(c.getStatus()) ? c.getCheckDate() : null);
            result.add(m);
        }
        return JsonResult.successful(result);
    }
}
