import type { ReactNode } from 'react';
import Icon from '../../../components/Icon';

/**
 * 백엔드가 주는 문서 본문(# 제목, - 목록, 1. 번호, [ ] 할 일, > 인용)을 Notion처럼 보여준다.
 * (NotionBlocks.toText와 같은 형식)
 */
export default function DocumentBody({ text }: { text: string | null }) {
  if (!text?.trim()) return <p className="editor-hint">(본문 없음)</p>;
  const out: ReactNode[] = [];
  let list: { ordered: boolean; items: string[] } | null = null;
  const flush = () => {
    if (!list) return;
    const items = list.items.map((t, i) => <li key={i}>{t}</li>);
    out.push(list.ordered ? <ol key={out.length}>{items}</ol> : <ul key={out.length}>{items}</ul>);
    list = null;
  };
  for (const raw of text.split('\n')) {
    const line = raw.trimEnd();
    const bullet = /^[-*] (.*)$/.exec(line);
    const numbered = /^\d+\. (.*)$/.exec(line);
    if (bullet || numbered) {
      const ordered = !!numbered;
      if (!list || list.ordered !== ordered) {
        flush();
        list = { ordered, items: [] };
      }
      list.items.push((bullet ?? numbered)![1]);
      continue;
    }
    flush();
    const key = out.length;
    if (!line.trim()) continue;
    if (line.startsWith('### ')) out.push(<h4 key={key}>{line.slice(4)}</h4>);
    else if (line.startsWith('## ')) out.push(<h4 key={key}>{line.slice(3)}</h4>);
    else if (line.startsWith('# ')) out.push(<h3 key={key}>{line.slice(2)}</h3>);
    else if (line === '---') out.push(<hr key={key} />);
    else if (line.startsWith('[ ] ') || line.startsWith('[x] ')) {
      const done = line.startsWith('[x] ');
      out.push(
        <div key={key} className={`todo${done ? ' done' : ''}`}>
          <input type="checkbox" checked={done} readOnly />
          <span>{line.slice(4)}</span>
        </div>,
      );
    } else if (line.startsWith('> ')) {
      out.push(
        <div key={key} className="notion-callout">
          <Icon name="quote" size={16} />
          <span>{line.slice(2)}</span>
        </div>,
      );
    } else out.push(<p key={key}>{line}</p>);
  }
  flush();
  return <>{out}</>;
}
