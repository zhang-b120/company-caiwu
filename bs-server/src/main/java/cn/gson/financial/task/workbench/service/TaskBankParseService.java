package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.entity.QBankStatement;
import cn.gson.financial.kernel.model.repo.BankStatementRepository;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务工作台 - 解析银行流水内部任务
 * <p>
 * 检查账套是否已有银行流水数据（fxy_financial_bank_statement 表），
 * 如果有则标记 done，如果无则标记 warn（提示手动导入）。
 * 实际文件解析由用户通过"银行对账"模块导入后触发，此任务仅做状态检查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBankParseService {

    private final BankStatementRepository bankStatementRepository;
    private final BlazeJPAQueryFactory bqf;

    private final QBankStatement qBankStatement = QBankStatement.bankStatement;

    /**
     * 检查账套的银行流水数据，返回执行结果 map。
     *
     * @param accountSetId 账套 ID
     * @return 结果 map：status / msg / count
     */
    public Map<String, Object> execute(Long accountSetId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            long count = bqf.selectFrom(qBankStatement)
                    .where(qBankStatement.accountSetsId.eq(accountSetId)
                            .and(qBankStatement.isDelete.eq(0)))
                    .fetchCount();

            if (count > 0) {
                result.put("status", "done");
                result.put("msg", "已找到银行流水记录 " + count + " 条");
                result.put("count", count);
            } else {
                result.put("status", "warn");
                result.put("msg", "暂无银行流水记录，请在[银行对账]模块导入后重新执行");
                result.put("count", 0L);
            }
        } catch (Exception e) {
            log.error("检查银行流水失败: accountSetId={}", accountSetId, e);
            result.put("status", "error");
            result.put("msg", "检查银行流水失败：" + e.getMessage());
            result.put("count", 0L);
        }
        return result;
    }
}
