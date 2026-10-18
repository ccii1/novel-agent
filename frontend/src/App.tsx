import {lazy, Suspense, useState} from 'react';
import {NavLink, Navigate, Route, Routes, useLocation} from 'react-router-dom';
import {useIsFetching, useQueryClient} from '@tanstack/react-query';
import {Activity, BookOpen, ChevronRight, CircleDollarSign, Compass, Menu, Plus, RefreshCw, X} from 'lucide-react';
import {useApi} from './api';
import {ErrorBox, Loading, Status} from './ui';
import type {Novel, SystemStatus} from './domain';
import NewNovel from './pages/NewNovel';

const Research = lazy(() => import('./pages/Research'));
const NovelWorkspace = lazy(() => import('./pages/NovelWorkspace'));
const Observability = lazy(() => import('./pages/Observability'));
const BudgetPage = lazy(() => import('./pages/Budget'));
export default function App() {
  const books = useApi<Novel[]>(['novels'], '/novels/summaries', 5000);
  const status = useApi<SystemStatus>(['status'], '/status', 5000);
  const client = useQueryClient();
  const fetching = useIsFetching();
  const [menu, setMenu] = useState(false);
  const location = useLocation();
  const title = location.pathname.startsWith('/books') ? '作品工作区' : location.pathname.startsWith('/research') ? '选题研究' : location.pathname.startsWith('/budget') ? '费用与调用' : location.pathname.startsWith('/agents') ? 'Agent 看板' : '创建作品';
  return <div className="app-shell"><aside className={`sidebar ${menu ? 'open' : ''}`}><div className="brand"><div className="brand-icon"><BookOpen size={21}/></div><div><strong>Novel Agent</strong><small>你的长篇创作工作台</small></div><button className="icon-button mobile-only" aria-label="关闭菜单" onClick={() => setMenu(false)}><X size={18}/></button></div><nav onClick={() => setMenu(false)}><NavLink to="/new"><Plus size={18}/>创建作品</NavLink><NavLink to="/research"><Compass size={18}/>选题研究</NavLink><NavLink to="/agents"><Activity size={18}/>Agent 看板</NavLink><NavLink to="/budget"><CircleDollarSign size={18}/>费用与调用</NavLink></nav><div className="sidebar-label">我的作品 <span>{books.data?.length ?? 0}</span></div><div className="book-links" onClick={() => setMenu(false)}>{books.data?.map(book => <NavLink key={book.id} to={`/books/${book.id}/overview`} className={({isActive}) => `book-link ${isActive || location.pathname.includes(book.id) ? 'selected' : ''}`}><span className="book-cover">{book.title.slice(0,1)}</span><div><strong>{book.title}</strong><small>{book.chapter_no} 章 · <Status value={book.status}/></small></div></NavLink>)}{books.data?.length === 0 && <p className="muted small">创建第一部作品，开始连载。</p>}</div><div className="sidebar-footer"><span className={`connection-dot ${status.isError ? 'offline' : ''}`}/>{status.data ? `后端已连接 · ${status.data.engine.active}/${status.data.engine.workers} 个任务` : status.isError ? '后端连接失败' : '正在连接后端'}<small>React + Vite · 自动同步</small></div></aside>{menu && <button className="menu-mask" aria-label="关闭菜单" onClick={() => setMenu(false)}/>}<main className="main"><header className="topbar"><div className="breadcrumb"><button className="icon-button mobile-only" aria-label="打开菜单" onClick={() => setMenu(true)}><Menu size={20}/></button><span>创作工作台</span><ChevronRight size={14}/><strong>{title}</strong></div><div className="sync"><span className={fetching ? 'sync-dot busy' : 'sync-dot'}/><span>{fetching ? '正在同步' : '自动同步已开启'}</span><button className="icon-button" title="立即同步" aria-label="立即同步" onClick={() => {void client.invalidateQueries();}}><RefreshCw size={16}/></button></div></header><div className="page"><ErrorBox error={status.error ?? books.error} retry={() => {void client.invalidateQueries();}}/><Suspense fallback={<Loading/>}><Routes><Route path="/new" element={<NewNovel/>}/><Route path="/research/:reportId?" element={<Research/>}/><Route path="/books/:bookId/:tab?" element={<NovelWorkspace/>}/><Route path="/agents" element={<Observability/>}/><Route path="/budget" element={<BudgetPage/>}/><Route path="*" element={<Navigate to="/research" replace/>}/></Routes></Suspense></div></main></div>;
}
