/** 디자인 시스템 아바타: 프로필 사진이 있으면 사진, 없으면 이니셜이 든 색깔 원 */

const TONES = ['', 'violet', 'green', 'orange'] as const;

/** 같은 사람·워크스페이스는 늘 같은 색이 되도록 ID나 이름으로 색을 고른다. */
export function toneOf(key: string | number | null | undefined): (typeof TONES)[number] {
  const text = String(key ?? '');
  let hash = 0;
  for (let i = 0; i < text.length; i++) hash = (hash * 31 + text.charCodeAt(i)) | 0;
  return TONES[Math.abs(hash) % TONES.length];
}

/** 워크스페이스 아이콘 배경색 (DB에 색이 없어서 ID로 정한다) */
export const WORKSPACE_TONES = ['blue', 'violet', 'orange', 'green'] as const;
export const workspaceTone = (workspaceId: number | string) =>
  WORKSPACE_TONES[Math.abs(Number(workspaceId) || 0) % WORKSPACE_TONES.length];

export default function Avatar({
  name,
  image,
  toneKey,
  size,
}: {
  name: string | null | undefined;
  image?: string | null;
  /** 색을 정하는 값. 없으면 이름 */
  toneKey?: string | number | null;
  size?: 'small';
}) {
  const label = (name ?? '?').trim();
  const cls = `avatar-chip ${toneOf(toneKey ?? label)} ${size ?? ''}`.trim();
  if (image) return <img className={cls} src={image} alt={label} />;
  return (
    <span className={cls} title={label}>
      {label.slice(0, 1) || '?'}
    </span>
  );
}
