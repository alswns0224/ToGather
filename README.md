# ToGather

팀 프로젝트에서 Slack, Notion, Google Drive, Google Calendar, Gmail에 흩어진 대화·문서·파일·일정을 하나의 워크스페이스에서 관리하는 협업 플랫폼입니다.

- `frontend/` : React
- `backend/` : Spring Boot
- 저장소 : PostgreSQL, ChromaDB (docker-compose)

## 로컬 실행

1. `.env.example`을 `.env`로 복사하고 값을 채웁니다.
2. 저장소 실행: `docker compose up -d`
3. 백엔드: `cd backend && ./gradlew bootRun` (http://localhost:8080, 첫 실행 시 Flyway가 스키마 생성)
4. 프론트엔드: `cd frontend && npm install && npm run dev` (http://localhost:5173, `/api`는 8080으로 프록시)

---

## 개발 규칙

세 명이 같은 구조와 형식으로 개발하기 위한 규칙입니다. 규칙을 바꿀 때는 이 파일을 수정하는 PR을 올리고 세 명이 모두 확인합니다.

### 1. 패키지 구조

```
com.togather
├── auth          # 로그인, 세션
├── workspace     # 워크스페이스, 멤버, 초대
├── integration   # OAuth 연동, 토큰 관리
├── sync          # 증분 동기화 (스케줄러, 어댑터)
├── content       # 외부 콘텐츠
│   ├── slack
│   ├── notion
│   ├── drive
│   ├── calendar
│   └── gmail
├── analysis      # Slack 논의 분석
├── task          # Task, 산출물, 협업 맥락
├── meeting       # 회의, 가용시간, 추천
├── search        # 임베딩, 통합 검색
├── assistant     # AI 업무 보조
└── common        # 공통 예외, 응답, 권한 검사, Enum
```

- 각 기능 패키지 안에는 `controller`, `service`, `repository`, `domain`(Entity), `dto`를 둡니다.
- 여러 파트가 같이 쓰는 코드는 `common`에만 둡니다.

### 2. 이름 규칙

- 클래스: `XxxController`, `XxxService`, `XxxRepository`
- DTO: 요청은 `XxxRequest`, 응답은 `XxxResponse`
- Entity는 Controller 밖으로 내보내지 않고 DTO로 변환해서 응답합니다.
- 메서드: 조회 `get` / `find`, 생성 `create`, 수정 `update`, 삭제 `delete`로 시작합니다.
- DB: 테이블·컬럼은 snake_case (`content_ref`, `owner_user_id`), Java 필드는 camelCase (`ownerUserId`)

### 3. API 규칙

- 경로: `/api/workspaces/{workspaceId}/...`
  - 워크스페이스에 속한 데이터는 모두 이 경로 아래에 둡니다.
  - 요청마다 현재 사용자가 해당 워크스페이스 멤버인지 검사하고, 아니면 403을 반환합니다.
- HTTP 메서드: 조회 `GET`, 생성 `POST`, 수정 `PATCH`, 삭제 `DELETE`
- 목록 조회: `page`(0부터 시작), `size`(기본값 20) 파라미터

**응답 형식**

성공

```json
{
  "data": { }
}
```

목록 성공

```json
{
  "data": {
    "items": [],
    "page": 0,
    "size": 20,
    "total": 135
  }
}
```

실패

```json
{
  "error": {
    "code": "WORKSPACE_ACCESS_DENIED",
    "message": "워크스페이스 멤버가 아닙니다."
  }
}
```

- 에러 코드는 `common`의 `ErrorCode` Enum에 모읍니다. 각자 필요한 코드를 이 Enum에 추가합니다.
- 예외는 `BusinessException(ErrorCode)`로 던지고, `@RestControllerAdvice`에서 위 형식으로 변환합니다.

### 4. Git 규칙

- `main`에 직접 push하지 않습니다.
- 브랜치: `feature/{a|b|c}-기능명`
  - 예: `feature/a-oauth-connect`, `feature/b-slack-sync`, `feature/c-task-crud`
- 커밋 메시지 접두어: `feat:` `fix:` `refactor:` `docs:` `test:` `chore:`
  - 예: `feat: Slack 증분 동기화 어댑터 추가`
- PR은 다른 한 명이 확인한 뒤 머지합니다.
- `common`, 공통 Entity, DB 마이그레이션을 바꾸는 PR은 세 명 모두 확인합니다.

### 5. 개발 환경

- Java: 21
- Spring Boot: 4.1.x
- Node: 22 LTS
- PostgreSQL, ChromaDB: `docker-compose.yml`에 적은 이미지 버전을 그대로 사용

Spring Boot 3.x는 2026년 6월 30일에 무료 지원(OSS)이 끝나서 4.1.x로 정했습니다(OSS 지원은 2027년 7월까지).

- 포매터 설정(`.editorconfig`, IDE 코드 스타일)을 저장소에 포함합니다.
- DB 스키마는 Flyway 마이그레이션 파일로만 변경합니다.
- 비밀값은 `.env`에만 둡니다. `.env`는 Git에 올리지 않고, 항목 이름만 적은 `.env.example`을 올립니다.

```
# .env.example
DB_URL=
DB_USERNAME=
DB_PASSWORD=
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
SLACK_CLIENT_ID=
SLACK_CLIENT_SECRET=
NOTION_CLIENT_ID=
NOTION_CLIENT_SECRET=
GEMINI_API_KEY=
TOGATHER_TOKEN_KEY=
```

### 6. 파트 간 규칙

- 다른 파트의 테이블을 직접 조회하거나 수정하지 않습니다. 담당자의 Service 메서드를 호출합니다.
- 다른 파트의 클래스를 직접 고치지 않습니다. 필요한 메서드가 있으면 담당자에게 추가를 요청합니다.
- 개인 콘텐츠(Gmail, Calendar)를 조회할 때는 `ownerUserId` 조건이 들어간 Repository 메서드만 사용합니다.
- 토큰은 `IntegrationService.getAccessToken()`으로만 받습니다. 토큰을 DTO, 응답, 로그에 넣지 않습니다.

### 7. 구현 시작 전 남은 작업

- [x] Java / Spring Boot / Node 버전 확정
- [x] 목록 API 기본 `size` 확정 (20)
- [ ] 공통 Entity, Enum, 응답 형식, `ErrorCode` 초안 생성
- [ ] `.editorconfig`, `docker-compose.yml`, `.env.example`, Flyway 초기 마이그레이션 파일 생성

아래 두 항목이 끝나면 공통 기반 구현을 시작합니다.

### 8. 파트 담당

- **A. 기반·연동·일정**: 로그인, 워크스페이스, OAuth 연동, 토큰 암호화, 권한 검사, Calendar, 회의
- **B. 수집·Slack·Notion·AI**: 동기화 프레임워크, Slack, Notion, 논의 분석, AI 업무 보조, GeminiClient
- **C. Drive·Gmail·Task·검색**: Drive, Gmail, Task, 협업 맥락 조회, 임베딩, 통합 검색
