# app (게이트웨이 애플리케이션)

여러 서비스의 LLM 호출을 대신 받아 인증, 크레딧 차감, LLM 호출을 처리하는 게이트웨이입니다.
지금은 일정표 2~3주차 범위(인증, 크레딧 예약·정산, LLM 호출, 가짜 LLM 서버)까지 구현되어 있습니다.

| 모듈 | 설명 | 포트 |
| --- | --- | --- |
| `gateway` | 게이트웨이 본체 (Spring Boot 3.4, Java 21) | 8080 앱, 8081 관리용 |
| `fake-llm` | Gemini와 같은 형식으로 응답하는 가짜 LLM 서버 (부하 테스트용) | 9090 |

## 로컬에서 실행하기 (MySQL·Docker 없이)

모든 명령은 `app/` 폴더에서 실행합니다. JDK 21이 필요합니다. `local` 프로필은 H2 메모리 DB와 가짜 LLM 서버를 씁니다.

```bash
./gradlew :fake-llm:bootRun
```

```bash
./gradlew :gateway:bootRun --args='--spring.profiles.active=local'
```

고객을 만들고 호출해 봅니다. 한글이 든 본문은 UTF-8 파일로 보내야 Windows Git Bash에서 깨지지 않습니다.

```bash
curl -X POST localhost:8080/admin/customers -H 'X-Admin-Token: local-admin' -H 'Content-Type: application/json' -d '{"name":"recipe-app","initial_credits":100000}'
```

```bash
curl -X POST localhost:8080/v1/chat/completions -H "Authorization: Bearer gw_발급받은키" -H 'Content-Type: application/json' -d '{"messages":[{"role":"user","content":"hello"}],"max_tokens":200}'
```

테스트: `./gradlew test` (H2와 테스트용 가짜 서버로 돕니다)

- `CreditConcurrencyMySqlTest`는 Docker로 실제 MySQL 8.4를 띄워 동시 요청 100~300개를 검증합니다. Docker가 없으면 이 테스트만 건너뜁니다. 측정 결과는 `gateway/build/reports/concurrency/mysql-credit.md`, 정리본은 `docs/results/`에 있습니다.
- "건너뜀(skipped)"도 실패처럼 확인하세요. Docker는 켜져 있는데 건너뛰었다면 Testcontainers와 Docker 버전이 안 맞는 것입니다.

### 로컬에서 실제 Gemini 부르기

`app/gateway/.env` 파일을 만들고 아래 두 줄을 적은 뒤 게이트웨이를 다시 띄웁니다. 이 파일이 없으면 가짜 LLM 서버(9090)를 씁니다.

```properties
GEMINI_BASE_URL=https://generativelanguage.googleapis.com
GEMINI_API_KEY=여기에_본인_키
```

- `.env`는 저장소 `.gitignore`에 들어 있어 깃에 올라가지 않습니다. 커밋 전에 `git status`에 `.env`가 보이지 않는지 한 번 더 확인합니다.
- 키는 이 파일 말고 코드, `application*.yml`, 노션, 채팅 어디에도 적지 않습니다.
- 배포 서버의 키는 SSM Parameter Store에 넣고 `GEMINI_API_KEY` 환경 변수로 주입합니다(종완).

## API 요약

| 메서드 | 경로 | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/v1/chat/completions` | `Authorization: Bearer gw_...` | LLM 호출. 현재는 한 번에 받기만 지원 (`stream: true`는 501). `Idempotency-Key` 헤더를 보내면 같은 키는 한 번만 처리·차감하고, 다시 보내면 저장된 응답을 `Idempotent-Replayed: true` 헤더와 함께 돌려줌 (24시간 보관) |
| GET | `/v1/credits` | API 키 | 잔액 조회 |
| POST | `/admin/customers` | `X-Admin-Token` | 고객 생성 + API 키 발급 (키 원문은 이 응답에서만 보임) |
| POST | `/admin/customers/{id}/api-keys` | `X-Admin-Token` | API 키 추가 발급 |
| POST | `/admin/customers/{id}/credits` | `X-Admin-Token` | 크레딧 충전 |

오류는 항상 `{"error": {"code", "message", "request_id"}}` 모양입니다.

| 상황 | 상태 코드 | code |
| --- | --- | --- |
| 키 없음·잘못됨 | 401 | `invalid_api_key` |
| 크레딧 부족 | 402 | `insufficient_credits` |
| 요청 형식 오류 | 400 | `invalid_request` |
| LLM 실패 (예약액 전액 환불) | 503 | `all_models_unavailable` |
| 스트리밍 요청 (4~5주차 예정) | 501 | `stream_not_supported_yet` |
| 같은 `Idempotency-Key` 요청이 아직 처리 중 | 409 | `idempotency_in_progress` (잠시 후 같은 키로 다시 보내면 결과를 받음) |
| 같은 `Idempotency-Key`로 다른 내용을 보냄 | 422 | `idempotency_key_reused` |

## 설정값 목록 (종완: 실제 값은 SSM에)

| 환경 변수 | 비밀값 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | | | 배포 시 `prod` (JSON 로그) |
| `DB_URL` | | `jdbc:mysql://localhost:3306/tollgate` | MySQL 주소 |
| `DB_USERNAME` | | `tollgate` | |
| `DB_PASSWORD` | 예 | | |
| `ADMIN_TOKEN` | 예 | (비어 있으면 관리자 API 전부 막힘) | 관리자 API 토큰 |
| `GEMINI_API_KEY` | 예 | | Gemini 키 |
| `GEMINI_BASE_URL` | | `https://generativelanguage.googleapis.com` | **가짜 LLM 스위치**: `http://fake-llm:9090`으로 바꾸면 비용 0 |
| `LLM_PRIMARY_MODEL` | | `gemini-3.1-flash-lite` | 1순위 모델. 단가표(`tollgate.pricing`)에 있는 모델만 쓸 수 있음 |
| `LLM_FIRST_TOKEN_TIMEOUT` | | `10s` | 첫 토큰 대기 |
| `LLM_IDLE_TIMEOUT` | | `15s` | 조각 사이 대기 |

가짜 LLM 서버: `FAKE_LLM_FIRST_TOKEN_DELAY`(300ms), `FAKE_LLM_CHUNK_DELAY`(50ms), `FAKE_LLM_CHUNKS`(20), `FAKE_LLM_TOKENS_PER_CHUNK`(5), `FAKE_LLM_STALL_DURATION`(60s).
프롬프트에 `#fail500`, `#fail429`, `#slow`(첫 토큰 30초), `#stall`(조각 2개 후 멈춤), `#nousage`를 넣으면 장애를 재현합니다.

헬스체크(8081): `/actuator/health/liveness`, `/actuator/health/readiness`(DB 포함, LLM 제외), 지표 `/actuator/prometheus`.

## Docker 이미지 (`app/` 폴더에서)

개발 서버는 x86(m7i-flex.large)이라 기본 빌드를 씁니다. Apple Silicon Mac처럼 ARM 컴퓨터에서 빌드할 때만 `docker buildx build --platform linux/amd64`로 바꿉니다.

```bash
docker build -t llm-tollgate-gateway .
```

```bash
docker build --build-arg MODULE=fake-llm -t llm-tollgate-fake-llm .
```

쿠버네티스에 올릴 때는 컨테이너 `resources.limits.memory`를 꼭 지정합니다. JVM이 그 한도의 75%를 힙으로 쓰기 때문에, 한도가 없으면 서버 전체 메모리(8GB)를 기준으로 잡습니다.

## 아직 없는 것 (일정표 기준)

- 4~5주차: 멱등성(`Idempotency-Key`), 스트리밍 응답(SSE), 가상 스레드
- 7~8주차: 사용량 제한, Redis 캐시, 예비 모델(Nova Micro) 전환 — 코드에 들어갈 자리를 주석으로 표시
- 6주차·9~10주차: 서버가 켜질 때 오래된 예약 환불, 정산 배치
