import {lazy, Suspense, useState} from 'react';
import {NavLink, Navigate, Route, Routes} from 'react-router-dom';
import {BookOpen, CircleDollarSign, Plus, Menu, X} from 'lucide-react';
import {useApi} from './api';
import {ErrorBox, Loading, Status} from './ui';
import type {Novel} from './domain';
import NewNovel from './pages/NewNovel';

const NovelWorkspace = lazy(() => import('./pages/NovelWorkspace'));
const BudgetPage = lazy(() => import('./pages/Budget'));
export default function App() {
  const books = useApi<Novel[]>(['novels'], '/novels/summaries', 5000);
  const [menu,setMenu] = useState(false);
  return <div className="app-shell">
    <aside className={`sidebar ${menu ? 'open' : ''}`}>
      <div className="brand"><BookOpen size={24}/><strong>Novel Agent</strong><button className="icon-button mobile-only" aria-label="关闭菜单" onClick={() => setMenu(false)}><X size={18}/></button></div>
      <nav onClick={() => setMenu(false)}><NavLink to="/new"><Plus size={18}/>创建作品</NavLink><NavLink to="/budget"><CircleDollarSign size={18}/>费用与调用</NavLink></nav>
      <div className="sidebar-label">我的作品</div>
      <div className="book-links" onClick={() => setMenu(false)}>{books.data?.map(book => <NavLink key={book.id} to={`/books/${book.id}/overview`} className="book-link">
        <span className="book-cover">{book.title.slice(0,1)}</span><div><strong>{book.title}</strong><small>第{book.chapter_no}章 · <Status value={book.status}/></small></div>
      </NavLink>)}</div>
    </aside>
    {menu && <button className="menu-mask" aria-label="关闭菜单" onClick={() => setMenu(false)}/>}
    <main className="main"><div className="page"><button className="icon-button mobile-only" aria-label="打开菜单" onClick={() => setMenu(true)}><Menu size={20}/></button><ErrorBox error={books.error}/><Suspense fallback={<Loading/>}><Routes>
      <Route path="/new" element={<NewNovel/>}/>
      <Route path="/books/:bookId/:tab?" element={<NovelWorkspace/>}/>
      <Route path="/budget" element={<BudgetPage/>}/>
      <Route path="*" element={<Navigate to="/new" replace/>}/>
    </Routes></Suspense></div></main>
  </div>;
}
