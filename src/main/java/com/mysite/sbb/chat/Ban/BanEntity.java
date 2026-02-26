package com.mysite.sbb.chat.Ban;

import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "chat_ban",
        uniqueConstraints = @UniqueConstraint(columnNames = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private SiteUser user;

    @Column(nullable = false)
    private int banDays; // -1 = 영구

    @Column(nullable = false)
    private LocalDateTime banStartedAt;

    @Column
    private LocalDateTime expireDate; // null = 영구
}

