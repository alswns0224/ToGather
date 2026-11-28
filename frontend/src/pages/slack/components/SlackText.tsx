import { Link, useParams } from 'react-router';

// Slack 본문의 링크 표기(<https://주소|이름>, <https://주소>)를 눌러 열 수 있는 링크로 보여 준다.
// (예: 파일을 첨부해 보내면 "📎 <Drive 주소|파일 이름>"이 붙는다)
// Google Drive 파일 링크는 Google Drive 대신 ToGather Drive 화면에서 그 파일을 연다.
const LINK = /<(https?:\/\/[^|>\s]+)(?:\|([^>]*))?>/g;
// Slack이 📎를 ":paperclip:"로 바꿔 돌려주므로 둘 다 지운다.
const PAPERCLIP = /(?::paperclip:|📎)[ \t]*/g;

/** drive.google.com/file/d/{id}, docs.google.com/.../d/{id}, ...?id={id} 에서 파일 ID를 꺼낸다. */
function driveFileId(url: string): string | null {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return null;
  }
  if (!/^(drive|docs)\.google\.com$/.test(parsed.hostname)) return null;
  const fromPath = parsed.pathname.match(/\/d\/([\w-]+)/)?.[1];
  return fromPath ?? parsed.searchParams.get('id');
}

export default function SlackText({ text }: { text: string }) {
  const { workspaceId } = useParams();
  const parts: (string | { url: string; label: string })[] = [];
  let last = 0;
  for (const match of text.matchAll(LINK)) {
    const at = match.index ?? 0;
    if (at > last) parts.push(text.slice(last, at));
    parts.push({ url: match[1], label: match[2]?.trim() || match[1] });
    last = at + match[0].length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return (
    <>
      {parts.map((part, i) => {
        if (typeof part === 'string') return part.replace(PAPERCLIP, '');
        const fileId = workspaceId ? driveFileId(part.url) : null;
        return fileId ? (
          <Link key={i} to={`/workspaces/${workspaceId}/drive?file=${encodeURIComponent(fileId)}`}
            onClick={(e) => e.stopPropagation()}>
            {part.label}
          </Link>
        ) : (
          <a key={i} href={part.url} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>
            {part.label}
          </a>
        );
      })}
    </>
  );
}
