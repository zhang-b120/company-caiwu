package cn.gson.financial.kernel.model.query;

import cn.gson.financial.kernel.model.entity.QInvoice;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateExpression;

import java.time.LocalDate;

/**
 * FN-FIX-OPENAPI-INVOICE-PERIOD-ASSIGNMENT-DATE
 *
 * <p>发票期间过滤的<b>统一口径</b>：有效期间 = {@code COALESCE(assignmentDate, tradeDate)}，
 * 即 <b>assignmentDate 优先、为空才兜底 tradeDate</b>。
 *
 * <p>与 FN 内部做账/勾稽口径一致（{@code InvoiceAutoVoucherService} /
 * {@code TaskBookkeepService} / {@code TaskWorkbenchService} /
 * {@code VoucherCompletenessService}：SQL 侧 {@code COALESCE(assignment_date, trade_date)}）。
 * 对外 openapi 发票查询/统计端点复用本 helper，避免「对内 assignment 优先、对外只看 tradeDate」口径分叉
 * 导致被指定到本期但开票在别月的发票漏查、做账漏票、BT 数量偏少。
 *
 * <p>注意：必须用「优先 + 兜底」，<b>不能</b>用纯 {@code tradeDate OR assignmentDate}——后者会让一张
 * 被指定改期的票在两个期间重复出现。
 */
public final class InvoicePeriodPredicate {

    private InvoicePeriodPredicate() {
    }

    /**
     * 构造发票期间过滤条件（assignmentDate 优先 + tradeDate 兜底）。
     *
     * <p>{@code start} / {@code end} 任一为 {@code null} 视为该侧开区间；两者皆 {@code null}
     * 返回 {@code null}（表示不加期间过滤，由调用方决定是否跳过）。
     *
     * @param q     调用方自己的 {@link QInvoice} 实例（保证 querydsl 别名与外层查询一致）
     * @param start 期间起（含），可空
     * @param end   期间止（含），可空
     * @return 期间过滤条件；当 start 与 end 都为 null 时返回 {@code null}
     */
    public static BooleanExpression byPeriod(QInvoice q, LocalDate start, LocalDate end) {
        BooleanExpression assignmentInRange = rangeCond(q.assignmentDate, start, end);
        if (assignmentInRange == null) {
            return null;
        }
        BooleanExpression tradeInRange = rangeCond(q.tradeDate, start, end);
        return assignmentInRange.or(q.assignmentDate.isNull().and(tradeInRange));
    }

    private static BooleanExpression rangeCond(DateExpression<LocalDate> date, LocalDate start, LocalDate end) {
        if (start != null && end != null) {
            return date.between(start, end);
        }
        if (start != null) {
            return date.goe(start);
        }
        if (end != null) {
            return date.loe(end);
        }
        return null;
    }
}
