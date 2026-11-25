package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.RenewalCodeDownloadDto;
import cn.gson.financial.kernel.model.dto.RenewalCodeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.mapper.RenewalCodeMapper;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.RenewalCodeRepository;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (RenewalCode)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-05 19:46:08</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RenewalCodeService extends AbsService {
    private final RenewalCodeMapper renewalCodeMapper;

    private static final QRenewalCode qRenewalCode = QRenewalCode.renewalCode;

    private static final QUser qUser = QUser.user;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final RenewalCodeRepository renewalCodeRepository;

    private final AccountSetsRepository accountSetsRepository;

    public PageResults<RenewalCodeDto> list(Page page, RenewalCodeQuery query) {
        PagedList<Tuple> pagedList = bqf.selectFrom(qRenewalCode)
                .select(qRenewalCode, qUser.realName.coalesce(qUser.nickname), qUser.mobile, qAccountSets.companyName)
                .leftJoin(qUser).on(qUser.id.eq(qRenewalCode.userId))
                .leftJoin(qAccountSets).on(qAccountSets.id.eq(qRenewalCode.asId))
                .orderBy(qRenewalCode.useTime.desc().nullsFirst(), qRenewalCode.id.desc())
                .where(query.builder)
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        ArrayList<RenewalCodeDto> collect = pagedList.stream().collect(ArrayList::new, (list, tuple) -> {
            RenewalCodeDto dto = renewalCodeMapper.toDto(tuple.get(qRenewalCode));
            dto.setUserMobile(tuple.get(qUser.mobile));
            dto.setUserName(tuple.get(qUser.realName.coalesce(qUser.nickname)));
            dto.setAstName(tuple.get(qAccountSets.companyName));
            list.add(dto);
        }, List::addAll);
        return new PageResults<>(collect, page, pagedList.getTotalSize());
    }

    @Transactional
    public void delete(Long renewalCodeId) {
        jqf.delete(qRenewalCode)
                .where(qRenewalCode.id.eq(renewalCodeId).and(qRenewalCode.userId.isNull())).execute();
    }

    @Transactional
    public RenewalCode valid(String code, UserVo userVo, Long astId) {
        RenewalCode renewalCode = bqf.selectFrom(qRenewalCode)
                .where(qRenewalCode.code.eq(code))
                .fetchFirst();
        Assert.notNull(renewalCode, "订购码错误~");
        Assert.isNull(renewalCode.getUserId(), "订购码已被使用~");

        renewalCode.setUserId(userVo.getId());

        //账套续期
        if ("astMonth".equals(renewalCode.getType())) {
            if (astId == null) {
                renewalCode.setAsId(userVo.getAccountSets().getId());
                //账套续期
                userVo.getAccountSets().setEffectiveDate(userVo.getAccountSets().getEffectiveDate().plusMonths(renewalCode.getMonth()));
                accountSetsRepository.saveAndFlush(userVo.getAccountSets());
            } else {
                AccountSets accountSets = bqf.selectFrom(qAccountSets)
                        .where(qAccountSets.id.eq(astId))
                        .fetchFirst();
                Assert.notNull(accountSets, "账套信息错误~");
                Assert.notNull(accountSets.getEffectiveDate(), "您是无限期用户，无需续期～");
                accountSets.setEffectiveDate(accountSets.getEffectiveDate().plusMonths(renewalCode.getMonth()));
                accountSetsRepository.saveAndFlush(accountSets);
                renewalCode.setAsId(astId);
            }
        } else {
            jqf.update(qUser)
                    .set(qUser.accountSetsNum, userVo.getAccountSetsNum() + renewalCode.getMonth())
                    .where(qUser.id.eq(userVo.getId()))
                    .execute();
        }

        renewalCode.setUseTime(LocalDateTime.now());
        renewalCodeRepository.save(renewalCode);
        return renewalCode;
    }

    @Transactional
    public void generate(String type, Integer num, Integer month, String channel) {
        List<RenewalCode> renewalCodes = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            RenewalCode code = new RenewalCode();
            code.setType(type);
            code.setCode(("astNum".equals(type) ? "n" : "t") + month + IdUtil.nanoId(7));
            code.setMonth(month);
            code.setChannel(channel);
            renewalCodes.add(code);
        }
        renewalCodeRepository.saveAll(renewalCodes);
    }

    public List<RenewalCodeDownloadDto> list(RenewalCodeQuery query) {
        return bqf.selectFrom(qRenewalCode)
                .where(query.builder)
                .fetch().stream().map(renewalCodeMapper::toDownloadDto).collect(Collectors.toList());
    }

    public static class RenewalCodeQuery {
        private BooleanBuilder builder = new BooleanBuilder();

        public void setCode(String code) {
            if (StrUtil.isNotEmpty(code)) {
                builder.and(qRenewalCode.code.eq(code));
            }
        }

        public void setChannel(String channel) {
            if (StrUtil.isNotEmpty(channel)) {
                builder.and(qRenewalCode.channel.eq(channel));
            }
        }

        public void setUsed(Boolean used) {
            if (used != null) {
                if (used) {
                    builder.and(qRenewalCode.userId.isNotNull());
                } else {
                    builder.and(qRenewalCode.userId.isNull());
                }
            }
        }

        public void setType(String type) {
            if (StrUtil.isNotEmpty(type)) {
                builder.and(qRenewalCode.type.eq(type));
            }
        }
    }
}

