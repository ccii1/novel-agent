import {useState} from 'react';
import {useAction, useDraft} from '../api';
import {ErrorBox, Panel} from '../ui';
import type {MemoryDelta, MemoryReview, Novel} from '../domain';

export default function MemoryRepairPanel({novel, pending}: {novel: Novel; pending: MemoryReview}) {
  const [draft,setDraft] = useState<MemoryDelta>(pending.delta!);
  const [instructions,setInstructions] = useDraft(`memory-repair:${pending.taskId}`,pending.review!.issues.map(issue => issue.suggestion).join('\n') || '确认本次记忆变更，保持正文和大纲版本。');
  const action = useAction();
  const submit = (delta?: MemoryDelta) => action.mutate({path:`/novels/${novel.id}/memory-repair`,body:{taskId:pending.taskId,instructions,delta}});
  return <Panel title="修正记忆并重新审核">
    <p className="muted">第 {novel.chapter_no} 章 · 正文修订 {pending.revision} · 自动修正 {pending.repairAttempt}/{pending.automaticLimit} 次</p>
    {pending.review!.majorChange && <p>本次变更需要人工确认，请核对检查意见与记忆内容。</p>}
    {pending.review!.issues.map((issue,index) => <article className="setting-text" key={index}>
      <strong>问题 {index + 1}</strong>
      <blockquote>{issue.quote}</blockquote>
      <small>{issue.quoteSource === 'MEMORY' ? `引用待审核记忆 ${issue.quotePath ?? ''}` : '引用当前正文'}</small>
      <p>{issue.reason}</p><p>修改建议：{issue.suggestion}</p>
    </article>)}
    <form onSubmit={event => {event.preventDefault(); submit();}}>
      <label>记忆修改要求<textarea aria-label="记忆修改要求" rows={4} required maxLength={12000} value={instructions} onChange={event => setInstructions(event.target.value)}/></label>
      <p className="muted">确认后仅修正待审核记忆，再次审核通过后继续写作。模型修正与审核按当前服务计费。</p>
      <button disabled={action.isPending}>按要求修正记忆并重新审核</button>
      <details><summary>直接编辑待审核记忆</summary>
        <label>章节摘要<textarea aria-label="章节摘要" rows={5} value={draft.summary} onChange={event => setDraft({...draft,summary:event.target.value})}/></label>
        {draft.facts.map((fact,index) => <div key={fact.key}>
          <label>{fact.name} · {fact.key}<textarea rows={3} value={fact.detail ?? ''} onChange={event => setDraft({...draft,facts:draft.facts.map((item,current) => current === index ? {...item,detail:event.target.value} : item)})}/></label>
          <label>正文证据<textarea rows={2} value={fact.evidence} onChange={event => setDraft({...draft,facts:draft.facts.map((item,current) => current === index ? {...item,evidence:event.target.value} : item)})}/></label>
        </div>)}
        {draft.relations.map((relation,index) => <div key={`${relation.subject}:${relation.predicate}:${relation.object}`}>
          <label>{relation.subject} · {relation.predicate} · {relation.object}<textarea rows={3} value={relation.detail ?? ''} onChange={event => setDraft({...draft,relations:draft.relations.map((item,current) => current === index ? {...item,detail:event.target.value} : item)})}/></label>
          <label>正文证据<textarea rows={2} value={relation.evidence} onChange={event => setDraft({...draft,relations:draft.relations.map((item,current) => current === index ? {...item,evidence:event.target.value} : item)})}/></label>
        </div>)}
        <button type="button" className="secondary" disabled={action.isPending || !instructions.trim()} onClick={() => submit(draft)}>提交编辑后的记忆审核</button>
      </details>
      <ErrorBox error={action.error}/>
    </form>
  </Panel>;
}
