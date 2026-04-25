"""
enqueue 전용 부하 테스트 (SECTION_SELECT only)

실행:
  cd reserve-service/loadtest
  source .venv/bin/activate
  locust -f enqueue_burst.py --host http://localhost:8080
"""

import random
from locust import task, between
from locust.contrib.fasthttp import FastHttpUser

SECTION_SELECT_EVENT_ID = 2
SECTIONS = ["A", "B", "C", "D"]
MAX_USER_ID = 200000


def is_business_error(response):
    if response.status_code == 400:
        response.success()


class EnqueueBurstUser(FastHttpUser):
    """SECTION_SELECT enqueue만 최대 속도로 요청."""

    wait_time = between(0, 0)

    @task
    def enqueue_section_select(self):
        uid = str(random.randint(1, MAX_USER_ID))
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
