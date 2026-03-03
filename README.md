# 🐍 NewSnake (뉴스네이크)
> **AI 기반 뉴스 인사이트 분석 및 실시간 기업 영향도 랭킹 플랫폼**
> 


<p align="center">
  <img src="https://img.shields.io/badge/Java-17-007396?style=flat-square&logo=java&logoColor=white"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat-square&logo=springboot&logoColor=white"/>
  <img src="https://img.shields.io/badge/React-20232A?style=flat-square&logo=react&logoColor=61DAFB"/>
  <img src="https://img.shields.io/badge/FastAPI-009688?style=flat-square&logo=fastapi&logoColor=white"/>
  <img src="https://img.shields.io/badge/Apache%20Kafka-231F20?style=flat-square&logo=apachekafka&logoColor=white"/>
  <img src="https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis&logoColor=white"/>
  <img src="https://img.shields.io/badge/OCI-F80000?style=flat-square&logo=oracle&logoColor=white"/>
</p>

### 핵심 기술 포인트

- Kafka 기반 비동기 이벤트 설계로 AI 장기 작업을 API 서버와 분리
- Redis Sorted Set 기반 랭킹 최적화 (p95 120ms → 2ms, 98% 개선)
- Kafka at-least-once 환경에서 3단계 중복 방지 설계로 데이터 정합성 확보
- 실제 운영 장애 경험 후 3-Tier 인프라 및 자동 감지 체계 구축
---

## 서비스 바로가기
**[https://newsnake.site](https://newsnake.site)** (현재 운영 중)

---



## Project Overview
NewSnake는 사용자가 입력한 뉴스 URL을 기반으로 AI가:

- 3줄 요약

- 핵심 키워드 추출

- 연관 기업 도출

- 주가 영향도 예측 (상승 / 하락 / 보합)

을 수행하고, 이를 기반으로 **실시간 기업 검색 랭킹**을 집계하는 플랫폼입니다.

AI 분석은 평균 수십 초가 소요되는 장기 작업이므로,
동기 API 구조가 아닌 **비동기 이벤트 기반 아키텍처**로 설계되었습니다.

---


## System Architecture (Cloud Native & Hybrid)
<img width="2452" height="1573" alt="오케이 찐 최종 drawio" src="https://github.com/user-attachments/assets/0fe4fa2e-c69d-4575-b241-6c5b1ac8ee40" />

본 프로젝트는 고가용성, 확장성, 그리고 보안성을 최우선으로 고려하여 
**클라우드 환경 및 Managed Service**를 조합한 하이브리드 아키텍처로 설계되었고
AI, API, Cache, DB를 논리적으로 분리하여
각 계층이 독립적으로 확장 가능하도록 설계했습니다.

## Key Features (핵심 기능)

### 1. AI 뉴스 분석 파이프라인
* **뉴스 분석**: URL 입력 시 AI가 본문을 스크래핑하여 **3줄 요약, 핵심 키워드, 연관 기업**을 도출합니다.
* **주가 예측**: 감성 분석(Sentiment Analysis)을 통해 해당 뉴스가 기업 주가에 미칠 영향(**상승/하락/보합**)을 예측합니다.
* **데이터 시각화**: 분석된 기업의 실시간 주가 그래프를 매칭하여 사용자에게 시각적인 통찰을 제공합니다.

### 2. 실시간 기업 영향도 랭킹
* **실시간 집계**: 분석 결과로 도출된 기업을 Redis의 **Sorted Set**에 즉시 반영하여 인기 순위를 실시간 산출합니다.
* **배치 시스템**: 매일 자정 **Spring Batch**를 통해 Redis의 실시간 데이터를 MySQL(`CompanyRankDaily`)로 이관(Upsert)하여 영구 보존합니다.

### 3. 커뮤니티 및 실시간 소통 (Community & Interaction)

*   **실시간 채팅 (Real-time Chat)**: WebSocket(STOMP) 기반의 채팅 시스템을 구축하여 뉴스 분석 결과를 바탕으로 사용자 간의 실시간 정보 공유 및 토론 환경을 제공합니다.
*   **기업별 소통 게시판**: 각 기업별 전용 게시판을 통해 댓글 및 반응(좋아요/싫어요) 기능을 지원하며, Redis를 활용해 대량의 리액션을 실시간으로 집계하고 정합성을 유지합니다.
*   **콘텐츠 가이드라인 (Clean Filter)**: 비속어 필터링 로직을 적용하여 건강한 커뮤니티 환경을 유지할 수 있도록 관리 시스템을 구축했습니다.


---

## 테크 스택

| 분류 | 기술 스택 |
| :--- | :--- |
| **Backend** | Java 17, Spring Boot 3.x, Spring Data JPA, Spring Security |
| **Frontend** | React, JavaScript, CSS3 |
| **AI Server** | Python, FastAPI, Hugging Face NLP Models |
| **Managed Infrastructure** | **Aiven for Kafka**, **Aiven for Redis** |
| **Cloud** | **OCI (Oracle Cloud)**, Oracle Object Storage, Hugging Face Spaces |
| **DevOps** | **GitHub Actions**, Docker, Docker Compose, Nginx |
| **Network/Security** | Cloudflare, GoDaddy, HTTPS (SSL/TLS) |
---



## Project Structure & Core Modules

```bash
.
├── .github/workflows      # CI/CD (Spring Boot & React 배포 자동화)
├── docker-compose.yml     # Nginx & Spring Boot 컨테이너 설정
├── src/main/java/com/newsnake
│    ├── user              # 강력한 비밀번호 정책(연속 문자/배열 차단) 적용
│    ├── jwt               # Stateless JWT 인증 시스템
|    |    └── oauth             # OAuth2 소셜 인증 및 Auth Code Exchange 방식
│    ├── fastapi           # Kafka 기반 AI 분석 및 Redis 랭킹 최적화
│    │    ├── Kafka        # Producer(Transactional Listener), Consumer(Manual Ack)
│    │    └── optimization # Redis ZSet 실시간 집계 및 배치 이관 로직
│    └── mail              # Redis Stream 기반 신뢰성 비동기 알림 시스템
|
```

## 기술적으로 해결한 문제들

### 1️ AI 장기 작업을 동기 처리하지 않음.
- 문제: AI 분석 20~200초 소요 → Thread Pool 고갈 위험

- 해결: Kafka 기반 EDA 설계

- 결과: AI 장기 작업 중에도 API 서버 응답 시간은 일정하게 유지되었으며
        Thread Pool 고갈 현상이 발생하지 않음
---

## 2️ 실시간 랭킹 집계를 DB에서 제거

- 문제: GROUP BY 집계로 p95 120ms

- 해결: Redis ZSET + Daily 집계 구조

- 결과: p95 2ms (98% 개선)

---

## 3️ Kafka Idempotent Processing Design (Kafka 중복 메시지 처리 설계)

- 문제: at-least-once → 중복 가능성

- 해결: DB Unique + Redis Key + Distributed Lock

- 결과: 데이터 정합성 보장

---

## 4️ 실제 장애를 겪고 인프라를 다시 설계

### AWS Free Tier 장애 경험
- 문제: 단일 인스턴스 기반 구조에서 인스턴스 재시작 루프 발생

- 해결: OCI 3-Tier + LB + Monitoring

- 결과: 30~60초 내 장애 감지
---
### 마무리

NewSnake는 기능 구현을 넘어,
장기 작업 처리, 실시간 집계 최적화, 이벤트 정합성 보장,
그리고 장애를 전제로 한 인프라 설계까지
실제 운영 환경에서의 기술적 문제를 해결하는 데 집중한 프로젝트입니다.

**감사합니다!**

