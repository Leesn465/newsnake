package com.mysite.sbb.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class InfoService {

    private final InfoRepository infoRepository;

    public InfoEntity getInfo() {
        return infoRepository.findTopByOrderByIdAsc().orElse(null);
    }

    public void updateInfo(String content, String modifierName) {
        InfoEntity info = infoRepository.findTopByOrderByIdAsc()
                .orElseGet(InfoEntity::new);

        info.setContent(content);
        info.setModifierName(modifierName);
        info.setUpdatedAt(LocalDateTime.now());

        infoRepository.save(info);
    }
}