// 通用模态框：登录/注册/发帖/建版块。发帖支持 Markdown 预览、插图上传与草稿提示。
import React, { FormEvent, useState } from 'react';
import type { Forum, ModalType } from '../types';
import { MentionTextarea } from './MentionTextarea';
import { uploadFile } from '../services/api';
import { renderMd } from '../utils/markdown';

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
  draftActive?: boolean;
  onClearDraft?: () => void;
};

export function Modal({ type, close, change, submit, active, postForm, setPostForm, token, onError, draftActive, onClearDraft }: Props) {
  const auth = type === 'login' || type === 'register';
  const registering = type === 'register';
  const title = auth ? (registering ? '创建你的账号' : '回到讨论中') : type === 'post' ? `在「${active?.name}」发布` : '创建你的版块';
  const [preview, setPreview] = useState(false);
  const [uploading, setUploading] = useState(false);

  async function handleUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) { onError(new Error('只能上传图片文件')); return; }
    if (file.size > 2 * 1024 * 1024) { onError(new Error('图片不能超过 2MB')); return; }
    setUploading(true);
    try {
      const data = await uploadFile(file, token);
      const suffix = postForm.content.trim().endsWith('\n') || postForm.content === '' ? '' : '\n';
      setPostForm({ ...postForm, content: `${postForm.content}${suffix}![图片](${data.url})\n` });
    } catch (err) { onError(err); }
    finally { setUploading(false); }
  }

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
            <div className="editor-bar">
              <div className="editor-tabs">
                <button type="button" className={!preview ? 'selected' : ''} onClick={() => setPreview(false)}>编辑</button>
                <button type="button" className={preview ? 'selected' : ''} onClick={() => setPreview(true)}>预览</button>
              </div>
              <label className="editor-upload">
                <input type="file" accept="image/*" hidden onChange={handleUpload} />
                {uploading ? '上传中…' : '插入图片'}
              </label>
            </div>
            {preview ? (
              <div className="markdown-body preview-body">
                {postForm.content.trim() ? <div dangerouslySetInnerHTML={{ __html: renderMd(postForm.content) }} /> : <p className="preview-empty">正文为空，写点什么再预览吧。</p>}
              </div>
            ) : (
              <MentionTextarea value={postForm.content} onChange={v => setPostForm({ ...postForm, content: v })} rows={8} token={token} onError={onError} placeholder="写下你的观点。支持 Markdown 格式，输入 @ 提及用户、# 添加标签" />
            )}
            {draftActive && onClearDraft && (
              <small className="draft-hint">已自动保存草稿 <button type="button" className="text-link" onClick={onClearDraft}>清空</button></small>
            )}
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
