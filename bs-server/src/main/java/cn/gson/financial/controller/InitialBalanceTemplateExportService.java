package cn.gson.financial.controller;

import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.service.BalanceInitialService;
import cn.gson.financial.kernel.service.BalanceService;
import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import cn.hutool.poi.excel.style.StyleUtil;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 按当前账套期初导出为 Excel 字节流，用作工作流「账套期初文件」。
 */
@Service
@RequiredArgsConstructor
public class InitialBalanceTemplateExportService {

    private final BalanceInitialService balanceInitialService;

    /**
     * 将指定账套的期初列表导出为与「期初导入模板」一致的 Excel 字节数组。
     */
    public byte[] exportToBytes(AccountSets accountSets, Long localCurrencyId) throws IOException {
        BalanceService.Query query = new BalanceService.Query();
        query.setAccountSets(accountSets);
        query.setLocalCurrencyId(localCurrencyId);
        List<BalanceDto> balanceDtoList = balanceInitialService.list(accountSets.getId(), query);
        boolean isUnit = balanceDtoList.stream().anyMatch(v -> StrUtil.isNotEmpty(v.getUnit()));
        ExcelWriter writer = isUnit ? withUnit(balanceDtoList) : withoutUnit(balanceDtoList);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.flush(out);
        writer.close();
        return out.toByteArray();
    }

    private static Object nullZeroToEmptyStr(Number n) {
        return n == null || n.longValue() == 0L ? "" : n;
    }

    private ExcelWriter withoutUnit(List<BalanceDto> balanceVos) {
        List<String> columns = StrUtil.split("科目编码,科目名称,方向,辅助核算项目,辅助核算编码,辅助核算名称,期初余额,借方累计,贷方累计,年初余额,ID,币别ID,科目ID,辅助ID,树ID,父科目,Leaf", ",");
        ExcelWriter writer = ExcelUtil.getWriter(true);
        CellStyle defaultCellStyle = writer.getCellStyle();
        writer.setColumnWidth(-1, 10);
        writer.setColumnWidth(0, 14);
        writer.setColumnWidth(1, 32);
        writer.setColumnWidth(2, 6);
        writer.setColumnWidth(3, 16);
        writer.setColumnWidth(4, 16);
        writer.setColumnWidth(5, 16);
        writer.setRowHeight(-1, 20);
        StyleUtil.setAlign(defaultCellStyle, HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        writer.renameSheet("财务初始余额表");
        writer.writeHeadRow(columns);
        XSSFSheet sheet = (XSSFSheet) writer.getSheet();
        sheet.protectSheet("891123");
        sheet.setColumnHidden(10, true);
        sheet.setColumnHidden(11, true);
        sheet.setColumnHidden(12, true);
        sheet.setColumnHidden(13, true);
        sheet.setColumnHidden(14, true);
        sheet.setColumnHidden(15, true);
        sheet.setColumnHidden(16, true);
        sheet.lockFormatColumns(false);
        writer.setOnlyAlias(true);
        CellStyle lockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        lockCellStyle.setLocked(true);
        StyleUtil.setColor(lockCellStyle, IndexedColors.GREY_25_PERCENT, FillPatternType.SOLID_FOREGROUND);
        Short format = StyleUtil.getFormat(writer.getWorkbook(), "#,##0.00_);[Red](#,##0.00)");
        CellStyle unLockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        unLockCellStyle.setLocked(false);
        unLockCellStyle.setDataFormat(format);
        StyleUtil.setAlign(unLockCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
        for (BalanceDto vo : balanceVos) {
            List<Object> row = new ArrayList<>();
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectCode());
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectName());
            row.add(vo.getBalanceDirection());
            row.add(vo.isAd() ? "是" : "否");
            row.add(vo.getAuxiliaryCode());
            row.add(vo.getAuxiliaryName());
            row.add(nullZeroToEmptyStr(vo.getBeginBalance()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebit()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCredit()));
            row.add("");
            row.add(String.valueOf(vo.getId()));
            row.add(String.valueOf(vo.getCurrencyId()));
            row.add(String.valueOf(vo.getSubjectId()));
            row.add(String.valueOf(vo.getAuxiliaryDetailId()));
            row.add(String.valueOf(vo.getTreeId()));
            row.add(String.valueOf(vo.getParentTreeId()));
            row.add(String.valueOf(vo.getLeaf()));
            int currentRow = writer.getCurrentRow();
            writer.writeRow(row);
            Row r = writer.getOrCreateRow(currentRow);
            if (!vo.getLeaf() || (vo.isAd() && vo.getParentTreeId() == null)) {
                for (int i = 0; i < 10; i++) {
                    r.getCell(i).setCellStyle(lockCellStyle);
                }
                for (int i = 6; i < 10; i++) {
                    CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), r.getCell(i).getCellStyle());
                    cellStyle.setAlignment(HorizontalAlignment.RIGHT);
                    r.getCell(i).setCellStyle(cellStyle);
                }
            } else {
                for (int i = 6; i < 9; i++) {
                    r.getCell(i).setCellStyle(unLockCellStyle);
                }
            }
            Cell cell = r.getCell(9);
            CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), cell.getCellStyle());
            cellStyle.setDataFormat(format);
            StyleUtil.setAlign(cellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            cell.setCellStyle(cellStyle);
            cell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($I:$I),$I:$I,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($I:$I),$I:$I,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($I:$I),$I:$I,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($I:$I),$I:$I,0)))");
        }
        return writer;
    }

    private ExcelWriter withUnit(List<BalanceDto> balanceVos) {
        List<String> columns = StrUtil.split("科目编码,科目名称,方向,辅助核算项目,辅助核算编码,辅助核算名称,期初余额,,借方累计,,贷方累计,,年初余额,,ID,币别ID,科目ID,辅助ID,树ID,父科目,Leaf", ",");
        List<String> columns2 = StrUtil.split(",,,,,,数量,金额,数量,金额,数量,金额,数量,金额", ",");
        ExcelWriter writer = ExcelUtil.getWriter(true);
        CellStyle defaultCellStyle = writer.getCellStyle();
        writer.setColumnWidth(-1, 10);
        writer.setColumnWidth(0, 14);
        writer.setColumnWidth(1, 32);
        writer.setColumnWidth(2, 6);
        writer.setColumnWidth(3, 16);
        writer.setColumnWidth(4, 16);
        writer.setColumnWidth(5, 16);
        writer.setRowHeight(-1, 20);
        StyleUtil.setAlign(defaultCellStyle, HorizontalAlignment.LEFT, VerticalAlignment.CENTER);
        writer.renameSheet("财务初始余额表");
        writer.writeHeadRow(columns);
        writer.writeHeadRow(columns2);
        writer.merge(0, 1, 0, 0, "科目编码", writer.getHeadCellStyle());
        writer.merge(0, 1, 1, 1, "科目名称", writer.getHeadCellStyle());
        writer.merge(0, 1, 2, 2, "方向", writer.getHeadCellStyle());
        writer.merge(0, 1, 3, 3, "辅助核算项目", writer.getHeadCellStyle());
        writer.merge(0, 1, 4, 4, "辅助核算编码", writer.getHeadCellStyle());
        writer.merge(0, 1, 5, 5, "辅助核算名称", writer.getHeadCellStyle());
        writer.merge(0, 0, 6, 7, "期初余额", writer.getHeadCellStyle());
        writer.merge(0, 0, 8, 9, "借方累计", writer.getHeadCellStyle());
        writer.merge(0, 0, 10, 11, "贷方累计", writer.getHeadCellStyle());
        writer.merge(0, 0, 12, 13, "年初余额", writer.getHeadCellStyle());
        XSSFSheet sheet = (XSSFSheet) writer.getSheet();
        sheet.protectSheet("891123");
        sheet.setColumnHidden(14, true);
        sheet.setColumnHidden(15, true);
        sheet.setColumnHidden(16, true);
        sheet.setColumnHidden(17, true);
        sheet.setColumnHidden(18, true);
        sheet.setColumnHidden(19, true);
        sheet.setColumnHidden(20, true);
        sheet.lockFormatColumns(false);
        writer.setOnlyAlias(true);
        CellStyle lockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        lockCellStyle.setLocked(true);
        StyleUtil.setColor(lockCellStyle, IndexedColors.GREY_25_PERCENT, FillPatternType.SOLID_FOREGROUND);
        Short format = StyleUtil.getFormat(writer.getWorkbook(), "#,##0.00_);[Red](#,##0.00)");
        Short slFormat = StyleUtil.getFormat(writer.getWorkbook(), "0.0000;[Red](0.0000)");
        CellStyle unLockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        unLockCellStyle.setLocked(false);
        unLockCellStyle.setDataFormat(format);
        StyleUtil.setAlign(unLockCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
        for (BalanceDto vo : balanceVos) {
            List<Object> row = new ArrayList<>();
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectCode());
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectName());
            row.add(vo.getBalanceDirection());
            row.add(vo.isAd() ? "是" : "否");
            row.add(vo.getAuxiliaryName());
            row.add(vo.getAuxiliaryCode());
            row.add(nullZeroToEmptyStr(vo.getBeginQuantity()));
            row.add(nullZeroToEmptyStr(vo.getBeginBalance()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebitQuantity()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebit()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCreditQuantity()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCredit()));
            row.add("");
            row.add("");
            row.add(String.valueOf(vo.getId()));
            row.add(String.valueOf(vo.getCurrencyId()));
            row.add(String.valueOf(vo.getSubjectId()));
            row.add(String.valueOf(vo.getAuxiliaryDetailId()));
            row.add(String.valueOf(vo.getTreeId()));
            row.add(String.valueOf(vo.getParentTreeId()));
            row.add(String.valueOf(vo.getLeaf()));
            int currentRow = writer.getCurrentRow();
            writer.writeRow(row);
            Row r = writer.getOrCreateRow(currentRow);
            if (!vo.getLeaf() || (vo.isAd() && vo.getParentTreeId() == null)) {
                for (int i = 0; i < 14; i++) {
                    r.getCell(i).setCellStyle(lockCellStyle);
                }
                for (int i = 6; i < 14; i++) {
                    CellStyle cs = StyleUtil.cloneCellStyle(writer.getWorkbook(), r.getCell(i).getCellStyle());
                    cs.setAlignment(HorizontalAlignment.RIGHT);
                    r.getCell(i).setCellStyle(cs);
                }
            } else {
                for (int i = 6; i < 12; i++) {
                    r.getCell(i).setCellStyle(unLockCellStyle);
                }
            }
            for (int i = 6; i < 12; i++) {
                Cell cell = r.getCell(i);
                CellStyle cs = StyleUtil.cloneCellStyle(writer.getWorkbook(), cell.getCellStyle());
                if (i % 2 == 0) {
                    cs.setDataFormat(slFormat);
                    if (StrUtil.isEmpty(vo.getUnit())) cs.setLocked(true);
                } else {
                    cs.setDataFormat(format);
                }
                cell.setCellStyle(cs);
            }
            Cell slCell = r.getCell(12);
            CellStyle slCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), slCell.getCellStyle());
            slCellStyle.setDataFormat(slFormat);
            StyleUtil.setAlign(slCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            slCell.setCellStyle(slCellStyle);
            slCell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($I:$I),$I:$I,0)-IF(ISNUMBER($K:$K),$K:$K,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($I:$I),$I:$I,0)+IF(ISNUMBER($K:$K),$K:$K,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($I:$I),$I:$I,0)-IF(ISNUMBER($K:$K),$K:$K,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($I:$I),$I:$I,0)+IF(ISNUMBER($K:$K),$K:$K,0)))");
            Cell jeCell = r.getCell(13);
            CellStyle jeStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), jeCell.getCellStyle());
            jeStyle.setDataFormat(format);
            StyleUtil.setAlign(jeStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            jeCell.setCellStyle(jeStyle);
            jeCell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($J:$J),$J:$J,0)-IF(ISNUMBER($L:$L),$L:$L,0),IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($J:$J),$J:$J,0)+IF(ISNUMBER($L:$L),$L:$L,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($J:$J),$J:$J,0)-IF(ISNUMBER($L:$L),$L:$L,0),IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($J:$J),$J:$J,0)+IF(ISNUMBER($L:$L),$L:$L,0)))");
        }
        return writer;
    }
}
