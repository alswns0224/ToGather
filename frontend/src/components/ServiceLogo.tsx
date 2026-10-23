// 외부 서비스 로고 (docs/ui-prototype 시안의 공식 로고 SVG)
import type { Provider } from '../pages/integration/api';

export type ServiceKey = 'slack' | 'notion' | 'drive' | 'gcal' | 'gmail' | 'gmeet';

/** 연동 Provider → 로고 */
export const PROVIDER_LOGO: Record<Provider, ServiceKey> = {
  SLACK: 'slack',
  NOTION: 'notion',
  GOOGLE_DRIVE: 'drive',
  GOOGLE_CALENDAR: 'gcal',
  GMAIL: 'gmail',
};

export default function ServiceLogo({ service, className = "w-full h-full" }: { service: ServiceKey; className?: string }) {
  switch (service) {
    case "slack":
      return (
        <svg className={className} viewBox="0 0 128 128" fill="none">
          <rect width="128" height="128" rx="28" fill="#FFFFFF" />
          <path d="M35.8 77.2c0 6.5-5.3 11.8-11.8 11.8S12.2 83.7 12.2 77.2c0-6.5 5.3-11.8 11.8-11.8h11.8v11.8zm5.9 0c0-6.5 5.3-11.8 11.8-11.8s11.8 5.3 11.8 11.8v29.6c0 6.5-5.3 11.8-11.8 11.8s-11.8-5.3-11.8-11.8V77.2z" fill="#E01E5A" />
          <path d="M53.5 35.8c-6.5 0-11.8-5.3-11.8-11.8S47 12.2 53.5 12.2s11.8 5.3 11.8 11.8v11.8H53.5zm0 5.9c6.5 0 11.8 5.3 11.8 11.8s-5.3 11.8-11.8 11.8H23.9c-6.5 0-11.8-5.3-11.8-11.8s5.3-11.8 11.8-11.8h29.6z" fill="#36C5F0" />
          <path d="M92.2 53.5c0-6.5 5.3-11.8 11.8-11.8s11.8 5.3 11.8 11.8-5.3 11.8-11.8 11.8H92.2V53.5zm-5.9 0c0 6.5-5.3 11.8-11.8 11.8s-11.8-5.3-11.8-11.8V23.9c0-6.5 5.3-11.8 11.8-11.8s11.8 5.3 11.8 11.8v29.6z" fill="#2EB67D" />
          <path d="M74.5 92.2c6.5 0 11.8 5.3 11.8 11.8s-5.3 11.8-11.8 11.8-11.8-5.3-11.8-11.8V92.2h11.8zm0-5.9c-6.5 0-11.8-5.3-11.8-11.8s5.3-11.8 11.8-11.8h29.6c6.5 0 11.8 5.3 11.8 11.8s-5.3 11.8-11.8 11.8H74.5z" fill="#ECB22E" />
        </svg>
      );
    case "notion":
      return (
        <svg className={className} viewBox="0 0 100 100" fill="none">
          <rect width="100" height="100" rx="22" fill="#FFFFFF" />
          <path
            d="M16.5 14.2c4.4 3.6 6.1 3.3 14.4 2.7l50.6-3c3.3 0 .6-3.3-.6-3.9L72.5 3.9C69.2 1.4 64.7.1 56.1.7L7.2 4.3C2.2 4.8 1.1 7.1 3.3 9.2l13.2 5zm3.1 13.3v59.4c0 3.2 1.6 4.4 5.2 4.2l56.4-3.3c3.6-.2 4.1-2.4 4.1-5.1V23.8c0-2.6-1.1-4.1-3.4-3.9l-58.9 3.4c-2.5.3-3.4 1.5-3.4 4.2zm55.6 4.4c.4 1.9 0 3.8-1.9 4l-2.7.5v43.8c-2.4 1.3-4.6 2-6.4 2-3 0-3.8-.9-6.1-3.8L40.3 49.4v27.9l5.9 1.3s0 3.8-5.3 3.8l-14.6.9c-.4-.9 0-3.1 1.4-3.5l3.8-1V39.2l-5.3-.4c-.4-1.9.6-4.7 3.3-4.9l15.7-1 19 29.1V36.2l-5-.5c-.4-2.3 1.2-3.9 3.5-4.1l12.5-.7z"
            fill="#111111"
            transform="translate(6, 5) scale(0.88)"
          />
        </svg>
      );
    case "drive":
      return (
        <svg className={className} viewBox="0 0 100 100" fill="none">
          <rect width="100" height="100" rx="22" fill="#FFFFFF" />
          <g transform="translate(6.5, 11)">
            <path d="M6.6 66.85l3.85 6.65c.8 1.4 1.95 2.5 3.3 3.3L27.5 53H0c0 1.55.4 3.1 1.2 4.5l5.4 9.35z" fill="#0066DA" />
            <path d="M43.65 25L29.9 1.2c-1.35.8-2.5 1.9-3.3 3.3L1.2 48.5A9.06 9.06 0 0 0 0 53h27.5L43.65 25z" fill="#00AC47" />
            <path d="M73.55 76.8c1.35-.8 2.5-1.9 3.3-3.3l1.6-2.75 7.65-13.25c.8-1.4 1.2-2.95 1.2-4.5H59.8l5.85 11.5 7.9 12.3z" fill="#EA4335" />
            <path d="M43.65 25L57.4 1.2C56.05.4 54.5 0 52.85 0H34.45c-1.65 0-3.2.45-4.55 1.2L43.65 25z" fill="#00832D" />
            <path d="M59.8 53H27.5L13.75 76.8c1.35.8 2.9 1.2 4.55 1.2h50.7c1.65 0 3.2-.45 4.55-1.2L59.8 53z" fill="#2684FC" />
            <path d="M73.4 26.5L60.7 4.5c-.8-1.4-1.95-2.5-3.3-3.3L43.65 25 59.8 53h27.45c0-1.55-.4-3.1-1.2-4.5L73.4 26.5z" fill="#FFBA00" />
          </g>
        </svg>
      );
    case "gcal":
      return (
        <svg className={className} viewBox="0 0 100 100" fill="none">
          <rect width="100" height="100" rx="22" fill="#FFFFFF" />
          <path d="M76 8H24C15.16 8 8 15.16 8 24v52h68V8z" fill="#4285F4" />
          <path d="M92 24c0-8.84-7.16-16-16-16v20h20V24z" fill="#1967D2" />
          <path d="M76 28h16v48H76V28z" fill="#FBBC04" />
          <path d="M8 76c0 8.84 7.16 16 16 16h52V76H8z" fill="#34A853" />
          <path d="M76 92l16-16H76v16z" fill="#EA4335" />
          <rect x="24" y="24" width="52" height="52" rx="4" fill="#FFFFFF" />
          <text x="50" y="61" textAnchor="middle" fill="#1A73E8" fontFamily="Inter, sans-serif" fontWeight="800" fontSize="31">
            31
          </text>
        </svg>
      );
    case "gmail":
      return (
        <svg className={className} viewBox="0 0 100 100" fill="none">
          <rect width="100" height="100" rx="22" fill="#FFFFFF" />
          <g transform="translate(8, 17)">
            <path d="M5.7 66h13.3V33.6L0 19.3V60c0 3.3 2.6 6 5.7 6z" fill="#4285F4" />
            <path d="M65 66h13.3c3.2 0 5.7-2.7 5.7-6V19.3L65 33.6V66z" fill="#34A853" />
            <path d="M65 6v27.6L84 19.3V11.7c0-7.4-8.4-11.6-14.3-7.2L65 6z" fill="#FBBC04" />
            <path d="M19 33.6V6l23 17.3L65 6v27.6L42 50.9 19 33.6z" fill="#EA4335" />
            <path d="M0 11.7v7.6l19 14.3V6l-4.7-1.5C8.4 0.1 0 4.3 0 11.7z" fill="#C5221F" />
          </g>
        </svg>
      );
    case "gmeet":
      return (
        <svg className={className} viewBox="0 0 100 100" fill="none">
          <rect width="100" height="100" rx="22" fill="#FFFFFF" />
          <rect x="10" y="22" width="54" height="56" rx="12" fill="#00AC47" />
          <path d="M10 34c0-6.6 5.4-12 12-12h42v18H10V34z" fill="#FFBA00" />
          <path d="M10 62h54v4c0 6.6-5.4 12-12 12H22c-6.6 0-12-5.4-12-12v-4z" fill="#0066DA" />
          <path d="M64 38l24-15v54L64 62V38z" fill="#00832D" />
          <path d="M64 38l24-15v27H64V38z" fill="#EA4335" />
        </svg>
      );
  }
}
