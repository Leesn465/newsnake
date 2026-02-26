package com.mysite.sbb.chat.Ban;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ban")
public class MyBanController {

    private final BanService banService;

    @GetMapping("/me")
    public BanStatusDto myBan(Authentication authentication) {
        return banService.getBanStatus(authentication.getName());
    }
}
