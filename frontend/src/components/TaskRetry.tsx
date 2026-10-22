import {useState} from 'react';
import {useAction, useApi} from '../api';
import {ErrorBox, Panel} from '../ui';
import ModelSelect from './ModelSelect';
import type {Choice, Row, SystemStatus, Task} from '../domain';

export default function TaskRetry({task,call,done}: {task: Task; call: Row | null; done: () => void}) {
  const previousRequest = call?.request_body ? JSON.parse(String(call.request_body)) as {reasoning_effort?: string} : null;
  const [choice,setChoice] = useState<Choice>({
    provider:task.provider || String(call?.provider ?? ''),
    model:task.model_name || String(call?.model ?? ''),
    reasoningEffort:task.reasoning_effort ?? previousRequest?.reasoning_effort ?? '',
  });
  const [ready,setReady] = useState(false);
  const retry = useAction<{id: string}>(done);
  const status = useApi<SystemStatus>(['status'],'/status');
  const reconcile = useAction();
  const uncertain = call?.status === 'UNCERTAIN';
  const inFlight = call?.status === 'IN_FLIGHT';
  const free = call?.billing_mode === 'unmetered';
  return <Panel title="选择本次重新执行的模型">
    <p className="muted">本次选择用于新任务，作品后续章节继续使用作品中的模型设置。原任务与费用记录保留。</p>
    {uncertain && <form className="reconcile-form" onSubmit={event => {
      event.preventDefault();
      const actual = free ? 0 : Number(new FormData(event.currentTarget).get('actual'));
      reconcile.mutate({path:`/calls/${call.id}/reconcile`,body:{actual}});
    }}>
      <p>{free ? '上次调用属于免费服务，需要确认费用为 0 元后重新执行。' : '上次调用费用尚未核对，请填写供应商确认的实际费用。'}</p>
      {!free && <label>上次调用实际费用 / 元<input name="actual" type="number" min={0} max={10000} step="0.000001" required/></label>}
      <button className="secondary" disabled={reconcile.isPending}>{free ? '确认上次免费调用为 0 元' : '确认上次调用费用'}</button>
    </form>}
    {inFlight && <p role="alert">上次调用尚未结束，请等待调用结束后处理。</p>}
    <form onSubmit={event => {
      event.preventDefault();
      retry.mutate({path:`/tasks/${task.id}/retry`,body:choice});
    }}>
      <ModelSelect value={choice} change={setChoice} ready={setReady}/>
      {status.data && !status.data.retryModelSelection && <p role="alert">请在 IDEA 重新启动后端，启用任务模型选择。</p>}
      <ErrorBox error={reconcile.error ?? retry.error ?? status.error}/>
      <button disabled={!status.data?.retryModelSelection || !ready || uncertain || inFlight || retry.isPending || reconcile.isPending}>使用所选模型重新执行</button>
    </form>
  </Panel>;
}
