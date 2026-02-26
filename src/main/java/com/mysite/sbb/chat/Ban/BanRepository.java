package com.mysite.sbb.chat.Ban;

import com.mysite.sbb.user.SiteUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BanRepository extends JpaRepository<BanEntity, Long> {

    /**
     * 유저 1명당 밴 1개 조회 (상태 조회용)
     */
    Optional<BanEntity> findByUser_Username(String username);

    /**
     * 밴 덮어쓰기용 (관리자 밴 처리)
     */
    Optional<BanEntity> findByUser(SiteUser user);

    /**
     * 메시지 차단용 (초경량 boolean 체크)
     */
    @Query("""
                SELECT 
                    CASE 
                        WHEN COUNT(b) > 0 THEN true 
                        ELSE false 
                    END
                FROM BanEntity b
                WHERE b.user.username = :username
                  AND (
                        b.expireDate IS NULL
                        OR b.expireDate > :now
                  )
            """)
    boolean existsActiveBan(
            @Param("username") String username,
            @Param("now") LocalDateTime now
    );

    /**
     * 현재 밴 중인 유저 전부 (스케줄러용)
     */
    @Query("""
                SELECT b.user.username
                FROM BanEntity b
                WHERE b.expireDate IS NULL
                   OR b.expireDate > :now
            """)
    List<String> findAllActiveUsers(@Param("now") LocalDateTime now);

    /**
     * 편의 메서드
     */
    default List<String> findAllActiveUsers() {
        return findAllActiveUsers(LocalDateTime.now());
    }
}
