package cn.gson.financial.agent.initialbalance.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 将科目余额表 Excel 按行拆成多份，用于分批调用工作流、减少单次返回长度。
 */
public final class BalanceExcelSplitUtil {

    private BalanceExcelSplitUtil() {
    }

    /**
     * 按行拆分余额表：保留第 0 行为表头，数据行按 maxRowsPerBatch 一批。
     *
     * @param balanceFileBytes  科目余额表 Excel 字节
     * @param maxRowsPerBatch   每份最多数据行数（不含表头），建议 30～100
     * @return 多份 Excel 字节（每份含表头 + 一批数据行），若仅一批则返回单元素列表
     */
    public static List<byte[]> splitByRows(byte[] balanceFileBytes, int maxRowsPerBatch) throws IOException {
        if (balanceFileBytes == null || balanceFileBytes.length == 0) {
            return new ArrayList<>();
        }
        if (maxRowsPerBatch <= 0) {
            maxRowsPerBatch = 50;
        }
        List<byte[]> result = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(balanceFileBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            int headerRowIndex = 0;
            int dataStartRow = 1;
            int dataRowCount = Math.max(0, lastRowNum - headerRowIndex);
            if (dataRowCount <= 0) {
                result.add(balanceFileBytes);
                return result;
            }
            for (int start = dataStartRow; start <= lastRowNum; start += maxRowsPerBatch) {
                int end = Math.min(start + maxRowsPerBatch - 1, lastRowNum);
                byte[] chunk = copySheetRowsToNewWorkbook(sheet, headerRowIndex, start, end);
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 将源 sheet 的表头行 + 指定数据行范围复制到新工作簿并输出为字节。
     */
    private static byte[] copySheetRowsToNewWorkbook(Sheet sourceSheet, int headerRowIndex, int dataStart, int dataEnd) throws IOException {
        try (Workbook destWb = new XSSFWorkbook()) {
            Sheet destSheet = destWb.createSheet(sourceSheet.getSheetName() != null ? sourceSheet.getSheetName() : "Sheet0");
            int destRowIdx = 0;
            copyRow(sourceSheet.getRow(headerRowIndex), destSheet.createRow(destRowIdx++));
            for (int i = dataStart; i <= dataEnd; i++) {
                Row srcRow = sourceSheet.getRow(i);
                if (srcRow != null) {
                    copyRow(srcRow, destSheet.createRow(destRowIdx++));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            destWb.write(out);
            return out.toByteArray();
        }
    }

    private static void copyRow(Row srcRow, Row destRow) {
        if (srcRow == null || destRow == null) return;
        short lastCellNum = srcRow.getLastCellNum();
        for (int c = 0; c < lastCellNum; c++) {
            Cell srcCell = srcRow.getCell(c);
            Cell destCell = destRow.createCell(c);
            if (srcCell != null) {
                copyCell(srcCell, destCell);
            }
        }
    }

    private static void copyCell(Cell src, Cell dest) {
        CellType type = src.getCellType();
        if (type == CellType.FORMULA) {
            try {
                type = src.getCachedFormulaResultType();
            } catch (Exception e) {
                type = CellType.STRING;
            }
        }
        switch (type) {
            case STRING:
                dest.setCellValue(src.getStringCellValue());
                break;
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(src)) {
                    dest.setCellValue(src.getDateCellValue());
                } else {
                    dest.setCellValue(src.getNumericCellValue());
                }
                break;
            case BOOLEAN:
                dest.setCellValue(src.getBooleanCellValue());
                break;
            case BLANK:
                dest.setBlank();
                break;
            case ERROR:
                dest.setCellErrorValue(src.getErrorCellValue());
                break;
            default:
                break;
        }
    }
}
