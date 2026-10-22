import {useEffect, useId} from 'react';
import {useApi} from '../api';
import {ErrorBox} from '../ui';
import type {Catalog, Choice} from '../domain';

export default function ModelSelect({value, change, ready, disabled = false}: {value: Choice; change: (choice: Choice) => void; ready: (value: boolean) => void; disabled?: boolean}) {
  const id = useId();
  const catalog = useApi<Catalog>(['providers'], '/providers', false, true, 300000);
  const provider = value.provider || catalog.data?.defaultProvider || '';
  const profile = catalog.data?.providers.find(item => item.id === provider);
  const efforts = profile?.reasoningEfforts ?? [];
  const effortValid = !value.reasoningEffort || efforts.includes(value.reasoningEffort);
  const names = useApi<string[]>(['models', provider], `/providers/${encodeURIComponent(provider)}/models`, false, !!profile?.ready, 300000);
  useEffect(() => {if (!value.provider && provider) change({provider, model: ''});}, [value.provider, provider, change]);
  useEffect(() => {ready(!!profile?.ready && names.isSuccess && !names.isFetching && effortValid);}, [profile?.ready, names.isSuccess, names.isFetching, effortValid, ready]);
  return <div className="model-select">
    <div className="form-grid">
      <div><label htmlFor={`${id}-provider`}>模型服务</label>
        <select id={`${id}-provider`} value={provider} disabled={disabled || !catalog.data}
          onChange={event => change({provider:event.target.value, model:'', reasoningEffort:''})}>
          {catalog.data?.providers.map(item => <option value={item.id} key={item.id}>
            {item.label} · {item.billingMode === 'unmetered' ? '免费' : '按 token 计费'}
          </option>)}
        </select>
      </div>
      <div><label htmlFor={`${id}-model`}>文本模型</label>
        <select id={`${id}-model`} value={value.model} disabled={disabled || !names.isSuccess || names.isFetching}
          onChange={event => change({...value, provider, model:event.target.value})}>
          <option value="">{names.isFetching ? '正在读取…' : profile?.ready ? `服务默认 · ${profile.defaultModel}` : '服务尚未配置'}</option>
          {value.model && !names.data?.includes(value.model) && <option value={value.model}>{value.model}</option>}
          {names.data?.map(name => <option key={name}>{name}</option>)}
        </select>
      </div>
      {efforts.length > 0 && <div><label htmlFor={`${id}-effort`}>思考强度</label>
        <select id={`${id}-effort`} value={value.reasoningEffort ?? ''} disabled={disabled}
          onChange={event => change({...value, provider, reasoningEffort:event.target.value})}>
          <option value="">服务默认{profile?.defaultReasoningEffort ? ` · ${profile.defaultReasoningEffort}` : ''}</option>
          {efforts.map(effort => <option value={effort} key={effort}>{effort}</option>)}
        </select>
        <small className="muted">强度越高，通常需要更多时间和推理 token。</small>
      </div>}
    </div>
    {!effortValid && <p role="alert" className="error-text">当前服务不支持保存的思考强度，请重新选择。</p>}
    <ErrorBox error={catalog.error ?? names.error} retry={() => {void (catalog.error ? catalog.refetch() : names.refetch());}}/>
    {profile && <small className="muted">{profile.billingMode === 'unmetered' ? '免费服务，仍记录 token 用量与执行时间。' : '按已配置单价估算费用，沿用作品与月度预算。'}</small>}
  </div>;
}
