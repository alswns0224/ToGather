// Notion 글 블록 보기: 서버가 주는 한 줄 형식(# 제목, - 목록, 1. 번호, [ ] 할 일, > 인용, ---)을
// 기호 그대로가 아니라 Notion처럼 제목·체크박스·인용으로 그린다. (편집기에서는 같은 형식으로 고친다)
import type { ReactNode } from 'react';

const URL_PATTERN = /(https?:\/\/[^\s)]+)/g;

/** 글 안의 주소는 누를 수 있는 링크로 */
function linkify(text: string): ReactNode[] {
  return text.split(URL_PATTERN).map((part, i) =>
    /^https?:\/\//.test(part) ? (
      <a key={i} href={part} target="_blank" rel="noreferrer" className="break-all text-[var(--primary)] underline">
        {part}
      </a>
    ) : (
      part
    ),
  );
}

function Line({ line }: { line: string }) {
  if (line.trim() === '') return <div className="h-2" />;
  if (line === '---') return <hr className="my-2 border-[var(--border-soft)]" />;
  if (line.startsWith('```')) return null; // 코드 블록 표시는 감춘다 (내용 줄은 그대로 보인다)

  const heading = /^(#{1,3}) (.*)$/.exec(line);
  if (heading) {
    const size = heading[1].length === 1 ? 'text-xl' : heading[1].length === 2 ? 'text-lg' : 'text-base';
    return <div className={`${size} mt-3 font-extrabold`}>{linkify(heading[2])}</div>;
  }
  const todo = /^\[( |x)\] (.*)$/.exec(line);
  if (todo) {
    const done = todo[1] === 'x';
    return (
      <div className="flex items-start gap-2">
        <span
          aria-hidden="true"
          className={`mt-[3px] inline-flex h-4 w-4 flex-shrink-0 items-center justify-center rounded border text-[11px] ${
            done ? 'border-[var(--primary)] bg-[var(--primary)] text-white' : 'border-[var(--border)]'
          }`}
        >
          {done ? '✓' : ''}
        </span>
        <span className={done ? 'text-[var(--muted)] line-through' : ''}>{linkify(todo[2])}</span>
      </div>
    );
  }
  if (line.startsWith('> ')) {
    return (
      <div className="ml-6 border-l-[3px] border-[var(--border)] pl-3 text-[14px] text-[var(--muted)]">
        {linkify(line.slice(2))}
      </div>
    );
  }
  const bullet = /^[-*] (.*)$/.exec(line);
  if (bullet) {
    return (
      <div className="flex items-start gap-2">
        <span className="flex-shrink-0 select-none">•</span>
        <span>{linkify(bullet[1])}</span>
      </div>
    );
  }
  const numbered = /^(\d+)\. (.*)$/.exec(line);
  if (numbered) {
    return (
      <div className="flex items-start gap-2">
        <span className="flex-shrink-0 select-none tabular-nums">{numbered[1]}.</span>
        <span>{linkify(numbered[2])}</span>
      </div>
    );
  }
  return <div className="whitespace-pre-wrap">{linkify(line)}</div>;
}

/** 여러 줄 글 (줄마다 블록 한 개) */
export default function NotionText({ text }: { text: string }) {
  return (
    <div className="flex flex-col gap-1 leading-relaxed">
      {text.split('\n').map((line, i) => (
        <Line key={i} line={line} />
      ))}
    </div>
  );
}
