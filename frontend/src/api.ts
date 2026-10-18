import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query';
import {useEffect, useRef, useState} from 'react';
import type {QueryKey} from '@tanstack/react-query';

export async function api<T>(path: string, signal?: AbortSignal, body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    signal, method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? undefined : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const failure = await response.json() as {error?: string};
    throw new Error(failure.error ?? `请求失败：HTTP ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export function useApi<T>(key: QueryKey, path: string, interval: number | false = false, enabled = true, staleTime = 10000) {
  return useQuery<T>({queryKey: key, queryFn: ({signal}) => api<T>(path, signal), refetchInterval: interval, enabled, staleTime});
}

export function useAction<T = unknown>(onSaved?: (result: T) => void) {
  const client = useQueryClient();
  return useMutation<T, Error, {path: string; body?: unknown}>({
    mutationFn: ({path, body}) => api<T>(path, undefined, body ?? {}),
    onSuccess: async result => {onSaved?.(result); await client.invalidateQueries();},
  });
}

export function useNovelSync(id: string) {
  const client = useQueryClient();
  const previous = useRef<string | undefined>(undefined);
  const snapshot = useApi<Record<string, unknown>>(['snapshot', id], `/novels/${id}/snapshot`, 2000);
  const signature = snapshot.data ? JSON.stringify(snapshot.data) : undefined;
  useEffect(() => {
    if (previous.current && signature && previous.current !== signature) {
      void client.invalidateQueries({queryKey: ['novel', id]});
      void client.invalidateQueries({queryKey: ['novels']});
      void client.invalidateQueries({queryKey: ['observability']});
    }
    previous.current = signature;
  }, [signature, client, id]);
  return snapshot;
}

export function useDraft(key: string, initial = ''): [string, (value: string) => void, () => void] {
  const storageKey = `novel-agent:draft:v1:${key}`;
  const [value, setValue] = useState(() => sessionStorage.getItem(storageKey) ?? initial);
  const update = (next: string) => {setValue(next); sessionStorage.setItem(storageKey, next);};
  const clear = () => {setValue(''); sessionStorage.removeItem(storageKey);};
  return [value, update, clear];
}
