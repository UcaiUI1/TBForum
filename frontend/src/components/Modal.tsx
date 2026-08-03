// 通用模态框：承载登录/注册/发帖/建版块四种表单
import React, { FormEvent } from 'react';
import type { Forum, ModalType } from '../types';
import { MentionTextarea } from './MentionTextarea';

type Props = {
  type: ModalType;
  close: () => void;
  change: (type: ModalType | null) => void;
  submit: (e: FormEvent<HTMLFormElement>) => void;
  active: Forum | null;
  postForm: { title: string; content: string };
  setPostForm: (f: { title: string; content: string }) => void;
  token: string;
  onError: (e: unknown) => void;
};

export function Modal({ type, close, change, submit, active, postForm, setPostForm, token, onError }: Props) {
  const auth = type === 'login' || type === 'register';
  const registering = type === 'register';
  const title = auth ? (registering ? '创建你的账号' : '回到讨论中') : type === 'post' ? `在「${active?.name}」发帖` : '创建你的版块';
  return (
    <div className="backdrop" onMouseDown={close}>
      <form className="modal" role="dialog" aria-modal="true" onSubmit={submit} onMouseDown={e => e.stopPropagation()}>
        <button type="button" className="close" onClick={close}>×</button>
        <p className="eyebrow">TBFORUM</p>
        <h2>{title}</h2>
        {auth && (
          <>
            <input required name="username" minLength={3} maxLength={40} placeholder="用户名（3–40 位）" />
            <input required name="password" type="password" minLength={8} maxLength={100} placeholder="密码（至少 8 位）" />
            <small>{registering ? '已有账号？' : '还没有账号？'} <button type="button" className="text-link" onClick={() => change(registering ? 'login' : 'register')}>{registering ? '去登录' : '立即注册'}</button></small>
          </>
        )}
        {type === 'post' && (
          <>
            <input required maxLength={180} placeholder="标题：一句让人想点开的表达" value={postForm.title} onChange={e => setPostForm({ ...postForm, title: e.target.value })} />
            <MentionTextarea value={postForm.content} onChange={v => setPostForm({ ...postForm, content: v })} rows={8} token={token} onError={onError} placeholder="写下你的观点。支持 Markdown 格式，输入 @ 提及用户" />
          </>
        )}
        {type === 'forum' && (
          <>
            <input required name="name" placeholder="版块名称" />
            <input required name="slug" pattern="[a-z0-9-]{3,64}" placeholder="唯一标识，如 design-lab" />
            <textarea name="description" rows={3} placeholder="一句话描述这里适合聊什么" />
          </>
        )}
        <button className="primary" type="submit">{auth ? (registering ? '注册并进入论坛' : '登录') : '确认发布'}</button>
      </form>
    </div>
  );
}
