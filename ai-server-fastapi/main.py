from pydoc import text
from fastapi import FastAPI, HTTPException, Query
import uvicorn
from pydantic import BaseModel
import requests
from bs4 import BeautifulSoup as bs
import os
from util.keywordExtract import *
from typing import Optional,List, Dict, Any, Union
import pandas as pd
import torch
import pandas as pd
from io import StringIO # pandas.read_html에 문자열을 전달할 때 필요
import logging # 로깅을 위해 추가
import time # 요청 간 지연을 위해 추가 (선택 사항이지만 권장)
from embedding_module import embed_keywords
from keyword_module import summarize_kobart as summarize, extract_keywords
from pykrx import stock
from functools import lru_cache
from fastapi.middleware.cors import CORSMiddleware
import traceback
from datetime import datetime, timedelta
from starlette.concurrency import run_in_threadpool
import FinanceDataReader as fdr
from groq import Groq
import asyncio
import json
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
import ssl


app = FastAPI()


# 로깅 설정
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

API_KEY = os.getenv("Groq_API_KEY")

if not API_KEY:
    # API 키가 없으면 에러를 발생시키거나 경고
    print(":x: Groq_API_KEY 환경 변수가 설정되지 않았습니다.")
    
else:
    groq_client = Groq(api_key=API_KEY)
    logger.info(":white_check_mark: Groq API 설정 완료 (환경 변수 사용)")

KAFKA_BOOTSTRAP = os.getenv(
    "KAFKA_BOOTSTRAP",
    "newsnake-kafka"
)

KAFKA_TOPIC = os.getenv("KAFKA_TOPIC", "news-analyze")
KAFKA_GROUP_ID = os.getenv("KAFKA_GROUP_ID", "ai-analyzer-group")
KAFKA_PROGRESS_TOPIC = os.getenv("KAFKA_PROGRESS_TOPIC", "analysis-progress")
KAFKA_DONE_TOPIC = os.getenv("KAFKA_DONE_TOPIC", "analysis-done")

KAFKA_CA_FILE = os.getenv("KAFKA_CA_FILE", "ca.pem")
KAFKA_CERT_FILE = os.getenv("KAFKA_CERT_FILE", "service.cert")
KAFKA_KEY_FILE = os.getenv("KAFKA_KEY_FILE", "service.key")

producer = None
consumer = None
consumer_task = None

def build_ssl_context():
    ctx = ssl.create_default_context(cafile=KAFKA_CA_FILE)
    ctx.load_cert_chain(certfile=KAFKA_CERT_FILE, keyfile=KAFKA_KEY_FILE)
    return ctx

SSL_CONTEXT = build_ssl_context()

@app.on_event("startup")
async def start_kafka():
    global producer, consumer, consumer_task

    producer = AIOKafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP,
        security_protocol="SSL",
        ssl_context=SSL_CONTEXT,
    )
    await producer.start()
    logger.info("[KAFKA] producer started (SSL)")

    consumer = AIOKafkaConsumer(
        KAFKA_TOPIC,
        bootstrap_servers=KAFKA_BOOTSTRAP,
        group_id=KAFKA_GROUP_ID,
        enable_auto_commit=True,
        auto_offset_reset="latest",
        security_protocol="SSL",
        ssl_context=SSL_CONTEXT,
    )
    await consumer.start()
    logger.info("[KAFKA] consumer started (SSL)")

    consumer_task = asyncio.create_task(consume_loop())

@app.on_event("shutdown")
async def stop_kafka():
    global producer, consumer, consumer_task
    if consumer_task:
        consumer_task.cancel()
        try:
            await consumer_task
        except asyncio.CancelledError:
            pass

    if consumer:
        await consumer.stop()
        logger.info("[KAFKA] consumer stopped")

    if producer:
        await producer.stop()
        logger.info("[KAFKA] producer stopped")



# ---------------------------------------
# 입력/출력 모델
# ---------------------------------------
class NewsRequest(BaseModel):
    url: str
    id: Optional[str] = None

class SummaryInput(BaseModel):
    url: str

class KeywordsInput(BaseModel):
    summary: str

class CompanyInput(BaseModel):
    summary: Optional[str] = None
    keywords: Optional[List[str]] = None

class SentimentInput(BaseModel):
    content: str

class PredictInput(BaseModel):
    keywords: List[Union[str, Dict[str, Any]]]


# ---------------------------------------
# 간단한 분류기 (기존과 동일)
# ---------------------------------------
class SimpleClassifier(torch.nn.Module):
    def __init__(self, input_dim):
        super().__init__()
        self.net = torch.nn.Sequential(
            torch.nn.Linear(input_dim, 64),
            torch.nn.ReLU(),
            torch.nn.Linear(64, 1),
            torch.nn.Sigmoid()
        )
    def forward(self, x):
        return self.net(x)

# ---------------------------------------
# 공통 유틸: HTML, 파서, 썸네일
# ---------------------------------------
def fetch_html(url: str) -> bs:
    headers = {"User-Agent": "Mozilla/5.0"}
    resp = requests.get(url, headers=headers, timeout=7)
    resp.raise_for_status()
    return bs(resp.text, "html.parser")

def parse_naver(soup: bs):
    title = soup.select_one("h2.media_end_head_headline") or soup.title
    title_text = title.get_text(strip=True) if title else "제목 없음"
    time_tag = soup.select_one("span.media_end_head_info_datestamp_time")
    time_text = time_tag.get_text(strip=True) if time_tag else "시간 없음"
    content_area = soup.find("div", {"id": "newsct_article"}) or soup.find("div", {"id": "dic_area"})
    if content_area:
        paragraphs = content_area.find_all("p")
        content = '\n'.join([p.get_text(strip=True) for p in paragraphs]) if paragraphs else content_area.get_text(strip=True)
    else:
        content = "본문 없음"
    return title_text, time_text, content

def parse_daum(soup: bs):
    title = soup.select_one("h3.tit_view") or soup.title
    title_text = title.get_text(strip=True) if title else "제목 없음"
    time_tag = soup.select_one("span.num_date")
    time_text = time_tag.get_text(strip=True) if time_tag else "시간 없음"

    content_area = soup.find("div", {"class": "article_view"})
    if content_area:
        paragraphs = content_area.find_all("p")
        content = '\n'.join([p.get_text(strip=True) for p in paragraphs]) if paragraphs else content_area.get_text(strip=True)
    else:
        content = "본문 없음"
    return title_text, time_text, content

def extract_thumbnail(soup: bs) -> Optional[str]:
    tag = soup.find("meta", property="og:image")
    return tag["content"] if tag and "content" in tag.attrs else None

def parse_article_all(url: str) -> Dict[str, Any]:
    soup = fetch_html(url)
    if "naver.com" in url:
        title, time_str, content = parse_naver(soup)
    elif "daum.net" in url:
        title, time_str, content = parse_daum(soup)
    else:
        raise HTTPException(status_code=400, detail="지원하지 않는 뉴스 사이트입니다.")
    thumbnail = extract_thumbnail(soup)
    return {
        "title": title,
        "time": time_str,
        "content": content,
        "thumbnail_url": thumbnail,
        "url": url,
    }

# ---------------------------------------
# 회사명 추론 (Gemini)
# ---------------------------------------

# 3. 함수 이름 및 내용 변경 (gemini_use -> groq_use)
def groq_use(text_content: Any) -> str:
    # 텍스트 추출 및 정제
    if isinstance(text_content, dict):
        text_for_ai = text_content.get('summary', '')
    else:
        text_for_ai = str(text_content)

    # 프롬프트 구성 (불필요한 특수문자 제거 및 슬라이싱)
    clean_text = text_for_ai[:500].replace('\n', ' ')
    prompt = f'''제공되는 뉴스 본문을 읽고, 뉴스와 가장 연관성이 높은 기업 
                 현재 주식 시장(KOSPI, KOSDAQ 등)에 상장된 기업의 이름 하나만로 출력해줘.
                [제약 사항]
                뉴스 본문과 가장 연관이 된 회사일 것
                꼭 하나의 회사를 추출할 것
                없음이라고 표시하지 말 것
                상장되지 않은 일반 단체, 정부 기관, 비상장사는 제외할 것.
                FinanceDataReader 이 라이브러리에 존재하는 회사만 추출할 것.
                설명 없이 회사 이름만 나열할 것.
                뉴스에 언급된 맥락상 '기업'임이 확실한 것만 포함할 것 : {clean_text}'''

    try:
        chat_completion = groq_client.chat.completions.create(
            messages=[{
                "role": "user",
                "content": prompt
            }],
            model="llama-3.3-70b-versatile",
        )
        return chat_completion.choices[0].message.content.strip()

    except Exception as e:
        # 에러 객체를 직접 출력하지 말고 repr()을 사용해 ASCII 충돌 방지
        logger.error(f" Groq 호출 실패: {repr(e)}")
        return "추출 실패"
    
def get_stock_info(company_info: str) -> Dict[str, str] | None:
    try:
        if krx_listings is not None:
            # 포함 관계 확인
            for _, row in krx_listings.iterrows():
                if row['Name'] in company_info:
                    return {"market": "KRX", "symbol": row['Code'], "name": row['Name']}
    except Exception as e:
        logger.error(f"주식 검색 에러: {e}")
    return None


# ---------------------------------------
# 1) 요약 단계
# ---------------------------------------
@app.post("/ai/summary")
def step_summary(inp: SummaryInput):
    meta = parse_article_all(inp.url)
    # 너가 기존 resultKeyword를 먼저 쓰고 싶다면 이 한 줄로 대체 가능:
    # rk = resultKeyword(meta["content"]); return {**meta, "summary": rk["summary"]}
    summary_text = summarize(meta["content"])
    return {**meta, "summary": summary_text}

# 2) 키워드 단계
@app.post("/ai/keywords")
def step_keywords(inp: KeywordsInput):
    print("키워드는 옴")
    try:
        rk = resultKeyword(inp.summary)
        return {"keywords": rk["keyword"]}
    except Exception as e:
        print("❌ 키워드 추출 오류:", e)
        return {"keywords": []}

# 3) 관련 상장사 단계
@app.post("/ai/company")
def step_company(inp: CompanyInput):
    if inp.summary:
        text = inp.summary
    elif inp.keywords:
        text = ", ".join(inp.keywords)
    else:
        raise HTTPException(status_code=400, detail="summary 또는 keywords 중 하나가 필요합니다.")
    company = groq_use(text)
    return {"company": company}

# 4) 감정 단계
@app.post("/ai/sentiment")
def step_sentiment(inp: SentimentInput):
    s = analyze_sentiment(inp.content)
    pos, neg, neu = s["positive"], s["negative"], s["neutral"]
    # 중립 절반, 나머지 비율 재분배 (기존 로직)
    reduced_net = neu / 2
    remaining = neu - reduced_net
    total_non_neu = neg + pos
    if total_non_neu > 0:
        neg += remaining * (neg / total_non_neu)
        pos += remaining * (pos / total_non_neu)
    else:
        neg += remaining / 2
        pos += remaining / 2
    neu = reduced_net

    max_label = max([("부정", neg), ("중립", neu), ("긍정", pos)], key=lambda x: x[1])[0]
    if max_label == "긍정":
        if pos >= 0.9: label = f"매우 긍정 ({pos*100:.1f}%)"
        elif pos >= 0.6: label = f"긍정 ({pos*100:.1f}%)"
        else: label = f"약한 긍정 ({pos*100:.1f}%)"
    elif max_label == "부정":
        if neg >= 0.9: label = f"매우 부정 ({neg*100:.1f}%)"
        elif neg >= 0.6: label = f"부정 ({neg*100:.1f}%)"
        else: label = f"약한 부정 ({neg*100:.1f}%)"
    else:
        label = f"중립 ({neu*100:.1f}%)"

    return {
        "raw": {"positive": s["positive"], "negative": s["negative"], "neutral": s["neutral"]},
        "adjusted": {"positive": pos, "negative": neg, "neutral": neu},
        "sentiment": label
    }

# 5) 주가 예측 단계
@app.post("/ai/predict")
def step_predict(inp: PredictInput):
    # 🔹 문자열 리스트로 정제 (딕셔너리인 경우 "word" 키 사용)
    clean_keywords = []
    for kw in inp.keywords:
        if isinstance(kw, str):
            clean_keywords.append(kw)
        elif isinstance(kw, dict) and "word" in kw:
            clean_keywords.append(kw["word"])

    if not clean_keywords:
        raise HTTPException(status_code=400, detail="keywords 리스트가 비어 있습니다.")

    # 🔹 이하 기존 로직 동일
    keyword_vec = embed_keywords(clean_keywords)
    input_vec = torch.tensor(keyword_vec, dtype=torch.float32).unsqueeze(0)
    input_dim = input_vec.shape[1]

    model = SimpleClassifier(input_dim)
    model.load_state_dict(torch.load("news_model.pt", map_location="cpu"))
    model.eval()

    with torch.no_grad():
        prob = model(input_vec).item()
        pred_label = '📈 상승 (1)' if prob >= 0.5 else '📉 하락 (0)'

    return {"prediction": pred_label, "prob": prob}



# ---------------------------------------
# 호환용: 기존 parse-news (한방 요청) - 유지
# ---------------------------------------
def analyze_news_sync(
    url: str,
    user_id: str | None = None,
    progress_cb=None,  # ✅ 추가
) -> Dict[str, Any]:

    def emit(percent: int, stage: str, message: str):
        if progress_cb:
            try:
                progress_cb(percent, stage, message)
            except Exception:
                pass

    emit(0, "START", "분석 시작")

    # 1) 기사 파싱
    emit(5, "PARSING", "뉴스 파싱 중...")
    meta = parse_article_all(url)
    emit(15, "PARSING", "뉴스 파싱 완료")

    # 2) 요약/키워드(1차) (네가 원래 하던 resultKeyword)
    emit(25, "SUMMARY", "요약/키워드 생성 중...")
    rk = resultKeyword(meta["content"])
    emit(35, "SUMMARY", "요약/키워드 생성 완료")

    # 3) 회사 추론
    emit(45, "COMPANY", "관련 회사 분석 중...")
    targetCompany = groq_use(rk)
    emit(55, "COMPANY", "관련 회사 분석 완료")

    # 4) 감성 분석
    emit(65, "SENTIMENT", "감정 분석 중...")
    s = analyze_sentiment(meta["content"])
    emit(75, "SENTIMENT", "감정 분석 완료")

    # (원래 감성 후처리 로직 그대로)
    pos, neg, neu = s["positive"], s["negative"], s["neutral"]

    reduced_net = neu / 2
    remaining = neu - reduced_net
    total_non_neu = neg + pos
    if total_non_neu > 0:
        neg += remaining * (neg / total_non_neu)
        pos += remaining * (pos / total_non_neu)
    else:
        neg += remaining / 2
        pos += remaining / 2
    neu = reduced_net  #  원래 코드에 있었던 거 유지해야 함

    max_label = max([("부정", neg), ("중립", neu), ("긍정", pos)], key=lambda x: x[1])[0]
    if max_label == "긍정":
        if pos >= 0.9:
            sentiment_label = f"매우 긍정 ({pos*100:.1f}%)"
        elif pos >= 0.6:
            sentiment_label = f"긍정 ({pos*100:.1f}%)"
        else:
            sentiment_label = f"약한 긍정 ({pos*100:.1f}%)"
    elif max_label == "부정":
        if neg >= 0.9:
            sentiment_label = f"매우 부정 ({neg*100:.1f}%)"
        elif neg >= 0.6:
            sentiment_label = f"부정 ({neg*100:.1f}%)"
        else:
            sentiment_label = f"약한 부정 ({neg*100:.1f}%)"
    else:
        sentiment_label = f"중립 ({neu*100:.1f}%)"

    # 5) (네 원래 코드 유지) summary_text / keywords_2nd / clean_keywords
    emit(82, "KEYWORDS", "키워드 추출(2차) 중...")
    summary_text = rk.get("summary") or summarize(meta["content"])
    _, keywords_2nd = extract_keywords(summary_text)
    clean_keywords = [kw for kw, _ in keywords_2nd]
    emit(88, "KEYWORDS", "키워드 추출 완료")

    # 6) 임베딩 + 예측
    emit(92, "PREDICT", "주가 예측 중...")
    keyword_vec = embed_keywords(clean_keywords)
    input_vec = torch.tensor(keyword_vec, dtype=torch.float32).unsqueeze(0)

    model = SimpleClassifier(input_vec.shape[1])
    model.load_state_dict(torch.load("news_model.pt", map_location="cpu"))
    model.eval()
    with torch.no_grad():
        prob = model(input_vec).item()
        prediction_label = "📈 상승 (1)" if prob >= 0.5 else "📉 하락 (0)"
    emit(98, "PREDICT", "주가 예측 완료")

    emit(100, "DONE", "분석 완료")

    #  리턴 키는 “원래 네 함수랑 최대한 동일하게”
    return {
        **meta,
        "message": "뉴스 파싱 및 저장 완료",
        "summary": rk.get("summary"),      # 원래: rk["summary"]
        "keyword": rk.get("keyword"),      # 원래: rk["keyword"]
        "company": targetCompany,
        "sentiment": sentiment_label,
        "sentiment_value": sentiment_label,
        "prediction": prediction_label,
        "prob": prob,
        "userId": user_id,
    }



@app.post("/ai/parse-news")
def parse_news(req: NewsRequest):
    try:
        return analyze_news_sync(req.url.strip(), user_id=req.id)
    except Exception as e:
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"서버 오류: {e}")


# ---------------------------------------
# 주가 데이터 (기존 유지)
# ---------------------------------------
krx_listings: pd.DataFrame = None
us_listings: pd.DataFrame = None

@app.on_event("startup")
async def load_initial_data():
    global krx_listings, us_listings
    file_path_kr = "krx_listings.csv"
    file_path_ns = "nas_listings.csv"
    
    # --- 1. 한국 시장 로딩 ---
    if os.path.exists(file_path_kr):
        # dtype={'Code': str} 를 설정해야 '005930'이 '5930'이 되지 않습니다.
        krx_listings = pd.read_csv(file_path_kr, dtype={'Code': str})
        logger.info(" 로컬 파일에서 KRX 목록을 불러왔습니다.")
    else:
        try:
            krx_listings = await run_in_threadpool(fdr.StockListing, 'KRX')
            # 한글 깨짐 방지를 위해 utf-8-sig 권장
            krx_listings.to_csv(file_path_kr, index=False, encoding='utf-8-sig')
            logger.info(" KRX 데이터를 새로 받아 저장했습니다.")
        except Exception as e:
            logger.error(f" KRX 데이터 로딩 실패: {e}")
            krx_listings = pd.DataFrame(columns=['Code', 'Name']) # 빈 데이터프레임 할당

    # --- 2. 미국 시장 로딩 ---
    if os.path.exists(file_path_ns):
        us_listings = pd.read_csv(file_path_ns, dtype={'Symbol': str})
        logger.info(" 로컬 파일에서 US 목록을 불러왔습니다.")
    else:
        try:
            # 여러 시장 데이터를 합칠 때 에러 방지
            nasdaq = await run_in_threadpool(fdr.StockListing, 'NASDAQ')
            nyse = await run_in_threadpool(fdr.StockListing, 'NYSE')
            amex = await run_in_threadpool(fdr.StockListing, 'AMEX')
            
            us_listings = pd.concat([nasdaq, nyse, amex], ignore_index=True)
            us_listings.to_csv(file_path_ns, index=False, encoding='utf-8-sig')
            logger.info(" 미국 상장 기업 목록 로딩 완료.")
        except Exception as e:
            logger.error(f" 미국 상장사 로딩 실패: {e}")
            us_listings = pd.DataFrame(columns=['Symbol', 'Name'])


async def produce_progress(analysis_id: str, user_id: str | None, percent: int, stage: str, message: str):
    if not producer:
        return

    payload = {
        "analysisId": analysis_id,
        "userId": user_id,
        "percent": percent,
        "stage": stage,
        "message": message,
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    await producer.send_and_wait(
        KAFKA_PROGRESS_TOPIC,
        key=analysis_id.encode("utf-8"),
        value=data
    )

def get_stock_info(company_name: str) -> Dict[str, str] | None:
    try:
        # 1. 한국 주식(KRX) 검색
        if krx_listings is not None:
            kr_match = krx_listings[krx_listings['Name'].str.contains(company_name, case=False, na=False)]
            if not kr_match.empty:
                s = kr_match.iloc[0]
                return {"market": "KRX", "symbol": s['Code'], "name": s['Name']}

        # 2. 미국 주식 검색 (번역기 없이 이름으로 직접 검색)
        if us_listings is not None:
            # 영어 이름이 포함되어 있을 수 있으므로 대소문자 무시하고 검색
            us_match = us_listings[
                us_listings['Name'].str.contains(company_name, case=False, na=False) |
                us_listings['Symbol'].str.fullmatch(company_name, case=False)
            ]
            if not us_match.empty:
                s = us_match.iloc[0]
                return {"market": "US", "symbol": s['Symbol'], "name": s['Name']}

    except Exception as e:
        logger.error(f"주식 종목 검색 중 오류 발생: {e}")
    
    return None
    

def fetch_stock_prices_sync(symbol: str, days: int = 365) -> Optional[pd.DataFrame]:
    end_date = datetime.today()
    start_date = end_date - timedelta(days=days)
    try:
        df = fdr.DataReader(symbol, start=start_date, end=end_date)
        if df.empty:
            return None
        return df
    except Exception as e:
        logger.error(f"'{symbol}' 데이터 조회 오류: {e}", exc_info=True)
        return None

@app.get("/ai/stock-data/by-name",
         summary="회사명으로 최근 1년 주가 데이터 조회 (JSON)",
         description="회사명(예: 삼성전자, 애플)을 입력받아 최근 1년간의 일별 주가 데이터를 JSON 형식으로 반환")
async def get_stock_data_by_name(company_name: str = Query(..., description="조회할 회사명")) -> List[Dict[str, Any]]:
    if not company_name or not company_name.strip():
        raise HTTPException(status_code=400, detail="회사명을 입력해주세요.")
    stock_info = await run_in_threadpool(get_stock_info, company_name.strip())
    if not stock_info:
        raise HTTPException(status_code=404, detail=f"'{company_name}'에 해당하는 종목을 찾을 수 없습니다.")
    prices_df = await run_in_threadpool(fetch_stock_prices_sync, stock_info['symbol'], 365)
    if prices_df is None or prices_df.empty:
        raise HTTPException(status_code=404, detail=f"'{stock_info['name']}'의 시세 데이터를 찾을 수 없습니다.")

    prices_df.index.name = 'Date'
    prices_df.reset_index(inplace=True)
    prices_df['Date'] = prices_df['Date'].dt.strftime('%Y-%m-%d')
    return prices_df.to_dict(orient='records')


# ---------------------------------------
# Kafka Consumer 루프 추가
# ---------------------------------------

async def consume_loop():
    global consumer
    logger.info(f"[KAFKA] started topic={KAFKA_TOPIC} group={KAFKA_GROUP_ID} bootstrap={KAFKA_BOOTSTRAP}")

    try:
        # 현재 이벤트 루프를 미리 잡아둠 (threadpool 콜백에서 필요)
        loop = asyncio.get_running_loop()

        async for msg in consumer:
            key = msg.key.decode() if msg.key else None
            raw = msg.value.decode() if msg.value else ""

            try:
                payload = json.loads(raw)
            except Exception:
                logger.warning(f"[KAFKA] invalid json: {raw}")
                continue

            analysis_id = payload.get("analysisId")
            url = (payload.get("url") or "").strip()
            user_id = payload.get("userId")

            if not analysis_id or not url:
                logger.warning(f"[KAFKA] missing analysisId/url payload={payload}")
                continue

            logger.info(f"[KAFKA] consume key={key} analysisId={analysis_id} url={url}")

            # (선택) 여기서 0%를 한 번 보내도 되지만,
            # analyze_news_sync 내부에서도 0%를 emit 하게 만들었으면 중복될 수 있음.
            # await produce_progress(analysis_id, user_id, 0, "START", "분석 시작")

            # threadpool에서 호출될 progress 콜백
            def progress_cb(percent: int, stage: str, message: str):
                # threadpool(다른 스레드) -> 현재 이벤트루프에서 코루틴 실행
                fut = asyncio.run_coroutine_threadsafe(
                    produce_progress(analysis_id, user_id, percent, stage, message),
                    loop
                )
                # (선택) progress 전송 실패 로그 보고 싶으면:
                try:
                    fut.result(timeout=2)
                except Exception as e:
                    logger.warning(f"[KAFKA] progress send failed: {e}")

            try:
                # 무거운 작업 → threadpool (progress_cb 전달!)
                result = await run_in_threadpool(analyze_news_sync, url, user_id, progress_cb)

                logger.info(f"[KAFKA] done analysisId={analysis_id} title={result.get('title')}")

                # analyze_news_sync가 100% DONE까지 emit 한다면 여기서 100% 또 보낼 필요 없음
                # await produce_progress(analysis_id, user_id, 100, "DONE", "분석 완료")

                # DONE 이벤트는 analysis-done 토픽으로 (이건 그대로 유지)
                if producer:
                    done_payload = json.dumps({
                        "analysisId": analysis_id,
                        "userId": user_id,
                        "result": result
                    }, ensure_ascii=False).encode("utf-8")

                    await producer.send_and_wait(
                        KAFKA_DONE_TOPIC,
                        key=analysis_id.encode("utf-8"),
                        value=done_payload
                    )
                    logger.info(f"[KAFKA] produced done analysisId={analysis_id}")

            except Exception as e:
                logger.error(f"[KAFKA] analysis failed analysisId={analysis_id}: {repr(e)}", exc_info=True)
                await produce_progress(analysis_id, user_id, 100, "ERROR", f"오류: {type(e).__name__}")

    finally:
        logger.info("[KAFKA] stopped")


# ---------------------------------------
# 실행
# ---------------------------------------
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)