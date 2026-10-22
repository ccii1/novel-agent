import {useState} from 'react';
import {useAction, useApi, useDraft} from '../api';
import {ErrorBox, Panel, Status, time} from '../ui';
import type {Novel, Task} from '../domain';

interface Route {target: string; instructions: string; reason: string}
const names: Record<string,string> = {DRAFT:'正文修改 Agent',OUTLINE:'局部大纲修改 Agent',MEMORY:'记忆修正 Agent'};

export default function CoordinationPanel({novel}: {novel: Novel}) {
  const tasks=useApi<Task[]>(['novel',novel.id,'coordination'],`/novels/${novel.id}/coordination`,2000);
  const [instructions,setInstructions]=useDraft(`coordination:${novel.id}`);
  const action=useAction();
  const busy=tasks.data?.some(task=>['WAITING','RUNNING','READY'].includes(task.status));
  return <Panel title="让协调 Agent 分析修改要求">
    <p className="muted">直接描述想调整的内容。协调 Agent 会选择正文、大纲或记忆处理角色，并说明范围，确认后才会创建处理任务。</p>
    <form onSubmit={event=>{event.preventDefault();action.mutate({path:`/novels/${novel.id}/coordination`,body:{instructions}});}}>
      <label>想调整什么？<textarea required rows={3} maxLength={12000} value={instructions} onChange={event=>setInstructions(event.target.value)}/></label>
      <ErrorBox error={action.error ?? tasks.error}/>
      <button disabled={action.isPending || busy}>{busy?'协调 Agent 正在分析…':'分析修改要求'}</button>
    </form>
    {tasks.data?.slice(0,5).map(task=><CoordinationResult key={task.id} novel={novel} task={task}/>)}
  </Panel>;
}
function CoordinationResult({novel,task}: {novel: Novel; task: Task}) {
  const [confirmed,setConfirmed]=useState(false);
  const action=useAction();
  const route: Route | null=task.result?JSON.parse(task.result) as Route:null;
  const payload=JSON.parse(task.payload) as {delegatedTaskId?: string};
  return <article className="plan-change-text">
    <small>{time(task.created_at)}</small> <Status value={task.status}/>
    {task.error && <p role="alert" className="error-text">{task.error}；可在执行记录中重新选择模型。</p>}
    {route && <><h3>建议交给{names[route.target]}</h3><p>{route.reason}</p><p style={{whiteSpace:'pre-wrap'}}>{route.instructions}</p>
      {payload.delegatedTaskId?<p className="success-text" role="status">已确认委派，处理进度可在执行记录查看。大纲建议和记忆审核继续按各自流程确认。</p>:<form onSubmit={event=>{event.preventDefault();action.mutate({path:`/novels/${novel.id}/coordination/${task.id}/approve`});}}>
        <label className="checkbox-label"><input type="checkbox" checked={confirmed} onChange={event=>setConfirmed(event.target.checked)}/>我已审核处理范围；涉及重要剧情时，同意按当前已保存大纲进行正文修改</label>
        <ErrorBox error={action.error}/><button disabled={!confirmed || action.isPending}>确认交给所选 Agent</button>
      </form>}
    </>}
  </article>;
}
