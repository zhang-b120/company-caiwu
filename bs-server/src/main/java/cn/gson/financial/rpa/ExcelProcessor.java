package cn.gson.financial.rpa;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ExcelProcessor {

    public  void excue(String path) throws IOException {
        String filePath = path;
        FileInputStream file = new FileInputStream(filePath);
        Workbook workbook = new XSSFWorkbook(file);

        // Process the first sheet (信息汇总表)
        Sheet infoSheet = workbook.getSheetAt(0);
        List<Integer> columnsToRemoveInfo = List.of(0, 4, 5, 6, 7, 8,9,20,21,22,23,24,25,26);
        Sheet newGoodsSheet = removeColumns(infoSheet, columnsToRemoveInfo);

        // Process the second sheet (发票基础信息)
        Sheet invoiceSheet = workbook.getSheetAt(1);
        List<Integer> columnsToRemoveInvoice = List.of(0,12, 15, 16,17);
        Sheet newInvoiceSheet = removeColumns(invoiceSheet, columnsToRemoveInvoice);

        // Create a new workbook and add the processed sheets
        Workbook newWorkbook = new XSSFWorkbook();
        newWorkbook.createSheet("发票基础信息");
        newWorkbook.createSheet("货物清单");

        // Copy the processed data to the new workbook
        copySheet(newInvoiceSheet, newWorkbook.getSheetAt(0));
        copySheet(newGoodsSheet, newWorkbook.getSheetAt(1));

        // Save the new workbook
        FileOutputStream outFile = new FileOutputStream(path);
        newWorkbook.write(outFile);
        outFile.close();
        workbook.close();
        newWorkbook.close();
    }

    private  Sheet removeColumns(Sheet sheet, List<Integer> columnsToRemove) {
        Sheet newSheet = sheet.getWorkbook().createSheet();
        for (int i = 0; i < sheet.getPhysicalNumberOfRows(); i++) {
            Row originalRow = sheet.getRow(i);
            Row newRow = newSheet.createRow(i);
            int newCol = 0;
            for (int j = 0; j < originalRow.getPhysicalNumberOfCells(); j++) {
                if (!columnsToRemove.contains(j)) {
                    Cell originalCell = originalRow.getCell(j);
                    Cell newCell = newRow.createCell(newCol++);
                    if (originalCell != null) {
                        newCell.setCellValue(originalCell.toString());
                    }
                }
            }
        }
        return newSheet;
    }

    private  void copySheet(Sheet sourceSheet, Sheet targetSheet) {
        for (int i = 0; i < sourceSheet.getPhysicalNumberOfRows(); i++) {
            Row sourceRow = sourceSheet.getRow(i);
            Row targetRow = targetSheet.createRow(i);
            for (int j = 0; j < sourceRow.getPhysicalNumberOfCells(); j++) {
                Cell sourceCell = sourceRow.getCell(j);
                Cell targetCell = targetRow.createCell(j);
                if (sourceCell != null) {
                    targetCell.setCellValue(sourceCell.toString());
                }
            }
        }
    }







}
