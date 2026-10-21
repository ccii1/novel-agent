import {useState} from 'react';
import {Link} from 'react-router-dom';
import {useAction, useApi, useDraft} from '../../api';
import {Empty, ErrorBox, JsonView, Loading, Modal, Panel, Stats, Status, time} from '../../ui';
import ModelSelect from '../../components/ModelSelect';
import TaskProgress from '../../components/TaskProgress';
import MemoryRepairPanel from '../../components/MemoryRepairPanel';
import CopyChapterButton from '../../components/CopyChapterButton';
import PlanChangePanel from '../../components/PlanChangePanel';
import type {Chapter, Choice, Novel, NovelDetail} from '../../domain';

export function Overview({data,inspect}: {data: NovelDetail; inspect: (id: string) => void}) {
  const {novel,tasks} = data;
  const active = tasks.filter(task => ['WAITING','RUNNING','READY','ERROR'].includes(task.status));
  const [settings,setSettings] = useState(false);
  const pending = data.memoryReview;
  const awaiting = ['AWAITING_HELP','AWAITING_CHANGE'].includes(novel.status);
  return <>
    <Stats items={[{label:'已接受章节',value:data.chapters.filter(chapter => chapter.status === 'ACCEPTED').length},{label:'待处理任务',value:active.length},{label:'当前记忆版本',value:novel.memory_version}]}/>
    <div className="workspace-columns"><div>
      <Panel title="创作设定"><p className="prose">{novel.premise}</p>{novel.bible && <div className="setting-text">{novel.bible}</div>}</Panel>
      {awaiting && <div id="intervention">{pending?.pending ? <MemoryRepairPanel key={pending.taskId} novel={novel} pending={pending}/> : <RevisionForm novel={novel}/>}</div>}
      <Panel title="当前执行"><div className="task-grid">{active.map(task => <TaskProgress key={task.id} task={task} inspect={inspect}/>)}</div>{active.length === 0 && <p className="muted">当前没有执行中的任务。<Link to={`/books/${novel.id}/plan`}>查看阶段大纲</Link></p>}</Panel>
    </div><div>
      <Panel title="接下来需要你处理">
        <p>{pending?.pending ? '记忆审核需要处理，请查看问题并修正待审核记忆。' : novel.status === 'AWAITING_PLAN' ? '阶段大纲已经生成，审核后开始写作。' : novel.status === 'AWAITING_HELP' ? '正文自动修改已达到限制，请查看检查意见并提供修改要求。' : novel.status === 'ERROR' ? '查看失败任务与调用记录，再决定是否重新执行。' : '重要剧情变化和阶段结局会暂停，等待你的审核。'}</p>
        {awaiting ? <button className="secondary" onClick={() => document.getElementById('intervention')?.scrollIntoView({behavior:'smooth',block:'start'})}>{pending?.pending ? '处理记忆审核' : '查看修改要求'}</button> : <Link className="button secondary" to={`/books/${novel.id}/${novel.status === 'AWAITING_PLAN' ? 'plan' : 'tasks'}`}>前往处理</Link>}
      </Panel>
      <Panel title="生成模型"><p className="muted">{novel.provider} · {novel.model_name || '服务默认模型'}</p><button className="subtle" onClick={() => setSettings(!settings)}>{settings ? '收起设置' : '调整后续调用模型'}</button>{settings && <BookModel novel={novel}/>}</Panel>
      <Panel title="最近事件"><div className="event-list">{data.events.slice(0,6).map(event => <div key={event.id}><small>{time(event.created_at)}</small><strong>{event.type}</strong></div>)}</div></Panel>
    </div></div>
  </>;
}
function BookModel({novel}: {novel: Novel}) {
  const [choice,setChoice] = useState<Choice>({provider:novel.provider,model:novel.model_name ?? ''});
  const [ready,setReady] = useState(false);
  const action = useAction();
  return <form onSubmit={event => {event.preventDefault(); action.mutate({path:`/novels/${novel.id}/model`,body:choice});}}><ModelSelect value={choice} change={setChoice} ready={setReady}/><ErrorBox error={action.error}/><button disabled={!ready || action.isPending}>保存模型选择</button>{action.isSuccess && <p className="success-text">模型选择已保存</p>}</form>;
}
function RevisionForm({novel}: {novel: Novel}) {
  const [instructions,setInstructions] = useDraft(`revision:${novel.id}`);
  const action = useAction();
  const [approved,setApproved] = useState(false);
  return <Panel title="人工修改要求">{novel.reflection && <JsonView value={novel.reflection} title="检查与修改计划"/>}<p><Link to={`/books/${novel.id}/plan`}>局部修改大纲：选择章节、人物或伏笔并审核变化</Link></p><form onSubmit={event => {event.preventDefault(); action.mutate({path:`/novels/${novel.id}/revise`,body:{instructions,useCurrentPlan:novel.status === 'AWAITING_CHANGE'}});}}><label>批准的修改要求<textarea rows={5} required maxLength={12000} value={instructions} onChange={event => setInstructions(event.target.value)}/></label>{novel.status === 'AWAITING_CHANGE' && <label className="checkbox-label"><input type="checkbox" checked={approved} onChange={event => setApproved(event.target.checked)}/>我已审核重要变更，同意按当前已保存大纲修改正文</label>}<ErrorBox error={action.error}/><button disabled={action.isPending || novel.status === 'AWAITING_CHANGE' && !approved}>批准修改并重新检查</button></form></Panel>;
}
export function Plan({novel}: {novel: Novel}) {
  if (!novel.plan) return <Panel><Empty title="阶段大纲正在规划">生成完成后会自动出现在这里，无需刷新页面。</Empty></Panel>;
  return <PlanEditor key={`${novel.id}:${novel.stage_no}`} novel={novel}/>;
}
function PlanEditor({novel}: {novel: Novel}) {
  const action = useAction();
  return <><PlanChangePanel novel={novel}/><Panel title="阶段大纲"><JsonView value={novel.plan} title="展开完整阶段大纲"/><ErrorBox error={action.error}/>{novel.status === 'AWAITING_PLAN' && <button disabled={action.isPending} onClick={() => action.mutate({path:`/novels/${novel.id}/approve-plan?useCurrentPlan=true`})}>批准已保存大纲并开始创作</button>}</Panel></>;
}
export function Chapters({id,chapters}: {id: string; chapters: Chapter[]}) {
  const [reading,setReading] = useState<number | null>(null);
  const action = useAction();
  return <><ErrorBox error={action.error}/><Panel title="章节列表">{chapters.length === 0 ? <Empty title="还没有正文">大纲批准后会逐章生成，列表会自动更新。</Empty> : <div className="table-wrap"><table><thead><tr><th>章节</th><th>状态</th><th>修改轮次</th><th>发布标记</th><th>操作</th></tr></thead><tbody>{chapters.map(chapter => <tr key={chapter.number}><td><strong>第 {chapter.number} 章</strong><small>{chapter.title}</small></td><td><Status value={chapter.status}/></td><td>{chapter.revision}</td><td>{chapter.published ? '已发布' : '未发布'}</td><td><div className="actions"><button className="secondary" onClick={() => setReading(chapter.number)}>阅读正文</button><CopyChapterButton key={`${chapter.number}:${chapter.revision}`} id={id} number={chapter.number}/>{chapter.status === 'ACCEPTED' && <button className="subtle" disabled={action.isPending} onClick={() => action.mutate({path:`/novels/${id}/chapters/${chapter.number}/published`,body:{published:!chapter.published}})}>{chapter.published ? '取消发布标记' : '标记已发布'}</button>}</div></td></tr>)}</tbody></table></div>}</Panel>{reading != null && <ChapterReader key={reading} id={id} number={reading} close={() => setReading(null)}/>}</>;
}
function ChapterReader({id,number,close}: {id: string; number: number; close: () => void}) {
  const chapter = useApi<Chapter>(['novel',id,'chapter',number],`/novels/${id}/chapters/${number}`);
  return <Modal title={`第 ${number} 章 ${chapter.data?.title ?? ''}`} close={close} wide><ErrorBox error={chapter.error}/>{chapter.data ? <article className="manuscript">{chapter.data.body}</article> : <Loading/>}</Modal>;
}
