export type Row = Record<string, unknown>;
export interface Novel {
  id: string; title: string; status: string; stage_no: number; chapter_no: number; memory_version: number;
  premise: string; bible: string; plan: string | null; reflection: string | null; error: string | null;
  provider: string; model_name: string | null;
}
export interface Task {
  id: string; novel_id: string | null; kind: string; status: string; chapter_no: number; revision: number;
  memory_version: number; created_at: string; error: string | null; payload: string; result: string | null;
  provider?: string | null; model_name?: string | null;
}
export interface Chapter {number: number; title: string; status: string; revision: number; published: boolean; body?: string; summary?: string}
export interface Event {id: number; type: string; message: string; created_at: string}
export interface Edit {id: string; chapter_no: number; instruction: string; status: string; impact: string}
export interface MemoryIssue {quote: string; reason: string; suggestion: string; quoteSource?: string; quotePath?: string}
export interface MemoryDelta {summary: string; facts: (Record<string, unknown> & {key: string; name: string; detail?: string; evidence: string})[]; relations: (Record<string, unknown> & {subject: string; predicate: string; object: string; detail?: string; evidence: string})[]}
export interface MemoryReview {pending: boolean; taskId?: string; revision?: number; repairAttempt?: number; automaticLimit?: number; delta?: MemoryDelta; review?: {majorChange: boolean; issues: MemoryIssue[]}}
export interface NovelDetail {novel: Novel; chapters: Chapter[]; tasks: Task[]; events: Event[]; edits: Edit[]; memoryReview?: MemoryReview}
export interface Provider {id: string; label: string; ready: boolean; defaultModel: string; billingMode: string}
export interface Catalog {defaultProvider: string; providers: Provider[]}
export interface Choice {provider: string; model: string}
export interface Idea {title: string; premise: string; audience: string; genre: string; hook: string; longTermConflict: string; risks: string[]; sources: string[]}
export interface Report {id: string; brief: string; ideas: string; sources: string; created_at: string}
export interface ResearchDetail {report: Report; tasks: Task[]; modelChoice: Choice; messages: {id: number; role: string; content: string; created_at: string; ideas: string | null; task_id: string | null}[]}
export interface Progress {task: Row; stage: string; events: Event[]}
export interface Quality {totals: {chapters: number; stored_characters: number}; dialogueRising: boolean; recent: {chapter_no: number; characters: number; dialogue_lines: number; total_lines: number; dialogue_ratio: number; pacing_data: string | null}[]}
export interface Graph {version: number; nodes: {id: string; name: string}[]; edges: {subject: string; predicate: string; object: string; chapter: number; evidence: string; detail: string}[]}
export interface Memory {id: string; memory_key: string; kind: string; chapter_no: number; content: string}
export interface Agent {kind: string; agent: string; purpose: string}
export interface Observation {summary: Row[]; tasks: (Task & Row)[]; total: number; page: number; pageSize: number; storage: string; historicalTiming: string}
export interface TaskDetail {task: Task; executions: Row[]; call: Row | null; events: Event[]; feedback: {id: number; rating: number; note: string; created_at: string}[]}
export interface Budget {month: string; limit: number; usedAndReserved: number; remaining: number; calls: Row[]}
export interface SystemStatus {engine: {active: number; workers: number; ready: boolean}; database?: string; rateLimiter: string; modelConfigured: boolean; researchModelSelection: boolean; retryModelSelection?: boolean}
