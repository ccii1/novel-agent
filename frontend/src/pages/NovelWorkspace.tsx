import {useState} from 'react';
import {NavLink, useParams} from 'react-router-dom';
import {Download, Pause, Play} from 'lucide-react';
import {useAction, useApi, useNovelSync} from '../api';
import {activeStates, ErrorBox, Loading, PageTitle, Status} from '../ui';
import type {NovelDetail} from '../domain';
import TaskModal from '../components/TaskModal';
import {Overview, Plan, Chapters} from './novel/WritingPanels';
import {MemoryPanel} from './novel/KnowledgePanels';
import {TasksPanel} from './novel/HistoryPanels';

const tabs = [['overview','概况'],['plan','阶段大纲'],['chapters','章节'],['memory','创作记忆'],['tasks','执行记录']];
export default function NovelWorkspace() {
  const {bookId = '', tab = 'overview'} = useParams();
  return <BookWorkspace key={bookId} id={bookId} tab={tab}/>;
}
function BookWorkspace({id,tab}: {id: string; tab: string}) {
  const detail = useApi<NovelDetail>(['novel',id], `/novels/${id}`);
  const sync = useNovelSync(id);
  const action = useAction();
  const [trace,setTrace] = useState<string | null>(null);
  if (!detail.data) return <><ErrorBox error={detail.error}/><Loading/></>;
  const data = detail.data, novel = data.novel;
  return <>
    <PageTitle title={novel.title} description={`第${novel.stage_no}阶段 · 当前第${novel.chapter_no}章 · 记忆版本${novel.memory_version}`}>
      <Status value={novel.status}/>
      {activeStates.has(novel.status) && <button className="secondary" disabled={action.isPending} onClick={() => action.mutate({path:`/novels/${id}/pause`})}><Pause size={15}/>暂停创作</button>}
      {novel.status === 'PAUSED' && <button disabled={action.isPending} onClick={() => action.mutate({path:`/novels/${id}/resume`})}><Play size={15}/>恢复创作</button>}
      {novel.status === 'AWAITING_STAGE_REVIEW' && <button disabled={action.isPending} onClick={() => action.mutate({path:`/novels/${id}/continue`})}>通过阶段审核并继续</button>}
      <a className="button secondary" href={`/api/novels/${id}/export`}><Download size={15}/>导出正文</a>
    </PageTitle>
    <ErrorBox error={action.error ?? detail.error ?? sync.error}/>{novel.error && <div className="error-box">{novel.error}</div>}
    <nav className="tabs" aria-label="作品页面">{tabs.map(([value,label]) => <NavLink to={`/books/${id}/${value}`} key={value}>{label}</NavLink>)}</nav>
    <div className="tab-content">
      {tab === 'overview' && <Overview data={data} inspect={setTrace}/>}
      {tab === 'plan' && <Plan novel={novel}/>}
      {tab === 'chapters' && <Chapters id={id} chapters={data.chapters}/>}
      {tab === 'memory' && <MemoryPanel id={id}/>}
      {tab === 'tasks' && <TasksPanel tasks={data.tasks} inspect={setTrace}/>}
    </div>
    {trace && <TaskModal id={trace} close={() => setTrace(null)}/>}
  </>;
}
