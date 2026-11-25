package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.UserMessage;
import cn.gson.financial.kernel.model.repo.UserMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMessageService extends AbsService{
    private final UserMessageRepository userMessageRepository;
    public UserMessage save(UserMessage userMessage){
        return  userMessageRepository.save(userMessage);
    }
}
