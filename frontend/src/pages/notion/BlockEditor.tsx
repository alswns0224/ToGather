// UI-05 문서 수정 (블록 단위) — 이미지·표가 있는 문서도 ToGather에서 고친다.
// 글은 이미지·표 사이 구간마다 글 칸으로, 표는 칸 단위로, 이미지는 바꾸기·캡션·삭제·추가로 고친다.
// 코드·토글·하위 페이지 같은 블록은 자리만 보여주고 그대로 둔다. 저장하면 고친 부분만 Notion에 반영한다.
import { useEffect, useState, type FormEvent } from 'react';
import { getPageBlocks, savePageBlocks, uploadPageImage, type NotionBlock, type NotionSaveItem } from './api';
import { ErrorBox, Loading } from '../../components/Status';

type TextItem = { key: string; kind: 'TEXT'; blockIds: string[]; original: string; text: string };
type FixedItem = { key: string; kind: 'FIXED'; id: string; label: string };
type TableRowState = { id: string | null; cells: string[]; original: string[] };
type TableItem = {
  key: string;
  kind: 'TABLE';
  id: string;
  width: number;
  hasColumnHeader: boolean;
  rows: TableRowState[];
  deletedRowIds: string[];
};
type ImageItem = {
  key: string;
  kind: 'IMAGE';
  id: string | null;
  /** 화면에 보여줄 주소 (새로 고른 파일이면 미리보기 주소) */
  preview: string | null;
  caption: string;
  originalCaption: string;
  /** 새로 올린 파일 / 새 외부 주소 (바꾸거나 새로 넣을 때) */
  fileUploadId: string | null;
  url: string | null;
  deleted: boolean;
  uploading: boolean;
};
type Item = TextItem | FixedItem | TableItem | ImageItem;

let seq = 0;
const nextKey = () => `k${++seq}`;

/** 블록 목록 → 편집 칸. 이어진 글은 한 칸으로 묶고, 이미지·표·고정 블록 뒤에는 글을 쓸 수 있는 빈 칸을 둔다. */
function toItems(blocks: NotionBlock[]): Item[] {
  const items: Item[] = [];
  const lastText = () => {
    const last = items[items.length - 1];
    return last?.kind === 'TEXT' ? last : null;
  };
  for (const b of blocks) {
    if (b.kind === 'TEXT') {
      const t = lastText();
      if (t) {
        t.blockIds.push(b.id);
        t.original = t.original ? `${t.original}\n${b.text ?? ''}` : (b.text ?? '');
        t.text = t.original;
      } else {
        items.push({ key: nextKey(), kind: 'TEXT', blockIds: [b.id], original: b.text ?? '', text: b.text ?? '' });
      }
      continue;
    }
    // 이미지·표·고정 블록 앞에 글 칸이 없으면(맨 앞 제외) 빈 칸을 둔다.
    if (items.length > 0 && !lastText()) {
      items.push({ key: nextKey(), kind: 'TEXT', blockIds: [], original: '', text: '' });
    }
    if (b.kind === 'TABLE' && b.table) {
      items.push({
        key: nextKey(),
        kind: 'TABLE',
        id: b.id,
        width: b.table.width,
        hasColumnHeader: b.table.hasColumnHeader,
        rows: b.table.rows.map((r) => ({ id: r.id, cells: padCells(r.cells, b.table!.width), original: padCells(r.cells, b.table!.width) })),
        deletedRowIds: [],
      });
    } else if (b.kind === 'IMAGE') {
      const caption = b.image?.caption ?? '';
      items.push({
        key: nextKey(),
        kind: 'IMAGE',
        id: b.id,
        preview: b.image?.url ?? null,
        caption,
        originalCaption: caption,
        fileUploadId: null,
        url: null,
        deleted: false,
        uploading: false,
      });
    } else {
      items.push({ key: nextKey(), kind: 'FIXED', id: b.id, label: b.label ?? 'Notion 블록' });
    }
  }
  // 마지막에 글을 이어 쓸 수 있는 칸 (빈 문서면 이 칸 하나)
  if (!lastText()) items.push({ key: nextKey(), kind: 'TEXT', blockIds: [], original: '', text: '' });
  return items;
}

function padCells(cells: string[], width: number) {
  return Array.from({ length: width }, (_, i) => cells[i] ?? '');
}

function toSaveItems(items: Item[]): NotionSaveItem[] {
  return items.map((item): NotionSaveItem => {
    switch (item.kind) {
      case 'TEXT':
        return { kind: 'TEXT', blockIds: item.blockIds, text: item.text, changed: item.text !== item.original };
      case 'FIXED':
        return { kind: 'FIXED', id: item.id };
      case 'TABLE':
        return {
          kind: 'TABLE',
          id: item.id,
          width: item.width,
          rows: item.rows.map((r) => ({ id: r.id, cells: r.cells, changed: r.cells.some((c, i) => c !== r.original[i]) })),
          deletedRowIds: item.deletedRowIds,
        };
      case 'IMAGE':
        return {
          kind: 'IMAGE',
          id: item.id,
          fileUploadId: item.fileUploadId,
          url: item.url,
          caption: item.caption,
          changed: !!item.fileUploadId || !!item.url || item.caption !== item.originalCaption,
          deleted: item.deleted,
        };
    }
  });
}

export default function BlockEditor({
  workspaceId,
  id,
  onCancel,
  onSaved,
}: {
  workspaceId: string;
  id: number;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [title, setTitle] = useState('');
  const [originalTitle, setOriginalTitle] = useState('');
  const [items, setItems] = useState<Item[] | null>(null);
  const [loadError, setLoadError] = useState<Error | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setLoadError(null);
    setItems(null);
    getPageBlocks(workspaceId, id)
      .then((res) => {
        setTitle(res.title);
        setOriginalTitle(res.title);
        setItems(toItems(res.blocks));
      })
      .catch((e: Error) => setLoadError(e));
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, [workspaceId, id]);

  const update = (key: string, patch: (item: Item) => Item) =>
    setItems((prev) => prev && prev.map((it) => (it.key === key ? patch(it) : it)));

  /** key 칸 뒤에 새 이미지 칸(+ 그 뒤에 글 칸)을 넣고 파일을 올린다. */
  const addImage = async (afterKey: string, file: File) => {
    const imageKey = nextKey();
    const preview = URL.createObjectURL(file);
    setItems((prev) => {
      if (!prev) return prev;
      const index = prev.findIndex((it) => it.key === afterKey);
      const image: ImageItem = {
        key: imageKey,
        kind: 'IMAGE',
        id: null,
        preview,
        caption: '',
        originalCaption: '',
        fileUploadId: null,
        url: null,
        deleted: false,
        uploading: true,
      };
      const after: TextItem = { key: nextKey(), kind: 'TEXT', blockIds: [], original: '', text: '' };
      return [...prev.slice(0, index + 1), image, after, ...prev.slice(index + 1)];
    });
    await upload(imageKey, file);
  };

  const upload = async (key: string, file: File) => {
    setError(null);
    update(key, (it) => ({ ...(it as ImageItem), uploading: true, preview: URL.createObjectURL(file) }));
    try {
      const { fileUploadId } = await uploadPageImage(workspaceId, id, file);
      update(key, (it) => ({ ...(it as ImageItem), uploading: false, fileUploadId, url: null }));
    } catch (e) {
      setError(`이미지를 올리지 못했습니다. ${(e as Error).message}`);
      // 새 이미지면 칸을 없애고, 기존 이미지면 원래대로 둔다.
      setItems((prev) =>
        prev &&
        prev.flatMap((it) => {
          if (it.key !== key || it.kind !== 'IMAGE') return [it];
          return it.id === null ? [] : [{ ...it, uploading: false }];
        }),
      );
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!items) return;
    setSaving(true);
    setError(null);
    try {
      await savePageBlocks(workspaceId, id, {
        title: title.trim() !== originalTitle ? title.trim() : undefined,
        items: toSaveItems(items),
      });
      onSaved();
    } catch (err) {
      setError(`저장하지 못했습니다. ${(err as Error).message}`); // 입력 내용은 유지
    } finally {
      setSaving(false);
    }
  };

  if (loadError) {
    return (
      <div className="card">
        <ErrorBox error={loadError} onRetry={load} />
        <div className="row">
          <span className="spacer" />
          <button type="button" className="btn" onClick={onCancel}>
            닫기
          </button>
        </div>
      </div>
    );
  }
  if (!items) return <div className="card"><Loading /></div>;

  const uploading = items.some((it) => it.kind === 'IMAGE' && it.uploading);

  return (
    <form className="form card notion-block-editor" onSubmit={submit}>
      <strong className="text-[17px]">문서 수정</strong>
      <label>
        제목
        <input className="input" required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} />
      </label>
      <span className="small muted">
        글은 # 제목, - 목록, 1. 번호, [ ] 할 일, &gt; 인용 형식으로 씁니다. 저장하면 고친 부분만 Notion에 반영돼요.
      </span>

      {items.map((item, index) => {
        switch (item.kind) {
          case 'TEXT':
            return (
              <div key={item.key} className="flex flex-col gap-1">
                <textarea
                  className="input notion-editor !min-h-[72px]"
                  maxLength={20000}
                  placeholder={index === items.length - 1 ? '여기에 이어서 쓰기' : '여기에 글 추가'}
                  value={item.text}
                  onChange={(e) => update(item.key, (it) => ({ ...(it as TextItem), text: e.target.value }))}
                />
                {index > 0 || item.blockIds.length > 0 ? <AddImageButton onPick={(f) => addImage(item.key, f)} /> : null}
              </div>
            );
          case 'FIXED':
            return (
              <div key={item.key} className="notion-fixed-block" title="이 블록은 Notion에서 수정해 주세요. 그대로 유지됩니다.">
                🔒 {item.label}
              </div>
            );
          case 'TABLE':
            return <TableEditor key={item.key} item={item} onChange={(next) => update(item.key, () => next)} />;
          case 'IMAGE':
            if (item.deleted) {
              return (
                <div key={item.key} className="notion-fixed-block">
                  🗑 이미지를 삭제합니다.{' '}
                  <button type="button" className="btn btn-small" onClick={() => update(item.key, (it) => ({ ...(it as ImageItem), deleted: false }))}>
                    되돌리기
                  </button>
                </div>
              );
            }
            return (
              <ImageEditor
                key={item.key}
                item={item}
                onCaption={(caption) => update(item.key, (it) => ({ ...(it as ImageItem), caption }))}
                onReplace={(file) => upload(item.key, file)}
                onDelete={() =>
                  item.id === null
                    ? setItems((prev) => prev && prev.filter((it) => it.key !== item.key))
                    : update(item.key, (it) => ({ ...(it as ImageItem), deleted: true }))
                }
              />
            );
        }
      })}

      {error && <div className="alert alert-error">{error}</div>}
      <div className="row">
        <span className="spacer" />
        <button type="button" className="btn" onClick={onCancel} disabled={saving}>
          취소
        </button>
        <button className="btn btn-primary" disabled={saving || uploading || !title.trim()}>
          {saving ? '저장 중...' : uploading ? '이미지 올리는 중...' : '저장'}
        </button>
      </div>
    </form>
  );
}

function AddImageButton({ onPick }: { onPick: (file: File) => void }) {
  return (
    <label className="btn btn-small self-start cursor-pointer">
      ＋ 이미지 추가
      <input
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          if (file) onPick(file);
        }}
      />
    </label>
  );
}

function ImageEditor({
  item,
  onCaption,
  onReplace,
  onDelete,
}: {
  item: ImageItem;
  onCaption: (caption: string) => void;
  onReplace: (file: File) => void;
  onDelete: () => void;
}) {
  return (
    <div className="notion-image-edit">
      {item.preview ? (
        <img src={item.preview} alt={item.caption || '이미지'} className="max-h-64 max-w-full rounded-lg object-contain" />
      ) : (
        <div className="small muted">이미지를 불러올 수 없습니다.</div>
      )}
      {item.uploading && <span className="small muted">이미지 올리는 중...</span>}
      <input
        className="input"
        placeholder="캡션 (선택)"
        maxLength={2000}
        value={item.caption}
        onChange={(e) => onCaption(e.target.value)}
      />
      <div className="flex gap-2">
        <label className="btn btn-small cursor-pointer">
          이미지 바꾸기
          <input
            type="file"
            accept="image/*"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = '';
              if (file) onReplace(file);
            }}
          />
        </label>
        <button type="button" className="btn btn-small btn-danger" onClick={onDelete}>
          이미지 삭제
        </button>
      </div>
    </div>
  );
}

function TableEditor({ item, onChange }: { item: TableItem; onChange: (next: TableItem) => void }) {
  const setCell = (rowIndex: number, col: number, value: string) =>
    onChange({
      ...item,
      rows: item.rows.map((r, i) => (i === rowIndex ? { ...r, cells: r.cells.map((c, j) => (j === col ? value : c)) } : r)),
    });
  const removeRow = (rowIndex: number) => {
    const row = item.rows[rowIndex];
    onChange({
      ...item,
      rows: item.rows.filter((_, i) => i !== rowIndex),
      deletedRowIds: row.id ? [...item.deletedRowIds, row.id] : item.deletedRowIds,
    });
  };
  const addRow = () =>
    onChange({ ...item, rows: [...item.rows, { id: null, cells: Array(item.width).fill(''), original: Array(item.width).fill('') }] });

  return (
    <div className="notion-table-edit">
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-[13px]">
          <tbody>
            {item.rows.map((row, i) => (
              <tr key={row.id ?? `new-${i}`}>
                {row.cells.map((cell, j) => (
                  <td key={j} className="border border-[var(--border)] p-0">
                    <input
                      className={`w-full bg-transparent px-2 py-1.5 outline-none ${item.hasColumnHeader && i === 0 ? 'font-bold' : ''}`}
                      value={cell}
                      onChange={(e) => setCell(i, j, e.target.value)}
                    />
                  </td>
                ))}
                <td className="w-8 text-center">
                  <button type="button" className="text-[var(--muted)] hover:text-[var(--danger)]" title="행 삭제" onClick={() => removeRow(i)}>
                    ✕
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <button type="button" className="btn btn-small self-start" onClick={addRow}>
        ＋ 행 추가
      </button>
    </div>
  );
}
