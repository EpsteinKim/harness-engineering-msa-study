# Security

> 보안 원칙과 정책.

---

## 1. 인증/인가

- Phase 1: 인증 없음 (개발 단계)
- Phase 2: JWT 기반 인증 (Gateway 레벨)
- 서비스 간 통신: Docker 내부 네트워크 신뢰 (Phase 2), mTLS 검토 (Phase 3)

## 2. API 보안

- Rate Limiting: Gateway에서 적용 (Phase 2)
- Input Validation: 각 서비스에서 수행
- SQL Injection 방지: Parameterized Query / ORM 사용
- CORS: Gateway에서 관리

## 3. 데이터 보안

- 민감 정보(비밀번호 등): BCrypt 해시
- 환경 변수로 시크릿 관리 (하드코딩 금지)
- `.env` 파일은 `.gitignore`에 포함

## 4. 컨테이너 보안

- 최소 권한 원칙 (non-root 사용자)
- 베이스 이미지 최신 유지
- 불필요한 포트 노출 금지
