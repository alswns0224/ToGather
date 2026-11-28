import { useState, type FormEvent } from 'react';
import type { MemberResponse } from '../../workspace/api';
import { deleteItem, updateItem, type AnalysisItemResponse, type ItemForm, type ItemType } from '../api';
import { formatDate, formatDateTime } from '../../../utils/date';
import AnalysisIcon from './AnalysisIcon';

/** 분석 항목 한 개: 내용, 근거 발언, 담당자·마감일(후속 작업), 일시·참여자(회의 제안) */
export default function ItemCard({
  workspaceId,
  item,
  editable,
  members,
  onViewEvidence,
  onChanged,
}: {
  workspaceId: string;
  item: AnalysisItemResponse;
  editable: boolean;
  members: MemberResponse[];
  onViewEvidence: () => void;
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
  // 처리 결과에서 Task·일정을 만들었으면 담당자·마감·일시는 거기서 정해졌으므로 "미정" 대신 확정으로 보여준다.
  const outputDone =
    item.outputStatus === 'SUCCESS' && (item.itemType === 'FOLLOW_UP' || item.itemType === 'MEETING_PROPOSAL');
  const decidedElsewhere = outputDone ? (item.itemType === 'FOLLOW_UP' ? '팀 작업에서 지정' : '일정에서 지정') : '미정';

  return (
    <li className={`analysis-item type-${item.itemType.toLowerCase()} stack`}>
      <div className="row">
        <span className="pre analysis-item-content">{item.content}</span>
        {outputDone ? (
          <span className="badge badge-success">
            확정 · {item.itemType === 'FOLLOW_UP' ? '팀 작업 생성됨' : '일정 등록됨'}
          </span>
        ) : (
          item.undetermined && <span className="badge badge-warning">미정</span>
        )}
        {editable && (
          <details
            className="analysis-item-menu"
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.currentTarget.open = false;
                event.currentTarget.querySelector('summary')?.focus();
                event.stopPropagation();
              }
            }}
            onBlur={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
            }}
          >
            <summary aria-label="항목 관리">
              <AnalysisIcon name="more" />
            </summary>
            <div>
              <button type="button" onClick={() => setEditing(true)}>
                수정
              </button>
              <button
                type="button"
                className="is-danger"
                onClick={(event) => {
                  event.currentTarget.closest('details')?.removeAttribute('open');
                  remove();
                }}
              >
                삭제
              </button>
            </div>
          </details>
        )}
      </div>

      {item.itemType === 'FOLLOW_UP' && (
        <div className="analysis-item-meta">
          <span className="analysis-meta-pill">담당자: {item.suggestedAssigneeName ?? decidedElsewhere}</span>
          <span className="analysis-meta-pill">
            마감일: {item.suggestedDueDate ? formatDate(item.suggestedDueDate) : decidedElsewhere}
          </span>
        </div>
      )}
      {item.itemType === 'MEETING_PROPOSAL' && (
        <div className="analysis-item-meta">
          <span className="analysis-meta-pill">
            일시: {item.suggestedDatetime ? formatDateTime(item.suggestedDatetime) : decidedElsewhere}
          </span>
          <span className="analysis-meta-pill">
            참여자:{' '}
            {item.suggestedParticipantIds?.length
              ? item.suggestedParticipantIds.map(memberName).join(', ')
              : '스레드 참여자'}
          </span>
        </div>
      )}

      <button type="button" className="analysis-evidence-button" aria-haspopup="dialog" onClick={onViewEvidence}>
        <AnalysisIcon name="quote" className="h-3.5 w-3.5" />
        근거 {item.evidences.length}개<AnalysisIcon name="chevron" className="h-3 w-3" />
      </button>

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
        placeholder="정리할 내용을 입력하세요"
        autoFocus
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
              onChange={(e) =>
                setForm({ ...form, suggestedAssigneeId: e.target.value ? Number(e.target.value) : null })
              }
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
