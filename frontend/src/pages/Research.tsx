import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {ArrowRight, Send} from 'lucide-react';
import {useAction, useApi, useDraft} from '../api';
import ModelSelect from '../components/ModelSelect';
import TaskProgress from '../components/TaskProgress';
import TaskModal from '../components/TaskModal';
import {Empty, ErrorBox, JsonView, Loading, PageTitle, Panel, time} from '../ui';
import type {Choice, Idea, Report, ResearchDetail, Task} from '../domain';

export default function Research() {
  const {reportId} = useParams();
  const navigate = useNavigate();
  const reports = useApi<Report[]>(['research'], '/research', 2000);
  const tasks = useApi<Task[]>(['research-tasks'], '/research/tasks', 2000);
  const [brief, setBrief, clearBrief] = useDraft('research:brief');
  const [urls, setUrls, clearUrls] = useDraft('research:urls');
  const [choice, setChoice] = useState<Choice>({provider:'',model:''});
  const [ready, setReady] = useState(false);
  const [trace, setTrace] = useState<string | null>(null);
  const action = useAction<{id: string}>();
  const [waiting, setWaiting] = useState<string | null>(null);
  useEffect(() => {
    const newest = reports.data?.[0];
    if (!reportId && newest && !waiting) navigate(`/research/${newest.id}`, {replace:true});
    if (waiting) {
      const completed = reports.data?.find(item => (item as Report & {task_id: string}).task_id === waiting);
      if (completed) {setWaiting(null); navigate(`/research/${completed.id}`);}
    }
  }, [reports.data, reportId, waiting, navigate]);
  return <><PageTitle title="从一个想法，找到长篇故事" description="一起讨论人物、冲突与读者期待，让故事有持续发展的空间。"/><div className="research-layout"><div><Panel title="你的创作想法"><form onSubmit={event => {event.preventDefault(); action.mutate({path:'/research', body:{brief:brief.trim(), urls:urls.split(/\r?\n/).map(value => value.trim()).filter(Boolean), ...choice}}, {onSuccess: result => {setWaiting(result.id); clearBrief(); clearUrls();}});}}><label>想写怎样的故事？<textarea rows={7} value={brief} onChange={event => setBrief(event.target.value)} maxLength={4000} placeholder="题材、人设、职业经历，或者一种希望读者感受到的情绪…"/></label><details className="optional-input"><summary>添加公开榜单或作品参考</summary><label>参考地址，每行一个，最多 5 个<textarea rows={3} value={urls} onChange={event => setUrls(event.target.value)}/></label></details><ModelSelect value={choice} change={setChoice} ready={setReady}/><ErrorBox error={action.error}/><button className="full-width" disabled={!ready || action.isPending || (!brief.trim() && !urls.trim())}>开始选题研究<ArrowRight size={16}/></button></form></Panel><div className="report-list"><h3>选题记录</h3>{reports.data?.map(report => <button className={report.id === reportId ? 'selected' : ''} key={report.id} onClick={() => {setWaiting(null); navigate(`/research/${report.id}`);}}><strong>{report.brief || (JSON.parse(report.ideas) as Idea[])[0]?.title}</strong><small>{time(report.created_at)}</small></button>)}</div></div><div className="research-main"><ErrorBox error={reports.error ?? tasks.error}/>{tasks.data?.filter(task => task.kind === 'RESEARCH' && !['DONE','CANCELLED'].includes(task.status)).map(task => <TaskProgress key={task.id} task={task} inspect={setTrace}/>)}{reportId ? <ReportDiscussion key={reportId} id={reportId} inspect={setTrace}/> : <Panel><Empty title="故事从这里开始">写下你的想法，Agent 会提出三个可讨论的故事方向。</Empty></Panel>}</div></div>{trace && <TaskModal id={trace} close={() => setTrace(null)}/>}</>;
}

function ReportDiscussion({id, inspect}: {id: string; inspect: (id: string) => void}) {
  const detail = useApi<ResearchDetail>(['research-detail',id], `/research/${id}`, 2000);
  const navigate = useNavigate();
  if (!detail.data) return <><ErrorBox error={detail.error}/><Loading/></>;
  const data = detail.data;
  const ideas = JSON.parse(data.report.ideas) as Idea[];
  return <>
    <ErrorBox error={detail.error}/>
    <div className="section-heading"><h2>三个故事方向</h2><span className="muted">方案会随讨论更新</span></div>
    <div className="ideas">{ideas.map((idea,index) => <article className="idea" key={index}>
      <div className="idea-index">0{index+1}</div>
      <div><small>{idea.audience} · {idea.genre}</small><h2>{idea.title}</h2><p>{idea.premise}</p><blockquote>{idea.hook}</blockquote>
        <details><summary>长期冲突与创作难点</summary><p>{idea.longTermConflict}</p><ul>{idea.risks.map(risk => <li key={risk}>{risk}</li>)}</ul></details>
        <div className="actions">
          <button className="secondary" onClick={() => {
            const composer = document.getElementById('discussion');
            composer?.scrollIntoView({behavior:'smooth'});
            composer?.querySelector('textarea')?.focus({preventScroll:true});
          }}>继续讨论</button>
          <button onClick={() => navigate('/new',{state:{idea}})}>创建作品<ArrowRight size={15}/></button>
        </div>
      </div>
    </article>)}</div>
    <Panel title="选题讨论"><div className="messages">{data.messages.map(message => <article className={`message ${message.role === 'USER' ? 'user' : ''}`} key={message.id}>
      <div className="split"><strong>{message.role === 'USER' ? '你' : '选题 Agent'}</strong><small>{time(message.created_at)}</small></div>
      <p>{message.content}</p>{message.ideas && <JsonView value={message.ideas} title="本轮方案"/>}
      {message.task_id && message.role !== 'USER' && <button className="subtle" onClick={() => inspect(message.task_id!)}>执行过程与费用</button>}
    </article>)}</div>
      {data.tasks.filter(task => !['DONE','CANCELLED'].includes(task.status)).map(task => <TaskProgress key={task.id} task={task} inspect={inspect}/>)}
      <ChatComposer key={id} data={data}/>
    </Panel>
    <JsonView value={{brief:data.report.brief,sources:JSON.parse(data.report.sources)}} title="最初想法与参考资料"/>
  </>;
}

function ChatComposer({data}: {data: ResearchDetail}) {
  const [message, setMessage, clearMessage] = useDraft(`chat:${data.report.id}`);
  const [choice, setChoice] = useState<Choice>(data.modelChoice ?? {provider:'',model:''});
  const [ready, setReady] = useState(false);
  const action = useAction();
  const busy = data.tasks.some(task => ['WAITING','RUNNING','READY','ERROR'].includes(task.status));
  return <form id="discussion" className="composer" onSubmit={event => {event.preventDefault(); action.mutate({path:`/research/${data.report.id}/messages`,body:{message,...choice}}, {onSuccess:clearMessage});}}><label>补充想法，继续讨论<textarea rows={4} value={message} onChange={event => setMessage(event.target.value)} required maxLength={4000} disabled={busy} placeholder="哪些地方吸引你？希望调整人物、世界观，还是故事的长期冲突？"/></label><ModelSelect value={choice} change={setChoice} ready={setReady} disabled={busy}/><ErrorBox error={action.error}/><div className="form-footer"><small className="muted">讨论与模型选择持续保存</small><button disabled={!ready || busy || action.isPending || !message.trim()}><Send size={16}/>发送想法</button></div></form>;
}
