package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.UserMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMessageRepository extends JpaRepository <UserMessage, Long> {
}




