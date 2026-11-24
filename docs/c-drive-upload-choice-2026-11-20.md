# Drive 동명 파일 저장 선택과 기존 파일 크기

2026-11-20. 설계 대조의 다음 우선순위 G06(SD-06-02, 52쪽; C-03-05/06, 116~117쪽), G07(UI-06, 17쪽)을 원본 저장소 `feature/c-fix-task-rules`에서 보완했다.

## 바뀐 동작

- Drive 화면의 업로드 전에 선택한 폴더에서 같은 이름의 파일을 조회한다. 동명 파일이 있으면 파일 내용 전송 전에 선택 화면을 보여준다.
- **새 파일로 저장**은 같은 이름의 별도 파일을 만든다. **선택한 파일 덮어쓰기**는 사용자가 고른 기존 파일의 내용을 교체한다. 대상은 자동 선택하지 않는다. 여러 동명 파일의 크기·수정 시각·원본 링크를 비교할 수 있다.
- 덮어쓰기는 Google Drive 파일 ID와 ContentRef ID를 유지한다. 기존 Task 참고 자료 연결, 제출한 산출물의 파일 ID, 제출 여부와 완료 상태도 유지한다.
- 취소·다시 확인은 파일을 업로드하지 않는다. 조회 실패 시 파일 선택을 유지해 재시도할 수 있다. 폴더·파일 변경 시 이전 후보를 지우며 워크스페이스 전환 시 이전 요청의 화면 상태를 격리한다.
- 서버에서도 업로드 직전 대상의 폴더·휴지통 여부·이름·버전·내용 수정 권한을 다시 확인한다. 버전·이름이 달라졌으면 409로 중단하고 재조회·재선택한다. 이동·휴지통 파일, Google 고유 문서·바로가기, 수정 권한 없는 대상은 덮어쓰지 않는다.
- 같은 서버에서 동일 폴더·이름에 동시에 업로드하면 하나만 처리한다. 실패 후에는 예약을 해제해 재시도할 수 있다.
- 목록·상세·휴지통에 기존 파일 크기를 표시한다. 크기를 제공하지 않은 파일은 `크기 정보 없음`, 실제 빈 파일은 `0 B`로 구분한다. 큰 파일도 64비트 바이트 수로 저장한다.
- 좁은 화면에서는 파일명·크기와 수정일을 나눠 표시해 파일명 칸이 사라지지 않도록 보완했다.

## API와 기존 호출 호환성

- 새 후보 조회: `GET /api/workspaces/{workspaceId}/drive/upload-candidates?folderId=&name=`.
- 기존 `POST /drive/files`의 multipart `folderId`, `file`을 유지하고 `mode`, `targetFileId`, `expectedVersion`을 추가했다.
- `mode=ASK`: 동명 파일이 있으면 `DRIVE_FILE_NAME_CONFLICT`(409). 화면의 사전 조회 후 새 충돌이 생겨도 서버에서 거절한다.
- `mode=CREATE_NEW`: 별도 파일 생성. `mode` 생략 시에도 이 동작을 유지하므로 기존 Task 산출물 업로드는 그대로 작동한다.
- `mode=OVERWRITE`: `targetFileId`, `expectedVersion` 필수. 선택 대상 변경은 `DRIVE_FILE_CHANGED`(409), 같은 서버의 중복 업로드는 `DRIVE_UPLOAD_IN_PROGRESS`(409).
- 응답은 기존 ContentRefDTO에 nullable `fileSize`를 추가했다. 기존 생성자와 ContentRefUpsert 시그니처를 유지하며 일반 upsert는 저장된 크기를 보존한다. Drive 전용 `upsertDriveFile(command, size)`만 크기까지 갱신한다.

## DB 이전과 범위

V11은 `content_ref.file_size BIGINT`와 음수 방지 제약을 추가한다. 기존 파일 크기는 초기 null이며 Drive 동기화 커서를 초기화해 **다음 동기화에서 한 번 전체 메타데이터를 다시 조회**한다. 그 뒤에는 기존 증분 동기화를 사용한다. Gmail 등 다른 제공자의 커서는 유지한다. 실제 사용자 DB에는 이번 작업에서 마이그레이션을 실행하지 않았다.

덮어쓰기 세션은 Google Drive의 기존 ID에 resumable `PATCH`로 만들고 부모 폴더 메타데이터를 변경하지 않는다. 이후 파일 전송은 기존 4MB 청크·308 재개 경로를 사용하며 100MB 업로드 제한을 유지한다.

버전 확인은 전송 직전 확인이며 외부 편집·폴더 이동과 전송 전체를 원자적으로 잠그는 장치는 아니다. 중복 업로드 예약도 서버 프로세스 하나의 범위다. 여러 서버 또는 업로드 도중의 외부 변경까지 방지한다고 보장하지 않는다.

공통 DTO·Entity 필드·V11이 포함되므로 추후 GitHub 반영 시 저장소의 공통 변경 리뷰 규칙을 적용해야 한다. 이번에는 로컬 수정만 했다.

## 검증

- 신규 백엔드 27개 통과: 덮어쓰기 후 ID·Task 연결·제출 보존, 새 파일 생성, 기존 호출 호환성, 버전 변경·폴더 이동·휴지통·권한·고유 문서 거절, 동시 요청·실패 후 재시도, 인증·소속·선택 폴더 검사, 크기·본문 제거 응답·전체/증분 동기화, 기존 DB V10→V11 이전.
- HTTP 계약 검증: 새 파일 POST와 기존 ID PATCH, 덮어쓰기 시 부모 폴더 메타데이터 미전송, Google 외 업로드 주소 거절, 큰 크기/0/null/잘못된 값, 이름 이스케이프·페이지 조회.
- 브라우저: 실제 Drive React 컴포넌트와 확인용 API 응답으로 서로 다른 22개 시나리오를 1440px·390px에서 모두 통과했다. 취소·명시적 선택·새 파일·덮어쓰기·조회 실패·충돌·버전 변경·재시도·폴더/파일/워크스페이스 변경과 좁은 화면 가독성을 확인하고 스크린샷도 확인했다.
- `npm run build`: 타입 검사와 배포 빌드 성공. 기존 500kB 초과 번들 경고는 남아 있다.
- `backend/gradlew.bat build`: 성공. 전체 43개 테스트 묶음, 총 323개 중 322개 통과·기존 제외 1개·실패/오류 0. 제외된 항목은 기존 `TogatherApplicationTests.contextLoads`다.
- `git diff --check`: 통과. 기존 작업과 사용자 ZIP 파일을 유지했다.

이번 검증은 내장 PostgreSQL·MockMvc·모의 Google 응답·격리 브라우저를 사용했다. 실제 Google Drive에 파일을 보내거나 기존 파일을 덮어쓰는 계정 E2E는 수행하지 않았다. 실제 계정 검증은 별도로 남는다. 확인용 HTTP 서버와 브라우저는 종료했으며 ToGather 개발 서버는 실행하지 않았다. 커밋·푸시·머지는 하지 않았다.

브라우저 결과와 화면: 작업 폴더 `output/drive-upload-ui-20261120`.

Google API 확인 근거: [파일 메타데이터(size·version·capabilities)](https://developers.google.com/workspace/drive/api/reference/rest/v3/files), [기존 파일 update](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update), [resumable 업로드](https://developers.google.com/workspace/drive/api/guides/manage-uploads).
