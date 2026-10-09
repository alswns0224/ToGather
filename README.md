# ToGather

팀 프로젝트에서 Slack, Notion, Google Drive, Google Calendar, Gmail에 흩어진 대화·문서·파일·일정을 하나의 워크스페이스에서 관리하는 협업 플랫폼입니다.

| 구분 | 기술 |
|---|---|
| `frontend/` | React 19, TypeScript, Vite, React Router |
| `backend/` | Java 21, Spring Boot 4.1, Spring Data JPA, Flyway |
| 저장소 | PostgreSQL(관계형 데이터), ChromaDB(임베딩 벡터) — `docker-compose.yml` |
| AI | Gemini API |

**문서**
- [docs/shared-interfaces.md](docs/shared-interfaces.md) — 파트 간 호출 메서드 (명세서보다 우선)
- [docs/issues.md](docs/issues.md) — 기능 단위 이슈 목록과 주차별 일정
- 설계명세서 v1.0 — HWP 파일이 30MB라 저장소에 올리지 않았습니다. 팀원끼리 직접 공유합니다.

---

## 시작하기

### 처음 한 번

1. 저장소 받기: `git clone https://github.com/alswns0224/ToGather.git`
2. `.env.example`을 복사해 `.env`를 만들고 값을 채웁니다. 값은 아래 [비밀값](#비밀값) 참고.
3. IDE 설정
   - IntelliJ: `backend/` 폴더를 Gradle 프로젝트로 열고, Settings → Build → Compiler → Annotation Processors에서 **Enable annotation processing** 체크(Lombok).
   - `.editorconfig`를 따르도록 설정합니다. (IntelliJ, VS Code 모두 기본 지원)

### 로컬 실행

1. 저장소 실행: `docker compose up -d`
2. 백엔드: `cd backend && ./gradlew bootRun` → http://localhost:8080
   - 첫 실행 때 Flyway가 스키마를 만듭니다.
3. 프론트엔드: `cd frontend && npm install && npm run dev` → http://localhost:5173
   - `/api`로 시작하는 요청은 8080으로 프록시됩니다.
4. 브라우저에서 http://localhost:5173 접속
   - `.env`에 `DEV_LOGIN_ENABLED=true`면 Google 설정 없이 이메일·이름만으로 로그인할 수 있습니다. (로컬 전용)

DB를 처음 상태로 되돌리려면 `docker compose down -v` 후 다시 `up` 합니다. (로컬 데이터가 모두 지워집니다)

**Docker를 쓸 수 없을 때** (가상화가 꺼진 PC 등)
- 1·2번 대신 `cd backend && ./gradlew bootTestRun`을 실행합니다. 내장 PostgreSQL이 함께 뜨고, 데이터는 `backend/.local-postgres`에 남습니다. (처음 상태로 되돌리려면 이 폴더를 지웁니다)
- ChromaDB는 뜨지 않으므로 통합 검색은 Docker 환경에서 확인합니다.

**테스트**: `cd backend && ./gradlew test` — 통합 테스트도 내장 PostgreSQL로 돌아가서 Docker가 필요 없습니다.

**Windows에서 Gradle이 `Unable to establish loopback connection`으로 실패할 때**: 임시 폴더 경로 문제입니다. 짧은 영어 경로를 임시 폴더로 지정하고 실행합니다.

```bash
mkdir -p /c/gtmp
export TMP='C:\gtmp' TEMP='C:\gtmp' JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\gtmp -Djava.io.tmpdir=C:\gtmp'
./gradlew bootRun
```

### 외부 서비스 앱 등록 (A 담당)

각 서비스 개발자 콘솔에서 OAuth 앱을 만들고, 아래 Redirect URI를 등록한 뒤 client id/secret을 `.env`에 넣습니다. `{APP_URL}`은 `.env`의 `APP_URL`(기본 `http://localhost:5173`)입니다.

| 서비스 | Redirect URI | 비고 |
|---|---|---|
| Google (로그인) | `{APP_URL}/api/auth/google/callback` | Google Cloud Console → OAuth 클라이언트(웹 애플리케이션) |
| Google (Drive·Calendar·Gmail) | `{APP_URL}/api/integrations/google/callback` | 같은 OAuth 클라이언트. Drive·Calendar·Gmail API 사용 설정, 동의 화면에 팀원을 테스트 사용자로 등록 |
| Slack | `{APP_URL}/api/integrations/slack/callback` | **https만 허용**됩니다. 로컬에서는 ngrok 등으로 https 주소를 만들어 `APP_URL`에 넣습니다. User Token Scopes는 `SlackOAuthClient.USER_SCOPE` 참고 |
| Notion | `{APP_URL}/api/integrations/notion/callback` | Public integration으로 만듭니다. |

### 비밀값

| 항목 | 받는 곳 |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 로컬 전용. 각자 정해도 됩니다. (`docker compose`가 이 값으로 DB를 만듭니다) |
| `GOOGLE_*`, `SLACK_*`, `NOTION_*` | A(백민준)가 앱을 등록하고 팀원에게 개인 메시지로 전달합니다. |
| `GEMINI_API_KEY` | 각자 Google AI Studio에서 발급합니다. |
| `TOGATHER_TOKEN_KEY` | 토큰 암호화 키. `openssl rand -base64 32`로 생성합니다. 로컬 DB마다 따로 써도 됩니다. |

- 비밀값은 **`.env`에만** 둡니다. 코드, 커밋, 이슈, PR, 단체 채팅방에 올리지 않습니다.
- 실수로 커밋했다면 바로 팀에 알리고 해당 키를 재발급합니다. (커밋을 지워도 기록이 남습니다)

---

## 팀과 담당

| 파트 | 담당 | 백엔드 | 화면 |
|---|---|---|---|
| **A. 기반·연동·일정** | 백민준 | 로그인, 워크스페이스, OAuth 연동, 토큰 암호화, 권한 검사, Calendar, 회의 | UI-01, 02, 03, 07, 10 |
| **B. 수집·Slack·Notion·AI** | 정철준 | 동기화 프레임워크, Slack, Notion, 논의 분석, AI 업무 보조, GeminiClient, ContentRef 저장 | UI-04, 05, 08, 13 |
| **C. Drive·Gmail·Task·검색** | 이찬서 | Drive, Gmail, Task, 협업 맥락 조회, 임베딩, 통합 검색 | UI-06, 09, 11, 12 |

- 각자 맡은 파트의 **백엔드와 화면을 함께** 구현합니다.
- 개발 기간: 2026-09-01 ~ 2026-12-09. 마지막 주(12/2~12/9)는 통합 테스트와 버그 수정에 씁니다.
- 막히는 일이 생기면 혼자 오래 붙잡지 말고 **하루 안에** 해당 이슈에 댓글로 남기고 팀에 알립니다.

---

## 개발 규칙

세 명이 같은 구조와 형식으로 개발하기 위한 규칙입니다. 규칙을 바꿀 때는 이 파일을 수정하는 PR을 올리고 다른 두 명이 승인합니다.

### 1. 변경 종류별 반영 방법

세 명짜리 팀이라 절차는 가볍게 하되, **모두가 같이 쓰는 것만 리뷰를 꼭 거칩니다.**

| 변경 | 반영 방법 |
|---|---|
| **문서만** — `docs/`의 진행 메모, README 설명 보충·오타, 코드 주석 | `main`에 바로 push 가능 |
| **내 파트 코드** — 자기 파트 패키지(`controller`·`service`·`repository`·`dto`·`client`)와 자기 화면(`pages/{기능}/`) | PR을 만들고 빌드가 통과하면 **리뷰 없이 바로 머지** 가능. 봐줬으면 하는 게 있으면 리뷰어를 지정 |
| **함께 쓰는 것** — 아래 목록 | PR을 만들고 **다른 두 명이 승인한 뒤** 머지 |

**함께 쓰는 것 (리뷰 필수)**
- `common` 패키지 (응답 형식, `ErrorCode` 구조, 권한 검사, Enum)
  - 단, `ErrorCode`에 내 파트 구역 코드만 추가하는 건 "내 파트 코드"로 봅니다.
- DB 마이그레이션 파일, Entity 필드 변경
- `docs/shared-interfaces.md`의 시그니처 (호출하는 사람의 코드가 깨짐)
- README의 **규칙** 내용 (설명 보충·오타는 제외)
- 빌드·실행 설정: `build.gradle`, `package.json`, `docker-compose.yml`, `application.yml`, `.env.example`
- 프론트 공통 폴더: `api/`, `layouts/`, `components/`

헷갈리면 리뷰를 받는 쪽으로 합니다.

### 2. 작업 흐름

```
이슈 확인 → 브랜치 생성 → 개발·커밋 → main 반영 → 빌드 확인 → PR → (리뷰) → 머지 → 브랜치 삭제
```

1. [docs/issues.md](docs/issues.md)의 이슈를 GitHub Issues에서 찾아 자신을 Assignee로 지정합니다.
2. 최신 `main`에서 브랜치를 만듭니다.
   ```bash
   git switch main
   git pull
   git switch -c feature/a-google-login
   ```
3. 작업하면서 의미 있는 단위로 커밋합니다.
4. PR을 올리기 전에 `main`의 최신 내용을 내 브랜치에 반영하고, 빌드가 되는지 확인합니다.
   ```bash
   git pull origin main
   cd backend && ./gradlew build
   cd frontend && npm run build
   ```
5. PR을 올립니다. "함께 쓰는 것"을 바꿨으면 두 명을 리뷰어로 지정합니다.
6. **PR 작성자가** 직접 머지하고 브랜치를 삭제합니다.
7. 리뷰 없이 머지했어도, 다른 파트에 영향이 있을 만한 변경이면 팀 채팅방에 PR 링크를 공유합니다.

### 3. Git 규칙

- 코드는 `main`에 직접 push하지 않고 PR로 넣습니다. (문서만 예외, 위 1번)
- 브랜치: `feature/{a|b|c}-기능명` — 기능명은 영어 소문자와 `-`만 씁니다.
  - 예: `feature/a-oauth-connect`, `feature/b-slack-sync`, `feature/c-task-crud`
  - 버그 수정도 같은 형식을 씁니다. 예: `feature/c-fix-task-progress`
- 커밋 메시지: `접두어: 무엇을 했는지` (한국어)
  - 접두어: `feat:` 기능 · `fix:` 버그 · `refactor:` 동작 변화 없는 수정 · `docs:` 문서 · `test:` 테스트 · `chore:` 설정·빌드
  - 예: `feat: Slack 증분 동기화 어댑터 추가`
- 다른 사람이 같이 쓰는 브랜치(`main` 등)에 `push --force` 하지 않습니다.
- 빌드 결과물, IDE 설정, `.env`는 커밋하지 않습니다. (`.gitignore`에 등록되어 있음)

### 4. PR 규칙

- **하나의 PR = 하나의 이슈.** PR 본문에 `Closes #이슈번호`를 적습니다.
- PR 제목은 커밋 메시지와 같은 형식입니다. 예: `feat: Google 로그인과 세션 구현`
- 본문은 `.github/pull_request_template.md` 양식을 채웁니다. (PR을 만들면 자동으로 들어갑니다)
- **리뷰 없이 머지하는 PR도 빌드는 반드시 통과**해야 합니다. 리뷰가 없는 대신 빌드가 최소한의 안전장치입니다.
- 리뷰를 요청받으면
  - **24시간 안에** 확인합니다. 바로 못 보면 언제 볼지 댓글로 남깁니다.
  - 고쳐야 하는 것과 의견(제안)을 구분해서 적습니다.
- 머지 방식: **Squash and merge** (PR 하나가 `main`에 커밋 하나로 남습니다)
- PR은 작게 유지합니다. 변경이 500줄을 크게 넘으면 나눌 수 있는지 먼저 생각합니다.

### 5. 패키지 구조

```
com.togather
├── auth          # 로그인, 세션                     (A)
├── workspace     # 워크스페이스, 멤버, 초대          (A)
├── integration   # OAuth 연동, 토큰 관리             (A)
├── sync          # 증분 동기화 (스케줄러, 어댑터)    (B)
├── content       # 외부 콘텐츠, ContentRef           (B)
│   ├── slack     #                                   (B)
│   ├── notion    #                                   (B)
│   ├── drive     #                                   (C)
│   ├── calendar  #                                   (A)
│   └── gmail     #                                   (C)
├── analysis      # Slack 논의 분석                   (B)
├── task          # Task, 산출물, 협업 맥락           (C)
├── meeting       # 회의, 가용시간, 추천              (A)
├── search        # 임베딩, 통합 검색                 (C)
├── assistant     # AI 업무 보조                      (B)
└── common        # 공통 예외, 응답, 권한 검사, Enum  (세 명 공동)
```

- 각 기능 패키지 안에는 `controller`, `service`, `repository`, `domain`(Entity), `dto`를 둡니다.
- 외부 API 호출 코드는 해당 패키지의 `client`에 둡니다. 예: `content.slack.client.SlackApiClient`
- 여러 파트가 같이 쓰는 코드는 `common`에만 둡니다.

### 6. 이름 규칙

- 클래스: `XxxController`, `XxxService`, `XxxRepository`, `XxxClient`(외부 API)
- DTO
  - **API 요청·응답**(Controller가 받고 내보내는 것): 요청 `XxxRequest`, 응답 `XxxResponse`
  - **파트 간 Service 호출용**: 명세서와 [shared-interfaces.md](docs/shared-interfaces.md)의 이름을 그대로 씁니다. (`ContentRefDTO`, `TaskCommand`, `TaskSummary` 등)
  - DTO는 `record`로 만듭니다.
- 메서드: 조회 `get` / `find`, 생성 `create`, 수정 `update`, 삭제 `delete`로 시작합니다.
  - `get`: 없으면 예외를 던집니다. `find`: 없으면 `Optional`이나 빈 목록을 반환합니다.
- DB: 테이블·컬럼은 snake_case (`content_ref`, `owner_user_id`), Java 필드는 camelCase (`ownerUserId`)
- Enum 상수는 대문자 snake_case (`IN_PROGRESS`)

### 7. API 규칙

- 경로: `/api/workspaces/{workspaceId}/...`
  - 워크스페이스에 속한 데이터는 모두 이 경로 아래에 둡니다.
  - 요청마다 현재 사용자가 해당 워크스페이스 멤버인지 검사하고, 아니면 403을 반환합니다.
  - 워크스페이스와 무관한 것만 예외입니다: `/api/auth/...`(로그인), `/api/workspaces`(내 워크스페이스 목록·생성), `/api/invites/{token}`(초대 참여), `/api/me/...`(개인 할 일, AI 후보)
- 경로의 자원 이름은 복수형 소문자, 단어 구분은 `-`: `/tasks`, `/meeting-times`
- HTTP 메서드: 조회 `GET`, 생성 `POST`, 수정 `PATCH`, 삭제 `DELETE`
  - 동작(확정, 승인, 재시도 등)은 `POST /자원/{id}/동작` 형식: `POST /meetings/{id}/confirm`
- 상태 코드: 조회·수정 `200`, 생성 `201`, 삭제 `204`, 입력 오류 `400`, 미로그인 `401`, 권한 없음 `403`, 없음 `404`, 외부 서비스 실패 `502`
- 목록 조회: `page`(0부터 시작), `size`(기본값 20) 파라미터
- 날짜·시간: ISO-8601 문자열, 서울 시간 기준 (`2026-10-07`, `2026-10-07T19:30:00`)

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

- 성공 응답은 `ApiResponse.ok(data)`, 목록은 `ApiResponse.ok(PageResponse.from(page, XxxResponse::from))`로 만듭니다.
- 에러 코드는 `common`의 `ErrorCode` Enum에 모읍니다. 자기 파트 구역(`// ===== A. ...`) 아래에 추가합니다.
- 예외는 `BusinessException(ErrorCode)`로 던지고, `GlobalExceptionHandler`가 위 형식으로 변환합니다. Controller에서 `try-catch`로 응답을 직접 만들지 않습니다.

**Controller 기본 형태**

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}/tasks")
public class TaskController {

    private final TaskService taskService;
    private final WorkspaceAccessChecker workspaceAccessChecker;

    @GetMapping
    public ApiResponse<PageResponse<TaskResponse>> getTasks(@LoginUser Long userId,
                                                            @PathVariable Long workspaceId,
                                                            Pageable pageable) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(taskService.getTasks(workspaceId, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TaskResponse> createTask(@LoginUser Long userId,
                                                @PathVariable Long workspaceId,
                                                @Valid @RequestBody TaskCreateRequest request) {
        workspaceAccessChecker.checkMember(workspaceId, userId);
        return ApiResponse.ok(taskService.createTask(workspaceId, userId, request));
    }
}
```

`@LoginUser`는 A-01에서 만듭니다. 그 전까지는 `@RequestHeader("X-User-Id") Long userId`로 임시 개발합니다.

### 8. 백엔드 코드 규칙

**Entity**
- `@Setter`를 쓰지 않습니다. 값 변경은 의미 있는 이름의 메서드로 합니다. (`task.changeStatus(...)`, `meeting.confirm(...)`)
- 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 막습니다.
- Enum 컬럼은 `@Enumerated(EnumType.STRING)`로 저장합니다.
- 다른 파트의 Entity는 연관관계(`@ManyToOne`)로 참조하지 않고 ID(`Long`)로만 가집니다.
- 생성·수정 시각이 필요하면 `BaseTimeEntity`를 상속합니다.

**Service**
- 클래스에 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional`을 붙입니다.
- 권한 검사(`checkMember`, `checkOwner`)는 Controller에서 하고, OWNER 같은 업무 규칙 검사는 Service에서 합니다.
- Entity는 Service 안에서만 다루고, Controller에는 DTO를 반환합니다.
- 다른 파트의 Repository를 주입받지 않습니다. 다른 파트 데이터가 필요하면 그 파트의 Service를 주입받습니다.

**입력 검증**
- 요청 DTO에 `@NotBlank`, `@NotNull`, `@Size` 등을 붙이고 Controller에서 `@Valid`로 검사합니다.
- 실패하면 `GlobalExceptionHandler`가 `INVALID_INPUT`(400)으로 변환합니다.

**외부 API 호출**
- 외부 API는 각 패키지의 `client` 클래스에서만 호출합니다. 호출에는 Spring의 `RestClient`를 씁니다.
- 연결·응답 타임아웃을 반드시 설정합니다. (기본: 연결 5초, 응답 10초. Gemini 분석은 30초)
- 실패하면 `EXTERNAL_API_ERROR`(502)로 바꿔 던집니다. 외부 장애가 시스템 전체 오류로 번지지 않게 하고, 이미 저장된 데이터는 유지합니다.
- 토큰 만료(401)를 받으면 `IntegrationService`가 갱신을 시도하고, 실패하면 `REAUTH_REQUIRED`로 알립니다.

**로그**
- `@Slf4j`로 남깁니다. `System.out.println`은 쓰지 않습니다.
- **토큰, 비밀값, 메일·메시지 본문을 로그에 남기지 않습니다.** ID(`workspaceId`, `contentRefId` 등)만 남깁니다.
- 예상한 업무 오류(`BusinessException`)는 `warn` 이하, 예상하지 못한 오류만 `error`로 남깁니다.

**시간**
- `LocalDate`, `LocalDateTime`을 쓰고 서울 시간 기준으로 저장합니다.
- 외부 API의 UTC 시간은 `client`에서 서울 시간으로 바꿔서 넘깁니다.

**테스트**
- 도메인 메서드(진행률, 위험도, 회의 시간 점수 등)와 복잡한 Service 로직에는 단위 테스트를 씁니다.
- PR 전에 `./gradlew build`(테스트 포함)가 통과해야 합니다.

### 9. DB 규칙

- DB 스키마는 **Flyway 마이그레이션 파일로만** 바꿉니다. (`ddl-auto`는 `validate`)
- 파일 위치·이름: `backend/src/main/resources/db/migration/V{번호}__{설명}.sql`
  - 예: `V2__add_meeting_video_link.sql`
- **이미 `main`에 머지된 마이그레이션 파일은 절대 수정하지 않습니다.** 고칠 것이 있으면 새 번호 파일을 추가합니다.
- 번호가 겹치지 않게, PR을 올리기 직전에 `main`의 마지막 번호를 확인하고 그다음 번호로 정합니다. 다른 PR이 먼저 같은 번호로 머지되면 내 파일 번호를 올립니다.
- 마이그레이션 PR은 다른 두 명이 승인한 뒤 머지합니다.
- Entity를 바꾸면 같은 PR에 마이그레이션 파일을 함께 넣습니다.

### 10. 파트 간 규칙

- 다른 파트의 테이블을 직접 조회하거나 수정하지 않습니다. 담당자의 Service 메서드를 호출합니다.
- 다른 파트의 클래스를 직접 고치지 않습니다. 필요한 메서드가 있으면 담당자에게 추가를 요청합니다. (이슈 또는 메시지)
- 파트 간 호출 메서드는 [docs/shared-interfaces.md](docs/shared-interfaces.md)에 적힌 것만 씁니다.
  - 시그니처를 바꾸려면 이 문서를 수정하는 PR을 올리고 다른 두 명이 승인합니다.
  - 상대 구현이 끝나기 전에는 문서의 시그니처로 가짜 구현을 두고 개발합니다.
- `content_ref` 테이블은 B가 담당합니다. A·C는 `ContentRefService`를 통해서만 저장·조회합니다.
- 개인 콘텐츠(Gmail, Calendar, AI 후보, 개인 할 일)는 `ownerUserId` 조건이 들어간 메서드로만 조회합니다. 다른 사람의 개인 콘텐츠가 응답에 섞이면 안 됩니다.
- 토큰은 `IntegrationService.getAccessToken()`으로만 받습니다. 토큰을 DTO, 응답, 로그에 넣지 않습니다.

### 11. 프론트엔드 규칙

```
frontend/src
├── api/client.ts      # 공통 API 클라이언트 (세 명 공동)
├── layouts/           # 공통 레이아웃 (세 명 공동)
├── components/        # 여러 화면에서 쓰는 공통 컴포넌트 (세 명 공동)
├── pages/{기능}/       # 화면 (각 파트)
│   ├── XxxPage.tsx
│   ├── components/    # 이 기능에서만 쓰는 컴포넌트
│   └── api.ts         # 이 기능의 API 호출 함수
└── router.tsx         # 라우트 (화면을 추가할 때 각자 한 줄씩 추가)
```

- 각 화면 파일 첫 줄에 UI 번호와 담당 파트를 적습니다. (예: `// UI-09 Task (담당: C)`)
- API는 `fetch`를 직접 쓰지 않고 `api/client.ts`의 `http`, `ws()`를 씁니다. 실패하면 `ApiError`가 던져지므로 `code`로 처리합니다.
  ```ts
  // pages/task/api.ts
  export const getTasks = (workspaceId: string, page = 0) =>
    http.get<PageData<TaskResponse>>(ws(workspaceId, `/tasks?page=${page}`));
  ```
- 백엔드 응답 DTO와 같은 이름의 TypeScript 타입을 `pages/{기능}/api.ts`에 정의합니다. (`TaskResponse` 등)
- 컴포넌트·파일 이름은 PascalCase(`TaskList.tsx`), 함수·변수는 camelCase를 씁니다.
- 모든 화면에서 다음 세 상태를 처리합니다: **불러오는 중**, **실패(재시도 버튼)**, **데이터 없음**.
- 삭제처럼 되돌릴 수 없는 동작은 확인 창을 띄웁니다. (UI 명세의 "확인 절차")
- 공통 폴더(`api/`, `layouts/`, `components/`)를 바꾸는 PR은 다른 두 명이 승인한 뒤 머지합니다.
- 새 라이브러리를 추가할 때는 PR 본문에 이유를 적습니다.

### 12. AI 기능 규칙

- AI가 만든 결과(논의 분석, 일정·Task 후보)는 **사용자가 검토·확정하기 전에 외부 서비스나 Task에 반영하지 않습니다.** (설계 제약사항)
- 확정 후 연계(Notion, Task, Calendar)는 단계별로 성공·실패를 기록하고, 실패한 단계만 다시 시도할 수 있게 합니다.
- Gemini 프롬프트는 코드에 문자열로 흩어 두지 않고 `resources/prompts/`에 파일로 둡니다.
- Gemini 응답은 JSON 형식으로 받고, 형식이 맞지 않으면 실패로 처리합니다.

### 13. 성능 목표 (설계 제약사항)

| 기능 | 목표 |
|---|---|
| 일반 페이지 로딩 | 2초 이내 |
| 외부 서비스 콘텐츠 조회 | 5초 이내 |
| Slack 논의 분석 | 30초 이내 |
| 회의 시간 추천 | 3초 이내 |
| Task 협업 맥락 조회 | 5초 이내 |
| 동시 사용자 50명 | 오류율 1% 미만 |

- 화면에서 외부 API를 매번 직접 부르지 않고, 동기화해 둔 `content_ref`를 먼저 보여줍니다.
- 목록은 반드시 페이지 단위로 조회합니다. 반복문 안에서 쿼리나 외부 API를 부르지 않습니다(N+1).

### 14. 개발 환경

- Java: 21
- Spring Boot: 4.1.x
- Node: 22 LTS 이상
- PostgreSQL, ChromaDB: `docker-compose.yml`에 적은 이미지 버전을 그대로 사용

Spring Boot 3.x는 2026년 6월 30일에 무료 지원(OSS)이 끝나서 4.1.x로 정했습니다(OSS 지원은 2027년 7월까지).

- 포매터 설정(`.editorconfig`)을 저장소에 포함합니다. 줄바꿈은 LF(`.gitattributes`로 강제)입니다.
- 들여쓰기: Java·SQL·Gradle 4칸, 그 외 2칸. Java 한 줄 최대 120자.
- 비밀값은 `.env`에만 둡니다. `.env`는 Git에 올리지 않고, 항목 이름만 적은 `.env.example`을 올립니다. 새 항목이 생기면 `.env.example`에도 이름을 추가하고 팀에 알립니다.

### 15. 진행 현황

- [x] Java / Spring Boot / Node 버전 확정
- [x] 목록 API 기본 `size` 확정 (20)
- [x] 공통 Entity, Enum, 응답 형식, `ErrorCode` 초안 생성
- [x] `.editorconfig`, `docker-compose.yml`, `.env.example`, Flyway 초기 마이그레이션 파일 생성
- [x] 파트 배정, 파트 간 공유 인터페이스 결정
- [ ] 세 명 모두 로컬에서 백엔드·프론트 실행 확인 (COMMON-02)
- [ ] 공유 인터페이스 시그니처 커밋 (COMMON-01)
