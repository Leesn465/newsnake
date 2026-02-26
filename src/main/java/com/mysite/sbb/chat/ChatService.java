package com.mysite.sbb.chat;

import com.mysite.sbb.chat.ChatSave.ChatMessageEntity;
import com.mysite.sbb.chat.ChatSave.ChatMessageRepository;
import com.mysite.sbb.fastapi.FastApiEntity;
import com.mysite.sbb.fastapi.FastApiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 실시간 채팅 기능 서비스
 * - 일반 채팅 메시지 저장
 * - AI 예측 태그 메시지 생성
 * - 회사 자동완성 검색
 * - 최근 채팅 30개 조회
 * 채팅은 실시간(WebSocket) + DB 영속화 구조로 설계.
 * 서버 재시작 시에도 채팅 기록을 유지하기 위해 DB에 저장한다.
 */

@Service
@RequiredArgsConstructor
@Transactional
public class ChatService {
    private final FastApiRepository fastApiRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 사용자가 특정 종목을 태그하면
     * 최신 AI 예측 결과를 조회하여 채팅 메시지로 변환.
     * TAG 타입으로 저장하여
     * 일반 채팅 메시지와 구분 가능하게 설계.
     */

    public ChatMessage tagPrediction(Map<String, String> chart, String username) {
        String companyName = chart.get("company");
        String prediction = fastApiRepository.findTopByCompanyOrderByCreatedAt(companyName)
                .map(FastApiEntity::getPrediction)
                .orElse("정보 없음");

        String text = "@" + companyName + " - [" + prediction + "]";

        // DB에 저장
        return saveAndReturnChatMessage(username, text, "TAG");
    }

    /**
     * 회사 자동완성 검색
     * 대소문자 무시 검색
     * 중복 제거
     * 최대 10개 제한 (프론트 자동완성 UX 최적화)
     */
    public List<String> searchCompany(String q) {
        return fastApiRepository.findByCompanyContainingIgnoreCase(q)
                .stream()
                .map(FastApiEntity::getCompany)
                .distinct()
                .limit(10)
                .toList();
    }

    /**
     * 채팅 메시지를 DB에 저장 후
     * 클라이언트 전송용 DTO로 변환.
     * WebSocket broadcast용 데이터 생성.
     */
    public ChatMessage saveAndReturnChatMessage(String from, String text, String type) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setFromUser(from);
        entity.setText(text);
        entity.setType(type);
        ChatMessageEntity saved = chatMessageRepository.save(entity);

        return new ChatMessage(saved.getId(), from, text, type);
    }

    /**
     * 최근 채팅 30개 조회
     * DB에서는 최신순(id desc)으로 조회하고,
     * 화면에서는 오래된 순서부터 출력해야 하므로 reverse 처리.
     */

    public List<ChatMessage> getRecentMessages() {
        List<ChatMessage> recent = new ArrayList<>(chatMessageRepository.findTop30ByOrderByIdDesc()
                .stream()
                .map(e -> new ChatMessage(e.getId().intValue(), e.getFromUser(), e.getText(), e.getType()))
                .toList());
        Collections.reverse(recent); // 오래된 메시지부터 보여주기
        return recent;
    }

}
