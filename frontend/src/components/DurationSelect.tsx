import { durationLabel } from '../pages/meeting/MeetingFormFields';

const OPTIONS = [30, 60, 90, 120, 180, 240];

/** 회의·일정 길이(분). 대화에서 찾은 길이가 목록에 없으면 함께 보여 준다. */
export default function DurationSelect({
  label,
  value,
  onChange,
  disabled,
  className = '',
}: {
  label: string;
  value: number;
  onChange: (minutes: number) => void;
  disabled?: boolean;
  className?: string;
}) {
  const options = OPTIONS.includes(value) ? OPTIONS : [...OPTIONS, value].sort((a, b) => a - b);
  return (
    <select
      className={`input ${className}`}
      aria-label={label}
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(Number(e.target.value))}
    >
      {options.map((minutes) => (
        <option key={minutes} value={minutes}>
          {durationLabel(minutes)}
        </option>
      ))}
    </select>
  );
}
