package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.ExcelMappingUtils;
import cn.gson.financial.kernel.common.ExcelRecognizeUtils;
import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.BankLibDto;
import cn.gson.financial.kernel.model.dto.ExcelMappingFieldDto;
import cn.gson.financial.kernel.model.dto.ExcelMappingTemplateDto;
import cn.gson.financial.kernel.model.dto.ExcelRecognizeTemplateDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.repo.BankLibRepository;
import cn.gson.financial.kernel.model.repo.ExcelMappingTemplateRepository;
import cn.gson.financial.kernel.model.repo.ExcelRecognizeTemplateRepository;
import cn.gson.financial.kernel.model.vo.RecognizeBankExcelVo;
import cn.gson.financial.kernel.model.vo.SaveBankLibVo;
import cn.gson.financial.kernel.model.vo.SaveExcelMappingVo;
import cn.gson.financial.kernel.model.vo.SaveExcelRecognizeVo;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.blazebit.persistence.PagedList;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Author zhb
 * @Date 2025-09-17
 * @Description
 */
@Service
@RequiredArgsConstructor
public class BankExcelService extends AbsService{

    private final QExcelRecognizeTemplate qExcelRecognizeTemplate = QExcelRecognizeTemplate.excelRecognizeTemplate;
    private final QExcelMappingTemplate qExcelMappingTemplate = QExcelMappingTemplate.excelMappingTemplate;
    private final ExcelRecognizeTemplateRepository excelRecognizeFieldRepository;
    private final ExcelMappingTemplateRepository excelMappingTemplateRepository;
    private final QBankLib qBankLib = QBankLib.bankLib;
    private final QExcelMappingField qExcelMappingField = QExcelMappingField.excelMappingField;
    private final BankLibRepository bankLibRepository;

    /**
     * 银行流水识别
     * @param filePath
     * @param businessCode
     * @param businessType
     * @return
     */
    public List<Map<String, Object>> recognizeBankExcel(String filePath, String businessCode, String businessType) {
        List<ExcelRecognizeTemplate> excelRecognizeTemplateList = bqf.selectFrom(qExcelRecognizeTemplate)
                .where(qExcelRecognizeTemplate.businessCode.eq(businessCode)
                        .and(qExcelRecognizeTemplate.businessType.eq(businessType))
                        .and(qExcelRecognizeTemplate.enabled.eq(ExcelRecognizeUtils.ENABLED_YES))
                        .and(qExcelRecognizeTemplate.isDelete.eq(0)))
                .fetch();
        Assert.isFalse(CollUtil.isEmpty(excelRecognizeTemplateList), "未查询到银行{"+businessCode+"}文件识别配置!!");
        Map<String, List<ExcelRecognizeTemplate>> collect = excelRecognizeTemplateList.stream().collect(Collectors.groupingBy(ExcelRecognizeTemplate::getRecognizeType));
        Assert.isTrue(collect.containsKey(ExcelRecognizeUtils.RECOGNIZE_TYPE_HEADER), "未查询到银行{"+businessCode+"}文件识别[数据表头]配置!!");
        List<ExcelMappingTemplate> excelMappingTemplateList = bqf.selectFrom(qExcelMappingTemplate)
                .where(qExcelMappingTemplate.businessCode.eq(businessCode)
                        .and(qExcelMappingTemplate.businessType.eq(businessType))
                        .and(qExcelMappingTemplate.enabled.eq(ExcelMappingUtils.ENABLED_YES))
                        .and(qExcelMappingTemplate.isDelete.eq(0)))
                .fetch();
        Assert.isFalse(CollUtil.isEmpty(excelMappingTemplateList), "未查询到银行{"+businessCode+"}数据映射配置!!");
        if(collect.containsKey(ExcelRecognizeUtils.RECOGNIZE_TYPE_HEADER)){
            ExcelRecognizeUtils.ReadOptions readOptions = ExcelRecognizeUtils.getReadOptions();
            readOptions.setHeaderField(collect.get(ExcelRecognizeUtils.RECOGNIZE_TYPE_HEADER)
                    .stream().map(ExcelRecognizeTemplate::getRecognizeField).toList());
            if(collect.containsKey(ExcelRecognizeUtils.RECOGNIZE_TYPE_SKIP)){
                readOptions.setSkipRows(Boolean.TRUE);
                readOptions.setSkipRowField(collect.get(ExcelRecognizeUtils.RECOGNIZE_TYPE_SKIP)
                        .stream().map(ExcelRecognizeTemplate::getRecognizeField).collect(Collectors.toSet()));
            }
            if(collect.containsKey(ExcelRecognizeUtils.RECOGNIZE_TYPE_END)){
                readOptions.setEndRows(Boolean.TRUE);
                readOptions.setEndRowField(collect.get(ExcelRecognizeUtils.RECOGNIZE_TYPE_END)
                        .stream().map(ExcelRecognizeTemplate::getRecognizeField).collect(Collectors.toSet()));
            }
            List<Map<String, String>> recognizeResultList = ExcelRecognizeUtils.readRecordsByField(filePath, readOptions);
            Assert.isFalse(CollUtil.isEmpty(recognizeResultList), "文件数据读取失败，请检查文件内容!!");
            List<Map<String, Object>> mappingResultList = ExcelMappingUtils.executeMapping(recognizeResultList, excelMappingTemplateList);
            return mappingResultList;
        }

        return Collections.emptyList();
    }

    /**
     * 保存银行流水识别字段
     * @param voList
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveExcelRecognizeField(List<SaveExcelRecognizeVo> voList) {
        if(CollUtil.isEmpty(voList)){
            return Boolean.FALSE;
        }
        //删除此次保存外其他数据
        String businessCode = voList.get(0).getBusinessCode();
        String businessType = voList.get(0).getBusinessType();
        List<Long> idList = voList.stream().filter(f -> ObjectUtils.isNotEmpty(f.getId())).map(SaveExcelRecognizeVo::getId).toList();
        jqf.update(qExcelRecognizeTemplate)
                .set(qExcelRecognizeTemplate.isDelete, 1)
                .where(qExcelRecognizeTemplate.businessCode.eq(businessCode)
                        .and(qExcelRecognizeTemplate.businessType.eq(businessType))
                        .and(CollUtil.isEmpty(idList) ? null : qExcelRecognizeTemplate.id.notIn(idList)))
                .execute();
        voList.forEach(item -> {
            if(ObjectUtils.isEmpty(item.getId())){
                ExcelRecognizeTemplate excelRecognizeField = CopierCopyUtils.convertTo(item, ExcelRecognizeTemplate.class);
                excelRecognizeFieldRepository.save(excelRecognizeField);
            }else{
                jqf.update(qExcelRecognizeTemplate)
                        .set(qExcelRecognizeTemplate.recognizeField, item.getRecognizeField())
                        .set(qExcelRecognizeTemplate.recognizeType, item.getRecognizeType())
                        .set(qExcelRecognizeTemplate.enabled, item.getEnabled())
                        .where(qExcelRecognizeTemplate.id.eq(item.getId()))
                        .execute();
            }
        });
        return Boolean.TRUE;
    }

    /**
     * 获取识别字段模板
     * @param businessCode
     * @param businessType
     * @return
     */
    public List<ExcelRecognizeTemplateDto> getExcelRecognizeTemplate(String businessCode, String businessType) {
        if(StringUtils.isEmpty(businessCode)){
            return Collections.emptyList();
        }
        List<ExcelRecognizeTemplate> list = bqf.selectFrom(qExcelRecognizeTemplate)
                .where(qExcelRecognizeTemplate.isDelete.eq(0)
                        .and(qExcelRecognizeTemplate.businessCode.eq(businessCode))
                        .and(qExcelRecognizeTemplate.businessType.eq(businessType))
                ).fetch();
        return list.stream().map(item -> CopierCopyUtils.convertTo(item, ExcelRecognizeTemplateDto.class)).toList();
    }

    /**
     * 保存文件解析字段模板
     * @param voList
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveExcelMappingTemplate(List<SaveExcelMappingVo> voList) {
        if(CollUtil.isEmpty(voList)){
            return Boolean.FALSE;
        }
        //删除此次保存外其他数据
        String businessCode = voList.get(0).getBusinessCode();
        String businessType = voList.get(0).getBusinessType();
        List<Long> idList = voList.stream().filter(f -> ObjectUtils.isNotEmpty(f.getId())).map(SaveExcelMappingVo::getId).toList();
        jqf.update(qExcelMappingTemplate)
                .set(qExcelMappingTemplate.isDelete, 1)
                .where(qExcelMappingTemplate.businessCode.eq(businessCode)
                        .and(qExcelMappingTemplate.businessType.eq(businessType))
                        .and(CollUtil.isEmpty(idList) ? null : qExcelMappingTemplate.id.notIn(idList)))
                .execute();
        voList.forEach(item -> {
            if(ObjectUtils.isEmpty(item.getId())){
                ExcelMappingTemplate excelMappingTemplate = CopierCopyUtils.convertTo(item, ExcelMappingTemplate.class);
                excelMappingTemplateRepository.save(excelMappingTemplate);
            }else{
                jqf.update(qExcelMappingTemplate)
                        .set(qExcelMappingTemplate.mappingType, item.getMappingType())
                        .set(qExcelMappingTemplate.excelFields, item.getExcelFields())
                        .set(qExcelMappingTemplate.judgmentFields, item.getJudgmentFields())
                        .set(qExcelMappingTemplate.conditions, item.getConditions())
                        .set(qExcelMappingTemplate.defaultValue, item.getDefaultValue())
                        .where(qExcelMappingTemplate.id.eq(item.getId()))
                        .execute();
            }
        });
        return Boolean.TRUE;
    }

    /**
     * 获取文件解析字段模板
     * @param businessCode
     * @param businessType
     * @return
     */
    public List<ExcelMappingTemplateDto> getExcelMappingTemplate(String businessCode, String businessType) {
        if(StringUtils.isEmpty(businessCode)){
            return Collections.emptyList();
        }
        List<ExcelMappingTemplate> list = bqf.selectFrom(qExcelMappingTemplate)
                .where(qExcelMappingTemplate.isDelete.eq(0)
                        .and(qExcelMappingTemplate.businessCode.eq(businessCode))
                        .and(qExcelMappingTemplate.businessType.eq(businessType))
                ).fetch();
        return list.stream().map(item -> CopierCopyUtils.convertTo(item, ExcelMappingTemplateDto.class)).toList();
    }

    /**
     * 获取映射字段
     * @return
     */
    public List<ExcelMappingFieldDto> getExcelMappingField() {
        List<ExcelMappingField> list = bqf.selectFrom(qExcelMappingField)
                .where(qExcelMappingField.isDelete.eq(0))
                .fetch();
        if(CollUtil.isEmpty(list)){
            return Collections.emptyList();
        }
        return list.stream().map(item -> CopierCopyUtils.convertTo(item, ExcelMappingFieldDto.class)).toList();
    }

    /**
     * 获取公共银行库
     * @param bankCode
     * @param bankName
     * @param page
     * @return
     */
    public PageResults<BankLibDto> getBankLibPage(String bankCode, String bankName, Page page) {
        PagedList<BankLib> bankLibList = bqf.selectFrom(qBankLib)
                .where(qBankLib.isDelete.eq(0)
                        .and(StringUtils.isEmpty(bankCode) ? null : qBankLib.bankCode.eq(bankCode))
                        .and(StringUtils.isEmpty(bankName) ? null : qBankLib.bankName.like("%" + bankName + "%"))
                )
                .orderBy(qBankLib.enabled.desc(), qBankLib.bankCode.asc(), qBankLib.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        if(ObjectUtils.isEmpty(bankLibList)){
            return null;
        }
        return new PageResults<>(bankLibList.stream().map(item -> {
            BankLibDto dto = CopierCopyUtils.convertTo(item, BankLibDto.class);
            return dto;
        }).toList(), page, bankLibList.getTotalSize());
    }

    /**
     * 删除银行
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteBankLib(Long id) {
        if(ObjectUtils.isEmpty(id)){
            return Boolean.FALSE;
        }
        jqf.update(qBankLib)
                .set(qBankLib.isDelete, 1)
                .where(qBankLib.id.eq(id))
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 启用银行
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean enabledBankLib(Long id) {
        if(ObjectUtils.isEmpty(id)){
            return Boolean.FALSE;
        }
        jqf.update(qBankLib)
                .set(qBankLib.enabled, 1)
                .where(qBankLib.id.eq(id))
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 禁用银行
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean unEnabledBankLib(Long id) {
        if(ObjectUtils.isEmpty(id)){
            return Boolean.FALSE;
        }
        jqf.update(qBankLib)
                .set(qBankLib.enabled, 0)
                .where(qBankLib.id.eq(id))
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 启用自动识别
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean autoRecognize(Long id) {
        if(ObjectUtils.isEmpty(id)){
            return Boolean.FALSE;
        }
        jqf.update(qBankLib)
                .set(qBankLib.isAutoRecognize, 1)
                .where(qBankLib.id.eq(id))
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 关闭自动识别
     * @param id
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean unAutoRecognize(Long id) {
        if(ObjectUtils.isEmpty(id)){
            return Boolean.FALSE;
        }
        jqf.update(qBankLib)
                .set(qBankLib.isAutoRecognize, 0)
                .where(qBankLib.id.eq(id))
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 获取公共银行库
     * @param bankCode
     * @param bankName
     * @return
     */
    public List<BankLibDto> getBankLibList(String bankCode, String bankName) {

        List<BankLib> bankLibList = bqf.selectFrom(qBankLib)
                .where(qBankLib.isDelete.eq(0)
                        .and(qBankLib.enabled.eq(1))
                        .and(StringUtils.isEmpty(bankCode) ? null : qBankLib.bankCode.eq(bankCode))
                        .and(StringUtils.isEmpty(bankName) ? null : qBankLib.bankName.like("%" + bankName + "%"))
                )
                .orderBy(qBankLib.enabled.desc(), qBankLib.bankCode.asc(), qBankLib.id.desc())
                .fetch();
        if(ObjectUtils.isEmpty(bankLibList)){
            return Collections.emptyList();
        }
        return bankLibList.stream().map(item -> {
            BankLibDto dto = CopierCopyUtils.convertTo(item, BankLibDto.class);
            return dto;
        }).toList();
    }

    /**
     * 保存银行 默认已启用 不自动识别
     * @param vo
     * @return
     */
    @Transactional
    public Boolean saveBankLib(SaveBankLibVo vo) {
        BankLib bankLib = bqf.selectFrom(qBankLib)
                .where(qBankLib.bankCode.eq(vo.getBankCode())
                        .and(qBankLib.isDelete.eq(0)).or(qBankLib.bankName.eq(vo.getBankName())))
                .fetchFirst();
        Assert.isTrue(ObjectUtils.isEmpty(bankLib), "银行名称或银行编码已存在，无需重复添加！");
        BankLib save = new BankLib();
        save.setBankCode(vo.getBankCode());
        save.setBankName(vo.getBankName());
        save.setIsAutoRecognize(0);
        save.setEnabled(1);
        bankLibRepository.save(save);
        return Boolean.TRUE;
    }
}
