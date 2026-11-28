/** 마감 시각(선택). 비우면 그날 하루 종일로 본다. 값은 "HH:mm" 또는 '' */
export default function DueTimeInput({
  label,
  value,
  onChange,
  disabled,
  className = '',
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <input
      type="time"
      className={`input !w-auto ${className}`}
      aria-label={label}
      title="마감 시각 (비우면 그날 하루 종일)"
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}
