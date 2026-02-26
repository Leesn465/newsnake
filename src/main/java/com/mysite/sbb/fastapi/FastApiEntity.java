package com.mysite.sbb.fastapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysite.sbb.user.SiteUser;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "news_articles",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "url"})})
@Getter
@Setter
public class FastApiEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;


    @Column(columnDefinition = "TEXT")
    private String content;

    private String url;

    private String thumbnailUrl;

    private String newsTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true, foreignKey = @ForeignKey(name = "FK_news_user"))
    private SiteUser user;

    @Column(name = "created_at", updatable = false, nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    private String company;

    // 스프링부트에서 DB를 전부 관리하려고 추가
    // 추가 기능 : 검색기록을 비교해서 실제 예측이 맞았나 확인
    // 그리고 DB에 fastapi로 크롤링과 요약문을 DB에 저장해서 바로 DB에서 값을 검색하여
    // 응답 속도를 높이는 것이 목표 (DB에 주가 예측 값과 요약문을 추가로 저장)
    //
    private String prediction;
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String keywordsJson;
    @Transient
    private List<Map<String, Object>> keywords;


    private String sentiment;
    private float prob;


    @Builder
    public FastApiEntity(String title, String content, String url, String thumbnailUrl, String newsTime,
                         SiteUser user, LocalDateTime createdAt, String prediction,
                         String summary, String company, List<Map<String, Object>> keywords,
                         String sentiment, float prob) {

        this.title = title;
        this.content = content;
        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.newsTime = newsTime;
        this.user = user;
        this.createdAt = createdAt;
        this.prediction = prediction;
        this.summary = summary;
        this.company = company;
        this.setKeywords(keywords);
        ;
        this.sentiment = sentiment;
        this.prob = prob;

    }

    public FastApiEntity() {

    }

    // FastApiEntity 안에 DTO로 변환하는 메서드 하나 추가
    public FastApiResponse toResponse() {
        return new FastApiResponse(
                null,               // message (없으면 null)
                this.title,
                this.newsTime,
                this.content,
                this.thumbnailUrl,
                this.url,
                this.summary,
                this.company,
                this.getKeywords(),
                this.sentiment,
                this.prediction,
                this.prob,
                this.createdAt
        );
    }

    public List<Map<String, Object>> getKeywords() {
        if (this.keywords == null && this.keywordsJson != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                this.keywords = mapper.readValue(
                        this.keywordsJson,
                        new TypeReference<List<Map<String, Object>>>() {
                        }
                );
            } catch (Exception e) {
                e.printStackTrace();
                this.keywords = null;
            }
        }
        return this.keywords;
    }

    public void setKeywords(List<Map<String, Object>> keywords) {
        this.keywords = keywords;
        try {
            ObjectMapper mapper = new ObjectMapper();
            this.keywordsJson = mapper.writeValueAsString(keywords);
        } catch (Exception e) {
            e.printStackTrace();
            this.keywordsJson = null;
        }
    }


}