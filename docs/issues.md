# 기능 단위 이슈 목록

GitHub Issues에 등록할 목록입니다. 한 이슈 = 한 브랜치 = 한 PR을 기준으로 나눴습니다.

- **라벨**: `part:A` `part:B` `part:C` `common` + 종류 `backend` `frontend`
- **브랜치**: `feature/{a|b|c}-{이슈의 브랜치명}` (예: `feature/a-google-login`)
- **PR 본문**: `Closes #이슈번호`를 적으면 머지할 때 이슈가 자동으로 닫힙니다.
- **주차**: 1주차 = 10/7 주. 9주차(12/2 ~ 12/9)는 통합 테스트와 버그 수정에 비워 둡니다.
- **선행**: 먼저 끝나야 하는 이슈입니다. 선행 이슈가 안 끝났으면 [shared-interfaces.md](shared-interfaces.md)의 시그니처로 가짜 구현을 두고 진행합니다.

---

## 공통 (세 명 모두 확인)

### COMMON-01. 파트 간 공유 인터페이스 시그니처 커밋
- 브랜치: 각자 `feature/{a|b|c}-shared-interfaces` · 1주차
- [ ] `docs/shared-interfaces.md`에서 내가 제공하는 메서드의 시그니처, DTO, 빈 구현(가짜 데이터 반환)을 커밋
- [ ] A: `@LoginUser`, `UserService`, `IntegrationService.getAccessToken` 등 / B: `ContentRefService`, `ContentRefDTO`, `GeminiClient` 등 / C: `TaskService.createTask`, `TaskSummary` 등

### COMMON-02. 빌드·실행 확인
- 브랜치: `feature/a-build-check` · 1주차
- [ ] `docker compose up -d` 후 백엔드 기동, Flyway V1 적용 확인
- [ ] 세 명 모두 로컬에서 백엔드·프론트 실행 확인

---

## A. 기반·연동·일정 (백민준)

### A-01. Google 로그인과 세션
- 브랜치: `a-google-login` · UC-01-01 · SD-01-01 · UI-01 · 1주차
- [ ] `AuthController` `login`, `googleCallback` / `AuthService.loginWithGoogle` / `SessionManager`
- [ ] 최초 로그인 시 `users` 저장, 이후 프로필 갱신
- [ ] `@LoginUser Long userId` 인자 주입, 미로그인 시 401 `UNAUTHORIZED`
- [ ] 로그인 화면 (처리 중 표시, 실패 사유 안내)

### A-02. 워크스페이스 CRUD
- 브랜치: `a-workspace-crud` · UC-02-01~04 · SD-02-01~04 · UI-02 · 1주차 · 선행: A-01
- [ ] 목록, 조회, 생성(생성자 OWNER 등록), 수정·삭제(OWNER만)
- [ ] 워크스페이스 목록 화면(카드, 팀원 수), 생성·수정·삭제 확인

### A-03. 초대 링크와 팀원 관리
- 브랜치: `a-invite-member` · UC-02-05~08 · SD-02-05~08 · UI-02 · 2주차 · 선행: A-02
- [ ] 초대 링크 생성·조회(OWNER), 초대 정보 조회, 링크로 참여(MEMBER)
- [ ] 팀원 목록(팀장·팀원 구분), 팀원 내보내기(OWNER)
- [ ] `UserService.getUsers`, `WorkspaceMemberService.getMemberUserIds` 제공
- [ ] 초대 참여 화면, 팀원 관리 화면

### A-04. OAuth 연동과 토큰 암호화
- 브랜치: `a-oauth-connect` · UC-03-01~02 · SD-03-01~02 · UI-03 · 3주차 · 선행: A-02
- [ ] Slack, Notion, Google(Drive·Calendar·Gmail) 인증 화면 이동과 콜백 처리
- [ ] 토큰 암호화 저장(`TOGATHER_TOKEN_KEY`), 연동 대상 선택
- [ ] `getAccessToken`(만료 시 갱신), `getConnectionTargets` 제공
- [ ] 연동 상태 화면

### A-05. 재인증과 연결 해제
- 브랜치: `a-oauth-reauth` · UC-03-03~04 · SD-03-03~04 · UI-03 · 4주차 · 선행: A-04
- [ ] 토큰 만료 시 `REAUTH_REQUIRED` 상태 전환과 화면 표시(구분 색상)
- [ ] 재인증(같은 계정인지 확인), 연결 해제(확인 절차)

### A-06. Google Calendar 일정
- 브랜치: `a-calendar` · UC-07-01~04 · SD-07-01~04 · UI-07 · 5주차 · 선행: A-04, B-01
- [ ] 일정 조회(월·주), 생성, 수정, 삭제
- [ ] `createContent`, `createPersonalEvent`, `getContentDetail` 제공 (B, C 사용)
- [ ] 달력 화면, 확정된 회의 일정 구분 표시

### A-07. 회의 생성·수정·취소와 안건
- 브랜치: `a-meeting-crud` · UC-10-01~05 · SD-10-01~05 · UI-10 · 6주차 · 선행: C-01
- [ ] 회의 생성(SCHEDULING), 수정, 취소
- [ ] 안건 추가·변경(진행 중 Task 중 선택), 안건별 관련 자료 조회
- [ ] `getMeetingsByTask` 제공 (C 사용)
- [ ] 회의 목록 화면(예정·지난 회의), 생성 화면, 안건 화면

### A-08. 가용 시간 수집
- 브랜치: `a-meeting-availability` · UC-10-06~07 · SD-10-06~07 · UI-10 · 7주차 · 선행: A-06, A-07
- [ ] 가능·불가능·비선호 입력, 연동 캘린더 일정은 불가능으로 자동 표시
- [ ] 참여자별 응답 현황
- [ ] 후보 기간 표 형태 입력 화면

### A-09. 회의 시간 추천과 확정
- 브랜치: `a-meeting-recommend` · UC-10-08~09 · SD-10-08~09 · UI-10 · 8주차 · 선행: A-08
- [ ] 가용 시간 집계와 점수 계산, 후보 순위(가능 인원, 비선호 인원) — 3초 이내
- [ ] 확정 시 Calendar 등록, 실패 시 `calendarSyncFailed` 기록과 재시도
- [ ] 추천 후보 화면, 확정 결과(일정, 화상 회의 링크) 화면

---

## B. 수집·Slack·Notion·AI (정철준)

### B-01. ContentRefService와 공통 콘텐츠 DTO
- 브랜치: `b-content-ref` · 1주차
- [ ] `ContentRefService` (upsert, 조회, 수정, 원본 삭제 표시, 개인 콘텐츠 조회)
- [ ] `ContentRefDTO`, `ContentDetailDTO`

### B-02. GeminiClient
- 브랜치: `b-gemini-client` · 1주차
- [ ] `generate`, `generateJson`, `embed`, `embedAll`
- [ ] 호출 실패 시 `AI_CALL_FAILED`, 재시도 정책

### B-03. 증분 동기화 프레임워크
- 브랜치: `b-sync-framework` · 2주차 · 선행: B-01
- [ ] 스케줄러, 서비스별 어댑터 인터페이스, 마지막 동기화 시각 관리
- [ ] 외부 API 실패 시 기존 데이터 유지, 실패 기록

### B-04. Slack 콘텐츠 조회
- 브랜치: `b-slack-read` · UC-04-01 · SD-04-01 · UI-04 · 3주차 · 선행: A-04, B-03
- [ ] 채널 메시지 조회(시간순), 스레드 답글 조회, Slack 증분 동기화
- [ ] `getContents`, `getContentsByUser`, `getContentDetail` 제공 (C 사용)
- [ ] 채널 목록 + 메시지 화면, 스레드 화면

### B-05. Slack 메시지 작성·수정·삭제
- 브랜치: `b-slack-write` · UC-04-02~04 · SD-04-02~04 · UI-04 · 4주차 · 선행: B-04
- [ ] 채널·스레드에 작성, 수정, 삭제
- [ ] 전송 실패 시 입력 내용 유지와 재시도

### B-06. Notion 문서
- 브랜치: `b-notion` · UC-05-01~04 · SD-05-01~04 · UI-05 · 5주차 · 선행: A-04, B-03
- [ ] 페이지 목록·조회, 생성, 수정, 삭제, 증분 동기화
- [ ] 문서 목록 화면(결정사항 문서 구분), 편집 화면

### B-07. Slack 논의 분석
- 브랜치: `b-discussion-analysis` · UC-08-01~02 · SD-08-01~02 · UI-08 · 6주차 · 선행: B-02, B-04
- [ ] 스레드 분석(결정사항, 근거 발언, 후속 작업, 미결 사항, 회의 제안) — 30초 이내
- [ ] 자동 수집 분석(`AnalysisScheduler`), 분석 범위(`analyzedUntil`)
- [ ] 검토: 항목 수정·추가·삭제, 근거 연결·해제, 확정
- [ ] `getItemSummary` 제공 (C 사용)
- [ ] 분석 결과 화면(처리 단계 표시, 빈 양식)

### B-08. 분석 결과 실행 연계
- 브랜치: `b-analysis-output` · UC-08-03~05 · SD-08-03~05 · UI-08 · 7주차 · 선행: B-06, B-07, A-06, C-01
- [ ] 확정 결과로 Notion 문서 생성(미리보기 포함)
- [ ] 후속 작업 → Task 생성, 회의 제안 → Calendar 등록
- [ ] 단계별 성공·실패 기록과 재시도, 처리 결과 화면

### B-09. AI 업무 보조
- 브랜치: `b-ai-assistant` · UC-13-01~02 · SD-13-01~02 · UI-13 · 8주차 · 선행: B-02, C-05, A-06
- [ ] Gmail·Slack 메시지에서 일정·할 일 후보 추출, 중복 후보 제외
- [ ] 후보 수정, 승인(일정 → Calendar, 할 일 → 개인 할 일), 무시
- [ ] 후보 목록 화면(시간 미정 표시), 승인 화면

---

## C. Drive·Gmail·Task·검색 (이찬서)

### C-01. Task CRUD
- 브랜치: `c-task-crud` · UC-09-01~04 · SD-09-01~04 · UI-09 · 1주차 · 선행: A-01(없으면 가짜 userId로 진행)
- [ ] Task 목록(팀 전체·개인별), 조회, 생성(필요 산출물 포함), 수정, 삭제
- [ ] `createTask`, `getTask`, `getTasksInProgress`, `getMyTasks` 제공 (A, B 사용)
- [ ] Task 목록 화면, 생성·수정 화면

### C-02. Task 진행 현황
- 브랜치: `c-task-progress` · UC-09-07 · SD-09-07 · UI-09 · 2주차 · 선행: C-01
- [ ] 진행률, 마감 위험도(정상·주의·위험) 기준 확정과 구현 (`Task.evaluateRisk`의 임시 기준 교체)
- [ ] 진행 현황 표시

### C-03. Drive 파일
- 브랜치: `c-drive` · UC-06-01~03 · SD-06-01~03 · UI-06 · 3주차 · 선행: A-04, B-01
- [ ] 파일 목록·조회(미리보기), 업로드(드래그 앤 드롭, 진행 상태), 삭제, 증분 동기화
- [ ] 파일 목록 화면, 미리보기 화면

### C-04. Task 관련 자료와 산출물
- 브랜치: `c-task-content` · UC-09-05~06 · SD-09-05~06 · UI-09 · 4주차 · 선행: C-01, C-03, B-04
- [ ] 관련 자료 연결·해제(Slack, Notion, Drive 후보)
- [ ] 산출물 제출(Drive 업로드, 확장자 검사), 제출 해제, 모두 제출 시 DONE 자동 전환

### C-05. Gmail 수집
- 브랜치: `c-gmail` · 5주차 · 선행: A-04, B-01
- [ ] 사용자별 메일 수집(ownerUserId 저장), `getContentsByUser` 제공 (B 사용)

### C-06. Task 협업 맥락 조회
- 브랜치: `c-task-context` · UC-11-01~02 · SD-11-01~02 · UI-11 · 6주차 · 선행: C-04, A-07, B-07
- [ ] Task별 연결 자료(대화, 결정사항, 문서, 산출물, 회의)를 유형별·생성 시각순으로 — 5초 이내
- [ ] 콘텐츠별 연결 Task 조회, 원본 삭제 시 사본 제공
- [ ] `getTaskContext`, `getTasksByContents` 제공 (A 사용)
- [ ] 협업 맥락 화면

### C-07. 콘텐츠 임베딩
- 브랜치: `c-embedding` · 7주차 · 선행: B-02, B-03
- [ ] Slack·Notion·Drive 콘텐츠를 임베딩해 ChromaDB에 저장, 동기화 시 갱신
- [ ] ChromaDB 클라이언트(`searchVectors`)

### C-08. 문맥 기반 통합 검색
- 브랜치: `c-search` · UC-12-01 · SD-12-01 · UI-12 · 8주차 · 선행: C-07, C-06
- [ ] 자연어 질의 → 유사도 검색 → 관련도순 결과, 출처 서비스 필터
- [ ] 결과에 연결된 Task 함께 표시
- [ ] 검색 화면(결과 없음 안내)

---

## 주차별 요약

| 주차 | A (백민준) | B (정철준) | C (이찬서) |
|---|---|---|---|
| 1 (10/7~) | A-01, A-02 | B-01, B-02 | C-01 |
| 2 (10/14~) | A-03 | B-03 | C-02 |
| 3 (10/21~) | A-04 | B-04 | C-03 |
| 4 (10/28~) | A-05 | B-05 | C-04 |
| 5 (11/4~) | A-06 | B-06 | C-05 |
| 6 (11/11~) | A-07 | B-07 | C-06 |
| 7 (11/18~) | A-08 | B-08 | C-07 |
| 8 (11/25~) | A-09 | B-09 | C-08 |
| 9 (12/2~12/9) | 통합 테스트, 성능 목표 점검, 버그 수정 | ← | ← |

**일정 위험**
- **A-01·A-04가 늦어지면 B와 C 전체가 밀립니다.** 1주차는 A-01을 최우선으로 합니다.
- **외부 서비스 앱 등록은 미리 신청하세요.** Google OAuth 동의 화면, Slack App, Notion Integration을 1주차에 등록해 두어야 A-04를 3주차에 시작할 수 있습니다. Gmail 범위는 Google 검토가 필요할 수 있으니 테스트 사용자 등록으로 진행합니다.
