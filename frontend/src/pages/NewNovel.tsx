import {useState} from 'react';
import {useLocation, useNavigate} from 'react-router-dom';
import {ArrowRight} from 'lucide-react';
import {useAction, useDraft} from '../api';
import {ErrorBox, PageTitle, Panel} from '../ui';
import ModelSelect from '../components/ModelSelect';
import type {Choice, Idea} from '../domain';

export default function NewNovel() {
  const navigate = useNavigate();
  const idea = (useLocation().state as {idea?: Idea} | null)?.idea;
  const draftId = idea ? `idea:${idea.title}` : 'new';
  const [title, setTitle, clearTitle] = useDraft(`${draftId}:title`, idea?.title ?? '');
  const [premise, setPremise, clearPremise] = useDraft(`${draftId}:premise`, idea ? JSON.stringify(idea, null, 2) : '');
  const [bible, setBible, clearBible] = useDraft(`${draftId}:bible`);
  const [choice, setChoice] = useState<Choice>({provider:'', model:''});
  const [ready, setReady] = useState(false);
  const action = useAction<{id: string}>();
  return <><PageTitle title="创建连载作品" description="确定故事方向，审核阶段大纲，让 Agent 接续创作。"/><div className="creation-layout"><Panel><form onSubmit={event => {
    event.preventDefault(); const values = new FormData(event.currentTarget);
    action.mutate({path:'/novels', body:{title, premise, bible, ...choice, stageSize:Number(values.get('stageSize')), novelBudget:Number(values.get('novelBudget')), chapterBudget:Number(values.get('chapterBudget'))}}, {onSuccess: result => {clearTitle(); clearPremise(); clearBible(); navigate(`/books/${result.id}/overview`);}});
  }}><h2>故事设定</h2><label>作品名称<input value={title} onChange={event => setTitle(event.target.value)} required maxLength={200} placeholder="给故事起一个名字"/></label><label>故事创意与读者期待<textarea value={premise} onChange={event => setPremise(event.target.value)} required maxLength={12000} rows={6} placeholder="谁想完成什么？面临什么阻碍？希望读者得到怎样的体验？"/></label><label>创作设定与限制<textarea value={bible} onChange={event => setBible(event.target.value)} maxLength={12000} rows={4} placeholder="人物、世界规则、文风与需要避免的内容"/></label><div className="form-grid three"><label>每阶段章节<input name="stageSize" type="number" min={1} max={20} defaultValue={5} required/></label><label>作品月预算 / 元<input name="novelBudget" type="number" min={0.01} max={300} step={0.01} defaultValue={100} required/></label><label>单章预算 / 元<input name="chapterBudget" type="number" min={0.01} max={100} step={0.01} defaultValue={5} required/></label></div><h2>生成模型</h2><ModelSelect value={choice} change={setChoice} ready={setReady}/><ErrorBox error={action.error}/><div className="form-footer"><span className="muted">输入自动保留在当前浏览器会话</span><button disabled={!ready || action.isPending}>生成阶段大纲<ArrowRight size={16}/></button></div></form></Panel><aside className="guide"><h2>接下来的创作流程</h2><ol><li><strong>审核阶段大纲</strong><p>确认人物目标、主要冲突和伏笔安排。</p></li><li><strong>逐章写作与独立检查</strong><p>人物、剧情、节奏和文风分别检查。</p></li><li><strong>保存正文与长期记忆</strong><p>通过审核的章节更新事实、关系和交接资料。</p></li></ol><p>核心剧情、重要人物转变与阶段结局由你审核。</p></aside></div></>;
}
