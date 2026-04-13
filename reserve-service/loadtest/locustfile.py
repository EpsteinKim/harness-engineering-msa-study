"""
reserve-service 부하 테스트

사전 조건:
  - reserve-service 실행 중 (localhost:8082 또는 Docker)
  - Redis 실행 중
  - 이벤트 1 (SEAT_PICK, OPEN) + 이벤트 2 (SECTION_SELECT, OPEN) + 좌석 데이터 존재
  - EventScheduler가 이벤트를 열어 Redis 캐시가 로드된 상태

실행:
  cd reserve-service/loadtest
  pip install locust
  locust                              # 웹 UI (http://localhost:8089)
  locust --headless -u 2000 -r 100 -t 60s  # headless 모드
"""

import itertools
import random
import threading
from locust import HttpUser, task, between


# === 설정 ===
SEAT_PICK_EVENT_ID = 1
SECTION_SELECT_EVENT_ID = 2
SECTIONS = ["A", "B", "C", "D"]
MAX_SEAT_ID = 400       # 전체 좌석 수
HOT_SEAT_ID = 50        # 인기 좌석 범위 (경쟁 유도)

# 전역 요청 제한
_user_counter = itertools.count(1)
_counter_lock = threading.Lock()



def next_user_id() -> str:
    with _counter_lock:
        return str(next(_user_counter))


def is_business_error(response):
    """ServerException(400)은 비즈니스 에러이므로 Locust 실패로 취급하지 않는다."""
    if response.status_code == 400:
        response.success()




# class SectionSelectUser(HttpUser):
#     """SECTION_SELECT 이벤트(ID=2) 시나리오: 구역 선택 후 자동 배정"""
#
#     host = "http://localhost:8082"
#     wait_time = between(0.05, 0.3)
#     weight = 3
#
#     def on_start(self):
#         self.last_user_id = None
#
#     @task(10)
#     def enqueue_section(self):
#         if stop_if_limit(self):
#             return
#         uid = next_user_id()
#         self.last_user_id = uid
#         with self.client.post(
#             "/api/v1/reservations",
#             json={
#                 "userId": uid,
#                 "eventId": SECTION_SELECT_EVENT_ID,
#                 "section": random.choice(SECTIONS),
#             },
#             name="POST /reservations [section]",
#             catch_response=True,
#         ) as response:
#             is_business_error(response)
#
#     @task(3)
#     def check_sections(self):
#         if stop_if_limit(self):
#             return
#         self.client.get(
#             f"/api/v1/reservations/seats/{SECTION_SELECT_EVENT_ID}/sections",
#             name="GET /seats/[eventId]/sections",
#         )
#
#     @task(1)
#     def cancel(self):
#         if stop_if_limit(self):
#             return
#         uid = self.last_user_id
#         if uid is None:
#             return
#         with self.client.delete(
#             f"/api/v1/reservations/queue/{SECTION_SELECT_EVENT_ID}/{uid}",
#             name="DELETE /reservations/queue/[eventId]/[userId]",
#             catch_response=True,
#         ) as response:
#             is_business_error(response)
#         self.last_user_id = None


class SeatPickUser(HttpUser):
    """SEAT_PICK 이벤트(ID=1) 시나리오: 특정 좌석 직접 선택"""

    host = "http://localhost:8082"
    wait_time = between(0.05, 0.3)
    weight = 2

    def on_start(self):
        self.last_user_id = None

    @task(10)
    def enqueue_seat(self):
        uid = next_user_id()
        self.last_user_id = uid
        # 70% 인기 좌석(1~50)에 몰려서 동시성 경쟁 유도
        if random.random() < 0.7:
            seat_id = random.randint(1, HOT_SEAT_ID)
        else:
            seat_id = random.randint(HOT_SEAT_ID + 1, MAX_SEAT_ID)

        with self.client.post(
            "/api/v1/reservations",
            json={
                "userId": uid,
                "eventId": SEAT_PICK_EVENT_ID,
                "seatId": seat_id,
            },
            name="POST /reservations [seatId]",
            catch_response=True,
        ) as response:
            is_business_error(response)

    @task(3)
    def check_sections(self):
        self.client.get(
            f"/api/v1/reservations/seats/{SEAT_PICK_EVENT_ID}/sections",
            name="GET /seats/[eventId]/sections",
        )

    @task(1)
    def cancel(self):
        uid = self.last_user_id
        if uid is None:
            return
        with self.client.delete(
            f"/api/v1/reservations/queue/{SEAT_PICK_EVENT_ID}/{uid}",
            name="DELETE /reservations/queue/[eventId]/[userId]",
            catch_response=True,
        ) as response:
            is_business_error(response)
        self.last_user_id = None

#
# class BurstUser(HttpUser):
#     """티켓 오픈 직후 폭주 시나리오: 이벤트 2(SECTION_SELECT) 대상"""
#
#     host = "http://localhost:8082"
#     wait_time = between(0, 0.05)
#     weight = 1
#
#     @task
#     def enqueue(self):
#         if stop_if_limit(self):
#             return
#         with self.client.post(
#             "/api/v1/reservations",
#             json={
#                 "userId": next_user_id(),
#                 "eventId": SECTION_SELECT_EVENT_ID,
#                 "section": random.choice(SECTIONS),
#             },
#             name="POST /reservations [burst]",
#             catch_response=True,
#         ) as response:
#             is_business_error(response)
