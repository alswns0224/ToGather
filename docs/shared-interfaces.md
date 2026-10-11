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
| 7 | **임베딩 API.** 통합 검색(C)에 임베딩이 필요하고 GeminiClient는 B 담당 | B의 `GeminiClient`에 `embed()` 포함 |
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

### Calendar (CalendarContentService)

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

사용처: B(논의 분석 회의 일정 등록 SD-08-05, AI 업무 보조 일정 승인 SD-13-01), C(통합 검색 상세)

### 회의 (MeetingService) [결정 3]

```java
List<MeetingSummary> getMeetingsByTask(Long taskId);
record MeetingSummary(Long meetingId, String title, MeetingStatus status, LocalDateTime startAt) {}
```

사용처: C(Task 협업 맥락 조회 SD-11-01)

---

## 2. B가 제공 (정철준)

> B-01에서 `getPersonalContents`, `markSyncFailed`를 추가하고, DTO의 `lastModifiedAt`·`messageCount`·`syncFailed`를 실제 값으로 채우도록 바꿨습니다.

### ContentRef 저장 · 조회 [결정 1]

```java
// ContentRefService
ContentRefDTO upsert(ContentRefUpsert command);  // workspaceId + provider + originId 기준. 있으면 최신 원본으로 갱신
// 기존 11개 인자 생성자도 그대로 쓸 수 있다. (수정 시각·작성자 없이 저장)
record ContentRefUpsert(Long workspaceId, Long ownerUserId, Provider provider, ContentType contentType,
                        String originId, String channelId, Long parentRefId,
                        String title, String content, String deepLink, LocalDateTime createdAt,
                        LocalDateTime lastModifiedAt, String authorExternalId, String authorName) {}

ContentRefDTO getContentRef(Long contentRefId);
List<ContentRefDTO> getContentRefs(Collection<Long> contentRefIds);
Optional<ContentRefDTO> findByOrigin(Long workspaceId, Provider provider, String originId);
ContentOwner getOwner(Long contentRefId);
void updateContent(Long contentRefId, String title, String content);
void markOriginDeleted(Long contentRefId);
void markSyncFailed(Long workspaceId, Provider provider, String originId);   // 동기화 중 이 콘텐츠 저장 실패 표시

// 개인 콘텐츠(Gmail, Calendar)는 ownerUserId 조건이 들어간 메서드만 사용 (README 10번). since가 null이면 전체
List<ContentRefDTO> getPersonalContents(Long ownerUserId, Provider provider, LocalDateTime since);
```

사용처: A(Calendar 일정 저장), C(Drive·Gmail 저장, 검색, 협업 맥락)

### 공통 DTO (명세서 CD-DTO)

```java
record ContentRefDTO(Long contentRefId, Provider provider, ContentType contentType, String externalId,
                     String title, String content, String deepLink, String fileFormat,
                     LocalDateTime lastModifiedAt, int messageCount, boolean syncFailed) {}

record ContentDetailDTO(Long contentRefId, Provider provider, String title, String body, String deepLink,
                        String previewLink, LocalDateTime startAt, List<String> participants,
                        String description, boolean isOriginDeleted) {}
```

위치: `content.dto` (콘텐츠 서비스가 모두 같이 사용)

### Slack · Notion 콘텐츠

```java
// SlackContentService
List<ContentRefDTO> getContents(Long workspaceId);
List<ContentRefDTO> getContentsByUser(Long userId);    // AI 업무 보조 후보 추출용
ContentDetailDTO getContentDetail(Long contentRefId);

// NotionContentService
List<ContentRefDTO> getContents(Long workspaceId);
ContentDetailDTO getContentDetail(Long contentRefId);
```

사용처: C(Task 관련 자료 후보 SD-09-05, 통합 검색 결과 상세 SD-12-01)

### Gemini (GeminiClient) [결정 7]

위치: `common.ai.GeminiClient` (여러 파트가 같이 쓰므로 common). 실패하면 `AI_CALL_FAILED`(502), 429·5xx·타임아웃은 2번까지 다시 시도.
프롬프트는 `resources/prompts/{이름}.txt`에 두고 `PromptTemplates.render(이름, 변수)`로 읽는다.

```java
String generate(String prompt);                 // 텍스트 생성 (논의 분석, 후보 추출)
<T> T generateJson(String prompt, Class<T> type); // JSON 응답을 객체로 변환
float[] embed(String text);                     // 임베딩 벡터
List<float[]> embedAll(List<String> texts);
```

사용처: C(임베딩, 통합 검색)

### 증분 동기화 (SyncAdapter) — C의 Drive·Gmail도 사용

```java
// sync.service.SyncAdapter를 구현해 @Component로 등록하면 스케줄러(5분 간격)와 즉시 동기화가 자동으로 사용한다.
Provider provider();
List<SyncTarget> findTargets(Long workspaceId, Long requestedBy);   // 팀: SyncTarget.team(폴더 ID), 개인: SyncTarget.personal(userId)
SyncResult sync(SyncContext context);   // context.since()가 null이면 처음 동기화. 실패하면 예외를 그대로 던진다
record SyncResult(int savedCount, LocalDateTime syncedUntil) {}

// SyncService
void syncIfStale(Long workspaceId, Provider provider, Long requestedBy, Duration maxAge);  // 화면 진입 시
List<SyncStatusResponse> syncNow(Long workspaceId, Provider provider, Long requestedBy);  // POST /api/workspaces/{id}/sync/{provider}
```

- 실패하면 저장된 콘텐츠는 그대로 두고 `sync_state`에 실패만 기록한다. 다음 동기화에서 같은 범위를 다시 가져온다.
- 연속 실패한 대상은 자동 동기화를 5분 → 10분 → … 최대 2시간까지 늦춘다.

### 결정사항 [결정 8]

```java
// AnalysisService
AnalysisItemDTO getItemSummary(Long itemId);
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
List<TaskSummary> getTasksInProgress(Long workspaceId);  // 회의 안건 후보 (UI-10)
List<TaskSummary> getMyTasks(Long userId);              // AI 업무 보조 중복 후보 제외
record TaskSummary(Long taskId, Long workspaceId, String title, Long assigneeId, LocalDate dueDate,
                   TaskStatus status, int progress, RiskLevel riskLevel) {}
```

사용처: A(회의 안건 SD-10-04·05), B(후속 Task 생성 SD-08-04, AI 업무 보조 SD-13-02)

### 협업 맥락 (TaskContextService)

```java
TaskContext getTaskContext(Long taskId);                         // 안건별 관련 자료 (SD-10-05)
Map<Long, List<TaskSummary>> getTasksByContents(List<Long> contentRefIds);
```

사용처: A(회의 안건 관련 자료 조회 SD-10-05)

### Gmail (GmailContentService)

```java
List<ContentRefDTO> getContentsByUser(Long userId);
```

사용처: B(AI 업무 보조 일정·할 일 후보 추출 SD-13-01·02)

---

## 4. 호출 관계 요약

```
            ┌──────────── getAccessToken / @LoginUser / checkMember ────────────┐
            │                                                                    │
            ▼                                                                    ▼
   B (Slack·Notion·AI)  ── createTask, getMyTasks ──▶  C (Drive·Gmail·Task·검색)
     │      ▲    │                                         │     │
     │      │    └── createContent(Calendar),             │     └── getMeetingsByTask ──▶ A
     │      │        createPersonalEvent ──────────▶ A    │
     │      └── getContents, getItemSummary, embed ───────┘
     │
     └── ContentRefService (A·C가 콘텐츠 저장 시 호출)

   A (회의) ── getTask, getTasksInProgress, getTaskContext ──▶ C
```
