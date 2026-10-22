import {useState} from 'react';
import {useAction, useApi, useDraft} from '../api';
import {duration, ErrorBox, JsonView, Loading, Modal, money, roles, Status, time} from '../ui';
import type {TaskDetail} from '../domain';
import TaskRetry from './TaskRetry';

export default function TaskModal({id, close}: {id: string; close: () => void}) {
  const detail = useApi<TaskDetail>(['task', id], `/observability/tasks/${id}`, 3000);
  const [rating, setRating] = useState('3');
  const [note, setNote, clearNote] = useDraft(`feedback:${id}`);
  const action = useAction();
  const data = detail.data;
  return <Modal title={data ? `${roles[data.task.kind]} · 第 ${data.task.chapter_no} 章` : '执行详情'} close={close} wide>
    <ErrorBox error={detail.error ?? action.error}/>
    {!data ? <Loading/> : <>
      <div className="split"><Status value={data.task.status}/><small className="muted">任务 {id.slice(0,8)} · 记忆版本 {data.task.memory_version}</small></div>
      {data.task.error && <p className="error-text">{data.task.error}</p>}
      {data.task.status === 'ERROR' && <TaskRetry key={id} task={data.task} call={data.call} done={close}/>}
      <h3>执行过程</h3>
      <div className="timeline">{data.executions.map(run => <article key={String(run.id)}>
        <strong>{String(run.status)}</strong>
        <small>{time(run.started_at)} · 执行 {duration(run.duration_ms)} · 排队 {duration(run.queue_ms)}</small>
        {run.error ? <p className="error-text">{String(run.error)}</p> : null}
        {run.approved != null && <p>自动检查：{run.approved ? '通过' : '未通过'} · {Number(run.issue_count ?? 0)} 个问题</p>}
        {run.result ? <JsonView value={run.result} title="本次执行输出"/> : null}
      </article>)}</div>
      <h3>模型调用</h3>
      {data.call ? <>
        <div className="task-meta"><span>{String(data.call.provider)} / {String(data.call.model)}</span><span>{duration(data.call.latency_ms)} · {money(data.call.actual)}</span></div>
        <JsonView value={Object.fromEntries(Object.entries(data.call).filter(([key]) => key !== 'response'))} title="用量、费用与调用信息"/>
        {data.call.response ? <JsonView value={data.call.response} title="模型原始输出"/> : null}
      </> : <p className="muted">尚无模型调用。</p>}
      {!!data.turns?.length && <><h3>Agent 逐次模型请求</h3><p className="muted">费用与用量已汇总到上方任务调用记录；各次请求独立保存。</p>{data.turns.map(turn=><article key={String(turn.id)}><strong>第 {Number(turn.turn_no)} 次 · {String(turn.status)}</strong><small> · {duration(turn.latency_ms)} · 输入 {Number(turn.input_tokens ?? 0)} / 输出 {Number(turn.output_tokens ?? 0)} token · {money(turn.cost)}</small><details className="json-view"><summary>本次模型响应</summary><pre>{String(turn.response_body ?? '尚无响应')}</pre></details></article>)}</>}
      <JsonView value={data.task.payload} title="任务输入"/>
      {data.task.result && <JsonView value={data.task.result} title="任务最终结果"/>}
      <details className="json-view"><summary>最近执行事件</summary>{data.events.map(event => <p key={event.id}><small>{time(event.created_at)} · {event.type}</small></p>)}</details>
      <h3>人工效果评价</h3>
      {data.task.result && <form onSubmit={event => {
        event.preventDefault();
        action.mutate({path:`/observability/tasks/${id}/feedback`,body:{rating:Number(rating),note}},{onSuccess:clearNote});
      }}>
        <label>评分<select value={rating} onChange={event => setRating(event.target.value)}>{[1,2,3,4,5].map(value => <option key={value} value={value}>{value} / 5</option>)}</select></label>
        <label>评价依据<textarea value={note} onChange={event => setNote(event.target.value)} required maxLength={4000} rows={3}/></label>
        <button disabled={action.isPending}>保存评价</button>{action.isSuccess && <span className="success-text">评价已保存</span>}
      </form>}
      {data.feedback.map(item => <div className="feedback" key={item.id}><strong>{item.rating} / 5</strong><small>{time(item.created_at)}</small><p>{item.note}</p></div>)}
    </>}
  </Modal>;
}
