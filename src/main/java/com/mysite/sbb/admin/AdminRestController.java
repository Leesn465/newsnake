package com.mysite.sbb.admin;

import com.mysite.sbb.chat.Ban.BanService;
import com.mysite.sbb.chat.Ban.BanStatusDto;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminRestController {

    private final BanService banService;
    private final UserRepository userRepository;

    @GetMapping("/check-ban/{username}")
    public BanStatusDto checkBan(@PathVariable String username) {
        return banService.getBanStatus(username);
    }

    @PostMapping("/ban")
    public void ban(@RequestBody BanRequest req) {

        SiteUser user = userRepository.findByUsername(req.targetUsername());

        if (user == null) {
            throw new IllegalArgumentException("유저 없음");
        }

        banService.banUser(user, req.banDays());
    }

    public record BanRequest(String targetUsername, int banDays) {}
}
