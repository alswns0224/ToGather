# 파트 간 공유 인터페이스

다른 파트가 호출하는 메서드만 모았습니다. 설계명세서 4.4 Design Classes를 기준으로 했고, 명세서와 다르게 정한 부분은 **[결정 N]** 으로 표시했습니다. 구현할 때는 명세서보다 이 문서가 우선입니다.

- 제공하는 쪽이 **시그니처와 빈 구현(또는 가짜 데이터)** 을 먼저 커밋합니다. (이슈 COMMON-01)
- 호출하는 쪽은 실제 구현이 끝나기 전에도 그 시그니처로 개발합니다.
- 시그니처를 바꾸려면 이 문서를 수정하는 PR을 올리고 다른 두 명이 승인한 뒤 머지합니다.

---

## 0. 명세서와 다르게 정한 것

| # | 문제 | 결정 |
|---|---|---|
| 1 | **`content_ref` 테이블의 담당자.** 다섯 서비스가 모두 쓰지만 명세서에 주인이 없고, 저장 메서드(`upsertMessage` 등)가 Repository에 붙어 있음 | B가 `ContentRefService`를 제공. A·C는 Repository를 직접 쓰지 않고 이 서비스 호출 |
| 2 | **파트 간 반환 타입.** 명세서의 `TaskService.getTask()`는 Entity(`Task`)를 반환 | 다른 파트에 주는 메서드는 DTO(record)를 반환. 같은 파트 안에서는 Entity 사용 가능 |
| 3 | **`TaskService.getAgendasByTask()`가 A의 Entity(`MeetingAgenda`)를 반환.** README 10번(다른 파트 테이블 직접 조회 금지) 위반 | A의 `MeetingService.getMeetingsByTask(taskId)`로 대체하고 C는 이것만 호출 |
| 4 | **Calendar·Notion 생성 메서드에 `workspaceId`, `userId`가 없음.** 누구의 토큰으로 호출할지 알 수 없음 | 모든 외부 호출 메서드 앞에 `workspaceId`, `userId` 추가 |
| 5 | **회의 제안(MEETING_PROPOSAL) 연계 결과.** `outputTargetId`가 Meeting ID인지 Calendar 일정(ContentRef) ID인지 불명확 | SD-08-05대로 Calendar 일정을 바로 등록하고 `outputTargetId` = ContentRef ID |
| 6 | **사용자 정보 조회.** 담당자·참여자 이름을 표시하려면 `users` 테이블이 필요한데 A 소유 | A가 `UserService.getUsers(ids)` 제공 |
| 7 | **임베딩 API.** 통합 검색에 임베딩이 필요함 | 주제제안서 기술 스택대로 **BGE-M3**(Ollama로 실행)로 임베딩하고 **Chroma**에 저장한다. `search.client.EmbeddingClient` / `VectorStore`. Gemini는 논의 분석·후보 추출에만 쓴다. C-07·08 이관 후 임베딩·통합 검색은 B 담당 |
| 8 | **결정사항 조회.** Task 협업 맥락(C)에 근거 결정사항을 보여줘야 함 | B가 `AnalysisService.getItemSummary(itemId)` 제공 |

---

## 1. A가 제공 (백민준) — ✅ 구현 완료

### 로그인 사용자 · 권한

```java
// Controller 파라미터로 현재 로그인 사용자 ID를 주입받는다. 로그인하지 않았으면 401
@GetMapping("/api/workspaces/{workspaceId}/tasks")
public ApiResponse<...> getTasks(@LoginUser Long userId, @PathVariable Long workspaceId)

// common.auth.WorkspaceAccessChecker
void checkMember(Long workspaceId, Long userId);   // 멤버가 아니면 403 WORKSPACE_ACCESS_DENIED
void checkOwner(Long workspaceId, Long userId);    // OWNER가 아니면 403 WORKSPACE_OWNER_REQUIRED
```

사용처: 모든 파트의 모든 Controller

### 사용자 · 팀원 [결정 6]

```java
// UserService
UserSummary getUser(Long userId);
List<UserSummary> getUsers(Collection<Long> userIds);   // 없는 ID는 결과에서 빠짐
record UserSummary(Long userId, String name, String email, String profileImage) {}

// WorkspaceMemberService
List<Long> getMemberUserIds(Long workspaceId);
boolean isMember(Long workspaceId, Long userId);
```

사용처: C(Task 담당자 표시·검증), B(후속 작업 제안 담당자, 스레드 참여자 매핑)

### 외부 서비스 토큰 (IntegrationService)

```java
// 유효한 Access Token을 반환한다. 만료됐으면 Refresh Token으로 갱신해서 반환한다.
// 연결이 없으면 CONNECTION_NOT_FOUND, 갱신에 실패하면 REAUTH_REQUIRED 예외
String getAccessToken(Long workspaceId, Long userId, Provider provider);

// 워크스페이스에서 선택한 연동 대상(Slack 채널, Notion DB, Drive 폴더)
// [결정] 대상이 여러 개일 수 있으므로 List로 반환 (명세서는 단건)
List<String> getConnectionTargets(Long workspaceId, Provider provider);

// 연결이 없으면 빈 값 (선택적으로 쓰는 기능: 예) 캘린더가 있으면 자동 반영)
Optional<String> findAccessToken(Long workspaceId, Long userId, Provider provider);
boolean hasConnection(Long workspaceId, Long userId, Provider provider);
```

- 팀 서비스(Slack·Notion·Drive)는 본인 연결이 없으면 워크스페이스에서 다른 팀원이 연결한 토큰을 준다.
- 개인 서비스(Gmail·Calendar)는 본인 연결만 쓴다.
- 외부 API가 401을 주면 `BusinessException(REAUTH_REQUIRED)`로 바꿔 던지면 화면에 재인증 안내가 나간다.

사용처: B(Slack, Notion), C(Drive, Gmail)
규칙: 받은 토큰은 DTO, 응답, 로그에 넣지 않는다. (README 10번)

### 연결 사용 중 해제 보호 (ConnectionOperationRegistry)

```java
// integration.service.ConnectionOperationRegistry
// HTTP 요청에는 Filter가 자동 적용한다. 별도 스레드의 작업은 전체 작업 범위를 감싼다.
ConnectionOperationRegistry.Scope openScope();  // Scope implements AutoCloseable
```

- B의 `SyncService`와 자동 `AnalysisScheduler`에서 `try (var scope = operations.openScope())`로 사용한다.
- 기존 `IntegrationService` 토큰 조회 시 실제 선택한 연결을 자동 예약한다. 호출자가 토큰이나 다른 파트 Entity를 추적할 필요는 없다.
- 작업 종료·실패 시 예약을 반납하며, 트랜잭션이 남아 있으면 종료 후 반납한다. HTTP 스트리밍은 비동기 응답 완료까지 유지한다.
- 사용 중인 연결의 해제 또는 해제 중인 계정으로의 새 토큰 요청은 `409 CONNECTION_IN_USE`다. 서로 다른 계정은 독립적으로 동작한다.
- Google은 같은 계정의 Drive·Calendar·Gmail 권한 폐기가 영향을 공유하므로 함께 보호한다. 폐기 성공 후 같은 계정의 나머지 연결은 `REAUTH_REQUIRED`로 표시한다.
- 현재 서버 한 대에서 실행 중인 작업을 보호한다. 서버를 여러 대로 늘릴 때는 공유 저장소 기반 예약으로 확장해야 한다.
- Registry의 `use(ServiceConnection)`와 `beginDisconnect(...)`는 A 파트 내부 구현이며 다른 파트에서 Entity를 넘기며 호출하지 않는다.

### Calendar (CalendarContentService)

기존 팀 일정 수정·삭제는 `ContentRefService.getChannelId`로 저장된 캘린더를 확인하고
`IntegrationService.findTargetAccess(workspaceId, provider, targetId)`로 그 대상을 선택한 연결을 사용한다.
연결이 없으면 실패를 알리며 다른 캘린더로 바꿔 쓰지 않는다. 기존 두 인자 메서드는 유지한다.

```java
// [결정 4] workspaceId, userId 추가. 팀 캘린더(연동 관리에서 선택)에 등록, 없으면 요청자의 기본 캘린더
ContentRefDTO createContent(Long workspaceId, Long userId, CalendarEventRequest request);
// endAt이 null이면 시작 후 1시간 (명세서에 없던 endAt 추가)
record CalendarEventRequest(String title, LocalDateTime startAt, LocalDateTime endAt, List<Long> participants, String description) {}

// 개인 일정 (AI 업무 보조 승인 시). [결정 4] workspaceId 추가 — Calendar 연결이 워크스페이스 단위라서
ContentRefDTO createPersonalEvent(Long workspaceId, Long userId, PersonalEventRequest request);
record PersonalEventRequest(String title, LocalDateTime startAt, LocalDateTime endAt) {}   // endAt null이면 1시간

// 원본이 지워졌으면 isOriginDeleted=true와 보관 사본
ContentDetailDTO getContentDetail(Long contentRefId);
```

사용처 B(논의 분석 회의 일정 등록 SD-08-05, AI 업무 보조 일정 승인 SD-13-01, 통합 검색 상세)

### 회의 (MeetingService) [결정 3]

```java
List<MeetingSummary> getMeetingsByTask(Long taskId);
record MeetingSummary(Long meetingId, String title, MeetingStatus status, LocalDateTime startAt) {}
```

사용처: C(Task 협업 맥락 조회 SD-11-01)

회의 안건 API는 기존 Task 연결과 직접 입력 제목을 함께 지원한다.
- 회의 생성 요청: 기존 `agendaTaskIds`를 유지하며 선택적으로 `agendaTitles: string[]`를 보낸다. 직접 입력 안건은 1~200자, 최대 50개이고, 입력 순서대로 저장한 뒤 Task 안건을 이어 붙인다.
- 안건 추가·수정 요청: `{ "taskId": 123 }` 또는 `{ "title": "발표 순서 논의" }` 중 하나를 보낸다. 둘 다 보내거나 빈 제목을 보내면 400이다.
- 상세 응답의 `agendas[]`에는 `title`이 추가된다. 직접 입력은 `taskId/task = null`, `title`에 제목이 있고, Task 연결은 기존 필드와 `title = null`을 사용한다.
- 직접 입력 안건은 Task를 만들지 않는다. 확정된 회의의 Calendar 설명에도 반영하며, 취소된 회의의 안건은 수정할 수 없다.


---

## 2. B가 제공 (정철준)

> B-01에서 `getPersonalContents`, `markSyncFailed`를 추가하고, DTO의 `lastModifiedAt`·`messageCount`·`syncFailed`를 실제 값으로 채우도록 바꿨습니다.

### ContentRef 저장 · 조회 [결정 1]

```java
// ContentRefService
ContentRefDTO upsert(ContentRefUpsert command);  // workspaceId + provider + originId 기준. 있으면 최신 원본으로 갱신
// 개인 Slack DM은 ownerUserId 필수, originId는 소유자·연결·Slack 계정별 접두사를 포함한다.
// 같은 originId의 소유자를 바꾸는 Slack upsert는 거절한다. 팀 조회·자료 연결·검색·논의 분석에서 제외한다.
ContentRefDTO upsertDriveFile(ContentRefUpsert command, Long fileSize); // GOOGLE_DRIVE/FILE 전용. 바이트 수, null=미확인
// 기존 11개 인자 생성자도 그대로 쓸 수 있다. (수정 시각·작성자 없이 저장)
record ContentRefUpsert(Long workspaceId, Long ownerUserId, Provider provider, ContentType contentType,
                        String originId, String channelId, Long parentRefId,
                        String title, String content, String deepLink, LocalDateTime createdAt,
                        LocalDateTime lastModifiedAt, String authorExternalId, String authorName) {}

ContentRefDTO getContentRef(Long contentRefId);
List<ContentRefDTO> getContentRefs(Collection<Long> contentRefIds);
// C-06 협업 맥락. 지정한 워크스페이스의 팀 콘텐츠만 포함하며 개인 콘텐츠는 제외한다.
Map<Long, ContentState> getTeamContentStates(Long workspaceId, Collection<Long> contentRefIds);
record ContentState(LocalDateTime createdAt, boolean originDeleted, boolean driveTrashed) {}
Optional<ContentRefDTO> findByOrigin(Long workspaceId, Provider provider, String originId);
ContentOwner getOwner(Long contentRefId);
String getChannelId(Long contentRefId);  // Calendar: 원래 등록된 캘린더 ID. 소속·개인 권한 확인 후 사용
void updateContent(Long contentRefId, String title, String content);
void markOriginDeleted(Long contentRefId);
void markSyncFailed(Long workspaceId, Provider provider, String originId);   // 동기화 중 이 콘텐츠 저장 실패 표시

// 개인 콘텐츠(Gmail, Calendar)는 ownerUserId 조건이 들어간 메서드만 사용 (README 10번). since가 null이면 전체
List<ContentRefDTO> getPersonalContents(Long ownerUserId, Provider provider, LocalDateTime since);
// 한 워크스페이스에서 연결한 개인 콘텐츠만 (C-05 Gmail 요청). 다른 워크스페이스의 메일·일정이 섞이지 않는다
List<ContentRefDTO> getPersonalContents(Long workspaceId, Long ownerUserId, Provider provider, LocalDateTime since);
```

사용처 A(Calendar 일정 저장), B(임베딩·통합 검색), C(Drive·Gmail 저장, 협업 맥락)

### 공통 DTO (명세서 CD-DTO)

```java
record ContentRefDTO(Long contentRefId, Provider provider, ContentType contentType, String externalId,
                     String title, String content, String deepLink, String fileFormat,
                     LocalDateTime lastModifiedAt, int messageCount, boolean syncFailed, Long fileSize) {}

record ContentDetailDTO(Long contentRefId, Provider provider, String title, String body, String deepLink,
                        String previewLink, LocalDateTime startAt, List<String> participants,
                        String description, boolean isOriginDeleted) {}
```

위치: `content.dto` (콘텐츠 서비스가 모두 같이 사용)

`fileSize`는 바이트 수이며 0과 null(크기 미확인)을 구분한다. 기존 11개 인자 `ContentRefDTO` 생성자는 유지한다.
`ContentRefUpsert` 시그니처는 그대로이며 일반 `upsert`는 저장된 크기를 보존한다. Drive 수집·업로드만
`upsertDriveFile`로 크기까지 갱신한다(null을 넘기면 미확인으로 갱신). V11은 크기 필드를 추가하고
Drive의 동기화 커서를 초기화해 다음 동기화에서 기존 파일 크기도 채운다. 다른 제공자의 커서는 유지한다.

### Drive 폴더 경로 (C-03)

- `GET /api/workspaces/{workspaceId}/drive/folders/hierarchy`: 본인 Google 계정으로 볼 수 있는 연동 폴더와 원본 부모 경로.
  응답은 `DriveFolderHierarchyResponse(folderId, name, ancestors: List<DriveFolderResponse(folderId, name)>)`.
- `ancestors`는 최상위 연동 폴더부터 직계 부모까지의 순서이며, 연동하지 않은 중간 폴더는 유지하고 연동 범위 밖 상위 경로는 제외한다.
- 화면은 부모가 함께 연결된 하위 폴더를 최상위 목록에서 중복 표시하지 않는다. 기존 `/folders`·`/folders/names`와 업로드 대상은 유지한다.

### Drive 업로드 선택 (C-03)

- `GET /api/workspaces/{workspaceId}/drive/upload-candidates?folderId=&name=`: 같은 이름의 선택 폴더 내 파일 목록.
  응답은 `DriveUploadCandidate(fileId, name, mimeType, fileSize, lastModifiedAt, version, deepLink, overwriteAllowed)`.
- `POST /api/workspaces/{workspaceId}/drive/files`: 기존 `folderId`, `file`에 `mode`를 추가했다.
  생략 시 `CREATE_NEW`이며 기존 Task 산출물 업로드 호출은 그대로 사용한다.
  `ASK`는 동명 파일이 있으면 `DRIVE_FILE_NAME_CONFLICT`(409), `CREATE_NEW`는 새 파일,
  `OVERWRITE`는 필수 `targetFileId`, `expectedVersion`으로 선택한 기존 파일 내용을 교체한다.
- 덮어쓰기는 파일 ID·ContentRef ID·Task 연결과 제출을 보존한다. 선택 후 버전·이름 변경은
  `DRIVE_FILE_CHANGED`(409)이며 다시 조회·선택한다. 이동·휴지통, 수정 불가, Google 고유 문서 등은 거절한다.
- 버전은 업로드 직전 확인하며 같은 서버의 동일 폴더·이름 업로드는 중복 실행을 막는다.
  외부 편집이나 여러 서버까지 원자적으로 잠그는 기능은 아니다.

### Slack · Notion 콘텐츠

```java
// SlackContentService
List<ContentRefDTO> getContents(Long workspaceId);      // 스레드 root 최근 200개
// [변경] workspaceId 추가 — Slack 연결이 워크스페이스 단위라서. B 내부(AI 업무 보조)에서만 사용
List<ContentRefDTO> getContentsByUser(Long workspaceId, Long userId);  // 본인이 쓰거나 멘션된 최근 14일 메시지
ContentDetailDTO getContentDetail(Long contentRefId);   // 스레드면 participants에 참여자 이름

// NotionContentService
List<ContentRefDTO> getContents(Long workspaceId);        // 삭제되지 않은 페이지, 최근 수정순
ContentDetailDTO getContentDetail(Long contentRefId);     // body는 "# 제목, - 목록" 형식의 텍스트
```

사용처 C(Task 관련 자료 후보 SD-09-05), B(통합 검색 결과 상세 SD-12-01)

### Gemini (GeminiClient) [결정 7]

위치: `common.ai.GeminiClient` (여러 파트가 같이 쓰므로 common). 실패하면 `AI_CALL_FAILED`(502), 429·5xx·타임아웃은 2번까지 다시 시도.
프롬프트는 `resources/prompts/{이름}.txt`에 두고 `PromptTemplates.render(이름, 변수)`로 읽는다.

```java
String generate(String prompt);                 // 텍스트 생성 (논의 분석, 후보 추출)
<T> T generateJson(String prompt, Class<T> type); // JSON 응답을 객체로 변환
float[] embed(String text);                     // Gemini 임베딩 (통합 검색에는 쓰지 않음, 아래 BGE-M3 사용)
List<float[]> embedAll(List<String> texts);
```

사용처 B(논의 분석, AI 업무 보조)

### 임베딩·통합 검색 (EmbeddingClient · VectorStore) [결정 7] — B (C-07·08 이관)

위치: `search.client`. 임베딩은 **BGE-M3**(다국어 모델, Ollama로 실행), 벡터 저장·유사도 검색은 **Chroma**.
연결할 수 없으면 `SEARCH_UNAVAILABLE`(503). 설정은 `togather.search.*` (`OLLAMA_URL`, `EMBEDDING_MODEL`, `CHROMA_URL`, `SEARCH_ENABLED`).

```java
// EmbeddingClient (OllamaEmbeddingClient: POST {OLLAMA_URL}/api/embed, model=bge-m3)
List<float[]> embedAll(List<String> texts);
float[] embed(String text);

// VectorStore (ChromaVectorStore: Chroma REST v2, 코사인 거리, 벡터 ID = "cr-{contentRefId}")
void upsert(List<VectorDoc> docs);              // VectorDoc(contentRefId, workspaceId, provider, vector)
void delete(Collection<Long> contentRefIds);
List<VectorHit> searchVectors(float[] vector, Long workspaceId, Collection<String> providers, int limit);
```

- 색인(`ContentIndexService`): 1분마다 새로 저장되거나 원본 수정 시각이 바뀐 Slack·Notion·Drive 콘텐츠를 모아 임베딩하고,
  원본에서 지워진 콘텐츠는 Chroma에서 뺀다. 임베딩한 콘텐츠는 `content_embedding`(V8)에 기록한다.
- 검색(`SearchService`): `GET /api/workspaces/{id}/search?q=&providers=SLACK,NOTION&limit=20` → 관련도순 결과 + 연결된 Task(`TaskContextService.getTasksByContents`)

### 증분 동기화 (SyncAdapter) — C의 Drive·Gmail도 사용

Slack 1:1 DM은 본인 사용자 토큰의 `im:read`, `im:history` 권한으로 별도 개인 수집 경로를 사용한다.
`IntegrationService.findOwnAccess(workspaceId, userId, provider)`는 본인 연결의
`Optional<OwnAccess(connectionId, externalAccountId, scope, accessToken)>`만 반환하며 팀원 토큰으로 대체하지 않는다.
OwnAccess의 문자열 표현에는 토큰·권한을 넣지 않는다. 기존 토큰 선택 메서드의 동작은 유지한다.
개인 DM 동기화 상태는 기존 `sync_state`에 ownerUserId와 계정·대화별 키로 저장하되 팀 Slack 상태 응답에는 포함하지 않는다.
V12는 커서를 TEXT로 확장한다. 고정된 수집 구간·페이지 커서·확인한 메시지 목록을 보존해 이어받고 완료 시 삭제를 판정한다.
AI 후보 탐지 전 첫 수집 후에는 5분 스케줄러가 이어서 수집한다. 수집량이 많으면 요청 예산 내에서 순환하며 계속한다.
DM 권한·인증·통신·요청 한도 문제는 탐지 응답 `warnings`에 안내하고 다른 출처의 탐지는 계속한다.
`DetectResultResponse(scannedMessages, created, warnings)`는 기존 2개 인자 생성자를 유지한다.
AI의 분석 여부는 메시지 ID·수정 시각별로 기록해 나중에 수집된 과거 메시지도 처리한다. 이 기록은 서버 재시작 시 초기화된다.

```java
// sync.service.SyncAdapter를 구현해 @Component로 등록하면 스케줄러(5분 간격)와 즉시 동기화가 자동으로 사용한다.
Provider provider();
List<SyncTarget> findTargets(Long workspaceId, Long requestedBy);   // 팀: SyncTarget.team(폴더 ID), 개인: SyncTarget.personal(userId)
SyncResult sync(SyncContext context);   // context.since()가 null이면 처음 동기화. 실패하면 예외를 그대로 던진다
record SyncContext(Long workspaceId, String targetId, Long ownerUserId, LocalDateTime since, String syncCursor, Long requestedBy) {}
record SyncResult(int savedCount, LocalDateTime syncedUntil, String syncCursor) {}
// syncCursor: 시각 대신 서비스가 주는 위치로 이어받을 때 사용 (예: Gmail historyId).
//   SyncResult로 돌려준 값을 sync_state.sync_cursor에 저장했다가 다음 SyncContext로 넘겨준다. 처음이면 null.
//   쓰지 않는 어댑터는 기존 생성자 new SyncContext(5개 인자) / new SyncResult(savedCount, syncedUntil)를 그대로 쓰면 된다 (cursor = null).

// SyncService
void syncIfStale(Long workspaceId, Provider provider, Long requestedBy, Duration maxAge);  // 화면 진입 시
List<SyncStatusResponse> syncNow(Long workspaceId, Provider provider, Long requestedBy);  // POST /api/workspaces/{id}/sync/{provider}
```

- 실패하면 저장된 콘텐츠는 그대로 두고 `sync_state`에 실패만 기록한다. 다음 동기화에서 같은 범위를 다시 가져온다.
- 연속 실패한 대상은 자동 동기화를 5분 → 10분 → … 최대 2시간까지 늦춘다.

### 결정사항 [결정 8]

```java
// AnalysisService (analysis.dto.AnalysisItemDTO)
AnalysisItemDTO getItemSummary(Long itemId);   // 없으면 ANALYSIS_ITEM_NOT_FOUND
record AnalysisItemDTO(Long itemId, ItemType itemType, String content, List<Long> evidenceContentRefIds) {}
```

사용처: C(Task 협업 맥락에서 근거 결정사항 표시, UI-09 "결정사항에서 생성된 Task는 근거를 함께 표시")

---

## 3. C가 제공 (이찬서)

> A의 회의 안건에 필요해서 `TaskService.getTask`, `getTasks(Collection<Long>)`, `getTasksInProgress`와 `TaskSummary`를 먼저 만들어 두었습니다.
> C가 이어서 CRUD와 나머지 메서드를 구현합니다.

### Task (TaskService)

```java
// 논의 분석의 후속 작업으로 Task 생성 (SD-08-04). [결정 2] 생성된 taskId만 반환
Long createTask(Long workspaceId, TaskCommand command);
record TaskCommand(String title, String description, Long assigneeId, LocalDate dueDate, TaskStatus status,
                   List<DeliverableInput> deliverables, Long originAnalysisItemId) {}
record DeliverableInput(String name, String ext) {}

// [결정 2] Entity 대신 DTO 반환
TaskSummary getTask(Long taskId);
List<TaskSummary> getTasks(Collection<Long> taskIds);     // 여러 Task 한 번에 조회 (회의 안건 목록). 없는 ID는 결과에서 빠짐
List<TaskSummary> getTasksInProgress(Long workspaceId);  // 회의 안건 후보 (UI-10)
List<TaskSummary> getMyTasks(Long userId);              // AI 업무 보조 중복 후보 제외
record TaskSummary(Long taskId, Long workspaceId, String title, Long assigneeId, LocalDate dueDate,
                   TaskStatus status, int progress, RiskLevel riskLevel) {}
```

사용처: A(회의 안건 SD-10-04·05), B(후속 Task 생성 SD-08-04, AI 업무 보조 SD-13-02)

> B는 `analysis.service.TaskGateway`에서만 `createTask`, `getMyTasks`를 호출합니다. (C-01 연결 완료)

### 협업 맥락 (TaskContextService)

```java
TaskContext getTaskContext(Long taskId);                         // 안건별 관련 자료 (SD-10-05)
Map<Long, List<TaskSummary>> getTasksByContents(List<Long> contentRefIds);
```

사용처: A(회의 안건 관련 자료 조회 SD-10-05)

### Gmail (GmailContentService)

```java
// 오래된 경우 먼저 동기화한 뒤 해당 워크스페이스·사용자의 메일을 조회한다.
List<ContentRefDTO> getContentsByUser(Long workspaceId, Long userId);
// 저장된 메일 중 since 이후 변경된 범위를 조회한다.
List<ContentRefDTO> getContentsByUser(Long workspaceId, Long userId, LocalDateTime since);
```

사용처: B(AI 업무 보조 일정·할 일 후보 추출 SD-13-01·02)

> B는 C가 `ContentRefService.upsert`로 저장한 메일(provider=GMAIL, ownerUserId=본인)을 content_ref에서 직접 읽습니다.
> 그래서 C는 메일을 저장할 때 `ownerUserId`, `title`(제목), `content`(본문), `authorName`(보낸 사람), `createdAt`을 채워 주세요.
> 첫 동기화는 `GmailContentService.getContentsByUser(workspaceId, userId)` 또는 `SyncService.syncNow(workspaceId, GMAIL, userId)` 호출로 시작한다. AI 후보 탐지 전 수집 호출 연결은 B 파트 후속 작업이다.

---

## 4. 호출 관계 요약

```
            ┌──────────── getAccessToken / @LoginUser / checkMember ────────────┐
            │                                                                    │
            ▼                                                                    ▼
   B (Slack·Notion·AI)  ── createTask, getMyTasks ──▶  C (Drive·Gmail·Task)
     │      ▲    │                                         │     │
     │      │    └── createContent(Calendar),             │     └── getMeetingsByTask ──▶ A
     │      │        createPersonalEvent ──────────▶ A    │
     │      └── getContents, getItemSummary, embed ───────┘
     │
     └── ContentRefService (A·C가 콘텐츠 저장 시 호출)

   A (회의) ── getTask, getTasks, getTasksInProgress, getTaskContext ──▶ C
```
