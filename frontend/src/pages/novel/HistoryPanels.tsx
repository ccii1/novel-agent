import {useState} from 'react';
import {useAction, useDraft} from '../../api';
import {Empty, ErrorBox, JsonView, Panel, Status} from '../../ui';
import TaskProgress from '../../components/TaskProgress';
import type {NovelDetail, Task} from '../../domain';

export function TasksPanel({tasks,inspect}: {tasks: Task[]; inspect: (id: string) => void}) {
  const [page,setPage] = useState(0);
  return <div className="task-list">
    {tasks.slice(page*30,(page+1)*30).map(task => <Panel key={task.id}><TaskProgress task={task} inspect={inspect}/><JsonView value={task.payload} title={`任务输入 · 记忆版本 ${task.memory_version}`}/>{task.result && <JsonView value={task.result} title="任务输出"/>}</Panel>)}
    {tasks.length === 0 ? <Empty title="暂无执行记录"/> : <div className="pagination">
      <span>共 {tasks.length} 个任务 · 第 {page+1} 页</span>
      <div className="actions"><button className="secondary" disabled={page === 0} onClick={() => setPage(page-1)}>上一页</button>
        <button className="secondary" disabled={(page+1)*30 >= tasks.length} onClick={() => setPage(page+1)}>下一页</button></div>
    </div>}
  </div>;
}
export function EditsPanel({data}: {data: NovelDetail}) {
  const action = useAction();
  const [instructions,setInstructions,clear] = useDraft(`edit:${data.novel.id}`);
  return <><Panel title="历史章节修改"><p className="muted">暂停创作并等待任务结束后，可分析已接受章节的修改影响。批准后重新规划受影响章节。</p><form onSubmit={event => {event.preventDefault(); const values = new FormData(event.currentTarget); action.mutate({path:`/novels/${data.novel.id}/edits`,body:{chapter:Number(values.get('chapter')),instructions}}, {onSuccess:clear});}}><label>章节编号<input name="chapter" type="number" min={1} required/></label><label>修改要求<textarea rows={5} required maxLength={12000} value={instructions} onChange={event => setInstructions(event.target.value)}/></label><ErrorBox error={action.error}/><button disabled={action.isPending}>分析影响范围</button></form></Panel>{data.edits.map(edit => <Panel key={edit.id}><div className="split"><h2>第 {edit.chapter_no} 章修改申请</h2><Status value={edit.status}/></div><p>{edit.instruction}</p><JsonView value={edit.impact} title="受影响章节与记忆"/>{edit.status === 'PENDING' && <form onSubmit={event => {event.preventDefault(); const values = new FormData(event.currentTarget); action.mutate({path:`/edits/${edit.id}/approve`,body:{acknowledgePublished:values.get('acknowledgePublished') === 'on'}});}}><label className="checkbox-label"><input type="checkbox" name="acknowledgePublished"/>确认涉及已发布章节时，由我处理平台上的正文修改</label><button disabled={action.isPending}>批准影响范围并重新生成</button></form>}</Panel>)}</>;
}
