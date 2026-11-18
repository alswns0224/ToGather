// UI-05 문서 보기 (블록 단위) — 글은 그대로, 표는 표로, 이미지는 이미지로 보여준다.
import type { NotionBlock } from './api';
import NotionText from './NotionText';

type Group = { key: string; kind: 'TEXT'; lines: string[] } | { key: string; kind: 'BLOCK'; block: NotionBlock };

/** 이어진 글 줄은 한 덩어리로 묶는다. */
function group(blocks: NotionBlock[]): Group[] {
  const groups: Group[] = [];
  for (const b of blocks) {
    const last = groups[groups.length - 1];
    if (b.kind === 'TEXT') {
      if (last?.kind === 'TEXT') last.lines.push(b.text ?? '');
      else groups.push({ key: b.id, kind: 'TEXT', lines: [b.text ?? ''] });
    } else {
      groups.push({ key: b.id, kind: 'BLOCK', block: b });
    }
  }
  return groups;
}

export default function BlockView({ blocks }: { blocks: NotionBlock[] }) {
  if (blocks.length === 0) return <div className="notion-body muted">(본문 없음)</div>;
  return (
    <div className="notion-body flex flex-col gap-3">
      {group(blocks).map((g) => {
        if (g.kind === 'TEXT') {
          return (
            <NotionText key={g.key} text={g.lines.join('\n')} />
          );
        }
        const b = g.block;
        if (b.kind === 'TABLE' && b.table) {
          return (
            <div key={g.key} className="overflow-x-auto">
              <table className="w-full border-collapse text-[14px]">
                <tbody>
                  {b.table.rows.map((row, i) => (
                    <tr key={row.id}>
                      {row.cells.map((cell, j) => {
                        const header = (b.table!.hasColumnHeader && i === 0) || (b.table!.hasRowHeader && j === 0);
                        const Cell = header ? 'th' : 'td';
                        return (
                          <Cell
                            key={j}
                            className={`border border-[var(--border)] px-3 py-2 text-left align-top ${header ? 'bg-[var(--surface-2)] font-bold' : ''}`}
                          >
                            {cell}
                          </Cell>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }
        if (b.kind === 'IMAGE') {
          return (
            <figure key={g.key} className="m-0 flex flex-col gap-1">
              {b.image?.url ? (
                <img src={b.image.url} alt={b.image.caption || '이미지'} className="max-h-[480px] max-w-full rounded-lg object-contain" />
              ) : (
                <div className="small muted">이미지를 불러올 수 없습니다.</div>
              )}
              {b.image?.caption && <figcaption className="text-xs text-[var(--muted)]">{b.image.caption}</figcaption>}
            </figure>
          );
        }
        // 코드·토글·하위 페이지 등: 내용만 보여준다. (수정은 Notion에서)
        return (
          <div key={g.key} className="notion-fixed-block pre">
            {b.label}
          </div>
        );
      })}
    </div>
  );
}
