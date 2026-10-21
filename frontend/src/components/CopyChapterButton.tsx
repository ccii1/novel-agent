import {useMutation} from '@tanstack/react-query';
import {api} from '../api';
import type {Chapter} from '../domain';

export default function CopyChapterButton({id, number}: {id: string; number: number}) {
  const copy = useMutation<void, Error>({
    mutationFn: async () => {
      const chapter = await api<Chapter>(`/novels/${id}/chapters/${number}`);
      if(typeof chapter.body !== 'string' || !chapter.body.trim()) throw new Error('本章暂无可复制的正文');
      await navigator.clipboard.writeText(chapter.body);
    },
  });
  return <div>
    <button type="button" className="secondary" disabled={copy.isPending} onClick={() => copy.mutate()} aria-label={`复制第 ${number} 章全文`}>
      {copy.isPending ? '正在复制…' : copy.isSuccess ? '已复制' : '复制全文'}
    </button>
    {copy.isSuccess && <small role="status">全文已复制到剪贴板</small>}
    {copy.error && <small className="error-text" role="alert">复制失败：{copy.error.message}</small>}
  </div>;
}
