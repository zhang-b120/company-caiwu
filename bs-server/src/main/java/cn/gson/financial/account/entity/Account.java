package cn.gson.financial.account.entity;

import java.util.Objects;

/**
 * 科目信息实体类
 * 用于表示财务系统中的科目信息，包含科目编码、名称、余额方向以及层级关系等关键属性
 */
public class Account {
    private String code; // 科目编码
    private String name; // 科目名称
    private String balanceDirection; // 余额方向 (借/贷)
    private String parentCode; // 父科目编码，用于表示层级关系
    private String parentName; // 父类科目名称



    // 默认构造函数
    public Account() {}

    // 带参数的构造函数
    public Account(String code, String name, String balanceDirection) {
        this.code = code;
        this.name = name;
        this.balanceDirection = balanceDirection;
    }

    public Account(String code, String name, String balanceDirection, String parentCode) {
        this.code = code;
        this.name = name;
        this.balanceDirection = balanceDirection;
        this.parentCode = parentCode;
    }

    public Account(String code, String name, String balanceDirection, String parentCode, String parentName) {
        this.code = code;
        this.name = name;
        this.balanceDirection = balanceDirection;
        this.parentCode = parentCode;
        this.parentName = parentName;
    }

    // Getter 和 Setter 方法
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBalanceDirection() {
        return balanceDirection;
    }

    public void setBalanceDirection(String balanceDirection) {
        this.balanceDirection = balanceDirection;
    }

    public String getParentCode() {
        return parentCode;
    }

    public void setParentCode(String parentCode) {
        this.parentCode = parentCode;
    }

    public String getParentName() {
        return parentName;
    }

    public void setParentName(String parentName) {
        this.parentName = parentName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Account account = (Account) o;
        return Objects.equals(code, account.code) &&
               Objects.equals(name, account.name) &&
               Objects.equals(balanceDirection, account.balanceDirection) &&
               Objects.equals(parentCode, account.parentCode) &&
               Objects.equals(parentName, account.parentName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, name, balanceDirection, parentCode, parentName);
    }

    @Override
    public String toString() {
        return "Account{" +
                "code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", balanceDirection='" + balanceDirection + '\'' +
                ", parentCode='" + parentCode + '\'' +
                ", parentName='" + parentName + '\'' +
                '}';
    }
} 