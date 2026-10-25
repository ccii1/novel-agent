import {useState} from 'react';
import {Empty, JsonView, Panel} from '../../ui';
import TaskProgress from '../../components/TaskProgress';
import type {Task} from '../../domain';

export function TasksPanel({tasks,inspect}: {tasks: Task[]; inspect: (id: string) => void}) {
  const [page,setPage] = useState(0);
  return <div className="task-list">
    {tasks.slice(page*30,(page+1)*30).map(task => <Panel key={task.id}>
      <TaskProgress task={task} inspect={inspect}/>
      <JsonView value={task.payload} title={`任务输入 · 记忆版本${task.memory_version}`}/>
      {task.result && <JsonView value={task.result} title="任务输出"/>}
    </Panel>)}
    {tasks.length === 0 ? <Empty title="暂无执行记录"/> : <div className="pagination">
      <span>共{tasks.length}个任务 · 第{page+1}页</span>
      <div className="actions"><button className="secondary" disabled={page === 0} onClick={() => setPage(page-1)}>上一页</button>
        <button className="secondary" disabled={(page+1)*30 >= tasks.length} onClick={() => setPage(page+1)}>下一页</button></div>
    </div>}
  </div>;
}
