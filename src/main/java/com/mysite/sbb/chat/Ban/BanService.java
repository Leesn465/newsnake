package com.mysite.sbb.chat.Ban;

import com.mysite.sbb.user.SiteUser;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BanService {

    private final BanRepository banRepository;
    private final SimpMessagingTemplate simpMessagingTemplate;

    /**
     * 밴 상태 상세 조회 (카운트다운용)
     */
    @Transactional(readOnly = true)
    public BanStatusDto getBanStatus(String username) {

        return banRepository.findByUser_Username(username)
                .filter(ban ->
                        ban.getExpireDate() == null ||
                                ban.getExpireDate().isAfter(LocalDateTime.now())
                )
                .map(ban -> {

                    // 영구 밴
                    if (ban.getBanDays() == -1) {
                        return new BanStatusDto(
                                true,
                                -1, 0, 0, 0,
                                null
                        );
                    }

                    Duration duration =
                            Duration.between(LocalDateTime.now(), ban.getExpireDate());

                    if (duration.isZero() || duration.isNegative()) {
                        return new BanStatusDto(false, 0, 0, 0, 0, null);
                    }

                    long totalSeconds = duration.getSeconds();

                    long days = totalSeconds / 86400;
                    long hours = (totalSeconds % 86400) / 3600;
                    long minutes = (totalSeconds % 3600) / 60;
                    long seconds = totalSeconds % 60;

                    return new BanStatusDto(
                            true,
                            days,
                            hours,
                            minutes,
                            seconds,
                            ban.getExpireDate()
                    );
                })
                .orElse(new BanStatusDto(false, 0, 0, 0, 0, null));
    }

    /**
     * 메시지 차단용 초경량 체크
     */
    @Transactional(readOnly = true)
    public boolean isUserBanned(String username) {
        return banRepository.existsActiveBan(
                username,
                LocalDateTime.now()
        );
    }

    /**
     * 밴 적용 (무조건 덮어쓰기)
     */
    @Transactional
    public void banUser(SiteUser user, int days) {

        LocalDateTime now = LocalDateTime.now();

        BanEntity ban = banRepository
                .findByUser_Username(user.getUsername())
                .orElseGet(() ->
                        BanEntity.builder()
                                .user(user)
                                .build()
                );

        ban.setBanDays(days);
        ban.setBanStartedAt(now);

        // 🔥 핵심
        ban.setExpireDate(
                days == -1 ? null : now.plusDays(days)
        );

        banRepository.save(ban);

        simpMessagingTemplate.convertAndSendToUser(
                user.getUsername(),
                "/queue/ban-status",
                getBanStatus(user.getUsername())
        );

    }

}
