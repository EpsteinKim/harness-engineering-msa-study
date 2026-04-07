# DB Schema (Generated)

> **이 문서의 DB 스키마 변경은 반드시 사용자 승인을 받아야 합니다.**
> 자동 생성 대상이지만, 스키마 자체의 변경은 승인 프로세스를 거칩니다.

## 승인 프로세스

1. 스키마 변경 필요 시 → 변경안 작성
2. 사용자 검토 및 승인
3. 승인 후 마이그레이션 스크립트 작성
4. 적용 후 이 문서 자동 갱신

---

## 현재 스키마

### reserve-service (NeonDB / PostgreSQL)

```sql
CREATE TABLE events (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    event_time  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE seats (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    BIGINT       NOT NULL REFERENCES events(id),
    seat_number VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | RESERVED
    reserved_by VARCHAR(255),
    reserved_at TIMESTAMP,
    version     BIGINT       NOT NULL DEFAULT 0,             -- 낙관적 락
    UNIQUE (event_id, seat_number)
);

CREATE INDEX idx_seats_event_status ON seats(event_id, status);
```

### user-service (NeonDB / PostgreSQL)

> 아직 스키마가 정의되지 않았습니다.

### queue-service

> DB 사용 안 함. Redis만 사용. (ARCHITECTURE.md Redis 구조 참조)

---

## 변경 이력

| 날짜 | 변경 내용 | 승인 |
|------|-----------|------|
| 2026-04-07 | events, seats 테이블 추가 (reserve-service) | 승인됨 |
