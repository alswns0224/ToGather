import NavIcon from '../../layouts/NavIcon';
import { DriveChevron, DriveFolderIcon } from './DriveFolderIcons';
import type { DriveFolderPathEntry } from './DriveFolderPicker';

export default function DriveFolderBreadcrumbs({ root, path, loading, disabled, onAll, onNavigate }: {
  root: DriveFolderPathEntry; path: DriveFolderPathEntry[]; loading: boolean; disabled: boolean;
  onAll: () => void; onNavigate: (index: number) => void;
}) {
  const entries = [root, ...path];
  return <nav className="drive-breadcrumb" aria-label="Drive 폴더 경로">
    <ol>
      <li><button type="button" disabled={disabled} onClick={onAll} aria-label="Drive 전체 파일 보기">
        <NavIcon name="drive" /><span>Drive</span>
      </button></li>
      {entries.map((entry, index) => <li key={entry.id}>
        <DriveChevron className="drive-breadcrumb__separator" />
        {index === entries.length - 1
          ? <span className="drive-breadcrumb__current" aria-current="page" title={entry.name}>
            <DriveFolderIcon /><span>{entry.name}</span>
          </span>
          : <button type="button" disabled={disabled} title={entry.name} aria-label={`${entry.name} 폴더로 이동`}
            onClick={() => onNavigate(index - 1)}><span>{entry.name}</span></button>}
      </li>)}
    </ol>
    {loading && <span className="drive-breadcrumb__loading" role="status">불러오는 중…</span>}
  </nav>;
}
