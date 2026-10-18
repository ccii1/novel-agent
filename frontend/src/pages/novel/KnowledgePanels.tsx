import {useState} from 'react';
import {Download} from 'lucide-react';
import {useApi} from '../../api';
import {Empty, ErrorBox, JsonView, Loading, Panel, Stats} from '../../ui';
import type {Graph, Memory, Quality} from '../../domain';

export function MemoryPanel({id}: {id: string}) {
  const [filter,setFilter] = useState({characters:'',depth:'2'});
  const memories = useApi<Memory[]>(['novel',id,'memories'],`/novels/${id}/memories`);
  const graph = useApi<Graph>(['novel',id,'graph',filter],`/novels/${id}/graph?characters=${encodeURIComponent(filter.characters)}&depth=${filter.depth}`);
  return <><ErrorBox error={memories.error ?? graph.error}/><Panel title="人物关系查询"><form className="filters" onSubmit={event => {event.preventDefault(); const values = new FormData(event.currentTarget); setFilter({characters:String(values.get('characters')),depth:String(values.get('depth'))});}}><label className="grow">人物或秘密名称<input name="characters" placeholder="英文逗号分隔，留空查询全部"/></label><label>关联深度<select name="depth" defaultValue="2"><option>1</option><option>2</option><option>3</option></select></label><button className="secondary">查询关系</button></form>{!graph.data ? <Loading/> : graph.data.edges.length === 0 ? <Empty title="当前没有已保存的关系">正文与记忆检查通过后，人物关系会自动更新。</Empty> : <><p className="muted small">记忆版本 {graph.data.version} · {graph.data.nodes.length} 个节点 · {graph.data.edges.length} 条关系</p><div className="table-wrap"><table><thead><tr><th>主体</th><th>关系</th><th>对象</th><th>来源与证据</th></tr></thead><tbody>{graph.data.edges.map((edge,index) => <tr key={index}><td>{edge.subject}</td><td><span className="relation-label">{edge.predicate}</span></td><td>{edge.object}</td><td>第 {edge.chapter} 章<small>{edge.evidence}</small></td></tr>)}</tbody></table></div></>}</Panel><Panel title="当前创作记忆">{memories.data?.map(memory => <JsonView key={memory.id} value={memory.content} title={`${memory.kind} · ${memory.memory_key} · 第 ${memory.chapter_no} 章`}/>)}{memories.data?.length === 0 && <p className="muted">还没有通过审核的记忆增量。</p>}</Panel></>;
}
export function QualityPanel({id}: {id: string}) {
  const query = useApi<Quality>(['novel',id,'quality'],`/novels/${id}/quality`);
  if (!query.data) return <><ErrorBox error={query.error}/><Loading/></>;
  const data = query.data;
  return <><ErrorBox error={query.error}/><Stats items={[{label:'已接受章节',value:data.totals.chapters},{label:'正文字符数（含空白）',value:data.totals.stored_characters.toLocaleString()},{label:'最近三章对话趋势',value:data.dialogueRising ? '需要关注' : '持续记录'}]}/><Panel title="质量记录"><p className="muted">对话占比按含引号的非空行数计算；节奏评价来自模型检查。此处展示通过验收的章节。</p>{data.recent.length === 0 ? <Empty title="暂无通过验收的章节记录"/> : <div className="table-wrap"><table><thead><tr><th>章节</th><th>非空白字符</th><th>对话行 / 全部非空行</th><th>对话占比</th><th>叙事结构</th></tr></thead><tbody>{data.recent.map(row => <tr key={row.chapter_no}><td>第 {row.chapter_no} 章</td><td>{row.characters}</td><td>{row.dialogue_lines} / {row.total_lines}</td><td><span className="ratio-bar"><i style={{width:`${Math.min(100,row.dialogue_ratio*100)}%`}}/></span>{(row.dialogue_ratio*100).toFixed(1)}%</td><td>{row.pacing_data ? <JsonView value={row.pacing_data} title="检查报告"/> : '未记录'}</td></tr>)}</tbody></table></div>}</Panel></>;
}
export function TruthPanel({id}: {id: string}) {
  const query = useApi<Record<string,string>>(['novel',id,'truth'],`/novels/${id}/truth-files`);
  return <Panel title="当前交接资料"><div className="split"><p className="muted">根据正式正文、记忆与质量记录生成，随版本自动同步。</p><a className="button secondary" href={`/api/novels/${id}/truth-files/export`}><Download size={15}/>下载 ZIP</a></div><ErrorBox error={query.error}/>{!query.data ? <Loading/> : <div className="truth-files">{Object.entries(query.data).map(([name,text]) => <details key={name}><summary><span className="file-extension">MD</span>{name}</summary><pre>{text}</pre></details>)}</div>}</Panel>;
}
