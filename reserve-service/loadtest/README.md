# 부하 테스트 (Locust)

## 사전 준비

```bash
cd reserve-service/loadtest
python3 -m venv .venv
source .venv/bin/activate
pip install locust
```

## 테스트 데이터 준비

1. DB에 테스트 이벤트 + 좌석 생성:
```bash
psql -U <user> -d <db> -f seed_test_data.sql
```

2. `locustfile.py`의 `EVENT_ID`를 생성된 이벤트 ID로 변경

3. reserve-service 실행 후 EventScheduler가 이벤트를 OPEN 처리하거나,
   수동으로 Redis 캐시를 로드해야 함 (이벤트의 ticket_open_time이 과거여야 함)

## 실행

```bash
cd reserve-service/loadtest

# 웹 UI 모드 (http://localhost:8089)
locust

# headless 모드
locust --headless -u 500 -r 50 -t 60s

# 동시 유저 1000명, 초당 100명씩 증가, 2분 실행
locust --headless -u 1000 -r 100 -t 120s
```

## 시나리오

| 클래스 | 비중 | 설명 |
|--------|------|------|
| SectionSelectUser | 3 | 구역 선택 → 자동 배정 |
| SeatPickUser | 2 | 특정 좌석 직접 선택 |
| BurstUser | 1 | 티켓 오픈 직후 폭주 |

## 측정 지표

- RPS, p50/p95/p99 응답시간, 에러율
- Locust 웹 UI에서 실시간 확인 가능
