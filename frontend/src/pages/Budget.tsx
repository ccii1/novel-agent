import {useAction, useApi} from '../api';
import {ErrorBox, Loading, money, PageTitle, Panel, Stats, Status, time} from '../ui';
import type {Budget} from '../domain';

export default function BudgetPage() {
  const query = useApi<Budget>(['budget'], '/budget', 3000);
  const action = useAction();
  return <><PageTitle title="费用与调用" description="免费服务记录用量，计费服务按已配置单价计算并保留预算。"/><ErrorBox error={query.error ?? action.error}/>{!query.data ? <Loading/> : <><Stats items={[{label:`${query.data.month} 月度额度`,value:money(query.data.limit)},{label:'结算与预留',value:money(query.data.usedAndReserved)},{label:'可用额度',value:money(query.data.remaining)}]}/><Panel title="模型调用记录"><div className="table-wrap"><table><thead><tr><th>时间与模型</th><th>状态</th><th>输入 / 输出 token</th><th>预留 / 结算</th><th>费用核对</th></tr></thead><tbody>{query.data.calls.map(call => <tr key={String(call.id)}><td><strong>{String(call.model)}</strong><small>{time(call.created_at)}</small><small>{String(call.provider)} · {call.billing_mode === 'unmetered' ? '免费服务' : '按 token 计费'}</small></td><td><Status value={String(call.status)}/></td><td>{String(call.input_tokens ?? '—')} / {String(call.output_tokens ?? '—')}</td><td>{money(call.reserved)}<small>{money(call.actual)}</small></td><td>{call.status === 'UNCERTAIN' ? <form className="inline-form" onSubmit={event => {event.preventDefault(); const actual = Number(new FormData(event.currentTarget).get('actual')); action.mutate({path:`/calls/${call.id}/reconcile`,body:{actual}});}}><input name="actual" aria-label="实际费用" type="number" min={0} max={10000} step="0.000001" required/><button className="secondary" disabled={action.isPending}>确认费用</button></form> : '—'}</td></tr>)}</tbody></table></div></Panel></>}</>;
}
