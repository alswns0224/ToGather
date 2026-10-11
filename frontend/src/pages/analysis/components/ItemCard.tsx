import { useState } from 'react';
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
import Avatar from '../../../components/Avatar';
import Dialog, { StatusTag } from '../../../components/Dialog';
import Icon from '../../../components/Icon';
import { formatDate, formatDateTime } from '../../../utils/date';

/**
 * 분석 항목 카드 (UI-08): 내용, 제안 담당자·마감일(후속 작업) 또는 제안 일시(회의 제안),
 * 근거 발언 인용 칩, 수정·삭제·근거 연결. 담당자·일시가 정해지지 않은 항목은 주황색으로 표시한다.
 */
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
  const [picking, setPicking] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const memberName = (id: number) => members.find((m) => m.userId === id)?.name ?? `#${id}`;

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <article className={`analysis-item${item.undetermined ? ' unconfirmed' : ''}`}>
      <div className="analysis-item-main">
        <div className="analysis-item-title">
          <strong>{item.content}</strong>
          {item.undetermined && <span>{item.itemType === 'MEETING_PROPOSAL' ? '일시 미정' : '담당·마감 미정'}</span>}
          {item.outputStatus === 'SUCCESS' && (
            <span className="output-tag">
              <StatusTag tone="success">{item.itemType === 'FOLLOW_UP' ? 'Task 생성됨' : '일정 등록됨'}</StatusTag>
            </span>
          )}
          {item.outputStatus === 'FAILED' && (
            <span className="output-tag">
              <StatusTag tone="danger">생성 실패</StatusTag>
            </span>
          )}
        </div>

        {editing ? (
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
        ) : (
          <>
            {item.itemType === 'FOLLOW_UP' && (
              <div className="follow-up-meta">
                <span>
                  {item.suggestedAssigneeName && <Avatar name={item.suggestedAssigneeName} toneKey={item.suggestedAssigneeId} />}
                  제안 담당자 <strong>{item.suggestedAssigneeName ?? '미정'}</strong>
                </span>
                <span>
                  <Icon name="calendar" size={13} />
                  마감일 <strong>{item.suggestedDueDate ? formatDate(item.suggestedDueDate) : '미정'}</strong>
                </span>
              </div>
            )}
            {item.itemType === 'MEETING_PROPOSAL' && (
              <div className="follow-up-meta">
                <span>
                  <Icon name="clock" size={13} />
                  제안 일시 <strong>{item.suggestedDatetime ? formatDateTime(item.suggestedDatetime) : '미정'}</strong>
                </span>
                <span>
                  <Icon name="users" size={13} />
                  참여자{' '}
                  <strong>
                    {item.suggestedParticipantIds?.length
                      ? item.suggestedParticipantIds.map(memberName).join(', ')
                      : '스레드 참여자'}
                  </strong>
                </span>
              </div>
            )}
          </>
        )}

        {item.evidences.map((e) => (
          <div className="evidence-row" key={e.evidenceId}>
            <a
              className="evidence-chip"
              href={e.deepLink ?? undefined}
              target="_blank"
              rel="noreferrer"
              title={`${e.authorName ?? ''}: ${e.text}`}
            >
              <Icon name="chat" size={13} />
              {e.authorName ? `${e.authorName}: ` : ''}“{e.text}”
            </a>
            {editable && (
              <button
                type="button"
                className="unlink"
                aria-label="근거 해제"
                title="근거 해제"
                onClick={() => run(() => unlinkEvidence(workspaceId, e.evidenceId))}
              >
                <Icon name="close" size={13} />
              </button>
            )}
          </div>
        ))}
        {error && <div className="notice error" style={{ marginTop: 8 }}>{error}</div>}
      </div>

      {editable && !editing && (
        <div className="analysis-item-actions">
          <button type="button" onClick={() => setEditing(true)}>
            <Icon name="edit" size={13} />
            수정
          </button>
          <button type="button" onClick={() => setDeleting(true)}>
            <Icon name="trash" size={13} />
            삭제
          </button>
          <button type="button" onClick={() => setPicking(true)}>
            <Icon name="link" size={13} />
            근거 연결
          </button>
        </div>
      )}

      {picking && (
        <EvidencePicker
          messages={messages.filter((m) => !item.evidences.some((e) => e.contentRefId === m.contentRefId))}
          onClose={() => setPicking(false)}
          onPick={async (contentRefId) => {
            await linkEvidence(workspaceId, item.itemId, contentRefId);
            setPicking(false);
            onChanged();
          }}
        />
      )}
      {deleting && (
        <Dialog
          title="항목 삭제"
          onClose={() => setDeleting(false)}
          footer={
            <>
              <button type="button" className="ui-button secondary" onClick={() => setDeleting(false)}>
                취소
              </button>
              <button
                type="button"
                className="ui-button danger"
                onClick={() => {
                  setDeleting(false);
                  void run(() => deleteItem(workspaceId, item.itemId));
                }}
              >
                삭제
              </button>
            </>
          }
        >
          <div className="delete-copy">
            <div className="delete-icon">
              <Icon name="trash" />
            </div>
            <div>
              <strong>이 항목을 삭제할까요?</strong>
              <span>연결된 근거 발언도 함께 삭제돼요.</span>
            </div>
          </div>
        </Dialog>
      )}
    </article>
  );
}

/** 항목 수정·추가 폼: 내용 + (후속 작업) 담당자·마감일 / (회의 제안) 일시·참여자 */
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
  const [touched, setTouched] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const contentError = touched && !form.content.trim() ? '내용을 입력해 주세요.' : '';

  const submit = async () => {
    setTouched(true);
    if (!form.content.trim()) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit({ ...form, content: form.content.trim() });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const toggle = (id: number) =>
    setForm((f) => ({
      ...f,
      suggestedParticipantIds: f.suggestedParticipantIds.includes(id)
        ? f.suggestedParticipantIds.filter((p) => p !== id)
        : [...f.suggestedParticipantIds, id],
    }));

  return (
    <div className="analysis-item-edit">
      <div className={`field${contentError ? ' has-error' : ''}`}>
        <textarea
          maxLength={2000}
          placeholder="내용"
          value={form.content}
          onChange={(e) => setForm({ ...form, content: e.target.value })}
          autoFocus
        />
        {contentError && <em>{contentError}</em>}
      </div>
      {itemType === 'FOLLOW_UP' && (
        <div className="row2">
          <label className="field">
            <span>담당자</span>
            <select
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
          <label className="field">
            <span>마감일</span>
            <input
              type="date"
              value={form.suggestedDueDate ?? ''}
              onChange={(e) => setForm({ ...form, suggestedDueDate: e.target.value || null })}
            />
          </label>
        </div>
      )}
      {itemType === 'MEETING_PROPOSAL' && (
        <>
          <label className="field">
            <span>제안 일시</span>
            <input
              type="datetime-local"
              value={form.suggestedDatetime?.slice(0, 16) ?? ''}
              onChange={(e) => setForm({ ...form, suggestedDatetime: e.target.value ? `${e.target.value}:00` : null })}
            />
          </label>
          <div className="field">
            <span>
              참여자<small>고르지 않으면 스레드 참여자로 등록해요</small>
            </span>
            <div className="participant-tags">
              {members.map((m) => (
                <button
                  type="button"
                  key={m.userId}
                  className={form.suggestedParticipantIds.includes(m.userId) ? 'on' : ''}
                  onClick={() => toggle(m.userId)}
                >
                  {m.name}
                </button>
              ))}
            </div>
          </div>
        </>
      )}
      {error && <div className="notice error" style={{ marginTop: 8 }}>저장하지 못했어요. {error}</div>}
      <div>
        <button type="button" className="ui-button secondary" onClick={onCancel} disabled={saving}>
          취소
        </button>
        <button type="button" className="ui-button primary" onClick={submit} disabled={saving}>
          {saving ? '저장 중' : '저장'}
        </button>
      </div>
    </div>
  );
}

function EvidencePicker({
  messages,
  onPick,
  onClose,
}: {
  messages: ThreadMessageResponse[];
  onPick: (contentRefId: number) => Promise<void>;
  onClose: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog title="근거 발언 연결" width={520} onClose={onClose}>
      {messages.length === 0 ? (
        <p className="editor-hint">연결할 수 있는 메시지가 없어요.</p>
      ) : (
        <div className="output-list">
          {messages.map((m) => (
            <button
              type="button"
              key={m.contentRefId}
              className="output-card"
              style={{ textAlign: 'left', cursor: 'pointer', background: '#fff' }}
              onClick={() => onPick(m.contentRefId).catch((e: Error) => setError(e.message))}
            >
              <span className="head">
                <strong>{m.authorName ?? '알 수 없음'}</strong>
                <span className="editor-hint">{m.createdAt ? formatDateTime(m.createdAt) : ''}</span>
              </span>
              <span style={{ fontSize: 13, color: '#5f6a79' }}>{m.text}</span>
            </button>
          ))}
        </div>
      )}
      {error && <div className="notice error" style={{ marginTop: 8 }}>{error}</div>}
    </Dialog>
  );
}
