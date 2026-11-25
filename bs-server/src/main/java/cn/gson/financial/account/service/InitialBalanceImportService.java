package cn.gson.financial.account.service;

import cn.gson.financial.account.entity.Account;
import cn.gson.financial.account.entity.InitialBalanceEntry;
import cn.gson.financial.account.repository.InitialBalanceRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 期初导入服务类
 * 负责处理期初数据的读取、解析、校验和与现有科目体系的匹配
 */
@Service
public class InitialBalanceImportService {

    @Autowired
    private InitialBalanceRepository initialBalanceRepository;

    /**
     * 从 Excel 文件导入期初余额数据。
     * 该方法负责读取 Excel 文件，解析数据，并进行初步校验。
     *
     * @param filePath Excel 文件路径。
     * @return 导入的期初余额记录列表。
     * @throws IOException 如果文件读取失败。
     */
    public List<InitialBalanceEntry> importFromExcel(String filePath) throws IOException {
        List<InitialBalanceEntry> entries = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0); // 假设数据在第一个工作表
            for (Row row : sheet) {
                if (row.getRowNum() == 0) { // 跳过标题行
                    continue;
                }

                // 假设列顺序：科目编码/名称, 科目名称, 借方金额, 贷方金额
                Cell accountNumNameCell = row.getCell(0);
                Cell accountNameCell = row.getCell(1);
                Cell debitAmountCell = row.getCell(2);
                Cell creditAmountCell = row.getCell(3);

                if (accountNumNameCell == null || accountNameCell == null || debitAmountCell == null || creditAmountCell == null) {
                    // 跳过空行或不完整的行
                    continue;
                }

                String accountNumber = getCellValue(accountNumNameCell);
                String accountName = getCellValue(accountNameCell);
                BigDecimal debitAmount = getNumericCellValue(debitAmountCell);
                BigDecimal creditAmount = getNumericCellValue(creditAmountCell);

                // 简单的非空校验
                if (accountNumber != null && !accountNumber.isEmpty() && accountName != null && !accountName.isEmpty()) {
                    entries.add(new InitialBalanceEntry(accountNumber, accountName, debitAmount, creditAmount));
                }
            }
        }
        return entries;
    }

    /**
     * 校验导入的期初余额数据。
     * 该方法主要检查借贷平衡和金额有效性。
     *
     * @param entries 导入的期初余额记录列表。
     * @return 校验结果，如果平衡且金额有效则返回 true，否则返回 false。
     */
    public boolean validateInitialBalances(List<InitialBalanceEntry> entries) {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (InitialBalanceEntry entry : entries) {
            if (entry.getDebitAmount() == null || entry.getCreditAmount() == null) {
                System.err.println("Error: Amount is null for entry: " + entry.getAccountName());
                return false; // 金额不能为空
            }
            if (entry.getDebitAmount().compareTo(BigDecimal.ZERO) < 0 || entry.getCreditAmount().compareTo(BigDecimal.ZERO) < 0) {
                System.err.println("Error: Negative amount found for entry: " + entry.getAccountName());
                return false; // 金额不能为负
            }
            totalDebit = totalDebit.add(entry.getDebitAmount());
            totalCredit = totalCredit.add(entry.getCreditAmount());
        }

        // 检查借贷是否平衡
        if (totalDebit.compareTo(totalCredit) != 0) {
            System.err.println("Error: Total debit (" + totalDebit + ") does not equal total credit (" + totalCredit + ").");
            return false;
        }
        return true;
    }

    /**
     * 将导入的期初余额与系统中的标准科目进行匹配和整合。
     * 该方法将导入的期初数据与现有的科目体系关联起来，并进行余额方向校验。
     *
     * @param importedEntries 导入的期初余额记录列表。
     * @param systemAccounts 系统中已有的标准科目列表。
     * @return 整合后的期初余额列表，包含与标准科目匹配的信息。
     */
    public List<InitialBalanceEntry> matchAndIntegrate(List<InitialBalanceEntry> importedEntries, List<Account> systemAccounts) {
        Map<String, Account> accountMapByCode = systemAccounts.stream()
                .collect(Collectors.toMap(Account::getCode, acc -> acc));
        Map<String, Account> accountMapByName = systemAccounts.stream()
                .collect(Collectors.toMap(Account::getName, acc -> acc));

        List<InitialBalanceEntry> integratedEntries = new ArrayList<>();

        for (InitialBalanceEntry entry : importedEntries) {
            Account matchedAccount = null;
            // 优先按编码匹配
            if (entry.getAccountNumber() != null && accountMapByCode.containsKey(entry.getAccountNumber())) {
                matchedAccount = accountMapByCode.get(entry.getAccountNumber());
            } else if (entry.getAccountName() != null && accountMapByName.containsKey(entry.getAccountName())) {
                // 其次按名称匹配
                matchedAccount = accountMapByName.get(entry.getAccountName());
            }

            if (matchedAccount != null) {
                // 校验余额方向
                if (matchedAccount.getBalanceDirection() != null) {
                    if ("借".equals(matchedAccount.getBalanceDirection()) && entry.getCreditAmount().compareTo(BigDecimal.ZERO) > 0) {
                        System.err.println("Warning: Debit account " + matchedAccount.getName() + " has credit amount.");
                    } else if ("贷".equals(matchedAccount.getBalanceDirection()) && entry.getDebitAmount().compareTo(BigDecimal.ZERO) > 0) {
                        System.err.println("Warning: Credit account " + matchedAccount.getName() + " has debit amount.");
                    }
                }
                // 将匹配到的标准科目信息关联到期初余额记录中
                integratedEntries.add(new InitialBalanceEntry(
                        matchedAccount.getCode(), // 使用标准科目编码
                        matchedAccount.getName(), // 使用标准科目名称
                        entry.getDebitAmount(),
                        entry.getCreditAmount()
                ));
            } else {
                // 如果无法匹配，记录警告并保留原始数据
                System.err.println("Warning: Cannot match account: " + entry.getAccountName());
                integratedEntries.add(entry);
            }
        }
        return integratedEntries;
    }

    /**
     * 保存期初余额数据到数据库
     *
     * @param entries 期初余额记录列表
     * @return 保存成功的记录数量
     */
    public int saveInitialBalances(List<InitialBalanceEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        
        List<InitialBalanceEntry> savedEntries = initialBalanceRepository.saveAll(entries);
        return savedEntries.size();
    }

    /**
     * 从数据库加载期初余额数据
     *
     * @return 期初余额记录列表
     */
    public List<InitialBalanceEntry> loadInitialBalances() {
        return initialBalanceRepository.findAll();
    }

    /**
     * 清空所有期初余额数据
     */
    public void clearAllInitialBalances() {
        initialBalanceRepository.deleteAll();
    }

    /**
     * 辅助方法：获取单元格的字符串值。
     */
    private String getCellValue(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                return String.valueOf((long) cell.getNumericCellValue());
            default:
                return null;
        }
    }

    /**
     * 辅助方法：获取单元格的数值。
     */
    private BigDecimal getNumericCellValue(Cell cell) {
        if (cell == null) return BigDecimal.ZERO;
        switch (cell.getCellType()) {
            case NUMERIC:
                return BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING:
                try {
                    return new BigDecimal(cell.getStringCellValue());
                } catch (NumberFormatException e) {
                    return BigDecimal.ZERO;
                }
            default:
                return BigDecimal.ZERO;
        }
    }
} 