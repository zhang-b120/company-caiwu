package cn.gson.financial.account.entity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 期初余额记录实体类
 * 用于表示导入的每一条期初余额记录
 */
public class InitialBalanceEntry {
    private String accountNumber; // 导入文件中的科目编码或名称
    private String accountName;   // 导入文件中的科目名称
    private BigDecimal debitAmount; // 借方金额
    private BigDecimal creditAmount; // 贷方金额
    private BigDecimal yearC; // 本年累计发生额借方
    private BigDecimal yearD; // 本年累计发生额贷方
    private BigDecimal lastC; // 期末余额借方
    private BigDecimal lastD; // 期末余额贷方

    // 默认构造函数
    public InitialBalanceEntry() {}

    // 带参数的构造函数
    public InitialBalanceEntry(String accountNumber, String accountName, BigDecimal debitAmount, BigDecimal creditAmount) {
        this.accountNumber = accountNumber;
        this.accountName = accountName;
        this.debitAmount = debitAmount != null ? debitAmount : BigDecimal.ZERO;
        this.creditAmount = creditAmount != null ? creditAmount : BigDecimal.ZERO;
    }

    // 新的构造函数，包含所有字段
    public InitialBalanceEntry(String accountNumber, String accountName, BigDecimal debitAmount, BigDecimal creditAmount, 
                              BigDecimal yearC, BigDecimal yearD, BigDecimal lastC, BigDecimal lastD) {
        this.accountNumber = accountNumber;
        this.accountName = accountName;
        this.debitAmount = debitAmount != null ? debitAmount : BigDecimal.ZERO;
        this.creditAmount = creditAmount != null ? creditAmount : BigDecimal.ZERO;
        this.yearC = yearC != null ? yearC : BigDecimal.ZERO;
        this.yearD = yearD != null ? yearD : BigDecimal.ZERO;
        this.lastC = lastC != null ? lastC : BigDecimal.ZERO;
        this.lastD = lastD != null ? lastD : BigDecimal.ZERO;
    }

    // Getter 和 Setter 方法
    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public BigDecimal getDebitAmount() {
        return debitAmount;
    }

    public void setDebitAmount(BigDecimal debitAmount) {
        this.debitAmount = debitAmount != null ? debitAmount : BigDecimal.ZERO;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }

    public void setCreditAmount(BigDecimal creditAmount) {
        this.creditAmount = creditAmount != null ? creditAmount : BigDecimal.ZERO;
    }

    public BigDecimal getYearC() {
        return yearC;
    }

    public void setYearC(BigDecimal yearC) {
        this.yearC = yearC != null ? yearC : BigDecimal.ZERO;
    }

    public BigDecimal getYearD() {
        return yearD;
    }

    public void setYearD(BigDecimal yearD) {
        this.yearD = yearD != null ? yearD : BigDecimal.ZERO;
    }

    public BigDecimal getLastC() {
        return lastC;
    }

    public void setLastC(BigDecimal lastC) {
        this.lastC = lastC != null ? lastC : BigDecimal.ZERO;
    }

    public BigDecimal getLastD() {
        return lastD;
    }

    public void setLastD(BigDecimal lastD) {
        this.lastD = lastD != null ? lastD : BigDecimal.ZERO;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InitialBalanceEntry that = (InitialBalanceEntry) o;
        return Objects.equals(accountNumber, that.accountNumber) &&
               Objects.equals(accountName, that.accountName) &&
               Objects.equals(debitAmount, that.debitAmount) &&
               Objects.equals(creditAmount, that.creditAmount) &&
               Objects.equals(yearC, that.yearC) &&
               Objects.equals(yearD, that.yearD) &&
               Objects.equals(lastC, that.lastC) &&
               Objects.equals(lastD, that.lastD);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountNumber, accountName, debitAmount, creditAmount, yearC, yearD, lastC, lastD);
    }

    @Override
    public String toString() {
        return "InitialBalanceEntry{" +
                "accountNumber='" + accountNumber + '\'' +
                ", accountName='" + accountName + '\'' +
                ", debitAmount=" + debitAmount +
                ", creditAmount=" + creditAmount +
                ", yearC=" + yearC +
                ", yearD=" + yearD +
                ", lastC=" + lastC +
                ", lastD=" + lastD +
                '}';
    }
} 