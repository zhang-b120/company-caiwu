package cn.gson.financial.kernel.model.ua.request;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.gson.financial.kernel.model.entity.CashierAccount;
import cn.gson.financial.kernel.model.vo.JournalImportVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ImportCashierJournalReq extends GetCommonReq{

    private Long accountId;

    private CashierAccount.CashierAccountType type;

    private List<JournalImportVo> cashierJournalVo;

}
