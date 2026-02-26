package com.mysite.sbb.chat.Ban;

import java.time.LocalDateTime;

public record BanStatusDto(
        boolean banned,
        long days,
        long hours,
        long minutes,
        long seconds,
        LocalDateTime expireDate
) {}
