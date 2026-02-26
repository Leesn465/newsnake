package com.mysite.sbb.chat.Ban;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EnableScheduling
public class BanStatusPushScheduler {

    private final BanRepository banRepository;
    private final BanService banService;
    private final SimpMessagingTemplate simpMessagingTemplate;

    @Scheduled(fixedRate = 1000)
    public void pushBanStatus() {

        banRepository.findAllActiveUsers()
                .forEach(username -> {

                    BanStatusDto status =
                            banService.getBanStatus(username);

                    simpMessagingTemplate.convertAndSendToUser(
                            username,
                            "/queue/ban-status",
                            status
                    );
                });
    }
}