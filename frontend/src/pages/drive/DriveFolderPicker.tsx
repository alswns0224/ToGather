import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { getDriveSubfolders, type DriveSubfolder } from './api';
import { DriveChevron, DriveFolderIcon as FolderIcon } from './DriveFolderIcons';
import './DriveFolderPicker.css';

export interface DriveFolderPathEntry { id: string; name: string }
type Bounds = Pick<DOMRect, 'left' | 'right' | 'top' | 'bottom'>;
type Branch = { folder: DriveFolderPathEntry; rootId: string; path: DriveFolderPathEntry[]; anchor: Bounds };
type Children = { status: 'loading' | 'ready' | 'error'; items: DriveSubfolder[] };

/** Portals keep the dropdown and flyouts outside the file card's overflow clipping. */
function FolderPanel({ depth, anchor, compact, label, onKeyDown, onScroll, children }: {
  depth: number; anchor: Bounds; compact: boolean; label: string;
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void; onScroll: () => void; children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: anchor.left, top: anchor.bottom + 6 });
  useLayoutEffect(() => {
    const panel = ref.current;
    if (!panel) return;
    const width = panel.offsetWidth;
    const height = panel.offsetHeight;
    const viewportWidth = document.documentElement.clientWidth;
    const viewportHeight = window.innerHeight;
    let left = anchor.left;
    let top = anchor.bottom + 6;
    if (depth > 0 && !compact) {
      left = anchor.right + 10 + width <= viewportWidth - 8 ? anchor.right + 10 : anchor.left - width - 10;
      top = anchor.top - 5;
    } else if (top + height > viewportHeight - 8 && anchor.top - height - 6 >= 8) {
      top = anchor.top - height - 6;
    }
    left = Math.max(8, Math.min(left, viewportWidth - width - 8));
    top = Math.max(8, Math.min(top, viewportHeight - height - 8));
    setPosition(previous => previous.left === left && previous.top === top ? previous : { left, top });
  }, [anchor, compact, depth, children]);
  return <div ref={ref} className="drive-folder-menu" role="menu" aria-label={label}
    data-folder-menu-depth={depth} style={position} onKeyDown={onKeyDown} onScroll={onScroll}>
    {children}
  </div>;
}

export default function DriveFolderPicker({ workspaceId, folders, connectedCount, activeFolderId, path, disabled, onChange, showAll = true, label: pickerLabel = '연결된 폴더' }: {
  workspaceId: string; folders: DriveFolderPathEntry[]; activeFolderId: string | null;
  connectedCount?: number;
  path: DriveFolderPathEntry[]; disabled: boolean;
  onChange: (rootId: string | null, path: DriveFolderPathEntry[]) => void;
  showAll?: boolean;
  label?: string;
}) {
  const id = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const popup = useRef<HTMLDivElement>(null);
  const generation = useRef(0);
  const cache = useRef<Record<string, Children>>({});
  const [children, setChildren] = useState<Record<string, Children>>({});
  const [open, setOpen] = useState(false);
  const [anchor, setAnchor] = useState<Bounds | null>(null);
  const [trail, setTrail] = useState<Branch[]>([]);
  const [focusDepth, setFocusDepth] = useState<number | null>(null);
  const [compact, setCompact] = useState(() => window.matchMedia('(max-width: 640px), (hover: none)').matches);
  const root = folders.find(folder => folder.id === activeFolderId);
  const selectedName = path.at(-1)?.name ?? root?.name ?? (showAll ? '전체 파일' : folders[0]?.name ?? '폴더 선택');
  const selectedPath = [root?.name, ...path.map(folder => folder.name)].filter(Boolean).join(' / ');

  const close = useCallback((restoreFocus = false) => {
    generation.current++;
    setOpen(false);
    setTrail([]);
    setFocusDepth(null);
    if (restoreFocus) trigger.current?.focus();
  }, []);

  useEffect(() => {
    const media = window.matchMedia('(max-width: 640px), (hover: none)');
    const update = () => { setCompact(media.matches); close(); };
    media.addEventListener('change', update);
    return () => { media.removeEventListener('change', update); generation.current++; };
  }, [close]);

  useEffect(() => { if (disabled) close(); }, [disabled, close]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: Event) => {
      const target = event.target;
      if (target instanceof Node && !popup.current?.contains(target) && !trigger.current?.contains(target)) close();
    };
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); close(true); }
    };
    const resize = () => close();
    document.addEventListener('pointerdown', outside);
    window.addEventListener('scroll', outside, true);
    window.addEventListener('resize', resize);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      window.removeEventListener('scroll', outside, true);
      window.removeEventListener('resize', resize);
      document.removeEventListener('keydown', escape);
    };
  }, [open, close]);

  useLayoutEffect(() => {
    if (!open || focusDepth == null) return;
    const choice = popup.current?.querySelector<HTMLButtonElement>(`[data-folder-menu-depth="${focusDepth}"] [data-menu-choice]`);
    if (choice) { choice.focus(); setFocusDepth(null); }
  }, [open, focusDepth, trail, children]);

  function show(focus = false) {
    if (disabled || !trigger.current) return;
    generation.current++;
    cache.current = {};
    setChildren({});
    setTrail([]);
    setAnchor(trigger.current.getBoundingClientRect());
    setOpen(true);
    if (focus) setFocusDepth(0);
  }

  function load(folderId: string, retry = false) {
    if (!retry && cache.current[folderId]) return;
    const version = generation.current;
    const update = (value: Children) => {
      if (version !== generation.current) return;
      cache.current = { ...cache.current, [folderId]: value };
      setChildren(cache.current);
    };
    update({ status: 'loading', items: [] });
    getDriveSubfolders(workspaceId, folderId).then(items => {
      update({ status: 'ready', items: [...new Map(items.filter(item => item.id !== folderId).map(item => [item.id, item])).values()]
        .sort((a, b) => (a.name ?? '').localeCompare(b.name ?? '', 'ko')) });
    }).catch(() => update({ status: 'error', items: [] }));
  }

  function expand(depth: number, folder: DriveFolderPathEntry, rootId: string, folderPath: DriveFolderPathEntry[], element: HTMLElement, focus = false) {
    setTrail(previous => previous[depth]?.folder.id === folder.id ? previous
      : [...previous.slice(0, depth), { folder, rootId, path: folderPath, anchor: element.getBoundingClientRect() }]);
    load(folder.id);
    if (focus) setFocusDepth(depth + 1);
  }

  function select(rootId: string | null, folderPath: DriveFolderPathEntry[]) {
    onChange(rootId, folderPath);
    close(true);
  }

  function keys(event: KeyboardEvent<HTMLDivElement>, depth: number) {
    const choices = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[data-menu-choice]:not(:disabled)')];
    const index = choices.indexOf(document.activeElement as HTMLButtonElement);
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? choices.length - 1
        : (index + (event.key === 'ArrowDown' ? 1 : -1) + choices.length) % choices.length;
      choices[next]?.focus();
    } else if (event.key === 'ArrowLeft' && depth > 0) {
      event.preventDefault();
      setTrail(previous => previous.slice(0, depth - 1));
      setFocusDepth(depth - 1);
    } else if (event.key === 'Tab') close(true);
  }

  function row(folder: DriveFolderPathEntry, depth: number, parent?: Branch) {
    const rootId = parent?.rootId ?? folder.id;
    const folderPath = parent ? [...parent.path, folder] : [];
    const selected = activeFolderId === rootId && (path.at(-1)?.id ?? rootId) === folder.id;
    const expanded = trail[depth]?.folder.id === folder.id;
    const hasChildren = children[folder.id]?.status !== 'ready' || children[folder.id].items.length > 0;
    return <div key={folder.id} className={`drive-folder-menu__row${selected ? ' is-selected' : ''}${expanded ? ' is-expanded' : ''}`}
      onPointerEnter={event => { if (event.pointerType === 'mouse' && !compact) expand(depth, folder, rootId, folderPath, event.currentTarget); }}>
      <button type="button" role="menuitemradio" aria-checked={selected} data-menu-choice tabIndex={-1}
        className="drive-folder-menu__choice" title={folder.name} onClick={() => select(rootId, folderPath)}
        onKeyDown={event => { if (event.key === 'ArrowRight') {
          event.preventDefault(); expand(depth, folder, rootId, folderPath, event.currentTarget.parentElement!, true);
        } }}>
        <FolderIcon /><span className="drive-folder-menu__name">{folder.name}</span>
        {selected && <span aria-hidden="true" className="drive-folder-menu__check">✓</span>}
      </button>
      {hasChildren && <button type="button" role="menuitem" tabIndex={-1} aria-label={`${folder.name} 하위 폴더 열기`}
        aria-haspopup="menu" aria-expanded={expanded} className="drive-folder-menu__expand"
        onClick={event => expand(depth, folder, rootId, folderPath, event.currentTarget.parentElement!, compact)}>
        <DriveChevron className="drive-folder-menu__right" />
      </button>}
    </div>;
  }

  return <div className="drive-folder-picker">
    <label htmlFor={id}>{pickerLabel}</label>
    <button ref={trigger} id={id} type="button" className="input drive-folder-picker__trigger" disabled={disabled}
      aria-label={`조회할 폴더: ${selectedName}`} aria-haspopup="menu" aria-expanded={open}
      aria-controls={open ? `${id}-menus` : undefined} title={selectedPath || '연결된 폴더 전체'}
      onClick={() => open ? close() : show()}
      onKeyDown={event => { if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault(); if (open) { if (compact) setTrail([]); setFocusDepth(0); } else show(true);
      } }}>
      <FolderIcon /><span className="drive-folder-menu__name">{selectedName}</span><DriveChevron className="drive-folder-picker__caret" />
    </button>
    {open && anchor && createPortal(<div ref={popup} id={`${id}-menus`} className="drive-folder-menu-layer">
      {(!compact || trail.length === 0) && <FolderPanel depth={0} anchor={anchor} compact={compact} label="연결된 폴더 선택"
        onKeyDown={event => keys(event, 0)} onScroll={() => setTrail([])}>
        <div className="drive-folder-menu__heading">연결된 폴더 <span>{connectedCount ?? folders.length}</span></div>
        {showAll && <div className={`drive-folder-menu__row${activeFolderId == null ? ' is-selected' : ''}`}
          onPointerEnter={() => setTrail([])}>
          <button type="button" role="menuitemradio" aria-checked={activeFolderId == null} data-menu-choice tabIndex={-1}
            className="drive-folder-menu__choice" onClick={() => select(null, [])}>
            <FolderIcon /><span className="drive-folder-menu__name">전체 파일</span>{activeFolderId == null && <span aria-hidden="true">✓</span>}
          </button>
        </div>}
        {folders.map(folder => row(folder, 0))}
      </FolderPanel>}
      {trail.map((branch, index) => {
        if (compact && index !== trail.length - 1) return null;
        const state = children[branch.folder.id];
        const ancestors = new Set([branch.rootId, ...branch.path.map(folder => folder.id)]);
        return <FolderPanel key={`${index}-${branch.folder.id}`} depth={index + 1} anchor={compact ? anchor : branch.anchor}
          compact={compact} label={`${branch.folder.name} 하위 폴더`} onKeyDown={event => keys(event, index + 1)}
          onScroll={() => setTrail(previous => previous.slice(0, index + 1))}>
          {compact && <button type="button" role="menuitem" data-menu-choice tabIndex={-1} className="drive-folder-menu__back"
            onClick={() => { setTrail(previous => previous.slice(0, -1)); setFocusDepth(index); }}><DriveChevron className="drive-folder-menu__left" />이전 폴더</button>}
          <div className="drive-folder-menu__heading" title={branch.folder.name}>{branch.folder.name}</div>
          <button type="button" role="menuitem" data-menu-choice tabIndex={-1} className="drive-folder-menu__current"
            onClick={() => select(branch.rootId, branch.path)}>이 폴더 보기</button>
          {state?.status === 'loading' && <p className="drive-folder-menu__message" role="status">하위 폴더 불러오는 중…</p>}
          {state?.status === 'error' && <div className="drive-folder-menu__message" role="status">
            하위 폴더를 불러오지 못했어요.
            <button type="button" role="menuitem" data-menu-choice tabIndex={-1} className="drive-folder-menu__retry" onClick={() => load(branch.folder.id, true)}>다시 불러오기</button>
          </div>}
          {state?.status === 'ready' && state.items.length === 0 && <p className="drive-folder-menu__message">하위 폴더가 없어요.</p>}
          {state?.status === 'ready' && state.items.filter(folder => !ancestors.has(folder.id)).map(folder => row({ id: folder.id, name: folder.name || '(이름 없음)' }, index + 1, branch))}
        </FolderPanel>;
      })}
    </div>, document.body)}
  </div>;
}
