import {useEffect, useState} from 'react';
import {useAction, useApi} from '../api';
import {duration, ErrorBox, roles, Status} from '../ui';
import type {Progress, Task} from '../domain';

export default function TaskProgress({task, inspect}: {task: Task; inspect: (id: string) => void}) {
  const live = ['WAITING','RUNNING','READY','ERROR'].includes(task.status);
  const query = useApi<Progress>(['progress', task.id], `/observability/tasks/${task.id}/progress`, ['WAITING','RUNNING','READY'].includes(task.status) ? 2000 : false, live);
  const action = useAction();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {if(task.status !== 'RUNNING') return; const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer);}, [task.status]);
  const progress = query.data;
  const elapsed = progress?.task.elapsed_ms;
  const runningElapsed = elapsed == null ? null : Number(elapsed) + (task.status === 'RUNNING' ? Math.max(0, now - query.dataUpdatedAt) : 0);
  return <article className="task-progress">
    <div className="split"><strong>{roles[task.kind] ?? task.kind}</strong><Status value={task.status}/></div>
    <p aria-live="polite">{live ? progress?.stage ?? '正在读取执行进度' : task.status === 'DONE' ? '结果已保存，执行时间与用量可在详情中查看。' : '任务已取消。'}</p>
    {live && <div className="task-meta"><span>执行 {duration(runningElapsed)}</span>{progress?.task.model ? <span>{String(progress.task.model)} · 模型 {duration(progress.task.model_elapsed_ms)}</span> : <span>尚未发出模型请求</span>}</div>}
    {task.error && <div className="error-text">{task.error}</div>}
    <ErrorBox error={query.error ?? action.error}/>
    <div className="actions"><button className="subtle" onClick={() => inspect(task.id)}>查看执行详情</button>
      {task.status === 'ERROR' && <button className="secondary" disabled={action.isPending} onClick={() => action.mutate({path:`/tasks/${task.id}/retry`})}>确认重新执行</button>}
    </div>
  </article>;
}
