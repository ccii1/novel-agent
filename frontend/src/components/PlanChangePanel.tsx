import {useState} from 'react';
import {Link} from 'react-router-dom';
import {useAction, useApi, useDraft} from '../api';
import {ErrorBox, Loading, Panel, time} from '../ui';
import type {Novel} from '../domain';

interface Target {path: string; label: string; value: string | string[]}
interface Suggestion {before: string | string[]; replacement: string | string[]; reason: string; coreChange: boolean; impacts: {chapter: number; reason: string}[]; affectedChapters: number[]; completedChapters: number[]}
interface Change {id: string; stage_no: number; target_path: string; target_label: string; stale: boolean; instructions: string; status: string; suggestion: string | null; task_status: string; task_error: string | null; created_at: string; applied_at: string | null}
interface Changes {editable: boolean; targets: Target[]; changes: Change[]}
const text = (value: string | string[]) => Array.isArray(value) ? value.join('、') || '暂无安排' : value;

export default function PlanChangePanel({novel}: {novel: Novel}) {
  const changes = useApi<Changes>(['novel',novel.id,'plan-changes'],`/novels/${novel.id}/plan-changes`,2000);
  const [path,setPath] = useState('');
  const [instructions,setInstructions] = useDraft(`plan-change:${novel.id}:${novel.stage_no}`);
  const action = useAction();
  if(!changes.data) return <Panel title="局部修改大纲"><ErrorBox error={changes.error}/>{!changes.error && <Loading/>}</Panel>;
  const {targets} = changes.data;
  const selected = targets.find(target => target.path === path) ?? targets[0];
  const busy = changes.data.changes.some(change => ['WAITING','RUNNING','READY'].includes(change.task_status));
  return <>
    <Panel title="局部修改大纲">
      <p className="muted">选择要调整的章节、人物或伏笔，描述修改要求。建议生成后只需审核本次变化和关联影响，确认后保存。</p>
      {!changes.data.editable && <p role="status">当前正在创作，请暂停并等待执行中的任务结束，或在人工审核阶段修改。</p>}
      <form onSubmit={event => {event.preventDefault(); action.mutate({path:`/novels/${novel.id}/plan-changes`,body:{path:selected?.path,instructions}});}}>
        <label>修改范围<select aria-label="修改范围" value={selected?.path ?? ''} onChange={event => setPath(event.target.value)} disabled={!changes.data.editable || busy}>{targets.map(target => <option key={target.path} value={target.path}>{target.label}</option>)}</select></label>
        {selected && <div className="plan-change-text"><strong>当前安排</strong><p>{text(selected.value)}</p></div>}
        <label>修改要求<textarea rows={4} required maxLength={12000} value={instructions} onChange={event => setInstructions(event.target.value)} placeholder="例如：人物在本章暂时保留怀疑，把信任转变延后；保留关键线索。"/></label>
        <ErrorBox error={action.error ?? changes.error}/>
        <button disabled={!selected || !changes.data.editable || busy || action.isPending}>{busy ? '正在生成修改建议…' : '生成局部修改建议'}</button>
      </form>
    </Panel>
    {changes.data.changes.map(change => <ChangeReview key={change.id} novel={novel} change={change} editable={changes.data.editable && !busy}/>) }
  </>;
}

function ChangeReview({novel,change,editable}: {novel: Novel; change: Change; editable: boolean}) {
  const [core,setCore] = useState(false);
  const [completed,setCompleted] = useState(false);
  const action = useAction();
  const suggestion: Suggestion | null = change.suggestion ? JSON.parse(change.suggestion) as Suggestion : null;
  const applied = change.status === 'APPLIED';
  const content = <>
    <p><strong>第 {change.stage_no} 阶段 · {change.target_label}</strong> · {time(change.created_at)}</p>
    <p>你的要求：{change.instructions}</p>
    {change.task_error && <div role="alert" className="error-box">{change.task_error} <Link to={`/books/${novel.id}/tasks`}>前往执行记录选择模型并重新执行</Link></div>}
    {!suggestion && !change.task_error && <p role="status">修改建议已保存为后台任务，生成完成后自动显示。</p>}
    {suggestion && <>
      <div className="plan-change-comparison"><div className="plan-change-text"><h3>修改前</h3><p>{text(suggestion.before)}</p></div><div className="plan-change-text"><h3>修改后</h3><p>{text(suggestion.replacement)}</p></div></div>
      <p>{suggestion.reason}</p>
      <h3>关联影响与审核范围</h3>
      <p>需要关注的章节：{suggestion.affectedChapters.map(number => `第${number}章`).join('、')}</p>
      {suggestion.impacts.map((impact,index) => <p key={index}>第 {impact.chapter} 章：{impact.reason}</p>)}
      {suggestion.coreChange && <p className="error-text">涉及核心剧情、重要人物转变或关键伏笔，需要明确审核。</p>}
      {suggestion.completedChapters.length > 0 && <p className="error-text">涉及已完成章节：{suggestion.completedChapters.join('、')}。本次保存更新大纲；已完成正文和记忆如需调整，请通过<Link to={`/books/${novel.id}/edits`}>历史修改</Link>处理。</p>}
      {applied ? <p className="success-text" role="status">已确认保存 · {time(change.applied_at)}。修改前后大纲与建议已保存在版本记录。</p> : <form onSubmit={event => {event.preventDefault(); action.mutate({path:`/novels/${novel.id}/plan-changes/${change.id}/approve`,body:{acknowledgeCore:core,acknowledgeCompleted:completed}});}}>
        {suggestion.coreChange && <label className="checkbox-label"><input type="checkbox" checked={core} onChange={event => setCore(event.target.checked)}/>我已审核核心剧情及相关人物、伏笔的变化</label>}
        {suggestion.completedChapters.length > 0 && <label className="checkbox-label"><input type="checkbox" checked={completed} onChange={event => setCompleted(event.target.checked)}/>我已了解已完成章节的影响和历史正文处理范围</label>}
        <ErrorBox error={action.error}/>
        {change.stale && <p role="alert">大纲、章节或记忆版本已经变化，请重新生成修改建议。</p>}
        <button disabled={!editable || change.stale || action.isPending || suggestion.coreChange && !core || suggestion.completedChapters.length > 0 && !completed}>确认并保存局部修改</button>
        <p className="muted">保存后保持当前审核或暂停状态；回到概况批准正文修改，或恢复创作后继续。</p>
      </form>}
    </>}
  </>;
  return <Panel>{applied ? <details><summary>已保存的局部修改 · {time(change.applied_at)}</summary>{content}</details> : content}</Panel>;
}
