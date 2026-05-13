# API Design Guide

> API 설계와 관련된 상세 가이드. 기본 원칙은 [CONSTITUTION.md](../CONSTITUTION.md) Section 4.2를 참조.

---

## 1. URL 설계

```
/api/v1/{resource}          # 컬렉션
/api/v1/{resource}/{id}     # 단일 리소스
/api/v1/{resource}/{id}/{sub-resource}  # 하위 리소스
```

### 명명 규칙

- 복수형 사용: `/queues`, `/users`
- kebab-case: `/queue-entries`
- 동사 지양, 명사 사용. 행위는 HTTP Method로 표현

---

## 2. 응답 형식

### 성공

```json
{
  "status": "success",
  "data": { ... },
  "message": null
}
```

### 에러

```json
{
  "status": "error",
  "data": null,
  "message": "대기열이 가득 찼습니다.",
  "code": "QUEUE_FULL"
}
```

### HTTP 상태 코드

| 코드 | 용도 |
|------|------|
| 200 | 성공 (조회, 수정) |
| 201 | 성공 (생성) |
| 204 | 성공 (삭제, 본문 없음) |
| 400 | 잘못된 요청 |
| 404 | 리소스 없음 |
| 409 | 충돌 (이미 대기열에 참가 등) |
| 429 | Rate Limit 초과 |
| 500 | 서버 내부 오류 |

---

## 3. 페이지네이션

```
GET /api/v1/queues?page=0&size=20&sort=createdAt,desc
```

```json
{
  "status": "success",
  "data": {
    "content": [...],
    "page": 0,
    "size": 20,
    "totalElements": 100,
    "totalPages": 5
  }
}
```

---

## 4. 버전 관리

- URI 기반: `/api/v1/`, `/api/v2/`
- 하위 호환성이 깨지는 변경 시에만 버전 증가
- 이전 버전은 최소 1개 버전까지 유지
