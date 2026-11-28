// 회의·일정 화면의 기존 인터페이스를 유지하며 공통 날짜·시간 선택기를 사용한다.
import DateTimePicker, { type DateTimePickerProps } from '../../components/DateTimePicker';

export default function MeetingDateTimePicker(props: DateTimePickerProps) {
  return <DateTimePicker {...props} required clearable={false} className={`mt-2 ${props.className ?? ''}`} />;
}
