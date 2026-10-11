import { useEffect, type ReactNode } from 'react';
import Icon from './Icon';

/** 디자인 시스템 모달: 제목 · 본문 · 하단 버튼. 배경을 누르거나 Esc를 누르면 닫힌다. */
export default function Dialog({
  title,
  children,
  footer,
  onClose,
  width,
}: {
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
  width?: number;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="dialog-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={title} style={width ? { width } : undefined}>
        <div className="dialog-head">
          <strong>{title}</strong>
          <button type="button" className="plain-icon" onClick={onClose} aria-label="닫기">
            <Icon name="close" size={16} />
          </button>
        </div>
        <div className="dialog-body">{children}</div>
        {footer && <div className="dialog-footer">{footer}</div>}
      </div>
    </div>
  );
}

/** 상태 태그: 연결됨·완료=success, 대기=muted, 주의=warning, 진행 중=active, 위험=danger */
export function StatusTag({
  tone,
  children,
}: {
  tone: 'success' | 'muted' | 'warning' | 'active' | 'danger' | 'violet';
  children: ReactNode;
}) {
  return (
    <span className={`status-tag ${tone}`}>
      <i />
      {children}
    </span>
  );
}
