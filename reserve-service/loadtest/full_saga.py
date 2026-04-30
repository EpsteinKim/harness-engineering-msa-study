"""
풀 saga 부하 테스트 (enqueue → 대기 → /pay → 결과 폴링)

payment-service 컨슈머 concurrency 1→10 변경 효과 측정용.
enqueue만이 아니라 결제 단계까지 통과시켜 payment.commands 처리량 변화를 본다.

실행:
  cd reserve-service/loadtest
  source .venv/bin/activate
  locust -f full_saga.py --host http://localhost:8080
  # 또는 headless
  locust -f full_saga.py --host http://localhost:8080 \
    -u 500 -r 50 -t 90s --headless

KPI:
  - enqueue → /pay 사이 saga 시작 평균 지연 (Grafana saga.started 카운터)
  - /pay → SUCCEEDED/FAILED 평균 지연 (saga.completed/failed 카운터 증가율)
  - payment-service consumer lag (kafka_consumergroup_lag{group="payment-service"})
"""

import random
import time
from locust import task, between
from locust.contrib.fasthttp import FastHttpUser


EVENT_IDS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
SECTIONS = ["A", "B", "C", "D"]
PAYMENT_METHODS = ["CARD", "BANK_TRANSFER", "MOBILE"]
MAX_USER_ID = 500_000

ENQUEUE_TO_PAY_DELAY_SEC = 1.0  # saga 시작될 시간 (dispatch + consumer + saga create)


def is_business_error(response):
    if response.status_code == 400:
        response.success()


class FullSagaUser(FastHttpUser):
    """enqueue → 대기 → /pay 호출. payment 경로 처리량 측정."""

    wait_time = between(0.5, 1.5)

    @task
    def full_flow(self):
        uid = str(random.randint(1, MAX_USER_ID))
        event_id = random.choice(EVENT_IDS)

        # 1. enqueue
        with self.client.post(
            "/api/v1/reservations",
            json={
                "userId": uid,
                "eventId": event_id,
                "section": random.choice(SECTIONS),
            },
            name="1) enqueue",
            catch_response=True,
        ) as r:
            if r.status_code != 200:
                is_business_error(r)
                return

        # 2. saga가 시작될 때까지 대기 (dispatch 200ms + consumer + saga insert)
        time.sleep(ENQUEUE_TO_PAY_DELAY_SEC)

        # 3. 결제 요청 (sagaId 응답 받음)
        method = random.choice(PAYMENT_METHODS)
        with self.client.post(
            "/api/v1/reservations/pay",
            json={
                "userId": int(uid),
                "eventId": event_id,
                "method": method,
            },
            name="2) pay",
            catch_response=True,
        ) as r:
            # 202 Accepted, 또는 400(좌석 배정 실패 등)
            if r.status_code in (200, 202):
                pass
            else:
                is_business_error(r)
