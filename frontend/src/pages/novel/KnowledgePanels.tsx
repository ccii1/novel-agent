import {useState} from 'react';
import {useApi} from '../../api';
import {Empty, ErrorBox, Loading, Panel} from '../../ui';

interface MemoryFiles {memoryVersion: number; files: {path: string; description: string}[]; total: number; nextOffset: number; hasMore: boolean}
interface MemorySearch {memoryVersion: number; matches: {path: string; text: string}[]; total: number; nextOffset: number; hasMore: boolean}
interface MemoryFileContent {content: string; nextOffset: number; hasMore: boolean}
function MemoryFileViewer({id,path,version}: {id: string; path: string; version: number}) {
  const [offset,setOffset] = useState(0);
  const file = useApi<MemoryFileContent>(['novel',id,'memory-file',version,path,offset],`/novels/${id}/memory-files/content?version=${version}&path=${encodeURIComponent(path)}&offset=${offset}`);
  return <Panel title={path}><ErrorBox error={file.error}/>{file.data ? <>
    <pre className="setting-text">{file.data.content}</pre><div className="filters">
      {offset > 0 && <button className="secondary" onClick={() => setOffset(0)}>回到开头</button>}
      {file.data.hasMore && <button className="secondary" onClick={() => setOffset(file.data!.nextOffset)}>读取后续内容</button>}
    </div></> : <Loading/>}</Panel>;
}
export function MemoryPanel({id}: {id: string}) {
  const [filter,setFilter] = useState({directory:'',query:'',offset:0});
  const [selected,setSelected] = useState<{path: string; version: number} | null>(null);
  const parameters = `directory=${encodeURIComponent(filter.directory)}&offset=${filter.offset}&limit=10`;
  const listing = useApi<MemoryFiles>(['novel',id,'memory-files',filter],`/novels/${id}/memory-files?${parameters}`,false,!filter.query);
  const search = useApi<MemorySearch>(['novel',id,'memory-search',filter],`/novels/${id}/memory-files/search?${parameters}&query=${encodeURIComponent(filter.query)}`,false,Boolean(filter.query));
  const data = filter.query ? search.data : listing.data;
  const rows = filter.query ? search.data?.matches.map(row => ({path:row.path,description:row.text})) : listing.data?.files;
  return <><Panel title="Markdown 创作记忆">
    <form className="filters" onSubmit={event => {
      event.preventDefault();const values=new FormData(event.currentTarget);
      setFilter({directory:String(values.get('directory')),query:String(values.get('query')).trim(),offset:0});
    }}><label>主题<select name="directory"><option value="">全部</option>{['characters','world','hooks','arcs','chapters','relations'].map(directory => <option key={directory} value={directory}>{directory}</option>)}</select></label>
      <label className="grow">关键词<input name="query" maxLength={200}/></label><button className="secondary">查询文件</button></form>
    <ErrorBox error={filter.query ? search.error : listing.error}/>
    {!data ? <Loading/> : <><p className="muted">记忆版本{data.memoryVersion} · 共{data.total}个文件</p>
      {rows?.length ? <div className="table-wrap"><table><thead><tr><th>文件</th><th>说明</th><th>操作</th></tr></thead><tbody>{rows.map(row => <tr key={row.path}>
        <td>{row.path}</td><td>{row.description}</td><td><button className="secondary" onClick={() => setSelected({path:row.path,version:data.memoryVersion})}>读取</button></td>
      </tr>)}</tbody></table></div> : <Empty title="没有匹配文件"/>}
      <div className="filters">{filter.offset > 0 && <button className="secondary" onClick={() => setFilter(current => ({...current,offset:0}))}>返回首页</button>}
        {data.hasMore && <button className="secondary" onClick={() => setFilter(current => ({...current,offset:data.nextOffset}))}>下一页</button>}</div>
    </>}
  </Panel>{selected && <MemoryFileViewer key={`${selected.version}:${selected.path}`} id={id} path={selected.path} version={selected.version}/>}</>;
}
