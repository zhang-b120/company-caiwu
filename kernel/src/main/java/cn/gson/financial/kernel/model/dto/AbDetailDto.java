package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.config.NumBigDecimalSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月11日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class AbDetailDto {
    private Long subjectId;
    private String subjectName;
    private String subjectSortName;
    private String subjectCode;
    private String balanceDirection;
    private String word;
    private Integer code;
    private Long voucherId;
    private String summary;
    private String currency;
    private Long currencyId;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal currencyRate;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private BigDecimal balance;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal num;
    private BigDecimal price;
    private LocalDate voucherDate;
    private boolean summaryRow = false;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal creditNum = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal debitNum = BigDecimal.ZERO;
    private BigDecimal creditPrice = BigDecimal.ZERO;
    private BigDecimal debitPrice = BigDecimal.ZERO;

    public Integer getMonth() {
        return voucherDate != null ? voucherDate.getMonthValue() : null;
    }

    public Integer getYear() {
        return voucherDate != null ? voucherDate.getYear() : null;
    }

    public static AbDetailDtoBuilder builder() {
        return new AbDetailDtoBuilder();
    }

    public static class AbDetailDtoBuilder {
        private Long subjectId;
        private String subjectName;
        private String subjectCode;
        private String subjectSortName;
        private String balanceDirection;
        private String word;
        private Integer code;
        private Long voucherId;
        private String summary;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private BigDecimal balance;
        private LocalDate voucherDate;

        private boolean summaryRow = false;
        private BigDecimal creditNum = BigDecimal.ZERO;
        private BigDecimal debitNum = BigDecimal.ZERO;
        private BigDecimal creditPrice = BigDecimal.ZERO;
        private BigDecimal debitPrice = BigDecimal.ZERO;
        private BigDecimal num = BigDecimal.ZERO;
        private BigDecimal price = BigDecimal.ZERO;

        public AbDetailDtoBuilder subjectId(Long subjectId) {
            this.subjectId = subjectId;
            return this;
        }

        public AbDetailDtoBuilder subjectName(String subjectName) {
            this.subjectName = subjectName;
            return this;
        }

        public AbDetailDtoBuilder subjectSortName(String subjectSortName) {
            this.subjectSortName = subjectSortName;
            return this;
        }

        public AbDetailDtoBuilder subjectCode(String subjectCode) {
            this.subjectCode = subjectCode;
            return this;
        }

        public AbDetailDtoBuilder balanceDirection(String balanceDirection) {
            this.balanceDirection = balanceDirection;
            return this;
        }

        public AbDetailDtoBuilder word(String word) {
            this.word = word;
            return this;
        }

        public AbDetailDtoBuilder code(Integer code) {
            this.code = code;
            return this;
        }

        public AbDetailDtoBuilder voucherId(Long voucherId) {
            this.voucherId = voucherId;
            return this;
        }

        public AbDetailDtoBuilder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public AbDetailDtoBuilder summaryRow(boolean summaryRow) {
            this.summaryRow = summaryRow;
            return this;
        }

        public AbDetailDtoBuilder debitAmount(BigDecimal debitAmount) {
            this.debitAmount = debitAmount;
            return this;
        }

        public AbDetailDtoBuilder creditAmount(BigDecimal creditAmount) {
            this.creditAmount = creditAmount;
            return this;
        }

        public AbDetailDtoBuilder balance(BigDecimal balance) {
            this.balance = balance;
            return this;
        }

        public AbDetailDtoBuilder voucherDate(LocalDate voucherDate) {
            this.voucherDate = voucherDate;
            return this;
        }

        public AbDetailDtoBuilder creditNum(BigDecimal creditNum) {
            this.creditNum = creditNum;
            return this;
        }

        public AbDetailDtoBuilder debitNum(BigDecimal debitNum) {
            this.debitNum = debitNum;
            return this;
        }

        public AbDetailDtoBuilder creditPrice(BigDecimal creditPrice) {
            this.creditPrice = creditPrice;
            return this;
        }

        public AbDetailDtoBuilder debitPrice(BigDecimal debitPrice) {
            this.debitPrice = debitPrice;
            return this;
        }

        public AbDetailDtoBuilder num(BigDecimal num) {
            this.num = num;
            return this;
        }

        public AbDetailDtoBuilder price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public AbDetailDto build() {
            AbDetailDto abDetailDto = new AbDetailDto();
            abDetailDto.setSubjectId(subjectId);
            abDetailDto.setSubjectCode(subjectCode);
            abDetailDto.setSubjectName(subjectName);
            abDetailDto.setSubjectSortName(subjectSortName);
            abDetailDto.setBalanceDirection(balanceDirection);
            abDetailDto.setWord(word);
            abDetailDto.setCode(code);
            abDetailDto.setVoucherId(voucherId);
            abDetailDto.setSummary(summary);
            abDetailDto.setDebitAmount(debitAmount);
            abDetailDto.setCreditAmount(creditAmount);
            abDetailDto.setBalance(balance);
            abDetailDto.setVoucherDate(voucherDate);
            abDetailDto.setSummaryRow(summaryRow);
            abDetailDto.setDebitNum(debitNum);
            abDetailDto.setCreditNum(creditNum);
            abDetailDto.setDebitPrice(debitPrice);
            abDetailDto.setCreditPrice(creditPrice);
            abDetailDto.setNum(num);
            abDetailDto.setPrice(price);
            return abDetailDto;
        }
    }
}
