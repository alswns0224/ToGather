import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import {
  createFollowUpTask,
  createNotionDocument,
  getMeetingDraft,
  getNotionDraft,
  registerMeeting,
  type AnalysisItemResponse,
  type MeetingDraftDTO,
} from '../api';
import type { MemberResponse } from '../../workspace/api';
import Dialog, { StatusTag } from '../../../components/Dialog';
import Icon from '../../../components/Icon';
import { Loading } from '../../../components/Status';

/**
 * 확정된 분석의 실행 연계 (UC-08-03 ~ 05). 단계마다 성공·실패를 보여주고, 실패한 단계만 다시 시도한다.
 * 이미 성공한 단계는 서버가 중복 생성을 막는다.
 */

/** Notion 결정사항 문서: 초안을 미리 보고 고친 뒤 만든다. */
export function NotionDialog({
  workspaceId,
  analysisId,
  onClose,
  onDone,
}: {
  workspaceId: string;
  analysisId: number;
  onClose: () => void;
  onDone: () => void;
}) {
  const [draft, setDraft] = useState<{ title: string; body: string } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getNotionDraft(workspaceId, analysisId)
      .then(setDraft)
      .catch((e: Error) => setLoadError(e.message));
  }, [workspaceId, analysisId]);

  const submit = async () => {
    if (!draft?.title.trim() || !draft.body.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await createNotionDocument(workspaceId, analysisId, draft);
      onDone();
    } catch (e) {
      setError((e as Error).message); // 초안은 유지하고 다시 시도
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      title="Notion 문서 생성"
      width={600}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="ui-button secondary" onClick={onClose} disabled={busy}>
            취소
          </button>
          <button type="button" className="ui-button primary" onClick={submit} disabled={busy || !draft}>
            <Icon name="doc" size={15} />
            {busy ? '만드는 중...' : error ? '다시 시도' : '문서 만들기'}
          </button>
        </>
      }
    >
      {loadError && <div className="notice error">{loadError}</div>}
      {!draft && !loadError && <Loading rows={2} />}
      {draft && (
        <>
          <label className={`field${!draft.title.trim() ? ' has-error' : ''}`}>
            <span>
              제목<b>*</b>
            </span>
            <input maxLength={200} value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
            {!draft.title.trim() && <em>제목을 입력해 주세요.</em>}
          </label>
          <label className="field">
            <span>
              본문<small>확정한 항목으로 만든 초안이에요. 고친 뒤 만들 수 있어요.</small>
            </span>
            <textarea
              style={{ minHeight: 260 }}
              value={draft.body}
              onChange={(e) => setDraft({ ...draft, body: e.target.value })}
            />
          </label>
        </>
      )}
      {error && <div className="notice error">문서를 만들지 못했어요. {error}</div>}
    </Dialog>
  );
}

/** 후속 작업 → Task: 항목마다 담당자·마감일을 정해 만든다. */
export function TaskDialog({
  workspaceId,
  items,
  members,
  onClose,
  onChanged,
}: {
  workspaceId: string;
  items: AnalysisItemResponse[];
  members: MemberResponse[];
  onClose: () => void;
  onChanged: () => void;
}) {
  return (
    <Dialog title="후속 작업 → Task 생성" width={600} onClose={onClose}>
      {items.length === 0 ? (
        <p className="editor-hint">후속 작업 항목이 없어요.</p>
      ) : (
        <div className="output-list">
          {items.map((item) => (
            <TaskRow key={item.itemId} workspaceId={workspaceId} item={item} members={members} onChanged={onChanged} />
          ))}
        </div>
      )}
    </Dialog>
  );
}

function TaskRow({
  workspaceId,
  item,
  members,
  onChanged,
}: {
  workspaceId: string;
  item: AnalysisItemResponse;
  members: MemberResponse[];
  onChanged: () => void;
}) {
  const [form, setForm] = useState({
    title: item.content.slice(0, 200),
    description: item.content,
    assigneeId: item.suggestedAssigneeId,
    dueDate: item.suggestedDueDate,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (item.outputStatus === 'SUCCESS') {
    return (
      <div className="output-card">
        <div className="head">
          <StatusTag tone="success">생성됨</StatusTag>
          <span style={{ flex: 1 }}>{item.content}</span>
          <Link to={`/workspaces/${workspaceId}/tasks/${item.outputTargetId}`}>Task 보기</Link>
        </div>
      </div>
    );
  }

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      await createFollowUpTask(workspaceId, item.itemId, form);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
      onChanged(); // 실패 상태를 다시 불러온다.
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="output-card">
      <div className="head">
        {item.outputStatus === 'FAILED' ? <StatusTag tone="danger">실패</StatusTag> : <StatusTag tone="muted">대기</StatusTag>}
      </div>
      <label className="field" style={{ margin: 0 }}>
        <span>Task 제목</span>
        <input maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
      </label>
      <div className="row2">
        <label className="field" style={{ margin: 0 }}>
          <span>
            담당자<b>*</b>
          </span>
          <select
            value={form.assigneeId ?? ''}
            onChange={(e) => setForm({ ...form, assigneeId: e.target.value ? Number(e.target.value) : null })}
          >
            <option value="">담당자 선택</option>
            {members.map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field" style={{ margin: 0 }}>
          <span>
            마감일<b>*</b>
          </span>
          <input type="date" value={form.dueDate ?? ''} onChange={(e) => setForm({ ...form, dueDate: e.target.value || null })} />
        </label>
      </div>
      {error && <div className="notice error">{error}</div>}
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <button
          type="button"
          className="ui-button primary"
          onClick={submit}
          disabled={busy || !form.title.trim() || !form.assigneeId || !form.dueDate}
        >
          {busy ? '만드는 중...' : item.outputStatus === 'FAILED' ? '다시 시도' : 'Task 만들기'}
        </button>
      </div>
    </div>
  );
}

/** 회의 제안 → 팀 캘린더 일정 */
export function MeetingDialog({
  workspaceId,
  items,
  members,
  onClose,
  onChanged,
}: {
  workspaceId: string;
  items: AnalysisItemResponse[];
  members: MemberResponse[];
  onClose: () => void;
  onChanged: () => void;
}) {
  return (
    <Dialog title="회의 제안 → 일정 등록" width={600} onClose={onClose}>
      {items.length === 0 ? (
        <p className="editor-hint">회의 제안 항목이 없어요.</p>
      ) : (
        <div className="output-list">
          {items.map((item) => (
            <MeetingRow key={item.itemId} workspaceId={workspaceId} item={item} members={members} onChanged={onChanged} />
          ))}
        </div>
      )}
    </Dialog>
  );
}

function MeetingRow({
  workspaceId,
  item,
  members,
  onChanged,
}: {
  workspaceId: string;
  item: AnalysisItemResponse;
  members: MemberResponse[];
  onChanged: () => void;
}) {
  const [draft, setDraft] = useState<MeetingDraftDTO | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (item.outputStatus === 'SUCCESS') return;
    getMeetingDraft(workspaceId, item.itemId)
      .then(setDraft)
      .catch((e: Error) => setError(e.message));
  }, [workspaceId, item.itemId, item.outputStatus]);

  if (item.outputStatus === 'SUCCESS') {
    return (
      <div className="output-card">
        <div className="head">
          <StatusTag tone="success">등록됨</StatusTag>
          <span style={{ flex: 1 }}>{item.content}</span>
          <Link to={`/workspaces/${workspaceId}/calendar`}>일정 보기</Link>
        </div>
      </div>
    );
  }

  const submit = async () => {
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      await registerMeeting(workspaceId, item.itemId, draft);
      onChanged();
    } catch (e) {
      setError((e as Error).message);
      onChanged();
    } finally {
      setBusy(false);
    }
  };

  const toggle = (id: number) =>
    draft &&
    setDraft({
      ...draft,
      participantIds: draft.participantIds.includes(id)
        ? draft.participantIds.filter((p) => p !== id)
        : [...draft.participantIds, id],
    });

  return (
    <div className="output-card">
      <div className="head">
        {item.outputStatus === 'FAILED' ? <StatusTag tone="danger">실패</StatusTag> : <StatusTag tone="muted">대기</StatusTag>}
      </div>
      {!draft && !error && <Loading rows={1} />}
      {draft && (
        <>
          <label className="field" style={{ margin: 0 }}>
            <span>일정 제목</span>
            <input maxLength={200} value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
          </label>
          <div className="row2">
            <label className="field" style={{ margin: 0 }}>
              <span>
                시작<b>*</b>
              </span>
              <input
                type="datetime-local"
                value={draft.startAt?.slice(0, 16) ?? ''}
                onChange={(e) => setDraft({ ...draft, startAt: e.target.value ? `${e.target.value}:00` : null })}
              />
            </label>
            <label className="field" style={{ margin: 0 }}>
              <span>종료</span>
              <input
                type="datetime-local"
                value={draft.endAt?.slice(0, 16) ?? ''}
                onChange={(e) => setDraft({ ...draft, endAt: e.target.value ? `${e.target.value}:00` : null })}
              />
            </label>
          </div>
          <div className="participant-tags">
            {members.map((m) => (
              <button
                type="button"
                key={m.userId}
                className={draft.participantIds.includes(m.userId) ? 'on' : ''}
                onClick={() => toggle(m.userId)}
              >
                {m.name}
              </button>
            ))}
          </div>
        </>
      )}
      {error && <div className="notice error">{error}</div>}
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <button type="button" className="ui-button primary" onClick={submit} disabled={busy || !draft?.startAt || !draft.title.trim()}>
          {busy ? '등록 중...' : item.outputStatus === 'FAILED' ? '다시 시도' : '팀 캘린더에 등록'}
        </button>
      </div>
    </div>
  );
}
