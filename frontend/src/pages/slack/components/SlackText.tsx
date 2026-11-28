// Slack 본문의 링크 표기(<https://주소|이름>, <https://주소>)를 눌러 열 수 있는 링크로 보여 준다.
// (예: 파일을 첨부해 보내면 "📎 <Drive 주소|파일 이름>"이 붙는다)
const LINK = /<(https?:\/\/[^|>\s]+)(?:\|([^>]*))?>/g;

export default function SlackText({ text }: { text: string }) {
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
      {parts.map((part, i) =>
        typeof part === 'string' ? (
          part
        ) : (
          <a key={i} href={part.url} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>
            {part.label}
          </a>
        ),
      )}
    </>
  );
}
