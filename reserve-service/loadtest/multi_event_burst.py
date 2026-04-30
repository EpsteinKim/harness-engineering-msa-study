"""
다중 이벤트 enqueue 부하 테스트 (per-event lock 분산 효과 측정용)

eventId 2~11 (모두 SECTION_SELECT, 각 50,000석) 에 enqueue 트래픽을 균등 분배.
QueueDispatchScheduler의 lock:queue-dispatch:{eventId} 분산이 5 파드를 활용하는지 검증.

실행:
  cd reserve-service/loadtest
  source .venv/bin/activate
  locust -f multi_event_burst.py --host http://localhost:8080
  # 또는
  locust -f multi_event_burst.py --host http://localhost:8080 \
    -u 2000 -r 200 -t 60s --headless
"""

import random
from locust import task, between
from locust.contrib.fasthttp import FastHttpUser


EVENT_IDS = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
SECTIONS = ["A", "B", "C", "D"]
MAX_USER_ID = 500_000


def is_business_error(response):
    """ServerException(400)은 비즈니스 에러로 Locust 실패로 취급하지 않는다."""
    if response.status_code == 400:
        response.success()


class MultiEventBurstUser(FastHttpUser):
    """10개 이벤트에 enqueue를 균등 분산 — per-event lock 분산 검증."""

    wait_time = between(0, 0)

    @task
    def enqueue_section_select(self):
        uid = str(random.randint(1, MAX_USER_ID))
        event_id = random.choice(EVENT_IDS)
        with self.client.post(
            "/api/v1/reservations",
            json={
                "userId": uid,
                "eventId": event_id,
                "section": random.choice(SECTIONS),
            },
            name="enqueue [multi-event]",
            catch_response=True,
        ) as response:
            is_business_error(response)
