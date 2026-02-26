package com.mysite.sbb.admin;

import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/info")
public class InfoController {

    private final InfoService infoService;
    private final UserRepository userRepository;

    // ✅ 소개글 조회 (비로그인 포함 전부 가능)
    @GetMapping
    public ResponseEntity<?> getInfo() {
        InfoEntity info = infoService.getInfo();

        if (info == null) {
            return ResponseEntity.ok(new HashMap<>());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", info.getContent());
        result.put("modifierName", info.getModifierName());
        result.put("updatedAt", info.getUpdatedAt());

        return ResponseEntity.ok(result);
    }

    // ✅ 소개글 수정 (관리자만)
    @PostMapping("/update")
    public ResponseEntity<?> updateInfo(
            @RequestBody Map<String, String> body,
            Authentication authentication
    ) {
        if (authentication == null) {
            return ResponseEntity.status(403).build();
        }

        String username = authentication.getName();
        SiteUser user = userRepository.findByUsername(username);

        if (user == null || !user.isAdmin()) {
            return ResponseEntity.status(403).build();
        }

        infoService.updateInfo(body.get("content"), user.getUsername());
        return ResponseEntity.ok().build();
    }
}
