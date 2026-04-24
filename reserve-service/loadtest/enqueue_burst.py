"""
enqueue 전용 부하 테스트 — 초당 100,000 RPS 목표

실행:
  cd reserve-service/loadtest
  source .venv/bin/activate

  # Docker Compose
  locust -f enqueue_burst.py --headless -u 5000 -r 500 -t 60s --host http://localhost:8988

  # K8s (OrbStack)
  locust -f enqueue_burst.py --headless -u 5000 -r 500 -t 60s --host http://harness.local.k8s.orb.local

  # 웹 UI
  locust -f enqueue_burst.py

참고:
  - 단일 머신에서 100K RPS는 Locust만으로는 한계가 있음
  - 분산 모드: locust --master / locust --worker 로 여러 프로세스 실행
  - FastHttpUser 사용으로 단일 프로세스 성능 극대화
  - wait_time=0으로 딜레이 없이 연속 요청
"""

import itertools
import random
import threading
from locust import task, between
from locust.contrib.fasthttp import FastHttpUser

SEAT_PICK_EVENT_ID = 1
SECTION_SELECT_EVENT_ID = 2
SECTIONS = ["A", "B", "C", "D"]
MAX_SEAT_ID = 400

_user_counter = itertools.count(1)
_counter_lock = threading.Lock()


def next_user_id() -> str:
    with _counter_lock:
        return str(next(_user_counter))


def is_business_error(response):
    if response.status_code == 400:
        response.success()


class EnqueueBurstUser(FastHttpUser):
    """enqueue만 최대 속도로 요청. wait_time 없음."""

    wait_time = between(0, 0)
    weight = 1

    @task(7)
    def enqueue_seat_pick(self):
        uid = next_user_id()
        seat_id = random.randint(1, MAX_SEAT_ID)
        with self.client.post(
            "/api/v1/reservations",
            json={
                "userId": uid,
                "eventId": SEAT_PICK_EVENT_ID,
                "seatId": seat_id,
            },
            name="enqueue [SEAT_PICK]",
            catch_response=True,
        ) as response:
            is_business_error(response)

    @task(3)
    def enqueue_section_select(self):
        uid = next_user_id()
        with self.client.post(
            "/api/v1/reservations",
            json={
                "userId": uid,
                "eventId": SECTION_SELECT_EVENT_ID,
                "section": random.choice(SECTIONS),
            },
            name="enqueue [SECTION_SELECT]",
            catch_response=True,
        ) as response:
            is_business_error(response)
