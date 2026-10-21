import {useEffect, useRef, useState} from 'react';
import type {ReactNode} from 'react';
import {AlertCircle, BookOpen, LoaderCircle, X} from 'lucide-react';

export const labels: Record<string, string> = {
  PLANNING:'规划中', AWAITING_PLAN:'等待大纲审核', WRITING:'写作中', CHECKING:'并行检查中', REFLECTING:'分析修改方案',
  EXTRACTING:'提取记忆', CHECKING_MEMORY:'审核记忆', AWAITING_STAGE_REVIEW:'等待阶段审核', AWAITING_CHANGE:'等待重要变更审核',
  AWAITING_HELP:'需要人工处理', PAUSED:'已暂停', ERROR:'执行失败', BUDGET_PAUSED:'费用额度不足', WAITING:'排队中',
  READY:'结果待处理', RUNNING:'执行中', DONE:'已完成', CANCELLED:'已取消', DRAFT:'草稿', ACCEPTED:'检查通过', INVALIDATED:'等待重新生成',
  IN_FLIGHT:'调用中', COMPLETE:'已结算', UNCERTAIN:'费用待核对', RECONCILED:'已人工核对', PENDING:'等待审批', APPROVED:'已批准',
};
export const roles: Record<string, string> = {PLAN:'阶段规划', PLAN_CHANGE:'局部大纲修改', WRITE:'正文创作', REWRITE:'正文修改', CHECK_CHAPTER:'正文联合审核', CHECK_CHARACTER:'人物检查', CHECK_PLOT:'剧情检查', CHECK_PACING:'节奏检查', CHECK_STYLE:'文风检查', REFLECT:'修改分析', MEMORY:'记忆提取', MEMORY_REPAIR:'记忆修正', CHECK_MEMORY:'记忆审核', RESEARCH:'选题研究', RESEARCH_CHAT:'选题讨论'};
export const activeStates = new Set(['PLANNING','WRITING','CHECKING','REFLECTING','EXTRACTING','CHECKING_MEMORY']);
export const money = (value: unknown) => value == null ? '未结算' : `¥${Number(value).toFixed(4)}`;
export const duration = (value: unknown) => value == null ? '未记录' : `${(Number(value) / 1000).toFixed(1)} 秒`;
export const time = (value: unknown) => value ? new Date(String(value)).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai', hour12:false}) : '未记录';
export const pretty = (value: unknown) => JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value, null, 2);
export function Status({value}: {value: string}) {return <span className={`status status-${value.toLowerCase()}`}><span/>{labels[value] ?? value}</span>;}
export function ErrorBox({error, retry}: {error?: Error | null; retry?: () => void}) {
  return error ? <div className="error-box" role="alert"><AlertCircle size={18}/><span>{error.message}</span>{retry && <button className="subtle" onClick={retry}>重新读取</button>}</div> : null;
}
export function Loading() {return <div className="loading" role="status"><LoaderCircle className="spin" size={20}/>正在读取数据…</div>;}
export function Empty({title, children}: {title: string; children?: ReactNode}) {return <div className="empty"><BookOpen size={28}/><h3>{title}</h3>{children && <p>{children}</p>}</div>;}
export function JsonView({value, title = '查看记录'}: {value: unknown; title?: string}) {
  const [open,setOpen] = useState(false);
  return <details className="json-view" onToggle={event => setOpen(event.currentTarget.open)}><summary>{title}</summary>{open && <pre>{pretty(value)}</pre>}</details>;
}
export function PageTitle({title, description, children}: {title: string; description?: string; children?: ReactNode}) {
  return <div className="page-title"><div><h1>{title}</h1>{description && <p>{description}</p>}</div>{children && <div className="actions">{children}</div>}</div>;
}
export function Panel({title, children, className = ''}: {title?: string; children: ReactNode; className?: string}) {return <section className={`panel ${className}`}>{title && <h2>{title}</h2>}{children}</section>;}
export function Stats({items}: {items: {label: string; value: ReactNode}[]}) {return <div className="stats">{items.map(item => <div className="stat" key={item.label}><span>{item.label}</span><strong>{item.value}</strong></div>)}</div>;}
export function Modal({title, children, close, wide = false}: {title: string; children: ReactNode; close: () => void; wide?: boolean}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {const element = dialog.current!; element.showModal(); return () => element.close();}, []);
  return <dialog ref={dialog} className={wide ? 'modal wide' : 'modal'} onCancel={close} onClick={event => {if(event.target === event.currentTarget) close();}}><div className="modal-title"><h2>{title}</h2><button type="button" className="icon-button" aria-label="关闭" onClick={close}><X size={20}/></button></div>{children}</dialog>;
}
