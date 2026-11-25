package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.CheckoutTemplateDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.CheckoutTemplateService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月18日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequestMapping("/checkout/template")
@RequiredArgsConstructor
@SaCheckLogin
public class CheckoutTemplateController extends AbsService {

	private final CheckoutTemplateService checkoutTemplateService;

	private final VoucherService voucherService;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QCheckoutTemplate qCheckoutTemplate = QCheckoutTemplate.checkoutTemplate;

    private final QSubject qSubject = QSubject.subject;

    /**
     * 结转模板
     *

     * @return
     */
    @SaIgnore
    @GetMapping("/textChange")
    public JsonResult textChange() {
        //遍历公司中的总账套，将其中的结转本年利润这个模板摘出来
        List<AccountSets> accountSetsList = jqf.selectFrom(qAccountSets).fetch();
        //查询账套模板中的结转本年利润（盈利） 和  结转本年利润（亏损）
        //要分小规模 432597845032308737还是一般纳税人 432597845602734082

        //从结转凭证模板中查询小规模或者一般纳税人的结转本年利润（盈利） 和  结转本年利润（亏损）的配置
        List<CheckoutTemplate> checkoutTemplateListxiao = jqf.selectFrom(qCheckoutTemplate)
                .where(qCheckoutTemplate.accountSetsId.eq(432597845032308737L)
                        .and(qCheckoutTemplate.title.like("结转本年利润%")))
                .fetch();
        List<CheckoutTemplate> checkoutTemplateListyiban = jqf.selectFrom(qCheckoutTemplate)
                .where(qCheckoutTemplate.accountSetsId.eq(432597845602734082L)
                        .and(qCheckoutTemplate.title.like("结转本年利润%")))
                .fetch();

        log.info("查询到小规模模板数量: {}, 一般纳税人模板数量: {}",
                checkoutTemplateListxiao.size(), checkoutTemplateListyiban.size());

        //遍历公司中的总账套,将查询出来的模板配置复制到公司中的总账套，给每个账套都新增这个模板
        int successCount = 0;
        int skipCount = 0;
        int errorCount = 0;

        for (AccountSets accountSets : accountSetsList) {
            // 跳过模板账套本身
            if (accountSets.getId().equals(432597845032308737L) || accountSets.getId().equals(432597845602734082L)) {
                log.debug("跳过模板账套: ID={}, 公司名={}", accountSets.getId(), accountSets.getCompanyName());
                continue;
            }

            try {
                // 检查vatType是否为null
                if (accountSets.getVatType() == null) {
                    log.warn("账套vatType为null，跳过: ID={}, 公司名={}", accountSets.getId(), accountSets.getCompanyName());
                    skipCount++;
                    continue;
                }

                List<CheckoutTemplate> templatesToCopy = List.of();
                String vatTypeDisplay = accountSets.getVatType().display;
                if ("小规模纳税人".equals(vatTypeDisplay)) {
                    //小规模
                    templatesToCopy = checkoutTemplateListxiao;
                } else if ("一般纳税人".equals(vatTypeDisplay)) {
                    //一般纳税人
                    templatesToCopy = checkoutTemplateListyiban;
                } else {
                    log.warn("账套vatType不支持，跳过: ID={}, 公司名={}, vatType={}",
                            accountSets.getId(), accountSets.getCompanyName(), vatTypeDisplay);
                    skipCount++;
                    continue;
                }

                // 如果模板列表为空，跳过
                if (CollUtil.isEmpty(templatesToCopy)) {
                    log.warn("源模板列表为空，跳过: ID={}, 公司名={}, vatType={}",
                            accountSets.getId(), accountSets.getCompanyName(), vatTypeDisplay);
                    skipCount++;
                    continue;
                }

                //为每个模板创建副本并保存到当前账套
                for (CheckoutTemplate template : templatesToCopy) {
                    try {
                        // 检查该账套是否已存在相同名称的模板
                        CheckoutTemplate existing = bqf.selectFrom(qCheckoutTemplate)
                                .where(qCheckoutTemplate.accountSetsId.eq(accountSets.getId())
                                        .and(qCheckoutTemplate.title.eq(template.getTitle())))
                                .fetchFirst();

                        if (existing != null) {
                            // 模板已存在，只更新items字段，不改其他字段
                            // 从源模板复制items，然后转换科目ID到当前账套
                            bqf.detach(template);
                            CheckoutTemplate tempTemplate = BeanUtil.toBean(template, CheckoutTemplate.class);

                            // 深拷贝items，避免修改原始template的items
                            if (CollUtil.isNotEmpty(template.getItems())) {
                                List<CheckoutTemplate.CtItem> copiedItems = template.getItems().stream()
                                        .map(item -> {
                                            CheckoutTemplate.CtItem newItem = BeanUtil.toBean(item, CheckoutTemplate.CtItem.class);
                                            // 深拷贝rules
                                            if (CollUtil.isNotEmpty(item.getRules())) {
                                                List<CheckoutTemplate.RuleItem> copiedRules = item.getRules().stream()
                                                        .map(rule -> BeanUtil.toBean(rule, CheckoutTemplate.RuleItem.class))
                                                        .collect(Collectors.toList());
                                                newItem.setRules(copiedRules);
                                            }
                                            return newItem;
                                        })
                                        .collect(Collectors.toList());
                                tempTemplate.setItems(copiedItems);
                            }

                            // 确保使用源模板的原始items（包含源账套的科目ID）
                            // 然后转换到当前循环中的目标账套
                            Long sourceAccountSetsId = template.getAccountSetsId(); // 源账套ID（模板账套）
                            Long targetAccountSetsId = accountSets.getId(); // 目标账套ID（当前循环的账套）

                            // 转换科目ID：从源账套的科目ID转换为目标账套的科目ID（通过科目全名fullName匹配）
                            convertSubjectIds(tempTemplate, sourceAccountSetsId, targetAccountSetsId);

                            // 检查转换后的items是否有效
                            if (CollUtil.isEmpty(tempTemplate.getItems())) {
                                log.error("转换后items为空，跳过更新: 账套ID={}, 公司名={}, 模板标题={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle());
                                errorCount++;
                                continue;
                            }

                            // 检查是否有"按公式取值"类型的item且subjectId不为null
                            boolean hasValidFormulaItem = tempTemplate.getItems().stream()
                                    .anyMatch(item -> CheckoutTemplate.ValueType.按公式取值.equals(item.getValueType())
                                            && item.getSubjectId() != null);
                            if (!hasValidFormulaItem) {
                                log.error("转换后没有有效的'按公式取值'类型item（subjectId不为null），跳过更新: 账套ID={}, 公司名={}, 模板标题={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle());
                                errorCount++;
                                continue;
                            }

                            // 只更新items字段，使用转换后的items（科目ID已经是目标账套的了）
                            existing.setItems(tempTemplate.getItems());
                            try {
                                checkoutTemplateService.save(existing);
                                log.info("更新模板成功: 账套ID={}, 公司名={}, 模板标题={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle());
                                successCount++;
                            } catch (Exception saveException) {
                                log.error("保存模板失败: 账套ID={}, 公司名={}, 模板标题={}, 错误={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle(),
                                        saveException.getMessage(), saveException);
                                errorCount++;
                            }
                        } else {
                            // 模板不存在，新增整个模板
                            bqf.detach(template);
                            CheckoutTemplate newTemplate = BeanUtil.toBean(template, CheckoutTemplate.class);
                            newTemplate.setId(null); // 清除ID，让系统生成新ID
                            newTemplate.setAccountSetsId(accountSets.getId());
                            newTemplate.setIsDefault(false); // 设置为非默认模板

                            // 转换科目ID：从源账套的科目ID转换为目标账套的科目ID（通过科目全名fullName匹配）
                            Long sourceAccountSetsId = template.getAccountSetsId();
                            Long targetAccountSetsId = accountSets.getId();

                            // 记录转换前的items数量
                            int itemsCountBefore = newTemplate.getItems() != null ? newTemplate.getItems().size() : 0;
                            log.debug("开始转换科目ID: 账套ID={}, 公司名={}, 模板标题={}, items数量={}",
                                    accountSets.getId(), accountSets.getCompanyName(), template.getTitle(), itemsCountBefore);

                            convertSubjectIds(newTemplate, sourceAccountSetsId, targetAccountSetsId);

                            // 检查转换后的items是否有效
                            if (CollUtil.isEmpty(newTemplate.getItems())) {
                                log.error("转换后items为空，跳过保存: 账套ID={}, 公司名={}, 模板标题={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle());
                                errorCount++;
                                continue;
                            }

                            // 检查是否有"按公式取值"类型的item且subjectId不为null
                            boolean hasValidFormulaItem = newTemplate.getItems().stream()
                                    .anyMatch(item -> CheckoutTemplate.ValueType.按公式取值.equals(item.getValueType())
                                            && item.getSubjectId() != null);
                            if (!hasValidFormulaItem) {
                                log.error("转换后没有有效的'按公式取值'类型item（subjectId不为null），跳过保存: 账套ID={}, 公司名={}, 模板标题={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle());
                                errorCount++;
                                continue;
                            }

                            // 记录转换后的items信息
                            log.debug("转换完成，准备保存: 账套ID={}, 公司名={}, 模板标题={}, items数量={}, 按公式取值item数量={}",
                                    accountSets.getId(), accountSets.getCompanyName(), template.getTitle(),
                                    newTemplate.getItems().size(),
                                    newTemplate.getItems().stream()
                                            .filter(item -> CheckoutTemplate.ValueType.按公式取值.equals(item.getValueType())
                                                    && item.getSubjectId() != null)
                                            .count());

                            try {
                                checkoutTemplateService.save(newTemplate);
                                log.info("新增模板成功: 账套ID={}, 公司名={}, 模板标题={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle());
                                successCount++;
                            } catch (Exception saveException) {
                                log.error("保存模板失败: 账套ID={}, 公司名={}, 模板标题={}, 错误={}",
                                        accountSets.getId(), accountSets.getCompanyName(), template.getTitle(),
                                        saveException.getMessage(), saveException);
                                errorCount++;
                            }
                        }
                        successCount++;
                    } catch (Exception e) {
                        log.error("处理模板失败: 账套ID={}, 公司名={}, 模板标题={}, 错误={}",
                                accountSets.getId(), accountSets.getCompanyName(),
                                template != null ? template.getTitle() : "未知", e.getMessage(), e);
                        errorCount++;
                    }
                }
            } catch (Exception e) {
                log.error("处理账套失败: ID={}, 公司名={}, 错误={}",
                        accountSets.getId(), accountSets.getCompanyName(), e.getMessage(), e);
                errorCount++;
            }
        }

        log.info("模板复制完成: 成功={}, 跳过={}, 失败={}, 总账套数={}",
                successCount, skipCount, errorCount, accountSetsList.size());
        return JsonResult.successful()
                .setMsg(String.format("处理完成: 成功=%d, 跳过=%d, 失败=%d", successCount, skipCount, errorCount));
    }

	/**
	 * 检查哪些账套没有被复制模板及原因
	 *
	 * @return
	 */
	@SaIgnore
	@GetMapping("/checkAccountSetsStatus")
	public JsonResult checkAccountSetsStatus() {
		List<AccountSets> accountSetsList = jqf.selectFrom(qAccountSets).fetch();

		// 查询源模板
		List<CheckoutTemplate> checkoutTemplateListxiao = jqf.selectFrom(qCheckoutTemplate)
				.where(qCheckoutTemplate.accountSetsId.eq(432597845032308737L)
						.and(qCheckoutTemplate.title.like("结转本年利润%")))
				.fetch();
		List<CheckoutTemplate> checkoutTemplateListyiban = jqf.selectFrom(qCheckoutTemplate)
				.where(qCheckoutTemplate.accountSetsId.eq(432597845602734082L)
						.and(qCheckoutTemplate.title.like("结转本年利润%")))
				.fetch();

		List<Map<String, Object>> result = new ArrayList<>();

		for (AccountSets accountSets : accountSetsList) {
			// 跳过模板账套本身
			if (accountSets.getId().equals(432597845032308737L) || accountSets.getId().equals(432597845602734082L)) {
				continue;
			}

			Map<String, Object> info = new java.util.HashMap<>();
			info.put("账套ID", accountSets.getId());
			info.put("公司名", accountSets.getCompanyName());

			// 检查vatType
			if (accountSets.getVatType() == null) {
				info.put("状态", "vatType为null");
				info.put("原因", "账套的增值税类型未设置");
				result.add(info);
				continue;
			}

			String vatTypeDisplay = accountSets.getVatType().display;
			info.put("vatType", vatTypeDisplay);

			// 检查vatType是否支持
			List<CheckoutTemplate> templatesToCopy = List.of();
			if ("小规模纳税人".equals(vatTypeDisplay)) {
				templatesToCopy = checkoutTemplateListxiao;
			} else if ("一般纳税人".equals(vatTypeDisplay)) {
				templatesToCopy = checkoutTemplateListyiban;
			} else {
				info.put("状态", "vatType不支持");
				info.put("原因", "vatType值为: " + vatTypeDisplay + "，只支持'小规模纳税人'或'一般纳税人'");
				result.add(info);
				continue;
			}

			// 检查源模板列表是否为空
			if (CollUtil.isEmpty(templatesToCopy)) {
				info.put("状态", "源模板列表为空");
				info.put("原因", "源模板账套中没有找到'结转本年利润%'的模板");
				result.add(info);
				continue;
			}

			// 检查目标账套中是否已有模板
			List<String> existingTemplates = new ArrayList<>();
			List<String> missingTemplates = new ArrayList<>();
			for (CheckoutTemplate template : templatesToCopy) {
				CheckoutTemplate existing = bqf.selectFrom(qCheckoutTemplate)
						.where(qCheckoutTemplate.accountSetsId.eq(accountSets.getId())
								.and(qCheckoutTemplate.title.eq(template.getTitle())))
						.fetchFirst();
				if (existing != null) {
					existingTemplates.add(template.getTitle());
				} else {
					missingTemplates.add(template.getTitle());
				}
			}

			if (missingTemplates.isEmpty()) {
				info.put("状态", "已存在");
				info.put("原因", "所有模板都已存在");
				info.put("已存在的模板", existingTemplates);
			} else {
				info.put("状态", "部分缺失");
				info.put("原因", "缺少以下模板: " + String.join(", ", missingTemplates));
				info.put("已存在的模板", existingTemplates);
				info.put("缺失的模板", missingTemplates);
			}
			result.add(info);
		}

		return JsonResult.successful()
				.setMsg(String.format("检查完成，共检查 %d 个账套", result.size()))
				.data("accountSetsStatus", result);
	}

	/**
	 * 检查源模板账套中的原始模板，查看哪些item没有subjectId
	 *
	 * @return
	 */
	@SaIgnore
	@GetMapping("/checkSourceTemplates")
	public JsonResult checkSourceTemplates() {
		// 源模板账套ID
		Long sourceAccountSetsIdXiao = 432597845032308737L;
		Long sourceAccountSetsIdYiban = 432597845602734082L;

		List<CheckoutTemplate> templatesXiao = jqf.selectFrom(qCheckoutTemplate)
				.where(qCheckoutTemplate.accountSetsId.eq(sourceAccountSetsIdXiao)
						.and(qCheckoutTemplate.title.like("结转本年利润%")))
				.fetch();
		List<CheckoutTemplate> templatesYiban = jqf.selectFrom(qCheckoutTemplate)
				.where(qCheckoutTemplate.accountSetsId.eq(sourceAccountSetsIdYiban)
						.and(qCheckoutTemplate.title.like("结转本年利润%")))
				.fetch();

		List<Map<String, Object>> result = new ArrayList<>();

		// 检查小规模模板
		for (CheckoutTemplate template : templatesXiao) {
			for (CheckoutTemplate.CtItem item : template.getItems()) {
				if (item.getSubjectId() == null) {
					Map<String, Object> info = new java.util.HashMap<>();
					info.put("账套类型", "小规模");
					info.put("账套ID", sourceAccountSetsIdXiao);
					info.put("模板ID", template.getId());
					info.put("模板标题", template.getTitle());
					info.put("item摘要", item.getSummary());
					info.put("取值类型", item.getValueType() != null ? item.getValueType().name() : "未知");
					info.put("借贷方向", item.getBalanceDirection());
					result.add(info);
					log.warn("源模板中item没有subjectId: {}", info);
				}
			}
		}

		// 检查一般纳税人模板
		for (CheckoutTemplate template : templatesYiban) {
			for (CheckoutTemplate.CtItem item : template.getItems()) {
				if (item.getSubjectId() == null) {
					Map<String, Object> info = new java.util.HashMap<>();
					info.put("账套类型", "一般纳税人");
					info.put("账套ID", sourceAccountSetsIdYiban);
					info.put("模板ID", template.getId());
					info.put("模板标题", template.getTitle());
					info.put("item摘要", item.getSummary());
					info.put("取值类型", item.getValueType() != null ? item.getValueType().name() : "未知");
					info.put("借贷方向", item.getBalanceDirection());
					result.add(info);
					log.warn("源模板中item没有subjectId: {}", info);
				}
			}
		}

		return JsonResult.successful()
				.setMsg(String.format("检查完成，发现 %d 个item没有subjectId", result.size()))
				.data("itemsWithoutSubjectId", result);
	}

	/**
	 * 修复已保存的模板中的科目ID（通过科目全名fullName匹配转换，只更新items字段）
	 *
	 * @return
	 */
	@SaIgnore
	@GetMapping("/fixSubjectIds")
	public JsonResult fixSubjectIds() {
        // 查询所有账套
        List<AccountSets> accountSetsList = jqf.selectFrom(qAccountSets).fetch();

        // 源模板账套ID
        Long sourceAccountSetsIdXiao = 432597845032308737L;
        Long sourceAccountSetsIdYiban = 432597845602734082L;

        // 遍历所有账套，修复模板中的科目ID
        for (AccountSets accountSets : accountSetsList) {
            // 跳过模板账套本身
            if (accountSets.getId().equals(sourceAccountSetsIdXiao) || accountSets.getId().equals(sourceAccountSetsIdYiban)) {
                continue;
            }

            // 确定源账套ID
            Long sourceAccountSetsId;
            if ("小规模纳税人".equals(accountSets.getVatType().display)) {
                sourceAccountSetsId = sourceAccountSetsIdXiao;
            } else if ("一般纳税人".equals(accountSets.getVatType().display)) {
                sourceAccountSetsId = sourceAccountSetsIdYiban;
            } else {
                continue;
            }

            // 查询该账套中所有"结转本年利润"相关的模板
            List<CheckoutTemplate> templates = bqf.selectFrom(qCheckoutTemplate)
                    .where(qCheckoutTemplate.accountSetsId.eq(accountSets.getId())
                            .and(qCheckoutTemplate.title.like("结转本年利润%")))
                    .fetch();

            // 修复每个模板中的科目ID（只更新items字段，不影响其他字段）
            for (CheckoutTemplate template : templates) {
                // 转换科目ID（在副本上操作，避免影响原对象）
                CheckoutTemplate templateCopy = BeanUtil.toBean(template, CheckoutTemplate.class);
                convertSubjectIds(templateCopy, sourceAccountSetsId, accountSets.getId());

                // 只更新items字段，使用Repository直接更新
                CheckoutTemplate existingTemplate = bqf.selectFrom(qCheckoutTemplate)
                        .where(qCheckoutTemplate.id.eq(template.getId())
                                .and(qCheckoutTemplate.accountSetsId.eq(accountSets.getId())))
                        .fetchFirst();

                if (existingTemplate != null) {
                    // 只更新items字段，其他字段保持不变
                    existingTemplate.setItems(templateCopy.getItems());
                    checkoutTemplateService.save(existingTemplate);
                }
            }
        }

        return JsonResult.successful();
    }


    /**
	 * 结转模板
	 *
	 * @param accountSetsId
	 * @param year          结转期间年
	 * @param month         结转期间月
	 * @return
	 */
	@GetMapping
	public JsonResult list(@SessionAccountSet AccountSets accountSets, Integer year, Integer month) {
		Long accountSetsId = accountSets.getId();
		List<CheckoutTemplateDto> checkoutTemplateDtos = checkoutTemplateService.list(accountSetsId);
		if (CollUtil.isNotEmpty(checkoutTemplateDtos)) {
			Map<Long, Voucher> checkVoucher = voucherService.loadByCheckTplId(accountSetsId, checkoutTemplateDtos.stream().map(CheckoutTemplateDto::getId).collect(Collectors.toList()), year, month);
			if (CollUtil.isNotEmpty(checkVoucher)) {
				checkoutTemplateDtos.forEach(dto -> {
					Voucher voucher = checkVoucher.get(dto.getId());
					if (voucher != null) {
						dto.setVoucherId(voucher.getId());
						dto.setAmount(voucher.getDebitAmount());
					}
				});
			}
		}
		return JsonResult.successful(checkoutTemplateDtos);
	}

	@PostMapping
	public JsonResult create(@RequestBody CheckoutTemplate checkoutTemplate, @SessionAccountSet AccountSets accountSets) {
		checkoutTemplate.setAccountSetsId(accountSets.getId());
		checkoutTemplate.setIsDefault(false);
		return JsonResult.successful(checkoutTemplateService.save(checkoutTemplate));
	}

	/**
	 * 结转模板数据
	 *
	 * @param tplId
	 * @return
	 */
	@GetMapping("data")
	public JsonResult tplData(@SessionAccountSet AccountSets accountSets, Long tplId) {
		return JsonResult.successful(checkoutTemplateService.tplData(accountSets.getId(), tplId));
	}

	/**
	 * 结转模板显示数据
	 *
	 * @param checkYear
	 * @param checkMonth
	 * @return
	 */
	@GetMapping("show/data")
	public JsonResult tplShowData(@SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo, Integer checkYear, Integer checkMonth) {
		return JsonResult.successful(checkoutTemplateService.tplShowData(accountSets.getId(), userVo.getLocalCurrencyId(), checkYear, checkMonth));
	}

	@DeleteMapping("/{checkoutTemplateId:\\d+}")
	public JsonResult delete(@PathVariable Long checkoutTemplateId, @SessionAccountSet AccountSets accountSets) {
		checkoutTemplateService.delete(new PrimaryKey(checkoutTemplateId, accountSets.getId()));
		return JsonResult.successful();
	}

	/**
	 * 转换模板中的科目ID：从源账套的科目ID转换为目标账套的科目ID（通过科目全名fullName匹配）
	 *
	 * @param template 模板对象
	 * @param sourceAccountSetsId 源账套ID
	 * @param targetAccountSetsId 目标账套ID
	 */
	private void convertSubjectIds(CheckoutTemplate template, Long sourceAccountSetsId, Long targetAccountSetsId) {
		if (CollUtil.isEmpty(template.getItems())) {
			return;
		}

		// 收集所有需要转换的科目ID（包括items中的subjectId和rules中的subjectId）
		Set<Long> sourceSubjectIds = template.getItems().stream()
				.filter(item -> item.getSubjectId() != null)
				.map(CheckoutTemplate.CtItem::getSubjectId)
				.collect(Collectors.toSet());

		// 收集rules中的subjectId
		template.getItems().forEach(item -> {
			if (CollUtil.isNotEmpty(item.getRules())) {
				item.getRules().stream()
						.filter(rule -> rule.getSubjectId() != null)
						.forEach(rule -> sourceSubjectIds.add(rule.getSubjectId()));
			}
		});

		if (CollUtil.isEmpty(sourceSubjectIds)) {
			return;
		}

		// 查询源账套的科目，建立ID到科目信息的映射（包含code, name, fullName）
		Map<Long, Subject> sourceSubjectMap = bqf.selectFrom(qSubject)
				.where(qSubject.accountSetsId.eq(sourceAccountSetsId)
						.and(qSubject.id.in(sourceSubjectIds)))
				.fetch()
				.stream()
				.collect(Collectors.toMap(Subject::getId, s -> s));

		Map<Long, String> sourceSubjectIdToFullNameMap = sourceSubjectMap.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFullName()));

		// 查询目标账套的科目，建立全名到ID的映射
		Set<String> subjectFullNames = sourceSubjectIdToFullNameMap.values().stream()
				.filter(fullName -> fullName != null && !fullName.isEmpty())
				.collect(Collectors.toSet());

		if (CollUtil.isEmpty(subjectFullNames)) {
			log.warn("源账套中没有找到任何科目的fullName，无法进行转换: 源账套ID={}, 目标账套ID={}, 模板标题={}",
					sourceAccountSetsId, targetAccountSetsId, template.getTitle());
			return;
		}

		// 查询目标账套的所有科目，建立多种映射关系
		List<Subject> targetSubjects = bqf.selectFrom(qSubject)
				.where(qSubject.accountSetsId.eq(targetAccountSetsId))
				.fetch();

		// 建立全名到ID的映射（处理重名情况，取第一个）
		Map<String, Long> targetFullNameToSubjectIdMap = targetSubjects.stream()
				.filter(s -> s.getFullName() != null && !s.getFullName().isEmpty())
				.collect(Collectors.toMap(
						Subject::getFullName,
						Subject::getId,
						(existing, replacement) -> existing // 如果有重名，保留第一个
				));

		// 建立name到ID的映射（备用匹配策略）
		Map<String, Long> targetNameToSubjectIdMap = targetSubjects.stream()
				.filter(s -> s.getName() != null && !s.getName().isEmpty())
				.collect(Collectors.toMap(
						Subject::getName,
						Subject::getId,
						(existing, replacement) -> existing // 如果有重名，保留第一个
				));

		// 获取目标账套的所有科目ID集合，用于验证转换后的ID是否属于目标账套
		Set<Long> targetAccountSubjectIds = bqf.selectFrom(qSubject)
				.where(qSubject.accountSetsId.eq(targetAccountSetsId))
				.fetch()
				.stream()
				.map(Subject::getId)
				.collect(Collectors.toSet());

		// 转换items中的subjectId
		template.getItems().forEach(item -> {
			if (item.getSubjectId() != null) {
				Long originalSubjectId = item.getSubjectId();
				Subject sourceSubject = sourceSubjectMap.get(originalSubjectId);

				if (sourceSubject == null) {
					// 源账套中找不到对应的科目
					log.warn("源账套中找不到对应的科目，清空subjectId: 源账套ID={}, 目标账套ID={}, 源科目ID={}, 模板标题={}, item摘要={}, 取值类型={}",
							sourceAccountSetsId, targetAccountSetsId, originalSubjectId,
							template.getTitle(), item.getSummary(),
							item.getValueType() != null ? item.getValueType().name() : "未知");
					item.setSubjectId(null);
				} else {
					String fullName = sourceSubject.getFullName();
					String code = sourceSubject.getCode();
					String name = sourceSubject.getName();

					Long targetSubjectId = null;
					String matchMethod = null;

					// 策略1: 通过fullName匹配（优先）
					if (fullName != null && !fullName.isEmpty()) {
						targetSubjectId = targetFullNameToSubjectIdMap.get(fullName);
						if (targetSubjectId != null) {
							matchMethod = "fullName";
						}
					}

					// 策略2: 如果fullName匹配失败，尝试通过name匹配（备用）
					if (targetSubjectId == null && name != null && !name.isEmpty()) {
						targetSubjectId = targetNameToSubjectIdMap.get(name);
						if (targetSubjectId != null) {
							matchMethod = "name";
							log.warn("通过name匹配到科目（fullName匹配失败）: 源账套ID={}, 目标账套ID={}, 源科目ID={}, 源科目name={}, 源科目fullName={}, 目标科目ID={}, 模板标题={}, item摘要={}",
									sourceAccountSetsId, targetAccountSetsId, originalSubjectId, name, fullName,
									targetSubjectId, template.getTitle(), item.getSummary());
						}
					}

					if (targetSubjectId != null) {
						// 找到匹配的科目，更新subjectId
						item.setSubjectId(targetSubjectId);
						log.debug("科目ID转换成功: 源账套ID={}, 目标账套ID={}, 源科目ID={}, 源科目code={}, 源科目name={}, 源科目fullName={}, 目标科目ID={}, 匹配方式={}, 模板标题={}, item摘要={}",
								sourceAccountSetsId, targetAccountSetsId, originalSubjectId, code, name, fullName,
								targetSubjectId, matchMethod, template.getTitle(), item.getSummary());
					} else {
						// 目标账套中找不到匹配的科目，清空subjectId，避免使用错误的ID
						log.warn("目标账套中找不到匹配的科目，清空subjectId: 源账套ID={}, 目标账套ID={}, 源科目ID={}, 源科目code={}, 源科目name={}, 源科目fullName={}, 模板标题={}, item摘要={}, 取值类型={}",
								sourceAccountSetsId, targetAccountSetsId, originalSubjectId, code, name, fullName,
								template.getTitle(), item.getSummary(),
								item.getValueType() != null ? item.getValueType().name() : "未知");
						item.setSubjectId(null);
					}
				}

				// 验证：如果转换后的subjectId不是目标账套的科目ID，则清空
				if (item.getSubjectId() != null && !targetAccountSubjectIds.contains(item.getSubjectId())) {
					log.warn("转换后的subjectId不属于目标账套，清空subjectId: 源账套ID={}, 目标账套ID={}, 科目ID={}, 模板标题={}, item摘要={}",
							sourceAccountSetsId, targetAccountSetsId, item.getSubjectId(),
							template.getTitle(), item.getSummary());
					item.setSubjectId(null);
				}
			} else {
				// 如果item本身就没有subjectId，记录日志（特别是"自动平衡"类型）
				if (item.getValueType() != null && item.getValueType().equals(CheckoutTemplate.ValueType.自动平衡)) {
					log.debug("item没有subjectId（自动平衡类型）: 目标账套ID={}, 模板标题={}, item摘要={}, 借贷方向={}",
							targetAccountSetsId, template.getTitle(), item.getSummary(), item.getBalanceDirection());
				} else {
					log.warn("item没有subjectId（非自动平衡类型）: 目标账套ID={}, 模板标题={}, item摘要={}, 取值类型={}",
							targetAccountSetsId, template.getTitle(), item.getSummary(),
							item.getValueType() != null ? item.getValueType().name() : "未知");
				}
			}

			// 转换rules中的subjectId
			if (CollUtil.isNotEmpty(item.getRules())) {
				item.getRules().forEach(rule -> {
					if (rule.getSubjectId() != null) {
						Long originalRuleSubjectId = rule.getSubjectId();
						Subject sourceRuleSubject = sourceSubjectMap.get(originalRuleSubjectId);

						if (sourceRuleSubject == null) {
							// 源账套中找不到对应的科目
							log.warn("rules中源账套找不到对应的科目，清空subjectId: 源账套ID={}, 目标账套ID={}, 源科目ID={}, 模板标题={}, item摘要={}",
									sourceAccountSetsId, targetAccountSetsId, originalRuleSubjectId,
									template.getTitle(), item.getSummary());
							rule.setSubjectId(null);
						} else {
							String fullName = sourceRuleSubject.getFullName();
							String name = sourceRuleSubject.getName();

							Long targetSubjectId = null;

							// 策略1: 通过fullName匹配（优先）
							if (fullName != null && !fullName.isEmpty()) {
								targetSubjectId = targetFullNameToSubjectIdMap.get(fullName);
							}

							// 策略2: 如果fullName匹配失败，尝试通过name匹配（备用）
							if (targetSubjectId == null && name != null && !name.isEmpty()) {
								targetSubjectId = targetNameToSubjectIdMap.get(name);
								if (targetSubjectId != null) {
									log.warn("rules中通过name匹配到科目（fullName匹配失败）: 源账套ID={}, 目标账套ID={}, 源科目ID={}, 源科目name={}, 源科目fullName={}, 目标科目ID={}, 模板标题={}, item摘要={}",
											sourceAccountSetsId, targetAccountSetsId, originalRuleSubjectId, name, fullName,
											targetSubjectId, template.getTitle(), item.getSummary());
								}
							}

							if (targetSubjectId != null) {
								// 找到匹配的科目，更新subjectId
								rule.setSubjectId(targetSubjectId);
							} else {
								// 目标账套中找不到匹配的科目，清空subjectId
								log.warn("rules中目标账套找不到匹配的科目，清空subjectId: 源账套ID={}, 目标账套ID={}, 源科目ID={}, 源科目name={}, 源科目fullName={}, 模板标题={}, item摘要={}",
										sourceAccountSetsId, targetAccountSetsId, originalRuleSubjectId, name, fullName,
										template.getTitle(), item.getSummary());
								rule.setSubjectId(null);
							}
						}

						// 验证：如果转换后的subjectId不是目标账套的科目ID，则清空
						if (rule.getSubjectId() != null && !targetAccountSubjectIds.contains(rule.getSubjectId())) {
							log.warn("rules中转换后的subjectId不属于目标账套，清空subjectId: 源账套ID={}, 目标账套ID={}, 科目ID={}, 模板标题={}, item摘要={}",
									sourceAccountSetsId, targetAccountSetsId, rule.getSubjectId(),
									template.getTitle(), item.getSummary());
							rule.setSubjectId(null);
						}
					}
				});
			}
		});
	}
}
