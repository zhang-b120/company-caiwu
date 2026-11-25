package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.dto.AdminDto;
import cn.gson.financial.kernel.model.entity.Admin;
import cn.gson.financial.kernel.model.entity.QAdmin;
import cn.gson.financial.kernel.model.repo.AdminRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService extends AbsService {

    private final QAdmin qAdmin = QAdmin.admin;

    private final AdminRepository adminRepository;

    private final Environment environment;

    /**
     * 登录
     *
     * @param username 账号电话
     * @param password 密码
     * @param ip       操作IP
     * @return
     */
    @Transactional
    public AdminDto login(String username, String password, String ip) {
        Admin admin = bqf.selectFrom(qAdmin)
                .where(qAdmin.telephone.endsWith(username))
                .fetchFirst();

        log.info("Login:{},ip:{}", username, ip);

        Assert.notNull(admin, "账号错误~");

        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            Assert.isTrue(DigestUtil.bcryptCheck(password, admin.getPassword()), "密码错误~");
        }

        AdminDto adminDto = BeanUtil.toBean(admin, AdminDto.class);

        admin.setLastLoginTime(LocalDateTime.now());
        admin.setLastIp(ip);
        adminRepository.save(admin);

        return adminDto;
    }

    @Transactional
    public void save(Admin admin) {
        if (admin.getId() == null) {
            long checkTelephone = bqf.selectFrom(qAdmin)
                    .where(qAdmin.telephone.eq(admin.getTelephone()))
                    .fetchCount();
            Assert.isTrue(checkTelephone == 0, "联系电话已存在~");
            admin.setPassword(DigestUtil.bcrypt(admin.getTelephone().substring(5)));
            adminRepository.save(admin);
        } else {
            long checkTelephone = bqf.selectFrom(qAdmin)
                    .where(qAdmin.telephone.eq(admin.getTelephone()).and(qAdmin.id.ne(admin.getId())))
                    .fetchCount();
            Assert.isTrue(checkTelephone == 0, "联系电话已存在~");
            //电话不在这里更新
            Admin reference = adminRepository.getReferenceById(admin.getId());
            BeanUtil.copyProperties(admin, reference, CopyOptions.create().ignoreNullValue());
            adminRepository.save(reference);
        }
    }

    public List<AdminDto> list() {
        List<AdminDto> adminDtos = new ArrayList<>();
        bqf.selectFrom(qAdmin)
                .orderBy(qAdmin.id.desc())
                .fetch().forEach(admin -> adminDtos.add(BeanUtil.toBean(admin, AdminDto.class)));
        return adminDtos;
    }

    @Transactional
    public void delete(Long adminId) {
        jqf.delete(qAdmin)
                .where(qAdmin.id.eq(adminId).and(qAdmin.master.isFalse()))
                .execute();
    }

    @Transactional
    public void resetPassword(Long adminId) {
        Admin admin = adminRepository.getReferenceById(adminId);
        admin.setPassword(DigestUtil.bcrypt(admin.getTelephone().substring(5)));
        adminRepository.save(admin);
    }

    public Admin load(Long id) {
        return bqf.selectFrom(qAdmin).where(qAdmin.id.eq(id)).fetchFirst();
    }

    /**
     * 判断是否存在主管理员。
     *
     * @return 如果存在主管理员则返回true，否则返回false。
     */
    public boolean hasMaster() {
        return bqf.selectFrom(qAdmin).where(qAdmin.master.isTrue()).fetchCount() > 0;
    }


    @Transactional
    public void updatePassword(Long id, String password) {
        jqf.update(qAdmin)
                .set(qAdmin.password, DigestUtil.bcrypt(password))
                .where(qAdmin.id.eq(id))
                .execute();
    }

    @Transactional
    public void initAdmin() {
        Admin admin = new Admin();
        admin.setId(0L);
        admin.setName("纷析云管理员");
        admin.setTelephone("18888888888");
        admin.setPassword(DigestUtil.bcrypt("888888"));
        admin.setMaster(true);
        admin.setEnabled(true);
        adminRepository.save(admin);
    }
}
