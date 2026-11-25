package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.UserAccountSetsDto;
import cn.gson.financial.kernel.model.dto.UserDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.mapper.UserMapper;
import cn.gson.financial.kernel.model.repo.RoleRepository;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import cn.gson.financial.kernel.model.repo.UserRepository;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.tool.RedisUtil;
import cn.gson.financial.kernel.model.ua.resp.GetUserInfoResp;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Service
@RequiredArgsConstructor
public class UserService extends AbsService {

    private final UserRepository userRepository;

    private final UserAccountSetsRepository userAccountSetsRepository;

    private final RoleRepository roleRepository;

    private final CheckoutService checkoutService;

    private final RoleService roleService;

    private final CurrencyService currencyService;

    private final RedisUtil redisUtil;

    private final Environment environment;

    private final static QUser qUser = QUser.user;

    private final static QRole qRole = QRole.role;

    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final UserMapper userMapper;

    public UserVo login(@NonNull String mobile, @NonNull String password) {
        Optional<User> user = userRepository.findOne(qUser.mobile.eq(mobile));
        if (user.isEmpty()) {
            throw new ServiceException("账号错误！");
        }

        //本地测试，不校验密码
//        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            String hashed = user.get().getPassword();
            Assert.isTrue(hashed.equals(DigestUtil.sha256Hex(password)) || DigestUtil.bcryptCheck(password, hashed), "账号或密码错误~");
//        }

        //获取角色信息
        return getUserVo(user.get());
    }


    public UserVo getUserVo(Long userId) {
        return getUserVo(bqf.selectFrom(qUser).where(qUser.id.eq(userId)).fetchFirst());

    }

    public List<UserVo> listByAccountSetId(Long accountSetsId) {
        return bqf.selectFrom(qUserAccountSets)
                .select(qUser, qRole)
                .leftJoin(qUser).on(qUserAccountSets.userId.eq(qUser.id))
                .leftJoin(qRole).on(qRole.id.eq(qUserAccountSets.roleId).and(qRole.accountSetsId.eq(accountSetsId)))
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId))
                .orderBy(qRole.id.asc(), qRole.astAdmin.desc())
                .fetch().stream().map(tuple -> {
                    UserVo vo = userMapper.toVo(tuple.get(qUser));
                    vo.setRoleInfo(tuple.get(qRole));
                    return vo;
                }).toList();
    }

    private UserVo getUserVo(User user) {
        UserVo userVo = BeanUtil.toBean(user, UserVo.class);
        UserAccountSets userAccountSets = bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.current.isTrue().and(qUserAccountSets.userId.eq(user.getId())))
                .fetchFirst();
        if (userAccountSets != null) {
            Role role = roleService.load(userAccountSets.getAccountSetsId(), userAccountSets.getRoleId());
            Assert.notNull(role, "未关联角色，请联系管理员处理~");
            userVo.setRoleInfo(role);
            userVo.setCheckoutList(checkoutService.list(userAccountSets.getAccountSetsId()));
            AccountSets accountSets = jqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(userAccountSets.getAccountSetsId())).fetchFirst();
            userVo.setAccountSets(accountSets);
            //设置本位币ID
            userVo.setLocalCurrencyId(currencyService.fetchLocal(userAccountSets.getAccountSetsId()).getId());
        }

        userVo.setAccountSetsList(bqf.selectFrom(qUserAccountSets)
                .select(qAccountSets)
                .leftJoin(qAccountSets).on(qAccountSets.id.eq(qUserAccountSets.accountSetsId))
                .where(qUserAccountSets.userId.eq(user.getId()))
                .fetch());
        return userVo;
    }

    @Transactional
    public User update(Long userId, User user) {
        User original = userRepository.getReferenceById(userId);
        BeanUtil.copyProperties(user, original, CopyOptions.create().ignoreNullValue());
        user = userRepository.save(original);
        return user;
    }

    @Transactional
    public User save(User user) {
        if (user.getId() != null) {
            User original = userRepository.getReferenceById(user.getId());
            BeanUtil.copyProperties(user, original, CopyOptions.create().ignoreNullValue());
            user = userRepository.save(original);
        } else {
            user = userRepository.save(user);
        }
        return user;
    }

    @Transactional
    public void delete(Long userId) {
        jqf.delete(qUser).where(qUser.id.eq(userId)).execute();
    }

    public long countByMobile(String mobile) {
        return bqf.selectFrom(qUser)
                .where(qUser.mobile.eq(mobile))
                .fetchCount();
    }

    public User getByMobile(String mobile) {
        return bqf.selectFrom(qUser)
                .where(qUser.mobile.eq(mobile)).fetchFirst();
    }
    public long countBypPassword(String mobile, String password) {
        return bqf.selectFrom(qUser)
                .where(qUser.password.eq(DigestUtil.sha256Hex(password)).and(qUser.mobile.eq(mobile)))
                .fetchCount();
    }

    public PageResults<UserDto> pageList(Page page, UserQuery query) {
        BlazeJPAQuery<Long> totalAccountSets = bqf.selectFrom(qUserAccountSets)
                .select(qUserAccountSets.count())
                .where(qUserAccountSets.userId.eq(qUser.id));
        OrderSpecifier<Long> orderSpecifier = qUser.id.desc();

        if (StrUtil.isNotEmpty(page.getProperty())) {
            orderSpecifier = ReflectUtil.invoke(ReflectUtil.getFieldValue(qUser, page.getProperty()), StrUtil.emptyToDefault(page.getOrder(), "asc"));
        }

        PagedList<Tuple> fetchPage = bqf.selectFrom(qUser)
                .select(qUser, totalAccountSets)
                .where(query.builder)
                .orderBy(orderSpecifier.nullsLast(), qUser.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());

        List<UserDto> userDtos = new ArrayList<>();
        fetchPage.forEach(tuple -> {
            UserDto userDto = BeanUtil.toBean(tuple.get(qUser), UserDto.class);
            if (tuple.get(totalAccountSets) != null) {
                userDto.setTotalAccounts(tuple.get(totalAccountSets).intValue());
            }
            userDtos.add(userDto);
        });

        return new PageResults<>(userDtos, page, fetchPage.getTotalSize());
    }

    public List<UserAccountSetsDto> getAccountSetsList(Long userId) {
        return bqf.selectFrom(qAccountSets)
                .select(qUserAccountSets.roleType, qAccountSets)
                .leftJoin(qUserAccountSets).on(qAccountSets.id.eq(qUserAccountSets.accountSetsId))
                .where(qUserAccountSets.userId.eq(userId))
                .orderBy(qAccountSets.createDate.desc())
                .fetch().stream().collect(ArrayList::new, (list, tuple) -> {
                    UserAccountSetsDto dto = BeanUtil.toBean(tuple.get(qAccountSets), UserAccountSetsDto.class);
                    dto.setRoleType(tuple.get(qUserAccountSets.roleType));
                    list.add(dto);
                }, List::addAll);
    }

    @Transactional
    public void changeAccountSets(Long accountSetsId, Long userId) {
        jqf.update(qUserAccountSets)
                .set(qUserAccountSets.current, false)
                .where(qUserAccountSets.userId.eq(userId))
                .execute();
        jqf.update(qUserAccountSets)
                .set(qUserAccountSets.current, true)
                .where(qUserAccountSets.userId.eq(userId).and(qUserAccountSets.accountSetsId.eq(accountSetsId)))
                .execute();
    }

    public long count() {
        return bqf.selectFrom(qUser).fetchCount();
    }

    @Transactional
    public void updateLastTime(Long id) {
        jqf.update(qUser)
                .set(qUser.lastUpdate, LocalDateTime.now())
                .where(qUser.id.eq(id))
                .execute();
    }

    /**
     * 最近7天注册用户数
     *
     * @return
     */
    public long regCount() {
        return bqf.selectFrom(qUser)
                .where(qUser.createDate.after(LocalDate.now().minusDays(7).atStartOfDay()))
                .fetchCount();
    }

    /**
     * 最近7天活跃用户数
     *
     * @return
     */
    public Object activeCount() {
        return bqf.selectFrom(qUser)
                .where(qUser.lastUpdate.after(LocalDate.now().minusDays(7).atStartOfDay()))
                .fetchCount();
    }

    /**
     * 近30天每天新注册人数
     */
    public List<Dict> chart30RegNum() {
        LocalDate date = LocalDate.now().minusDays(30);
        return lazyDao.findBySql("chartUser30RegNum", Dict.create().set("date", date), Dict.class);
    }

    /**
     * 根据用户ID和账套ID获取用户的账套信息
     *
     * @param accountSetsId
     * @param userId
     * @return
     */
    public AccountSets loadByAstId(Long accountSetsId, Long userId) {
        String cached = redisUtil.get("ua:" + accountSetsId + ":" + userId);
        if (StrUtil.isNotEmpty(cached)) {
            return JSON.parseObject(cached, AccountSets.class);
        }
        AccountSets accountSets = bqf.selectFrom(qUserAccountSets)
                .select(qAccountSets)
                .leftJoin(qAccountSets).on(qUserAccountSets.accountSetsId.eq(qAccountSets.id))
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                        .and(qUserAccountSets.userId.eq(userId)))
                .fetchFirst();
        if (accountSets != null) {
            redisUtil.set("ua:" + accountSetsId + ":" + userId, JSON.toJSONString(accountSets), 60);
        }
        return accountSets;
    }

    /**
     * 重建账套用户关系
     *
     * @param user
     * @return
     */
    @Transactional
    public int resetAstCurrent(UserVo user) {
        List<AccountSets> setsList = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creatorId.eq(user.getId()))
                .fetch();
        int resetNum = 0;
        for (AccountSets current : setsList) {
            if (bqf.selectFrom(qUserAccountSets)
                        .where(qUserAccountSets.userId.eq(user.getId()).and(qUserAccountSets.accountSetsId.eq(current.getId())))
                        .fetchCount() == 0) {
                jqf.update(qUserAccountSets)
                        .set(qUserAccountSets.current, false)
                        .where(qUserAccountSets.userId.eq(current.getId()))
                        .execute();
                Role role = bqf.selectFrom(qRole)
                        .where(qRole.astAdmin.isTrue()
                                .and(qRole.accountSetsId.eq(current.getId())))
                        .fetchFirst();
                if (role == null) {
                    Role baseRole = bqf.selectFrom(qRole)
                            .where(qRole.accountSetsId.eq(0L).and(qRole.astAdmin))
                            .fetchFirst();
                    role = new Role();
                    role.setName(baseRole.getName());
                    role.setDescribe(baseRole.getDescribe());
                    role.setEnabled(true);
                    role.setAstAdmin(true);
                    role.setSystemDefault(true);
                    role.setAuthorities(baseRole.getAuthorities());
                    role.setAccountSetsId(current.getId());
                    roleRepository.saveAndFlush(role);
                }
                UserAccountSets userAccountSets = new UserAccountSets();
                userAccountSets.setUserId(user.getId());
                userAccountSets.setRoleId(role.getId());
                userAccountSets.setCurrent(true);
                userAccountSets.setCreator(true);
                userAccountSets.setAccountSetsId(current.getId());
                userAccountSetsRepository.save(userAccountSets);
                resetNum++;
            }
        }
        return resetNum;
    }

    /**
     * 账套创建人关联的所有子用户
     * @param creatorId
     * @return
     */
    public List<Dict> astListByCreator(Long creatorId) {
        return bqf.selectFrom(qUserAccountSets)
                .select(qUser.mobile, qUser.realName.coalesce(qUser.nickname)).distinct()
                .join(qUser).on(qUser.id.eq(qUserAccountSets.userId))
                .where(qUserAccountSets.accountSetsId.in(
                        bqf.selectFrom(qAccountSets)
                                .select(qAccountSets.id)
                                .where(qAccountSets.creatorId.eq(creatorId))
                )).fetch().stream().map(tuple -> {
                    return Dict.create()
                            .set("mobile", tuple.get(qUser.mobile))
                            .set("realName", tuple.get(qUser.realName.coalesce(qUser.nickname)));
                }).toList();
    }

    /**
     * 获取系统中所有用户列表（用于批量选择）
     * 返回 id、手机号和姓名
     * @return
     */
    public List<Dict> getAllUsers() {
        return bqf.selectFrom(qUser)
                .select(qUser.id, qUser.mobile, qUser.realName.coalesce(qUser.nickname))
                .orderBy(qUser.mobile.asc())
                .fetch().stream().map(tuple -> {
                    return Dict.create()
                            .set("id", tuple.get(qUser.id))
                            .set("mobile", tuple.get(qUser.mobile))
                            .set("realName", tuple.get(qUser.realName.coalesce(qUser.nickname)));
                }).toList();
    }

    public static class UserQuery {
        private final BooleanBuilder builder = new BooleanBuilder();

        public void setMobile(String mobile) {
            if (StrUtil.isNotEmpty(mobile)) {
                builder.and(qUser.mobile.contains(mobile)
                        .or(qUser.realName.contains(mobile))
                        .or(qUser.nickname.contains(mobile)));
            }
        }
    }

    /**
     * 根据用户税号获取账套ID
     * @param creditCode
     * @return
     */
    public AccountSets uaGetAccountSetsId(String creditCode) {
        AccountSets accountSets = null;
        if (StringUtils.isNotEmpty(creditCode)) {
            accountSets = bqf.selectFrom(qAccountSets)
                    .select(qAccountSets)
                    .where(qAccountSets.creditCode.eq(creditCode)).fetchFirst();
        }
        return accountSets;
    }

    /**
     *
     * @param accountSetsId
     * @return
     */
    public GetUserInfoResp uaGetUserInfo(Long accountSetsId) {
        GetUserInfoResp resp = new GetUserInfoResp();
        if(ObjectUtils.isNotEmpty(accountSetsId)){
            Tuple tuple = bqf.selectFrom(qUserAccountSets)
                    .select(qUserAccountSets.accountSetsId, qUserAccountSets.userId, qUser.mobile, qAccountSets.enableDate)
                    .leftJoin(qUser).on(qUser.id.eq(qUserAccountSets.userId))
                    .leftJoin(qAccountSets).on(qAccountSets.id.eq(qUserAccountSets.accountSetsId))
                    .where(qUserAccountSets.accountSetsId.eq(accountSetsId))
                    .fetchFirst();
            resp.setAccountSetsId(tuple.get(qUserAccountSets.accountSetsId));
            resp.setUserId(tuple.get(qUserAccountSets.userId));
            resp.setMobile(tuple.get(qUser.mobile));
            resp.setEnableDate(tuple.get(qAccountSets.enableDate));
        }
        return resp;
    }

    /**
     * 获取账套信息列表
     * @param creditCodes
     * @return
     */
    public List<AccountSets> uaGetAccountSetsIdList(String creditCodes) {
        if(StringUtils.isEmpty(creditCodes)){
            return Collections.emptyList();
        }
        List<String> list = Arrays.asList(creditCodes.split(","));
        return bqf.selectFrom(qAccountSets)
                .select(qAccountSets)
                .where(qAccountSets.creditCode.in(list))
                .fetch();
    }
}


