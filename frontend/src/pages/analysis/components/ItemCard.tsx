import { useState, type FormEvent } from 'react';
import type { MemberResponse } from '../../workspace/api';
import {
  deleteItem,
  linkEvidence,
  unlinkEvidence,
  updateItem,
  type AnalysisItemResponse,
  type ItemForm,
  type ItemType,
  type ThreadMessageResponse,
} from '../api';
import { formatDate, formatDateTime } from '../../../utils/date';

/** 분석 항목 한 개: 내용, 근거 발언, 담당자·마감일(후속 작업), 일시·참여자(회의 제안) */
export default function ItemCard({
  workspaceId,
  item,
  editable,
  members,
  messages,
  onChanged,
}: {
  workspaceId: string;
  item: AnalysisItemResponse;
  editable: boolean;
  members: MemberResponse[];
  messages: ThreadMessageResponse[];
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const linked = new Set(item.evidences.map((e) => e.contentRefId));
  const candidates = messages.filter((m) => !linked.has(m.contentRefId));

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const remove = () => {
    if (!window.confirm('이 항목을 삭제할까요? 연결된 근거 발언도 함께 삭제됩니다.')) return;
    void run(() => deleteItem(workspaceId, item.itemId));
  };

  if (editing) {
    return (
      <li className="analysis-item">
        <ItemEditor
          itemType={item.itemType}
          members={members}
          initial={{
            content: item.content,
            suggestedAssigneeId: item.suggestedAssigneeId,
            suggestedDueDate: item.suggestedDueDate,
            suggestedDatetime: item.suggestedDatetime,
            suggestedParticipantIds: item.suggestedParticipantIds ?? [],
          }}
          onCancel={() => setEditing(false)}
          onSubmit={async (form) => {
            await updateItem(workspaceId, item.itemId, form);
            setEditing(false);
            onChanged();
          }}
        />
      </li>
    );
  }

  const memberName = (id: number) => members.find((m) => m.userId === id)?.name ?? `#${id}`;

  return (
    <li className="analysis-item stack">
      <div className="row">
        <span className="pre grow">{item.content}</span>
        {item.undetermined && <span className="badge badge-warning">미정</span>}
        {editable && (
          <>
            <button className="link-button small" onClick={() => setEditing(true)}>
              수정
            </button>
            <button className="link-button small" onClick={remove}>
              삭제
            </button>
          </>
        )}
      </div>

      {item.itemType === 'FOLLOW_UP' && (
        <div className="row small muted">
          <span>담당자: {item.suggestedAssigneeName ?? '미정'}</span>
          <span>마감일: {item.suggestedDueDate ? formatDate(item.suggestedDueDate) : '미정'}</span>
        </div>
      )}
      {item.itemType === 'MEETING_PROPOSAL' && (
        <div className="row small muted">
          <span>일시: {item.suggestedDatetime ? formatDateTime(item.suggestedDatetime) : '미정'}</span>
          <span>
            참여자:{' '}
            {item.suggestedParticipantIds?.length
              ? item.suggestedParticipantIds.map(memberName).join(', ')
              : '스레드 참여자'}
          </span>
        </div>
      )}

      <div className="analysis-evidences">
        <span className="small muted">근거 발언</span>
        {item.evidences.length === 0 && <span className="small muted"> 없음</span>}
        <ul className="list">
          {item.evidences.map((e) => (
            <li key={e.evidenceId} className="row small">
              <strong>{e.authorName ?? '알 수 없음'}</strong>
              <span className={`grow clamp${e.isOriginDeleted ? ' muted' : ''}`}>{e.text}</span>
              {e.deepLink && (
                <a href={e.deepLink} target="_blank" rel="noreferrer">
                  원본
                </a>
              )}
              {editable && (
                <button className="link-button" onClick={() => run(() => unlinkEvidence(workspaceId, e.evidenceId))}>
                  해제
                </button>
              )}
            </li>
          ))}
        </ul>
        {editable && candidates.length > 0 && (
          <select
            className="input small"
            value=""
            onChange={(e) => e.target.value && run(() => linkEvidence(workspaceId, item.itemId, Number(e.target.value)))}
          >
            <option value="">+ 근거 발언 연결</option>
            {candidates.map((m) => (
              <option key={m.contentRefId} value={m.contentRefId}>
                {m.authorName ?? '?'}: {m.text.slice(0, 60)}
              </option>
            ))}
          </select>
        )}
      </div>

      {error && <div className="alert alert-error small">{error}</div>}
    </li>
  );
}

export function ItemEditor({
  itemType,
  members,
  initial,
  onSubmit,
  onCancel,
}: {
  itemType: ItemType;
  members: MemberResponse[];
  initial?: ItemForm;
  onSubmit: (form: ItemForm) => Promise<void>;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<ItemForm>(
    initial ?? {
      content: '',
      suggestedAssigneeId: null,
      suggestedDueDate: null,
      suggestedDatetime: null,
      suggestedParticipantIds: [],
    },
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await onSubmit({ ...form, content: form.content.trim() });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const toggleParticipant = (id: number) =>
    setForm((f) => ({
      ...f,
      suggestedParticipantIds: f.suggestedParticipantIds.includes(id)
        ? f.suggestedParticipantIds.filter((p) => p !== id)
        : [...f.suggestedParticipantIds, id],
    }));

  return (
    <form className="form" onSubmit={submit}>
      <textarea
        className="input"
        required
        maxLength={2000}
        placeholder="내용"
        value={form.content}
        onChange={(e) => setForm({ ...form, content: e.target.value })}
      />
      {itemType === 'FOLLOW_UP' && (
        <div className="row">
          <label className="grow">
            담당자
            <select
              className="input"
              value={form.suggestedAssigneeId ?? ''}
              onChange={(e) => setForm({ ...form, suggestedAssigneeId: e.target.value ? Number(e.target.value) : null })}
            >
              <option value="">미정</option>
              {members.map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.name}
                </option>
              ))}
            </select>
          </label>
          <label className="grow">
            마감일
            <input
              type="date"
              className="input"
              value={form.suggestedDueDate ?? ''}
              onChange={(e) => setForm({ ...form, suggestedDueDate: e.target.value || null })}
            />
          </label>
        </div>
      )}
      {itemType === 'MEETING_PROPOSAL' && (
        <>
          <label>
            일시
            <input
              type="datetime-local"
              className="input"
              value={form.suggestedDatetime?.slice(0, 16) ?? ''}
              onChange={(e) => setForm({ ...form, suggestedDatetime: e.target.value ? `${e.target.value}:00` : null })}
            />
          </label>
          <div className="tags">
            {members.map((m) => (
              <button
                type="button"
                key={m.userId}
                className={`tag selectable${form.suggestedParticipantIds.includes(m.userId) ? ' tag-done' : ''}`}
                onClick={() => toggleParticipant(m.userId)}
              >
                {m.name}
              </button>
            ))}
          </div>
          <span className="small muted">참여자를 고르지 않으면 스레드 참여자로 등록합니다.</span>
        </>
      )}
      {error && <div className="alert alert-error small">저장하지 못했습니다. {error}</div>}
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn btn-small" onClick={onCancel} disabled={saving}>
          취소
        </button>
        <button className="btn btn-primary btn-small" disabled={saving || !form.content.trim()}>
          {saving ? '저장 중...' : '저장'}
        </button>
      </div>
    </form>
  );
}
