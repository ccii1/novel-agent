import {useState} from 'react';
import {Download} from 'lucide-react';
import {useApi} from '../../api';
import {Empty, ErrorBox, JsonView, Loading, Panel, Stats} from '../../ui';
import type {Quality} from '../../domain';

interface MemoryFileDescriptor {path: string; title: string; description: string; hash: string; bytes: number; legacy: boolean}
interface MemoryFiles {memoryVersion: number; files: MemoryFileDescriptor[]; total: number; nextOffset: number; hasMore: boolean}
interface MemorySearch {memoryVersion: number; matches: {path: string; line: number; text: string; hash: string}[]; total: number; nextOffset: number; hasMore: boolean}
interface MemoryFileContent {path: string; memoryVersion: number; content: string; offset: number; nextOffset: number; totalCharacters: number; hasMore: boolean}
const memoryDirectories = [['characters','人物'],['world','世界规则'],['hooks','伏笔'],['arcs','支线'],['chapters','章节'],['stages','阶段'],['relations','关系'],['events','历史事件']] as const;

function MemoryFileViewer({id,path,version}: {id: string; path: string; version: number}) {
  const [offset,setOffset] = useState(0);
  const query = useApi<MemoryFileContent>(['novel',id,'memory-file',version,path,offset],`/novels/${id}/memory-files/content?version=${version}&path=${encodeURIComponent(path)}&offset=${offset}`);
  return <Panel title={path}>
    <ErrorBox error={query.error}/>
    {!query.data ? <Loading/> : <>
      <p className="muted">记忆版本 {version} · 字符范围 {query.data.offset}—{query.data.nextOffset} / {query.data.totalCharacters}</p>
      <pre className="setting-text">{query.data.content}</pre>
      <div className="filters">
        {offset > 0 && <button className="secondary" onClick={() => setOffset(0)}>回到开头</button>}
        {query.data.hasMore && <button className="secondary" onClick={() => setOffset(query.data!.nextOffset)}>读取后续内容</button>}
      </div>
    </>}
  </Panel>;
}

export function MemoryPanel({id}: {id: string}) {
  const [filter,setFilter] = useState({directory:'',query:'',offset:0});
  const [selected,setSelected] = useState<{path: string; version: number} | null>(null);
  const parameters = `directory=${encodeURIComponent(filter.directory)}&offset=${filter.offset}&limit=10`;
  const listing = useApi<MemoryFiles>(['novel',id,'memory-files',filter],`/novels/${id}/memory-files?${parameters}`,false,!filter.query);
  const search = useApi<MemorySearch>(['novel',id,'memory-search',filter],`/novels/${id}/memory-files/search?${parameters}&query=${encodeURIComponent(filter.query)}`,false,Boolean(filter.query));
  const data = filter.query ? search.data : listing.data;
  const rows = filter.query ? search.data?.matches.map(row => ({path:row.path,title:row.path,description:row.text})) : listing.data?.files;
  return <>
    <Panel title="Markdown 创作记忆">
      <p className="muted">人物、世界规则、伏笔和章节记录按主题保存。选择文件读取内容，可以按姓名、别名或关键词搜索。</p>
      <form className="filters" onSubmit={event => {
        event.preventDefault();
        const values = new FormData(event.currentTarget);
        setFilter({directory:String(values.get('directory')),query:String(values.get('query')).trim(),offset:0});
      }}>
        <label>主题<select name="directory" defaultValue=""><option value="">全部</option>{memoryDirectories.map(([directory,label]) => <option key={directory} value={directory}>{label}</option>)}</select></label>
        <label className="grow">搜索关键词<input name="query" maxLength={200} placeholder="人物、物品、地点或伏笔"/></label>
        <button className="secondary">查询文件</button>
      </form>
      <ErrorBox error={filter.query ? search.error : listing.error}/>
      {!data ? <Loading/> : <>
        <p className="muted">记忆版本 {data.memoryVersion} · 共 {data.total} 个匹配文件</p>
        {rows?.length ? <div className="table-wrap"><table><thead><tr><th>文件</th><th>内容说明</th><th>操作</th></tr></thead><tbody>{rows.map(row => <tr key={row.path}><td>{row.path}</td><td>{row.description}</td><td><button className="secondary" onClick={() => setSelected({path:row.path,version:data.memoryVersion})}>读取</button></td></tr>)}</tbody></table></div> : <Empty title="没有匹配文件"/>}
        <div className="filters">
          {filter.offset > 0 && <button className="secondary" onClick={() => setFilter(current => ({...current,offset:0}))}>返回首页</button>}
          {data.hasMore && <button className="secondary" onClick={() => setFilter(current => ({...current,offset:data.nextOffset}))}>下一页</button>}
        </div>
      </>}
    </Panel>
    {selected && <MemoryFileViewer key={`${selected.version}:${selected.path}`} id={id} path={selected.path} version={selected.version}/>}
  </>;
}
export function QualityPanel({id}: {id: string}) {
  const query = useApi<Quality>(['novel',id,'quality'],`/novels/${id}/quality`);
  if (!query.data) return <><ErrorBox error={query.error}/><Loading/></>;
  const data = query.data;
  return <><ErrorBox error={query.error}/><Stats items={[{label:'已接受章节',value:data.totals.chapters},{label:'正文字符数（含空白）',value:data.totals.stored_characters.toLocaleString()},{label:'最近三章对话趋势',value:data.dialogueRising ? '需要关注' : '持续记录'}]}/><Panel title="质量记录"><p className="muted">对话占比按含引号的非空行数计算；节奏评价来自模型检查。此处展示通过验收的章节。</p>{data.recent.length === 0 ? <Empty title="暂无通过验收的章节记录"/> : <div className="table-wrap"><table><thead><tr><th>章节</th><th>非空白字符</th><th>对话行 / 全部非空行</th><th>对话占比</th><th>叙事结构</th></tr></thead><tbody>{data.recent.map(row => <tr key={row.chapter_no}><td>第 {row.chapter_no} 章</td><td>{row.characters}</td><td>{row.dialogue_lines} / {row.total_lines}</td><td><span className="ratio-bar"><i style={{width:`${Math.min(100,row.dialogue_ratio*100)}%`}}/></span>{(row.dialogue_ratio*100).toFixed(1)}%</td><td>{row.pacing_data ? <JsonView value={row.pacing_data} title="检查报告"/> : '未记录'}</td></tr>)}</tbody></table></div>}</Panel></>;
}
export function TruthPanel({id}: {id: string}) {
  return <><Panel title="交接资料导出"><div className="split"><p className="muted">下载当前正式版本的全部 Markdown 记忆、批准的大纲和创作进度。</p><a className="button secondary" href={`/api/novels/${id}/truth-files/export`}><Download size={15}/>下载 ZIP</a></div></Panel><MemoryPanel id={id}/></>;
}
