package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.TimeSeriesQueryVo;
import cn.gson.financial.kernel.service.AccountBookService;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import cn.hutool.poi.excel.style.StyleUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月17日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping("report/export")
public class ReportExportController {

    private final AccountBookService accountBookService;

    @PostMapping("/download")
    public void download(@RequestBody Map<String, Object> body, HttpServletResponse response) throws IOException {
        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
        JSONObject data = new JSONObject(body);
        JSONArray dataList = data.getJSONArray("dataList");
        String title = data.getString("title");
        ExcelWriter writer = ExcelUtil.getWriter();
        writer.getCellStyle()
                .setAlignment(HorizontalAlignment.LEFT);
        writer.setDefaultRowHeight(25);
        int[] numCols = new int[]{1, 2, 4, 5};
        int startRow = 3;
        if ("资产负债表".equals(title)) {
            writer.setColumnWidth(0, 20);
            writer.setColumnWidth(3, 28);
            writer.setColumnWidth(1, 18);
            writer.setColumnWidth(2, 18);
            writer.setColumnWidth(4, 18);
            writer.setColumnWidth(5, 18);
            CellStyle cellStyle1 = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
            cellStyle1.setAlignment(HorizontalAlignment.CENTER);
            writer.merge(0, 0, 0, 5, title, cellStyle1);
            writer.merge(1, 1, 0, 1, data.getString("accountSetsName"), false);
            writer.merge(1, 1, 2, 3, data.getString("voucherDate"), false);
            CellStyle cellStyle2 = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
            cellStyle2.setAlignment(HorizontalAlignment.RIGHT);
            writer.merge(1, 1, 4, 5, "单位：元", cellStyle2);
        } else if (title.startsWith("利润表") || title.startsWith("现金流量表")) {
            writer.setColumnWidth(0, title.startsWith("现金流量表") ? 50 : 40);
            writer.setColumnWidth(1, 25);
            writer.setColumnWidth(2, 25);
            CellStyle cellStyle1 = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
            cellStyle1.setAlignment(HorizontalAlignment.CENTER);
            writer.merge(0, 0, 0, 2, title, cellStyle1);
            writer.getOrCreateCell(0, 1).setCellValue(data.getString("accountSetsName"));
            writer.getOrCreateCell(1, 1).setCellValue(data.getString("voucherDate"));
            CellStyle cellStyle2 = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
            cellStyle2.setAlignment(HorizontalAlignment.RIGHT);
            Cell cell = writer.getOrCreateCell(2, 1);
            cell.setCellStyle(cellStyle2);
            cell.setCellValue("单位：元");
            numCols = new int[]{1, 2};
        } else if (title.startsWith("部门利润") || title.startsWith("项目利润")) {
            int maxCol = dataList.getJSONArray(3).size();
            CellStyle cellStyle1 = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
            cellStyle1.setAlignment(HorizontalAlignment.CENTER);
            writer.merge(0, 0, 0, maxCol - 1, title, cellStyle1);
            writer.getOrCreateCell(0, 1).setCellValue(data.getString("accountSetsName"));
            if (maxCol > 3) {
                writer.merge(1, 1, 1, Math.max(2, maxCol - 3), data.getString("voucherDate"), false);
                CellStyle cellStyle2 = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
                cellStyle2.setAlignment(HorizontalAlignment.RIGHT);
                writer.merge(1, 1, Math.max(3, maxCol - 2), Math.max(4, maxCol - 1), "单位：元", cellStyle2);
            } else {
                writer.getOrCreateCell(1, 1).setCellValue(data.getString("voucherDate"));
                writer.getOrCreateCell(2, 1).setCellValue("单位：元");
            }

            writer.merge(2, 3, 0, 0, "项目", cellStyle1);
            JSONArray jsonArray = dataList.getJSONArray(0);
            int next = 0;
            for (int i = 1; i < jsonArray.size(); i++) {
                String t = jsonArray.getString(i);
                writer.merge(2, 2, i + next, i + next + 1, t, cellStyle1);
                next = i;
            }
            JSONArray cols = dataList.getJSONArray(1);
            for (int i = 0; i < cols.size(); i++) {
                Cell cell = writer.getOrCreateCell(i + 1, 3);
                cell.setCellValue(cols.getString(i));
                cell.setCellStyle(cellStyle1);
            }
            writer.passRows(2);
            dataList.remove(0);
            dataList.remove(0);

            numCols = new int[maxCol - 1];
            writer.setColumnWidth(0, 40);
            for (int i = 0; i < numCols.length; i++) {
                numCols[i] = i + 1;
                writer.setColumnWidth(i + 1, 18);
            }
            startRow = 4;
        }
        writer.passRows(2);

        writer.write(dataList);

        CellStyle numberCell = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        Short format = StyleUtil.getFormat(writer.getWorkbook(), "#,##0.00_);[Red](#,##0.00)");
        numberCell.setAlignment(HorizontalAlignment.RIGHT);
        numberCell.setDataFormat(format);
        for (int i = startRow; i < writer.getRowCount(); i++) {
            for (int numCol : numCols) {
                writer.getCell(numCol, i).setCellStyle(numberCell);
            }
        }

        writer.flush(response.getOutputStream()).close();
    }

    @PostMapping("/book/download")
    public void bookDownload(@RequestBody Map<String, Object> body, HttpServletResponse response) throws IOException {
        JSONObject params = new JSONObject(body);
        JSONArray header = params.getJSONArray("header");
        JSONArray dataList = params.getJSONArray("dataList");
        ExcelWriter writer = ExcelUtil.getWriter();
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            JSONObject item = header.getJSONObject(i);
            headers.add(item.getString("title"));
            writer.addHeaderAlias(item.getString("id"), item.getString("title"));
        }
        writer.writeHeadRow(headers);
        writer.write(dataList);

        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);

        writer.flush(response.getOutputStream()).close();
    }

    @PostMapping("/book/timeSeries/download")
    public JsonResult bookDownload(@RequestBody TimeSeriesQueryVo queryVo, @SessionAccountSet AccountSets accountSets) {
        queryVo.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(accountBookService.timeSeries(queryVo));
    }
}
