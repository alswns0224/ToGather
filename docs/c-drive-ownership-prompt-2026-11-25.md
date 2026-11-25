# [작업 프롬프트] Drive 파일 소유권을 팀 소유자에게 자동으로 모으기

> 이 문서는 새 작업 세션에 그대로 붙여 넣어 쓰는 프롬프트다. (작성 2026-11-25, 브랜치 `feature/c-drive-folder-menu` 기준)

## 배경

ToGather의 Drive 연동은 팀원의 **개인 Google 계정(무료) 내 드라이브 폴더를 공유한 구조**를 쓴다.
공유 드라이브는 Google Workspace 유료 요금제가 있어야 만들 수 있어 쓰지 않는다.

내 드라이브 파일은 **소유자 한 명만 휴지통으로 보내거나 복원할 수 있다.** 편집자 권한으로는 안 된다.
그래서 ToGather에서 휴지통 이동을 누르면 요청 계정이 소유자가 아닐 때 Google이 `HTTP 403`으로 거절한다.

현재 토큰 사용 방식:
- 업로드(`DriveContentService.uploadFile`)는 `resolveToken` → `TeamTokenResolver.tokenFor`를 쓴다. 이 함수는 **폴더를 연동한 사람의 토큰**을 먼저 쓴다.
  → ToGather에서 올린 파일은 누가 올렸든 **폴더 연동자 소유**가 된다.
- 휴지통 이동·복원(`trashFile`, `restoreFile`)은 2026-11-25에 `resolveOwnToken`(요청자 본인 토큰 우선)으로 바뀌었다. (아직 커밋 안 됨)
  → 연동자가 아닌 팀원이 누르면 ToGather로 올린 파일도 403이 난다. 이 작업에서 같이 정리해야 한다.
- Drive 웹에서 직접 올린 파일은 **올린 사람 소유**다.

## 목표

동기화할 때 팀 폴더의 파일 소유권을 **팀 소유자 계정(기본: 폴더 연동자)**에게 자동으로 모은다.
그다음 휴지통 이동·복원·영구 삭제는 팀 소유자 토큰 하나로 처리한다.

## Google Drive API 사실 (구현 전에 다시 확인할 것)

- 같은 Workspace 조직 안에서는 바로 이전할 수 있다. **개인 Gmail 계정끼리는 받는 사람이 수락해야 한다.** Workspace와 개인 Gmail 사이(조직 밖)는 이전할 수 없다.
- 개인 계정 이전 흐름:
  1. 현재 소유자가 자기 토큰으로 요청한다. 받는 사람이 이미 편집자여야 한다.
     `PATCH /drive/v3/files/{fileId}/permissions/{받는사람 permissionId}` body `{"role":"writer","pendingOwner":true}`
  2. 받는 사람이 자기 토큰으로 수락한다.
     `PATCH /drive/v3/files/{fileId}/permissions/{본인 permissionId}?transferOwnership=true` body `{"role":"owner"}`
- 두 사람이 모두 ToGather에 Drive를 연동했다면 서버가 1번과 2번을 둘 다 자동으로 실행할 수 있다. 이메일로 수락할 필요가 없다.
- **폴더 소유권을 넘겨도 안의 파일은 넘어가지 않는다.** 파일마다 따로 이전해야 한다.
- 이전된 파일은 새 소유자의 저장 용량(무료 15GB)을 차지한다.
- 모든 호출에 `supportsAllDrives=true`를 붙인다.
- **확인할 것**: Google 문서가 아닌 일반 파일(PDF, 이미지 등)도 개인 계정끼리 `pendingOwner` 흐름으로 이전되는지 테스트 파일로 먼저 확인한다.

## 구현 범위 (제안)

1. **소유자 확인**: 파일 정보 필드(`DriveApiClient.FILE_FIELDS`)에 `owners(emailAddress,permissionId)`, `capabilities(canTrash,canUntrash,canDelete)`를 추가한다.
2. **계정 매칭**: `ServiceConnection.externalAccountId`가 Google 이메일인지 ID인지 확인한다. 그 값으로 파일 소유자와 워크스페이스 멤버의 Drive 연동을 이어 준다.
3. **팀 소유자 결정**: 기본은 폴더 연동자(`IntegrationService.findTargetAccess`). 팀장이 바꿀 수 있게 할지 결정한다.
4. **자동 이전**: `DriveSyncAdapter` 동기화 중에 소유자가 팀 소유자가 아닌 파일을 찾는다.
   - 원래 소유자가 연동되어 있으면: 원래 소유자 토큰으로 `pendingOwner` 요청 → 팀 소유자 토큰으로 수락
   - 원래 소유자가 연동되어 있지 않으면: 건너뛰고 "소유권 이전 필요" 상태로 표시
   - 실패하면 다음 동기화에서 다시 시도하고, 계속 실패하는 파일은 로그와 상태로 남긴다.
5. **휴지통 토큰 정리**: `trashFile`, `restoreFile`, (만들 예정인) 영구 삭제는 **파일 실제 소유자의 토큰**을 쓴다. 소유자 토큰이 없으면 `DRIVE_TRASH_FORBIDDEN`을 낸다.
   - 이 단계가 2026-11-25에 넣은 `resolveOwnToken`을 대체한다.
6. **권한 정책**: ToGather 안에서 누가 휴지통 이동을 할 수 있는지 정한다. (예: 올린 사람, 팀장, 관리자)
7. **동의 안내**: Drive를 연동할 때 "팀 폴더에 올린 파일의 소유권은 팀 소유자에게 옮겨질 수 있다"고 알린다.
8. **화면**: 파일 목록 응답에 `canTrash`를 내려준다. 휴지통 이동이 불가능한 파일은 버튼을 숨기거나 안내로 바꾼다.

## 고려할 위험

- 모든 파일이 팀 소유자 한 명의 용량과 계정에 몰린다. (용량 초과, 팀 소유자가 팀을 떠나는 경우)
- 다른 사람의 토큰으로 작업이 실행되므로 동의 안내와 감사 로그가 필요하다.
- 팀 소유자가 바뀌면 기존 파일을 다시 이전해야 한다.
- 대안: 소유권을 옮기지 않고 "소유자 토큰으로 대신 처리 + 실패 시 ToGather 자체 휴지통" 방식(방안 1+2)도 있다. 착수 전에 어느 쪽으로 갈지 팀과 정한다.

## 완료 기준

- 연동한 두 계정 A(팀 소유자)와 B로 테스트한다. B가 Drive 웹에서 직접 올린 파일이 동기화 후 A 소유로 바뀐다.
- B가 ToGather에서 그 파일을 휴지통으로 보내고 복원할 수 있다. 403이 나지 않는다.
- 소유자가 연동되지 않은 파일은 403 대신 안내 문구가 뜨고, 로그에 사유(`errorReason`)가 남는다.
- 백엔드 빌드와 기존 테스트가 통과한다.
