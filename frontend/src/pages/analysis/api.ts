import { http, ws } from '../../api/client';

export type ItemType = 'DECISION' | 'FOLLOW_UP' | 'OPEN_ISSUE' | 'MEETING_PROPOSAL';
export type AnalysisStatus = 'ANALYZING' | 'FAILED' | 'NO_CONTENT' | 'REVIEWING' | 'CONFIRMED';
export type OutputStatus = 'SUCCESS' | 'FAILED';

export const ITEM_TYPE_TEXT: Record<ItemType, string> = {
  DECISION: '결정사항',
  FOLLOW_UP: '후속 작업',
  OPEN_ISSUE: '미결 사항',
  MEETING_PROPOSAL: '회의 제안',
};

export const STATUS_TEXT: Record<AnalysisStatus, string> = {
  ANALYZING: '분석 중',
  FAILED: '분석 실패',
  NO_CONTENT: '정리할 내용 없음',
  REVIEWING: '검토 대기',
  CONFIRMED: '확정',
};

export interface AnalysisEvidenceDTO {
  evidenceId: number;
  itemId: number;
  contentRefId: number;
  authorName: string | null;
  text: string;
  createdAt: string | null;
  deepLink: string | null;
  isOriginDeleted: boolean;
}

export interface AnalysisItemResponse {
  itemId: number;
  itemType: ItemType;
  content: string;
  undetermined: boolean;
  suggestedAssigneeId: number | null;
  suggestedAssigneeName: string | null;
  suggestedDueDate: string | null;
  suggestedDatetime: string | null;
  suggestedParticipantIds: number[];
  outputStatus: OutputStatus | null;
  outputTargetId: number | null;
  evidences: AnalysisEvidenceDTO[];
}

export interface ThreadMessageResponse {
  contentRefId: number;
  authorName: string | null;
  text: string;
  createdAt: string | null;
  analyzed: boolean;
}

export interface AnalysisResultDTO {
  analysisId: number;
  contentRefId: number;
  channelId: string;
  threadTitle: string | null;
  threadPreview: string | null;
  threadLink: string | null;
  status: AnalysisStatus;
  auto: boolean;
  analyzedUntil: string | null;
  createdAt: string;
  items: AnalysisItemResponse[];
  messages: ThreadMessageResponse[];
  notionPageRefId: number | null;
  notionOutputStatus: OutputStatus | null;
}

export interface AnalysisSummaryDTO {
  analysisId: number;
  contentRefId: number;
  threadTitle: string | null;
  threadPreview: string | null;
  status: AnalysisStatus;
  auto: boolean;
  itemCount: number;
  createdAt: string;
}

export interface ItemForm {
  content: string;
  suggestedAssigneeId: number | null;
  suggestedDueDate: string | null;
  suggestedDatetime: string | null;
  suggestedParticipantIds: number[];
}

export const analyzeThread = (workspaceId: string, contentRefId: number) =>
  http.post<AnalysisResultDTO>(ws(workspaceId, '/analyses'), { contentRefId });

export const getPendingAnalyses = (workspaceId: string) =>
  http.get<AnalysisSummaryDTO[]>(ws(workspaceId, '/analyses'));

export const getLatestAnalysis = (workspaceId: string, contentRefId: number) =>
  http.get<{ analysisId: number | null }>(ws(workspaceId, `/analyses/latest?contentRefId=${contentRefId}`));

export const getAnalysis = (workspaceId: string, analysisId: number | string) =>
  http.get<AnalysisResultDTO>(ws(workspaceId, `/analyses/${analysisId}`));

export const addItem = (workspaceId: string, analysisId: number, body: ItemForm & { itemType: ItemType }) =>
  http.post<AnalysisItemResponse>(ws(workspaceId, `/analyses/${analysisId}/items`), body);

export const updateItem = (workspaceId: string, itemId: number, body: ItemForm) =>
  http.patch<AnalysisItemResponse>(ws(workspaceId, `/analysis-items/${itemId}`), body);

export const deleteItem = (workspaceId: string, itemId: number) =>
  http.delete<void>(ws(workspaceId, `/analysis-items/${itemId}`));

export const linkEvidence = (workspaceId: string, itemId: number, contentRefId: number) =>
  http.post<AnalysisEvidenceDTO>(ws(workspaceId, `/analysis-items/${itemId}/evidences`), { contentRefId });

export const unlinkEvidence = (workspaceId: string, evidenceId: number) =>
  http.delete<void>(ws(workspaceId, `/analysis-evidences/${evidenceId}`));

export const confirmAnalysis = (workspaceId: string, analysisId: number) =>
  http.post<AnalysisResultDTO>(ws(workspaceId, `/analyses/${analysisId}/confirm`));

export const discardAnalysis = (workspaceId: string, analysisId: number) =>
  http.delete<void>(ws(workspaceId, `/analyses/${analysisId}`));
