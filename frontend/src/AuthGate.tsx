import {useEffect, useState, type FormEvent, type ReactNode} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import {BookOpen, LogOut} from 'lucide-react';
import {api, login} from './api';

type User = {username: string};

export default function AuthGate({children}: {children: ReactNode}) {
  const client = useQueryClient();
  const [user, setUser] = useState<User | null>(null);
  const [checking, setChecking] = useState(true);
  const [registering, setRegistering] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    void fetch('/api/auth/me').then(async response => {
      if (response.status === 401) return null;
      if (!response.ok) throw new Error('无法连接登录服务，请刷新页面');
      return await response.json() as User;
    }).then(current => {
      if (active) {setUser(current); if (current) sessionStorage.setItem('novel-agent:user', current.username);}
    }).catch((failure: Error) => {if (active) setError(failure.message);}).finally(() => {if (active) setChecking(false);});
    const expired = () => {setUser(null); setRegistering(false); client.clear(); sessionStorage.removeItem('novel-agent:user');};
    window.addEventListener('novel-auth-expired', expired);
    return () => {active = false; window.removeEventListener('novel-auth-expired', expired);};
  }, [client]);
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      if (registering) await api('/auth/register', undefined, {username, password});
      await login(username, password);
      const current = await api<User>('/auth/me');
      client.clear(); sessionStorage.setItem('novel-agent:user', current.username);
      setPassword(''); setRegistering(false); window.location.hash='/research'; setUser(current);
    } catch (failure) {setError((failure as Error).message);}
    finally {setBusy(false);}
  }
  async function logout() {
    setBusy(true); setError('');
    try {await api('/auth/logout', undefined, {}); client.clear(); setUser(null); setRegistering(false); sessionStorage.removeItem('novel-agent:user');}
    catch (failure) {setError((failure as Error).message);}
    finally {setBusy(false);}
  }
  if (checking) return <div className="auth-screen"><p role="status">正在检查登录状态…</p></div>;
  if (user) return <><div className="account-bar"><span>{user.username}</span><button className="icon-button" disabled={busy} onClick={() => {void logout();}} aria-label="退出登录"><LogOut size={16}/></button>{error && <span role="alert">{error}</span>}</div>{children}</>;
  return <main className="auth-screen"><form className="auth-card" onSubmit={event => {void submit(event);}}>
    <div className="brand-icon"><BookOpen size={24}/></div><h1>{registering ? '创建创作账号' : '登录创作工作台'}</h1>
    <p className="muted">每个账号独立管理作品、选题、记忆与执行记录。</p>
    <label>用户名<input name="username" autoComplete="username" required minLength={3} maxLength={32} pattern="[a-z][a-z0-9_]{2,31}" value={username} onChange={event => setUsername(event.target.value)} placeholder="小写字母开头，支持数字与下划线"/></label>
    <label>密码<input name="password" type="password" autoComplete={registering ? 'new-password' : 'current-password'} required minLength={10} maxLength={64} value={password} onChange={event => setPassword(event.target.value)}/></label>
    {error && <p className="error-box" role="alert">{error}</p>}
    <button className="button primary" disabled={busy} type="submit">{busy ? '正在处理…' : registering ? '注册并登录' : '登录'}</button>
    <button className="button" disabled={busy} type="button" onClick={() => {setRegistering(!registering); setError('');}}>{registering ? '已有账号，返回登录' : '注册新账号'}</button>
    <p className="muted small">已有作品归入 owner 账号。首次启动生成的登录信息保存在本机 config/auth-local.json。</p>
  </form></main>;
}
