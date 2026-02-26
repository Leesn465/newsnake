package com.mysite.sbb.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Service
public class Clean {
    private final Set<String> badWords = new HashSet<>();

    @PostConstruct
    public void init() {
        try {
            // resources 폴더의 badwords.txt 읽기
            ClassPathResource resource = new ClassPathResource("badwords.txt");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        badWords.add(line.trim());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ 금칙어 파일을 불러오는 데 실패했습니다: " + e.getMessage());
        }
    }

    public boolean checkBadWord(String text) {
        if (text == null || text.isBlank()) return false;

        // 문장에 금칙어가 포함되어 있는지 검사
        return badWords.stream().anyMatch(text::contains);
    }
    public String filterText(String text) {
        if (text == null || text.isBlank()) return text;
        String filteredText = text;
        for (String word : badWords) {
            if (filteredText.contains(word)) {
                // 단어 길이만큼 *로 치환
                filteredText = filteredText.replace(word, "*".repeat(word.length()));
            }
        }
        return filteredText;
    }
}